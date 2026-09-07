package org.alaurie.jw365.rdp;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Launch configuration parameters for an RDP session.
 */
public record RdpSessionConfig(
    Path rdpFile,
    String username,
    boolean fullscreen,
    int scalePercent,
    boolean sound,
    boolean microphone,
    boolean multiMonitor,
    boolean ignoreCert,
    boolean clipboard,
    boolean dynamicResolution,
    boolean gfxProgressive,
    boolean asyncUpdate,
    boolean autoReconnect,
    boolean shareFolder,
    String sharedFolderPath,
    List<String> extraArgs
) {

    public RdpSessionConfig {
        Objects.requireNonNull(rdpFile, "rdpFile must not be null");
        extraArgs = extraArgs != null ? List.copyOf(extraArgs) : Collections.emptyList();
    }

    public RdpSessionConfig(
        Path rdpFile,
        String username,
        boolean fullscreen,
        int scalePercent,
        boolean sound,
        boolean microphone,
        boolean multiMonitor,
        boolean ignoreCert,
        boolean clipboard,
        boolean dynamicResolution,
        List<String> extraArgs
    ) {
        this(rdpFile, username, fullscreen, scalePercent, sound, microphone, multiMonitor, ignoreCert, clipboard, dynamicResolution, true, true, true, false, null, extraArgs);
    }

    public RdpSessionConfig(
        Path rdpFile,
        String username,
        boolean fullscreen,
        int scalePercent,
        boolean sound,
        boolean microphone,
        boolean multiMonitor,
        boolean ignoreCert,
        List<String> extraArgs
    ) {
        this(rdpFile, username, fullscreen, scalePercent, sound, microphone, multiMonitor, ignoreCert, true, true, true, true, true, false, null, extraArgs);
    }

    public static RdpSessionConfig defaults(Path rdpFile, String username) {
        return new RdpSessionConfig(
            rdpFile,
            username,
            false,
            0,
            true,
            true,
            false,
            true,
            true,
            true,
            true,
            true,
            true,
            false,
            null,
            Collections.emptyList()
        );
    }
}
