package org.alaurie.jw365.rdp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FreeRdpLauncherTest {

    @Test
    @DisplayName("FreeRdpFlavor accurately maps binary names")
    void testFreeRdpFlavorDetection() {
        assertThat(FreeRdpFlavor.fromBinaryName("sdl-freerdp")).isEqualTo(FreeRdpFlavor.SDL_FREERDP);
        assertThat(FreeRdpFlavor.fromBinaryName("sdl-freerdp3")).isEqualTo(FreeRdpFlavor.SDL_FREERDP);
        assertThat(FreeRdpFlavor.fromBinaryName("xfreerdp3")).isEqualTo(FreeRdpFlavor.XFREERDP3);
        assertThat(FreeRdpFlavor.fromBinaryName("xfreerdp")).isEqualTo(FreeRdpFlavor.XFREERDP);
        assertThat(FreeRdpFlavor.fromBinaryName("wlfreerdp3")).isEqualTo(FreeRdpFlavor.WLFREERDP3);
        assertThat(FreeRdpFlavor.fromBinaryName("wlfreerdp")).isEqualTo(FreeRdpFlavor.WLFREERDP);
        assertThat(FreeRdpFlavor.fromBinaryName("flatpak")).isEqualTo(FreeRdpFlavor.FLATPAK);
        assertThat(FreeRdpFlavor.fromBinaryName("/opt/custom/myrdp")).isEqualTo(FreeRdpFlavor.CUSTOM);
    }

    @Test
    @DisplayName("buildCommandLine composes correct FreeRDP parameters with gateway, AAD auth, audio, and display options")
    void testBuildCommandLine(@TempDir Path tempDir) {
        Path rdpFile = tempDir.resolve("test-session.rdp");
        Path binPath = Path.of("/usr/bin/sdl-freerdp");

        FreeRdpInfo freeRdp = new FreeRdpInfo(binPath, FreeRdpFlavor.SDL_FREERDP, "FreeRDP 3.30.0", false, null);

        RdpSessionConfig config = new RdpSessionConfig(
            rdpFile,
            "alex@contoso.com",
            true, // fullscreen
            150,  // scale-desktop:150
            true, // sound
            true, // microphone
            true, // multiMonitor
            true, // ignoreCert
            List.of("/bpp:32")
        );

        List<String> cmd = RdpProcessSupervisor.buildCommandLine(freeRdp, config);

        assertThat(cmd.get(0)).isEqualTo("/usr/bin/sdl-freerdp");
        assertThat(cmd).contains(rdpFile.toAbsolutePath().toString());
        assertThat(cmd).contains("/gateway:type:arm");
        assertThat(cmd).contains("/sec:aad");
        assertThat(cmd).contains("/u:alex@contoso.com");
        assertThat(cmd).contains("/sound:sys:pulse,rate:48000,channel:2,quality:high");
        assertThat(cmd).contains("/microphone:sys:pulse,rate:48000");
        assertThat(cmd).contains("/usb:auto");
        assertThat(cmd).contains("/smartcard");
        assertThat(cmd).contains("/f");
        assertThat(cmd).contains("/scale-desktop:150");
        assertThat(cmd).contains("/multimon");
        assertThat(cmd).contains("/cert:ignore");
        assertThat(cmd).contains("+clipboard");
        assertThat(cmd).contains("+dynamic-resolution");
        assertThat(cmd).contains("/network:auto");
        assertThat(cmd).contains("+async-update");
        assertThat(cmd).contains("+async-channels");
        assertThat(cmd).contains("+auto-reconnect");
        assertThat(cmd).contains("/gfx:progressive");
        assertThat(cmd).contains("/gdi:hw");
        assertThat(cmd).contains("/bpp:32");
        assertThat(cmd).contains("/log-level:info");
    }
    @Test
    @DisplayName("buildCommandLine handles Flatpak FreeRDP invocation")
    void testBuildCommandLineFlatpak(@TempDir Path tempDir) {
        Path rdpFile = tempDir.resolve("cloudpc.rdp");

        FreeRdpInfo freeRdp = new FreeRdpInfo(null, FreeRdpFlavor.FLATPAK, "FreeRDP Flatpak", true, "com.freerdp.FreeRDP");

        RdpSessionConfig config = RdpSessionConfig.defaults(rdpFile, "user@tenant.onmicrosoft.com");

        List<String> cmd = RdpProcessSupervisor.buildCommandLine(freeRdp, config);

        assertThat(cmd.get(0)).isEqualTo("flatpak");
        assertThat(cmd.get(1)).isEqualTo("run");
        assertThat(cmd.get(2)).isEqualTo("--file-forwarding");
        assertThat(cmd.get(3)).isEqualTo("com.freerdp.FreeRDP");
        assertThat(cmd).contains("@@");
        assertThat(cmd).contains("/sec:aad");
        assertThat(cmd).contains("/u:user@tenant.onmicrosoft.com");
    }
}
