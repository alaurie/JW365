package org.alaurie.jw365.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.alaurie.jw365.auth.OAuthClient;
import org.alaurie.jw365.rdp.FreeRdpSource;

import java.util.Collections;
import java.util.List;

/**
 * User and client preferences for JW365.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClientConfig(
    @JsonProperty("defaultTenant") String defaultTenant,
    @JsonProperty("freerdpSource") FreeRdpSource freerdpSource,
    @JsonProperty("preferredFreeRdpPath") String preferredFreeRdpPath,
    @JsonProperty("scalePercent") int scalePercent,
    @JsonProperty("fullscreen") boolean fullscreen,
    @JsonProperty("sound") boolean sound,
    @JsonProperty("microphone") boolean microphone,
    @JsonProperty("multiMonitor") boolean multiMonitor,
    @JsonProperty("ignoreCert") boolean ignoreCert,
    @JsonProperty("clipboard") boolean clipboard,
    @JsonProperty("dynamicResolution") boolean dynamicResolution,
    @JsonProperty("gfxProgressive") boolean gfxProgressive,
    @JsonProperty("asyncUpdate") boolean asyncUpdate,
    @JsonProperty("autoReconnect") boolean autoReconnect,
    @JsonProperty("autoConnect") boolean autoConnect,
    @JsonProperty("usbRedirection") boolean usbRedirection,
    @JsonProperty("smartcard") boolean smartcard,
    @JsonProperty("autoRefreshMinutes") int autoRefreshMinutes,
    @JsonProperty("extraArgs") List<String> extraArgs
) {

    public ClientConfig {
        if (defaultTenant == null || defaultTenant.isBlank()) {
            defaultTenant = OAuthClient.DEFAULT_TENANT;
        }
        freerdpSource = freerdpSource == null ? FreeRdpSource.AUTO : freerdpSource;
        if (autoRefreshMinutes <= 0) {
            autoRefreshMinutes = 15;
        }
        extraArgs = extraArgs != null ? List.copyOf(extraArgs) : Collections.emptyList();
    }


    public ClientConfig(
        String defaultTenant,
        String preferredFreeRdpPath,
        int scalePercent,
        boolean fullscreen,
        boolean sound,
        boolean microphone,
        boolean multiMonitor,
        boolean ignoreCert,
        int autoRefreshMinutes,
        List<String> extraArgs
    ) {
        this(defaultTenant, FreeRdpSource.AUTO, preferredFreeRdpPath, scalePercent, fullscreen, sound, microphone, multiMonitor, ignoreCert, true, true, true, true, true, false, false, false, autoRefreshMinutes, extraArgs);
    }

    public static ClientConfig defaultConfig() {
        return new ClientConfig(
            OAuthClient.DEFAULT_TENANT,
            FreeRdpSource.AUTO,
            null,
            0,
            false,
            true,
            true,
            false,
            false,
            true,
            true,
            true,
            true,
            true,
            false,
            false,
            false,
            15,
            Collections.emptyList()
        );
    }
}