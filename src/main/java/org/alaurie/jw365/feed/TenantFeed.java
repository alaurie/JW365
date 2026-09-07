package org.alaurie.jw365.feed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.util.Objects;

/**
 * Tenant feed endpoint returned by AVD ARM feed discovery.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TenantFeed(
    @JsonProperty("tenantId") String tenantId,
    @JsonProperty("tenantDisplayName") String tenantDisplayName,
    @JsonProperty("feedUrl") URI feedUrl
) {

    public TenantFeed {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(feedUrl, "feedUrl must not be null");
        if (tenantDisplayName == null || tenantDisplayName.isBlank()) {
            tenantDisplayName = tenantId;
        }
    }
}
