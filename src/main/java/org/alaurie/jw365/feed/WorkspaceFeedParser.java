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

/**
 * Secure XML parser for Windows 365 and AVD Workspace Feed Discovery and Resource XML documents.
 */
public final class WorkspaceFeedParser {

    private WorkspaceFeedParser() {
    }

    /**
     * Parses the Discovery XML response into a list of {@link TenantFeed} endpoints.
     *
     * @param xml discovery XML string
     * @return list of TenantFeed instances
     */
    public static List<TenantFeed> parseDiscoveryXml(String xml) {
        List<TenantFeed> feeds = new ArrayList<>();
        if (xml == null || xml.isBlank()) {
            return feeds;
        }

        try {
            Document doc = parseSecurely(xml);
            NodeList feedNodes = doc.getElementsByTagNameNS("*", "TenantFeedURL");
            for (int i = 0; i < feedNodes.getLength(); i++) {
                Node node = feedNodes.item(i);
                if (node instanceof Element el) {
                    String feedUrlStr = getAttributeIgnoreCase(el, "FeedURL", "FeedUrl", "url", "href");
                    String tenantId = getAttributeIgnoreCase(el, "TenantId", "tenantId", "id");
                    String displayName = getAttributeIgnoreCase(el, "TenantDisplayName", "DisplayName", "name");

                    if (feedUrlStr != null && !feedUrlStr.isBlank()) {
                        if (tenantId == null || tenantId.isBlank()) {
                            tenantId = "tenant-" + (i + 1);
                        }
                        if (displayName == null || displayName.isBlank()) {
                            displayName = tenantId;
                        }

                        URI feedUri = parseAllowedUri(feedUrlStr.trim());
                        if (feedUri == null) {
                            continue;
                        }
                        feeds.add(new TenantFeed(tenantId, displayName, feedUri));
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse Workspace Feed Discovery XML: " + e.getMessage(), e);
        }

        return feeds;
    }

    /**
     * Parses a tenant's Workspace Feed XML into a {@link Workspace} containing its {@link WorkspaceResource} items.
     *
     * @param xml        feed XML string
     * @param tenantFeed the parent TenantFeed
     * @return parsed Workspace
     */
    public static Workspace parseFeedXml(String xml, TenantFeed tenantFeed) {
        String workspaceName = tenantFeed != null ? tenantFeed.tenantDisplayName() : "Workspace";
        String tenantId = tenantFeed != null ? tenantFeed.tenantId() : "default";
        String tenantDisplayName = tenantFeed != null ? tenantFeed.tenantDisplayName() : tenantId;

        List<WorkspaceResource> resources = new ArrayList<>();
        if (xml == null || xml.isBlank()) {
            return new Workspace(workspaceName, tenantId, tenantDisplayName, resources);
        }

        try {
            Document doc = parseSecurely(xml);

            // Look for Publisher elements
            NodeList publisherNodes = doc.getElementsByTagNameNS("*", "Publisher");
            if (publisherNodes.getLength() > 0) {
                for (int p = 0; p < publisherNodes.getLength(); p++) {
                    Node pNode = publisherNodes.item(p);
                    if (pNode instanceof Element pEl) {
                        String publisherName = getAttributeIgnoreCase(pEl, "Name", "name", "Title", "title");
                        if (publisherName == null || publisherName.isBlank()) {
                            publisherName = workspaceName;
                        }

                        parseResourcesUnderElement(pEl, resources, tenantDisplayName, tenantId, publisherName);
                    }
                }
            } else {
                // Parse directly under root
                Element root = doc.getDocumentElement();
                parseResourcesUnderElement(root, resources, tenantDisplayName, tenantId, workspaceName);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse Workspace Feed XML: " + e.getMessage(), e);
        }

        return new Workspace(workspaceName, tenantId, tenantDisplayName, resources);
    }

    private static void parseResourcesUnderElement(
        Element parentElement,
        List<WorkspaceResource> outputList,
        String tenantName,
        String tenantId,
        String publisherName
    ) {
        NodeList resourceNodes = parentElement.getElementsByTagNameNS("*", "Resource");
        for (int r = 0; r < resourceNodes.getLength(); r++) {
            Node rNode = resourceNodes.item(r);
            if (rNode instanceof Element rEl) {
                String id = getAttributeIgnoreCase(rEl, "ID", "id", "Id");
                String title = getAttributeIgnoreCase(rEl, "Title", "title", "Name", "name");
                String typeStr = getAttributeIgnoreCase(rEl, "Type", "type", "ResourceType");
                String armPath = getAttributeIgnoreCase(rEl, "ArmPath", "armPath", "ARMPath");

                if (id == null || id.isBlank()) {
                    id = "resource-" + (outputList.size() + 1);
                }
                if (title == null || title.isBlank()) {
                    title = id;
                }

                ResourceType type = ResourceType.fromString(typeStr);

                URI rdpUrl = findChildAttributeUri(rEl, "ResourceFile", "URL", "Url", "url", "href");
                URI iconUrl = findChildAttributeUri(rEl, "Icon32", "FileURL", "FileUrl", "fileUrl", "URL", "url", "href");
                if (iconUrl == null) {
                    iconUrl = findChildAttributeUri(rEl, "Icon64", "FileURL", "FileUrl", "fileUrl", "URL", "url", "href");
                }
                if (iconUrl == null) {
                    iconUrl = findChildAttributeUri(rEl, "IconRaw", "FileURL", "FileUrl", "fileUrl", "URL", "url", "href");
                }

                outputList.add(new WorkspaceResource(
                    id,
                    title,
                    type,
                    tenantName,
                    tenantId,
                    publisherName,
                    armPath,
                    rdpUrl,
                    iconUrl
                ));
            }
        }
    }

    private static URI findChildAttributeUri(Element parent, String tagName, String... attrNames) {
        NodeList list = parent.getElementsByTagNameNS("*", tagName);
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n instanceof Element el) {
                String val = getAttributeIgnoreCase(el, attrNames);
                if (val != null && !val.isBlank()) {
                    URI uri = parseAllowedUri(val.trim());
                    if (uri != null) {
                        return uri;
                    }
                }
            }
        }
        return null;
    }

    private static String getAttributeIgnoreCase(Element el, String... candidateNames) {
        for (String name : candidateNames) {
            if (el.hasAttribute(name)) {
                return el.getAttribute(name);
            }
        }
        return null;
    }

    private static URI parseAllowedUri(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if ("https".equalsIgnoreCase(scheme)) {
                return uri;
            }
            if ("http".equalsIgnoreCase(scheme)) {
                String host = uri.getHost();
                if ("localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host)) {
                    return uri;
                }
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    private static Document parseSecurely(String xml) throws Exception {
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("XML content is empty");
        }
        if (xml.length() > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("XML content exceeds 10 MiB limit");
        }

        // Strip UTF-8 Byte Order Mark (\uFEFF) and any leading whitespace or pre-prolog characters
        int startIdx = xml.indexOf('<');
        if (startIdx > 0) {
            xml = xml.substring(startIdx);
        } else if (startIdx == -1) {
            throw new IllegalArgumentException("Invalid XML: no opening '<' tag found");
        }

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
