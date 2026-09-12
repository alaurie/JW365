package org.alaurie.jw365.feed;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Secure XML parser for Windows 365 and AVD workspace feed documents. */
public final class WorkspaceFeedParser {
    private WorkspaceFeedParser() {}

    /** Parses discovery XML, accepting only HTTPS endpoints by default. */
    public static List<TenantFeed> parseDiscoveryXml(String xml) {
        return parseDiscoveryXml(xml, WorkspaceFeedParser::isProductionAllowedUri);
    }

    /** Parses discovery XML under an explicit endpoint policy (for isolated tests). */
    public static List<TenantFeed> parseDiscoveryXml(String xml, Predicate<URI> endpointPolicy) {
        List<TenantFeed> feeds = new ArrayList<>();
        if (xml == null || xml.isBlank()) return feeds;
        if (endpointPolicy == null) throw new NullPointerException("endpointPolicy must not be null");
        try {
            Document doc = parseSecurely(xml);
            NodeList feedNodes = doc.getElementsByTagNameNS("*", "TenantFeedURL");
            for (int i = 0; i < feedNodes.getLength(); i++) {
                if (!(feedNodes.item(i) instanceof Element el)) continue;
                String feedUrlStr = getAttributeIgnoreCase(el, "FeedURL", "FeedUrl", "url", "href");
                if (feedUrlStr == null || feedUrlStr.isBlank()) continue;
                String tenantId = getAttributeIgnoreCase(el, "TenantId", "tenantId", "id");
                String displayName = getAttributeIgnoreCase(el, "TenantDisplayName", "DisplayName", "name");
                tenantId = tenantId == null || tenantId.isBlank() ? "tenant-" + (i + 1) : tenantId;
                displayName = displayName == null || displayName.isBlank() ? tenantId : displayName;
                URI feedUri = parseAllowedUri(feedUrlStr.trim(), endpointPolicy);
                if (feedUri != null) feeds.add(new TenantFeed(tenantId, displayName, feedUri));
            }
            return feeds;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse Workspace Feed Discovery XML: " + e.getMessage(), e);
        }
    }

    public static Workspace parseFeedXml(String xml, TenantFeed tenantFeed) {
        return parseFeedXml(xml, tenantFeed, WorkspaceFeedParser::isProductionAllowedUri);
    }

    /** Parses feed XML under an explicit endpoint policy (for isolated tests). */
    public static Workspace parseFeedXml(String xml, TenantFeed tenantFeed, Predicate<URI> endpointPolicy) {
        if (endpointPolicy == null) throw new NullPointerException("endpointPolicy must not be null");
        String workspaceName = tenantFeed != null ? tenantFeed.tenantDisplayName() : "Workspace";
        String tenantId = tenantFeed != null ? tenantFeed.tenantId() : "default";
        String tenantDisplayName = tenantFeed != null ? tenantFeed.tenantDisplayName() : tenantId;
        List<WorkspaceResource> resources = new ArrayList<>();
        if (xml == null || xml.isBlank()) return new Workspace(workspaceName, tenantId, tenantDisplayName, resources);
        try {
            Document doc = parseSecurely(xml);
            NodeList publisherNodes = doc.getElementsByTagNameNS("*", "Publisher");
            if (publisherNodes.getLength() > 0) {
                for (int p = 0; p < publisherNodes.getLength(); p++) {
                    if (publisherNodes.item(p) instanceof Element pEl) {
                        String publisherName = getAttributeIgnoreCase(pEl, "Name", "name", "Title", "title");
                        parseResourcesUnderElement(pEl, resources, tenantDisplayName, tenantId,
                            publisherName == null || publisherName.isBlank() ? workspaceName : publisherName,
                            endpointPolicy, tenantFeed != null ? tenantFeed.feedUrl() : null);
                    }
                }
            } else {
                parseResourcesUnderElement(doc.getDocumentElement(), resources, tenantDisplayName, tenantId,
                    workspaceName, endpointPolicy, tenantFeed != null ? tenantFeed.feedUrl() : null);
            }
            return new Workspace(workspaceName, tenantId, tenantDisplayName, resources);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse Workspace Feed XML: " + e.getMessage(), e);
        }
    }

