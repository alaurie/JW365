package org.alaurie.jw365.rdp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    @DisplayName(
            "buildCommandLine composes correct FreeRDP parameters with gateway, AAD auth, audio, and display options")
    void testBuildCommandLine(@TempDir Path tempDir) {
        Path rdpFile = tempDir.resolve("test-session.rdp");
        Path binPath = Path.of("/usr/bin/sdl-freerdp");

        FreeRdpInfo freeRdp = new FreeRdpInfo(binPath, FreeRdpFlavor.SDL_FREERDP, "FreeRDP 3.30.0", false, null);

        RdpSessionConfig config = new RdpSessionConfig(
                rdpFile,
                "alex@contoso.com",
                true, // fullscreen
                150, // scale-desktop:150
                true, // sound
                true, // microphone
                true, // multiMonitor
                true, // ignoreCert
                List.of("/bpp:32"));

        List<String> cmd = RdpProcessSupervisor.buildCommandLine(freeRdp, config);

        assertThat(cmd.getFirst()).isEqualTo("/usr/bin/sdl-freerdp");
        assertThat(cmd).contains(rdpFile.toAbsolutePath().toString());
        assertThat(cmd).contains("/gateway:type:arm");
        assertThat(cmd).contains("/sec:aad");
        assertThat(cmd).contains("/u:alex@contoso.com");
        assertThat(cmd).contains("/sound:sys:pulse,rate:48000,channel:2,quality:high");
        assertThat(cmd).contains("/microphone:sys:pulse,rate:48000");
        assertThat(cmd).contains("/usb:auto");
        assertThat(cmd).contains("/smartcard");
        assertThat(cmd).contains("/f");
        assertThat(cmd).doesNotContain("/scale-desktop:150");
        assertThat(cmd).contains("/multimon:force");
        assertThat(cmd).contains("/cert:ignore");
        assertThat(cmd).contains("+clipboard");
        assertThat(cmd).doesNotContain("+dynamic-resolution");
        assertThat(cmd).contains("/network:auto");
        assertThat(cmd).contains("+compression");
        assertThat(cmd).contains("+fonts");
        assertThat(cmd).contains("+aero");
        assertThat(cmd).doesNotContain("+async-update");
        assertThat(cmd).contains("+async-channels");
        assertThat(cmd).contains("+auto-reconnect");
        assertThat(cmd).contains("/prevent-session-lock:120");
        assertThat(cmd).contains("/gfx:AVC420,progressive");
        assertThat(cmd).contains("+rfx");
        assertThat(cmd).contains("/gdi:sw");
        assertThat(cmd).doesNotContain("/gdi:hw");
        assertThat(cmd).contains("/bpp:32");
        assertThat(cmd).contains("/log-level:info");
    }

    @Test
    @DisplayName("buildCommandLine handles Flatpak FreeRDP invocation")
    void testBuildCommandLineFlatpak(@TempDir Path tempDir) {
        Path rdpFile = tempDir.resolve("cloudpc.rdp");

        FreeRdpInfo freeRdp =
                new FreeRdpInfo(null, FreeRdpFlavor.FLATPAK, "FreeRDP Flatpak", true, "com.freerdp.FreeRDP");

        RdpSessionConfig config = RdpSessionConfig.defaults(rdpFile, "user@tenant.onmicrosoft.com");

        List<String> cmd = RdpProcessSupervisor.buildCommandLine(freeRdp, config);

        assertThat(cmd.getFirst()).isEqualTo("flatpak");
        assertThat(cmd.get(1)).isEqualTo("run");
        assertThat(cmd.get(2)).isEqualTo("--file-forwarding");
        assertThat(cmd.get(3)).isEqualTo("com.freerdp.FreeRDP");
        assertThat(cmd).contains("@@");
        assertThat(cmd).contains("/sec:aad");
        assertThat(cmd).contains("/u:user@tenant.onmicrosoft.com");
        assertThat(cmd).contains("/prevent-session-lock:120");
    }

    @Test
    @DisplayName("buildCommandLine omits /prevent-session-lock when disabled or overridden in extraArgs")
    void testPreventSessionLockOptions(@TempDir Path tempDir) {
        Path rdpFile = tempDir.resolve("session.rdp");
        FreeRdpInfo freeRdp = new FreeRdpInfo(
                Path.of("/usr/bin/sdl-freerdp"), FreeRdpFlavor.SDL_FREERDP, "FreeRDP 3.30.0", false, null);

        // Disabled
        RdpSessionConfig disabled = new RdpSessionConfig(
                rdpFile,
                "user@tenant.com",
                false,
                0,
                true,
                false,
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
                List.of());
        List<String> disabledCmd = RdpProcessSupervisor.buildCommandLine(freeRdp, disabled);
        assertThat(disabledCmd).noneMatch(arg -> arg.startsWith("/prevent-session-lock"));

        // Overridden with custom interval in extraArgs
        RdpSessionConfig customExtra = new RdpSessionConfig(
                rdpFile,
                "user@tenant.com",
                false,
                0,
                true,
                false,
                false,
                false,
                true,
                true,
                true,
                true,
                true,
                false,
                false,
                true,
                List.of("/prevent-session-lock:60"));
        List<String> customCmd = RdpProcessSupervisor.buildCommandLine(freeRdp, customExtra);
        assertThat(customCmd).contains("/prevent-session-lock:60");
        assertThat(customCmd).doesNotContain("/prevent-session-lock:120");
    }

    @Test
    @DisplayName("prepareRdpProfile disables smartcard and usb redirection when config sets them false")
    void testPrepareRdpProfile(@TempDir Path tempDir) throws Exception {
        Path rdpFile = tempDir.resolve("original.rdp");
        String rdpContent = """
            full address:s:rdgateway.wvd.microsoft.com
            redirectsmartcards:i:1
            devicestoredirect:s:*
            usbdevicestoredirect:s:*
            redirectclipboard:i:1
            """;
        java.nio.file.Files.writeString(rdpFile, rdpContent);

        RdpSessionConfig config = new RdpSessionConfig(
                rdpFile,
                "user@tenant.com",
                false,
                0,
                true,
                false,
                false,
                false,
                true,
                true,
                true,
                true,
                true,
                false, // usbRedirection false
                false, // smartcard false
                true,
                List.of());

        Path active = RdpProcessSupervisor.prepareRdpProfile(rdpFile, config);
        assertThat(active).isNotEqualTo(rdpFile);
        String activeContent = java.nio.file.Files.readString(active);
        assertThat(activeContent).contains("redirectsmartcards:i:0");
        assertThat(activeContent).contains("devicestoredirect:s:");
        assertThat(activeContent).contains("usbdevicestoredirect:s:");
        assertThat(activeContent).contains("redirectclipboard:i:1");
    }
}
