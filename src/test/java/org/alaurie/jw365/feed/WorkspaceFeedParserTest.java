package org.alaurie.jw365.feed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceFeedParserTest {

    private static final String SAMPLE_DISCOVERY_XML = """
        <?xml version="1.0" encoding="utf-8"?>
        <FeedDiscoveryResult xmlns="http://schemas.microsoft.com/2008/11/msts/radc">
            <TenantFeedURL FeedURL="https://rdweb.wvd.microsoft.com/api/arm/feeddiscovery/tenant/t1"
                           TenantId="00000000-0000-0000-0000-000000000001"
                           TenantDisplayName="Contoso Cloud PCs" />
            <TenantFeedURL FeedURL="https://rdweb.wvd.microsoft.com/api/arm/feeddiscovery/tenant/t2"
                           TenantId="00000000-0000-0000-0000-000000000002"
                           TenantDisplayName="Fabrikam Desktops" />
        </FeedDiscoveryResult>
        """;

    private static final String SAMPLE_WORKSPACE_XML = """
        <?xml version="1.0" encoding="utf-8"?>
        <ResourceCollection xmlns="http://schemas.microsoft.com/2008/11/msts/radc">
            <Publisher Name="Contoso Enterprise">
                <Resources>
                    <Resource ID="/subscriptions/sub1/resourceGroups/rg1/providers/Microsoft.DesktopVirtualization/hostpools/hp1"
                              Title="Windows 365 Cloud PC 01"
                              Type="Desktop"
                              ArmPath="/subscriptions/sub1/hostpool/hp1">
                        <ResourceFile URL="https://rdweb.wvd.microsoft.com/api/arm/rdp/cloudpc01.rdp" />
                        <Icon32 FileURL="https://rdweb.wvd.microsoft.com/api/arm/icons/cloudpc01.png" />
                    </Resource>
                    <Resource ID="/subscriptions/sub1/resourceGroups/rg1/providers/Microsoft.DesktopVirtualization/hostpools/hp2"
                              Title="Excel 365 RemoteApp"
                              Type="RemoteApp"
                              ArmPath="/subscriptions/sub1/hostpool/hp2">
                        <ResourceFile URL="https://rdweb.wvd.microsoft.com/api/arm/rdp/excel.rdp" />
                        <Icon32 FileURL="https://rdweb.wvd.microsoft.com/api/arm/icons/excel.png" />
                    </Resource>
                </Resources>
            </Publisher>
        </ResourceCollection>
        """;

    @Test
    @DisplayName("parseDiscoveryXml parses all TenantFeedURL elements with URLs and IDs")
    void testDiscoveryXmlParsing() {
        List<TenantFeed> feeds = WorkspaceFeedParser.parseDiscoveryXml(SAMPLE_DISCOVERY_XML);

        assertThat(feeds).hasSize(2);

        TenantFeed f1 = feeds.get(0);
        assertThat(f1.tenantId()).isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(f1.tenantDisplayName()).isEqualTo("Contoso Cloud PCs");
        assertThat(f1.feedUrl()).isEqualTo(URI.create("https://rdweb.wvd.microsoft.com/api/arm/feeddiscovery/tenant/t1"));

        TenantFeed f2 = feeds.get(1);
        assertThat(f2.tenantId()).isEqualTo("00000000-0000-0000-0000-000000000002");
        assertThat(f2.tenantDisplayName()).isEqualTo("Fabrikam Desktops");
        assertThat(f2.feedUrl()).isEqualTo(URI.create("https://rdweb.wvd.microsoft.com/api/arm/feeddiscovery/tenant/t2"));
    }

    @Test
    @DisplayName("parseFeedXml parses workspaces, publishers, resources, RDP URLs and icons")
    void testWorkspaceFeedXmlParsing() {
        TenantFeed tenant = new TenantFeed(
            "00000000-0000-0000-0000-000000000001",
            "Contoso Cloud PCs",
            URI.create("https://rdweb.wvd.microsoft.com/api/arm/feeddiscovery/tenant/t1")
        );

        Workspace ws = WorkspaceFeedParser.parseFeedXml(SAMPLE_WORKSPACE_XML, tenant);

        assertThat(ws.name()).isEqualTo("Contoso Cloud PCs");
        assertThat(ws.tenantId()).isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(ws.tenantDisplayName()).isEqualTo("Contoso Cloud PCs");

        List<WorkspaceResource> resources = ws.resources();
        assertThat(resources).hasSize(2);

        WorkspaceResource r1 = resources.get(0);
        assertThat(r1.title()).isEqualTo("Windows 365 Cloud PC 01");
        assertThat(r1.type()).isEqualTo(ResourceType.DESKTOP);
        assertThat(r1.type().isDesktop()).isTrue();
        assertThat(r1.publisher()).isEqualTo("Contoso Enterprise");
        assertThat(r1.rdpUrl()).isEqualTo(URI.create("https://rdweb.wvd.microsoft.com/api/arm/rdp/cloudpc01.rdp"));
        assertThat(r1.iconUrl()).isEqualTo(URI.create("https://rdweb.wvd.microsoft.com/api/arm/icons/cloudpc01.png"));

        WorkspaceResource r2 = resources.get(1);
        assertThat(r2.title()).isEqualTo("Excel 365 RemoteApp");
        assertThat(r2.type()).isEqualTo(ResourceType.REMOTE_APP);
        assertThat(r2.type().isRemoteApp()).isTrue();
        assertThat(r2.publisher()).isEqualTo("Contoso Enterprise");
        assertThat(r2.rdpUrl()).isEqualTo(URI.create("https://rdweb.wvd.microsoft.com/api/arm/rdp/excel.rdp"));
        assertThat(r2.iconUrl()).isEqualTo(URI.create("https://rdweb.wvd.microsoft.com/api/arm/icons/excel.png"));
    }

    @Test
    @DisplayName("parseDiscoveryXml and parseFeedXml return empty list/workspace on empty XML")
    void testEmptyXmlHandling() {
        assertThat(WorkspaceFeedParser.parseDiscoveryXml("")).isEmpty();
        assertThat(WorkspaceFeedParser.parseDiscoveryXml(null)).isEmpty();

        Workspace ws = WorkspaceFeedParser.parseFeedXml("", null);
        assertThat(ws.resources()).isEmpty();
    }

    @Test
    @DisplayName("parseDiscoveryXml and parseFeedXml handle UTF-8 Byte Order Mark (BOM)")
    void testBomXmlHandling() {
        String discoveryWithBom = "\uFEFF" + SAMPLE_DISCOVERY_XML;
        List<TenantFeed> feeds = WorkspaceFeedParser.parseDiscoveryXml(discoveryWithBom);
        assertThat(feeds).hasSize(2);

        String feedWithBom = "\uFEFF" + SAMPLE_WORKSPACE_XML;
        Workspace ws = WorkspaceFeedParser.parseFeedXml(feedWithBom, feeds.get(0));
        assertThat(ws.resources()).hasSize(2);
    }
}
