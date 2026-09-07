package org.alaurie.jw365.feed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A workspace grouping published resources for a tenant.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Workspace(
    @JsonProperty("name") String name,
    @JsonProperty("tenantId") String tenantId,
    @JsonProperty("tenantDisplayName") String tenantDisplayName,
    @JsonProperty("resources") List<WorkspaceResource> resources
) {

    public Workspace {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (tenantDisplayName == null || tenantDisplayName.isBlank()) {
            tenantDisplayName = tenantId;
        }
        resources = resources != null ? List.copyOf(resources) : Collections.emptyList();
    }
}
