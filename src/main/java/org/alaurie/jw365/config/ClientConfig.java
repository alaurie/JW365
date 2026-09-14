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
    @JsonProperty("preventSessionLock") boolean preventSessionLock,
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
        this(defaultTenant, FreeRdpSource.AUTO, preferredFreeRdpPath, scalePercent, fullscreen, sound, microphone, multiMonitor, ignoreCert, true, true, true, true, true, false, false, false, true, autoRefreshMinutes, extraArgs);
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
            true,
            15,
            Collections.emptyList()
        );
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public static ClientConfig create(
        @JsonProperty("defaultTenant") String defaultTenant,
        @JsonProperty("freerdpSource") FreeRdpSource freerdpSource,
        @JsonProperty("preferredFreeRdpPath") String preferredFreeRdpPath,
        @JsonProperty("scalePercent") Integer scalePercent,
        @JsonProperty("fullscreen") Boolean fullscreen,
        @JsonProperty("sound") Boolean sound,
        @JsonProperty("microphone") Boolean microphone,
        @JsonProperty("multiMonitor") Boolean multiMonitor,
        @JsonProperty("ignoreCert") Boolean ignoreCert,
        @JsonProperty("clipboard") Boolean clipboard,
        @JsonProperty("dynamicResolution") Boolean dynamicResolution,
        @JsonProperty("gfxProgressive") Boolean gfxProgressive,
        @JsonProperty("asyncUpdate") Boolean asyncUpdate,
        @JsonProperty("autoReconnect") Boolean autoReconnect,
        @JsonProperty("autoConnect") Boolean autoConnect,
        @JsonProperty("usbRedirection") Boolean usbRedirection,
        @JsonProperty("smartcard") Boolean smartcard,
        @JsonProperty("preventSessionLock") Boolean preventSessionLock,
        @JsonProperty("autoRefreshMinutes") Integer autoRefreshMinutes,
        @JsonProperty("extraArgs") List<String> extraArgs
    ) {
        return new ClientConfig(
            defaultTenant,
            freerdpSource,
            preferredFreeRdpPath,
            scalePercent != null ? scalePercent : 0,
            Boolean.TRUE.equals(fullscreen),
            sound == null || sound,
            microphone != null && microphone,
            Boolean.TRUE.equals(multiMonitor),
            Boolean.TRUE.equals(ignoreCert),
            clipboard == null || clipboard,
            dynamicResolution == null || dynamicResolution,
            gfxProgressive == null || gfxProgressive,
            asyncUpdate == null || asyncUpdate,
            autoReconnect == null || autoReconnect,
            Boolean.TRUE.equals(autoConnect),
            Boolean.TRUE.equals(usbRedirection),
            Boolean.TRUE.equals(smartcard),
            preventSessionLock == null || preventSessionLock,
            autoRefreshMinutes != null ? autoRefreshMinutes : 15,
            extraArgs
        );
    }
}