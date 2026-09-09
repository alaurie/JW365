package org.alaurie.jw365.feed;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.util.Objects;

/**
 * An individual Cloud PC Desktop or RemoteApp resource in a Windows 365 / AVD workspace.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkspaceResource(
    @JsonProperty("id") String id,
    @JsonProperty("title") String title,
    @JsonProperty("type") ResourceType type,
    @JsonProperty("tenantName") String tenantName,
    @JsonProperty("tenantId") String tenantId,
    @JsonProperty("publisher") String publisher,
    @JsonProperty("armPath") String armPath,
    @JsonProperty("rdpUrl") URI rdpUrl,
    @JsonProperty("iconUrl") URI iconUrl
) {

    public WorkspaceResource {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(title, "title must not be null");
        if (type == null) {
            type = ResourceType.UNKNOWN;
        }
    }

    /**
     * Sanitizes resource ID for use as a filesystem file name.
     */
    @JsonIgnore
    public String sanitizedFileName() {
        return id.replaceAll("[^a-zA-Z0-9._-]", "_");
    }


    @JsonIgnore
    public String cacheFileName() {
        return (tenantId + "_" + id).replaceAll("[^a-zA-Z0-9._-]", "_");
    }
    /**
     * Display label combining title and publisher if available.
     */
    @JsonIgnore
    public String displaySubtitle() {
        if (publisher != null && !publisher.isBlank()) {
            return publisher;
        }
        if (tenantName != null && !tenantName.isBlank()) {
            return tenantName;
        }
        return type.getValue();
    }
}
