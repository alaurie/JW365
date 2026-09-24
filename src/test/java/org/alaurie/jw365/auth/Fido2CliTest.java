package org.alaurie.jw365.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Base64.Encoder;
import java.util.List;

import org.alaurie.jw365.auth.Fido2Cli.Assertion;
import org.alaurie.jw365.auth.Fido2Cli.Failure;
import org.alaurie.jw365.auth.Fido2Cli.Fido2Exception;
import org.alaurie.jw365.auth.Fido2Cli.ResidentCredential;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Fido2CliTest {

    private static final Encoder B64 = Base64.getEncoder();
    private static final Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    @Test
    @DisplayName("clientDataJSON keeps the spec key order and the base64url challenge")
    void clientDataJsonMatchesSpec() {
        byte[] json = Fido2Cli.clientDataJson("Y2hhbGxlbmdl", "https://login.microsoft.com");

        assertThat(new String(json, StandardCharsets.UTF_8)).isEqualTo("{\"type\":\"webauthn.get\",\"challenge\":\"Y2hhbGxlbmdl\",\"origin\":\"https://login.microsoft.com\",\"crossOrigin\":false}");
    }

    @Test
    @DisplayName("rpId must be the origin host or a registrable parent of it")
    void rpIdRule() {
        assertThat(Fido2Cli.rpIdMatchesOrigin("login.microsoft.com", "https://login.microsoft.com")).isTrue();
        assertThat(Fido2Cli.rpIdMatchesOrigin("microsoft.com", "https://login.microsoft.com")).isTrue();
        assertThat(Fido2Cli.rpIdMatchesOrigin("login.live.com", "https://login.microsoft.com")).isFalse();
        assertThat(Fido2Cli.rpIdMatchesOrigin("com", "https://login.microsoft.com")).isFalse();
        assertThat(Fido2Cli.rpIdMatchesOrigin("", "https://login.microsoft.com")).isFalse();
    }

    @Test
    @DisplayName("fido2-assert output with echoed input lines parses into a base64url assertion")
    void parsesAssertionOutputWithEcho() throws Exception {
        byte[] authData = new byte[37];
        authData[32] = 0x05;
        byte[] cborAuthData = new byte[39];
        cborAuthData[0] = 0x58;
        cborAuthData[1] = 37;
        System.arraycopy(authData, 0, cborAuthData, 2, 37);
        byte[] signature = {1, 2, 3, 4};
        byte[] userId = "user-1".getBytes(StandardCharsets.UTF_8);
        String stdout = String.join("\n", "aGFzaA==", "login.microsoft.com", B64.encodeToString(cborAuthData),
                B64.encodeToString(signature), B64.encodeToString(userId))
                + "\n";
        byte[] clientData = "{}".getBytes(StandardCharsets.UTF_8);

        Assertion assertion = Fido2Cli.parseAssertionOutput(stdout, "login.microsoft.com", clientData, "Y3JlZA");

        assertThat(assertion.credentialId()).isEqualTo("Y3JlZA");
        assertThat(assertion.authenticatorData()).isEqualTo(B64URL.encodeToString(authData));
        assertThat(assertion.signature()).isEqualTo(B64URL.encodeToString(signature));
        assertThat(assertion.userHandle()).isEqualTo(B64URL.encodeToString(userId));
        assertThat(assertion.clientDataJson()).isEqualTo(B64URL.encodeToString(clientData));
    }

    @Test
    @DisplayName("fido2-assert output without echo and without a user id still parses")
    void parsesAssertionOutputWithoutEcho() throws Exception {
        byte[] cborAuthData = {0x43, 9, 8, 7};
        String stdout = B64.encodeToString(cborAuthData) + "\n" + B64.encodeToString(new byte[] {1}) + "\n";

        Assertion assertion = Fido2Cli.parseAssertionOutput(stdout, "login.microsoft.com", new byte[0], "id");

        assertThat(assertion.authenticatorData()).isEqualTo(B64URL.encodeToString(new byte[] {9, 8, 7}));
        assertThat(assertion.userHandle()).isNull();
    }

    @Test
    @DisplayName("Truncated fido2-assert output is rejected")
    void rejectsShortAssertionOutput() {
        assertThatThrownBy(() -> Fido2Cli.parseAssertionOutput("only-one-line\n", "rp", new byte[0], "id")).isInstanceOf(Fido2Exception.class);
    }

    @Test
    @DisplayName("CBOR byte strings unwrap for short, one byte and two byte lengths")
    void unwrapsCborByteStrings() {
        assertThat(Fido2Cli.unwrapCborByteString(new byte[] {0x42, 1, 2})).containsExactly(1, 2);
        byte[] oneByteLength = new byte[26];
        oneByteLength[0] = 0x58;
        oneByteLength[1] = 24;
        assertThat(Fido2Cli.unwrapCborByteString(oneByteLength)).hasSize(24);
        byte[] twoByteLength = new byte[3 + 300];
        twoByteLength[0] = 0x59;
        twoByteLength[1] = 1;
        twoByteLength[2] = 44;
        assertThat(Fido2Cli.unwrapCborByteString(twoByteLength)).hasSize(300);
        assertThatThrownBy(() -> Fido2Cli.unwrapCborByteString(new byte[] {(byte) 0xA1})).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Resident credential listing parses names with spaces and the 1.17 payment column")
    void parsesResidentCredentialList() {
        String credId = B64.encodeToString("cred".getBytes(StandardCharsets.UTF_8));
        String userId = B64.encodeToString("user".getBytes(StandardCharsets.UTF_8));
        String stdout = "00: "
                + credId
                + " Nikolai Nyegaard "
                + userId
                + " es256 uvopt nopay\n"
                + "01: "
                + credId
                + " single "
                + userId
                + " eddsa uvreq\n";

        List<ResidentCredential> credentials = Fido2Cli.parseResidentCredentialList(stdout);

        assertThat(credentials).hasSize(2);
        assertThat(credentials.getFirst().credentialId()).isEqualTo(B64URL.encodeToString("cred".getBytes(StandardCharsets.UTF_8)));
        assertThat(credentials.getFirst().userHandle()).isEqualTo(B64URL.encodeToString("user".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("A credential line that does not parse fails closed instead of being skipped")
    void rejectsUnparseableResidentLine() {
        assertThatThrownBy(() -> Fido2Cli.parseResidentCredentialList("00: garbage\n")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Fido2Cli.parseResidentCredentialList("Existing resident credentials: 0\n")).isEmpty();
    }

    @Test
    @DisplayName("Device listing strips the trailing colon from hidraw paths")
    void parsesDeviceList() {
        List<String> devices = Fido2Cli.parseDeviceList("/dev/hidraw9: vendor=0x1050, product=0x0402 (Yubico YubiKey FIDO)\n");

        assertThat(devices).containsExactly("/dev/hidraw9");
    }

    @Test
    @DisplayName("libfido2 error names map to failure kinds")
    void classifiesFailures() {
        assertThat(Fido2Cli.failureFrom("fido2-assert: fido_dev_get_assert: FIDO_ERR_NO_CREDENTIALS", "fido2-assert").failure()).isEqualTo(Failure.NO_CREDENTIALS);
        assertThat(Fido2Cli.failureFrom("FIDO_ERR_PIN_INVALID", "fido2-assert").failure()).isEqualTo(Failure.PIN_INVALID);
        assertThat(Fido2Cli.failureFrom("FIDO_ERR_PIN_AUTH_BLOCKED", "fido2-assert").failure()).isEqualTo(Failure.PIN_BLOCKED);
        assertThat(Fido2Cli.failureFrom("FIDO_ERR_USER_ACTION_TIMEOUT", "fido2-assert").failure()).isEqualTo(Failure.TIMEOUT);
        assertThat(Fido2Cli.failureFrom("something else", "fido2-token").failure()).isEqualTo(Failure.OTHER);
    }

    @Test
    @DisplayName("Values on the line based stdin protocol lose control characters")
    void sanitizesStdinValues() {
        assertThat(Fido2Cli.sanitize("login.microsoft.com\ninjected")).isEqualTo("login.microsoft.cominjected");
        assertThat(Fido2Cli.sanitize(null)).isEmpty();
    }
}