    private static void parseResourcesUnderElement(Element parent, List<WorkspaceResource> output, String tenantName,
                                                   String tenantId, String publisherName, Predicate<URI> policy, URI baseUri) {
        NodeList resourceNodes = parent.getElementsByTagNameNS("*", "Resource");
        for (int r = 0; r < resourceNodes.getLength(); r++) {
            if (!(resourceNodes.item(r) instanceof Element resource)) continue;
            String id = getAttributeIgnoreCase(resource, "ID", "id", "Id");
            String title = getAttributeIgnoreCase(resource, "Title", "title", "Name", "name");
            String typeStr = getAttributeIgnoreCase(resource, "Type", "type", "ResourceType");
            String armPath = getAttributeIgnoreCase(resource, "ArmPath", "armPath", "ARMPath");
            if (id == null || id.isBlank()) id = "resource-" + (output.size() + 1);
            if (title == null || title.isBlank()) title = id;
            URI rdpUrl = findChildAttributeOrTextUri(resource, "ResourceFile", policy, baseUri, "URL", "Url", "url", "href");
            URI iconUrl = findIconUri(resource, policy, baseUri);
            output.add(new WorkspaceResource(id, title, ResourceType.fromString(typeStr), tenantName, tenantId,
                publisherName, armPath, rdpUrl, iconUrl));
        }
    }

    private static URI findIconUri(Element resource, Predicate<URI> policy, URI baseUri) {
        String[] iconTags = { "Icon64", "Icon48", "Icon32", "Icon128", "Icon256", "Icon16", "Icon", "IconRaw" };
        String[] attrNames = { "FileURL", "FileUrl", "fileUrl", "URL", "Url", "url", "href", "Href", "src", "Src" };
        for (String tag : iconTags) {
            URI uri = findChildAttributeOrTextUri(resource, tag, policy, baseUri, attrNames);
            if (uri != null) return uri;
        }
        String directAttr = getAttributeIgnoreCase(resource, "IconUrl", "IconURL", "iconUrl", "Icon", "icon");
        if (directAttr != null && !directAttr.isBlank()) {
            URI uri = parseAllowedUri(directAttr.trim(), policy, baseUri);
            if (uri != null) return uri;
        }
        return null;
    }

    private static URI findChildAttributeOrTextUri(Element parent, String tagName, Predicate<URI> policy, URI baseUri, String... names) {
        NodeList list = parent.getElementsByTagNameNS("*", tagName);
        for (int i = 0; i < list.getLength(); i++) {
            if (!(list.item(i) instanceof Element el)) continue;
            String value = getAttributeIgnoreCase(el, names);
            if (value == null || value.isBlank()) {
                String text = el.getTextContent();
                if (text != null && !text.isBlank() && !text.contains("<")) {
                    value = text.trim();
                }
            }
            if (value != null && !value.isBlank()) {
                URI uri = parseAllowedUri(value.trim(), policy, baseUri);
                if (uri != null) return uri;
            }
        }
        return null;
    }

    private static String getAttributeIgnoreCase(Element el, String... names) {
        for (String name : names) if (el.hasAttribute(name)) return el.getAttribute(name);
        return null;
    }

    private static URI parseAllowedUri(String value, Predicate<URI> policy) {
        return parseAllowedUri(value, policy, null);
    }

    private static URI parseAllowedUri(String value, Predicate<URI> policy, URI baseUri) {
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute() && baseUri != null) {
                uri = baseUri.resolve(uri);
            }
            return (policy == null || policy.test(uri)) ? uri : null;
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
    private static boolean isProductionAllowedUri(URI uri) {
        return uri != null && uri.getUserInfo() == null && "https".equalsIgnoreCase(uri.getScheme())
            && uri.getHost() != null && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private static Document parseSecurely(String xml) throws Exception {
        if (xml == null || xml.isBlank()) throw new IllegalArgumentException("XML content is empty");
        if (xml.length() > 10 * 1024 * 1024) throw new IllegalArgumentException("XML content exceeds 10 MiB limit");
        int startIdx = xml.indexOf('<');
        if (startIdx > 0) xml = xml.substring(startIdx);
        else if (startIdx == -1) throw new IllegalArgumentException("Invalid XML: no opening '<' tag found");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }
}
