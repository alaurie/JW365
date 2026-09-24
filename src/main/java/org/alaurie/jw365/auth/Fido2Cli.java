package org.alaurie.jw365.auth;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.Base64.Encoder;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.alaurie.jw365.util.ExecutableLocator;

/**
 * Drives a USB FIDO2 security key through the libfido2 command line tools
 * (fido2-assert, fido2-token) to produce WebAuthn assertions. One instance
 * serves one ceremony; {@link #cancel()} aborts whatever is in flight.
 */
public final class Fido2Cli {

    /** Why a ceremony failed, mapped from libfido2 error names on stderr. */
    public enum Failure {
        NO_DEVICE,
        NO_CREDENTIALS,
        PIN_INVALID,
        PIN_BLOCKED,
        TIMEOUT,
        CANCELLED,
        UNSUPPORTED,
        OTHER
    }

    public static final class Fido2Exception extends Exception {
        private static final long serialVersionUID = 1L;
        private final Failure failure;

        public Fido2Exception(Failure failure, String message) {
            super(message);
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }

    /**
     * A WebAuthn get() request as sent by the polyfill. All binary fields are
     * base64url.
     */
    public record Request(String origin, String rpId, String challenge,
                          List<String> allowCredentials, String userVerification, long timeoutMs) {

        public boolean requiresUserVerification() {
            return "required".equals(userVerification);
        }
    }

    /** A completed assertion. All binary fields are base64url. */
    public record Assertion(String credentialId, String authenticatorData, String clientDataJson,
                            String signature, String userHandle) {}

    /**
     * One resident (discoverable) credential as listed by fido2-token.
     * Base64url fields.
     */
    public record ResidentCredential(String credentialId, String userHandle) {}

    private record CliResult(int exitCode, String stdout, String stderr) {}

    private static final long PROBE_TIMEOUT_MS = 5_000;
    private static final long DEFAULT_TIMEOUT_MS = 60_000;
    private static final long MIN_TIMEOUT_MS = 1_000;
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;
    private static final Pattern DEVICE_LINE = Pattern.compile("^(/dev/\\S+?):");
    private static final Pattern RESIDENT_INDEX = Pattern.compile("^\\d+:$");
    private static final Pattern STANDARD_BASE64 = Pattern.compile("^[A-Za-z0-9+/]+={0,2}$");
    private static final Set<String> RESIDENT_KEY_TYPES = Set.of("es256", "es384", "rs256", "eddsa");
    private static final Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Decoder B64URL_DECODER = Base64.getUrlDecoder();

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile Process current;

    /** True when the fido2-tools binaries this class needs are on PATH. */
    public static boolean isAvailable() {
        return ExecutableLocator.findOnPath("fido2-assert").isPresent() && ExecutableLocator.findOnPath("fido2-token").isPresent();
    }

    /**
     * Aborts the running ceremony; the pending call fails with {@link
     * Failure#CANCELLED}.
     */
    public void cancel() {
        cancelled.set(true);
        Process process = current;
        if (process != null) {
            process.destroyForcibly();
        }
    }

    /** First connected FIDO2 device path, from {@code fido2-token -L}. */
    public Optional<String> findDevice() throws Fido2Exception {
        CliResult result = run(List.of("fido2-token", "-L"), List.of(), null, PROBE_TIMEOUT_MS);
        if (result.exitCode() != 0) {
            return Optional.empty();
        }
        return parseDeviceList(result.stdout()).stream().findFirst();
    }

    static List<String> parseDeviceList(String stdout) {
        List<String> devices = new ArrayList<>();
        for (String line : stdout.split("\n")) {
            Matcher matcher = DEVICE_LINE.matcher(line.trim());
            if (matcher.find()) {
                devices.add(matcher.group(1));
            }
        }
        return devices;
    }

    /**
     * Runs the full assertion ceremony on {@code device}. The PIN is only used
     * when user verification is required or the request has no allowCredentials
     * (listing resident credentials is PIN gated on every key).
     */
    public Assertion getAssertion(Request request, String device, String pin) throws Fido2Exception {
        byte[] clientDataJson = clientDataJson(request.challenge(), request.origin());
        String clientDataHash = Base64.getEncoder().encodeToString(sha256(clientDataJson));
        String rpId = sanitize(request.rpId());
        List<String> inputLines = List.of(clientDataHash, rpId);
        long timeoutMs = request.timeoutMs() > 0 ? Math.max(MIN_TIMEOUT_MS, request.timeoutMs()) : DEFAULT_TIMEOUT_MS;
        long startedAt = System.currentTimeMillis();

        List<String> candidates = request.allowCredentials();
        String residentUserHandle = null;
        if (candidates.isEmpty()) {
            List<ResidentCredential> resident = listResidentCredentials(rpId, device, pin, timeoutMs);
            if (resident.isEmpty()) {
                throw new Fido2Exception(Failure.NO_CREDENTIALS, "No passkey for this site is stored on the security key. Start the sign-in by entering your email address instead.");
            }
            if (resident.size() > 1) {
                // ponytail: no account picker; add one when a key with several accounts for one site turns up
                throw new Fido2Exception(Failure.UNSUPPORTED, "The security key holds passkeys for more than one account on this site. Start the sign-in by entering your email address instead.");
            }
            candidates = List.of(resident.getFirst()
                    .credentialId());
            residentUserHandle = resident.getFirst().userHandle();
        } else if (candidates.size() > 1) {
            candidates = narrowCandidates(candidates, inputLines, device);
        }

        List<String> args = new ArrayList<>(List.of("fido2-assert", "-G"));
        if (request.requiresUserVerification()) {
            args.add("-v");
        }
        args.add(device);
        String assertPin = request.requiresUserVerification() ? pin : null;

        Fido2Exception lastFailure = null;
        for (String credentialId : candidates) {
            long remainingMs = Math.max(MIN_TIMEOUT_MS, timeoutMs - (System.currentTimeMillis() - startedAt));
            List<String> lines = new ArrayList<>(inputLines);
            lines.add(toStandardBase64(credentialId));
            CliResult result = run(args, lines, assertPin, remainingMs);
            if (result.exitCode() == 0) {
                Assertion assertion = parseAssertionOutput(result.stdout(), rpId, clientDataJson, credentialId);
                if (assertion.userHandle() == null && residentUserHandle != null) {
                    assertion = new Assertion(assertion.credentialId(), assertion.authenticatorData(), assertion.clientDataJson(),
                            assertion.signature(), residentUserHandle);
                }
                return assertion;
            }
            lastFailure = failureFrom(result.stderr(), "fido2-assert");
            // Only "this credential is not on this key" is worth trying the next candidate.
            if (lastFailure.failure() != Failure.NO_CREDENTIALS) {
                throw lastFailure;
            }
        }
        throw lastFailure != null ? lastFailure : new Fido2Exception(Failure.OTHER, "No credential to assert with");
    }

    /**
     * Silent probes ({@code -t up=false}) find the one listed credential that
     * lives on this key, so the user touches once instead of once per entry.
     * Falls back to the full list when nothing probes present.
     */
    private List<String> narrowCandidates(List<String> allowCredentials, List<String> inputLines, String device) throws Fido2Exception {
        for (String credentialId : allowCredentials) {
            List<String> lines = new ArrayList<>(inputLines);
            lines.add(toStandardBase64(credentialId));
            CliResult result = run(List.of("fido2-assert", "-G", "-t", "up=false", device),
                    lines, null, PROBE_TIMEOUT_MS);
            if (result.exitCode() == 0) {
                return List.of(credentialId);
            }
            throwIfCancelled();
        }
        return allowCredentials;
    }

    private List<ResidentCredential> listResidentCredentials(String rpId, String device, String pin,
            long timeoutMs)
            throws Fido2Exception {
        CliResult result = run(List.of("fido2-token", "-L", "-k", rpId, device),
                List.of(), pin, timeoutMs);
        if (result.exitCode() != 0) {
            Fido2Exception failure = failureFrom(result.stderr(), "fido2-token");
            if (failure.failure() == Failure.OTHER) {
                throw new Fido2Exception(Failure.UNSUPPORTED, "The security key cannot list its passkeys (CTAP 2.1 credential management). Start the sign-in by entering your email address instead.");
            }
            throw failure;
        }
        try {
            return parseResidentCredentialList(result.stdout());
        } catch (IllegalArgumentException e) {
            throw new Fido2Exception(Failure.UNSUPPORTED, "Could not read the passkey listing from the security key. Start the sign-in by entering your email address instead.");
        }
    }

    /**
     * Parses {@code fido2-token -L -k} output. Each line reads {@code "NN:
     * <credId> <displayName> <userId> <type> <prot>"} with libfido2 1.17 adding
     * a trailing pay/nopay column. The display name may contain spaces, so
     * fixed fields are anchored from the end.
     */
    static List<ResidentCredential> parseResidentCredentialList(String stdout) {
        List<ResidentCredential> credentials = new ArrayList<>();
        for (String line : stdout.split("\n")) {
            List<String> parts = new ArrayList<>(Arrays.asList(line.trim()
                    .split("\\s+")));
            if (!RESIDENT_INDEX.matcher(parts.getFirst()).matches()) {
                continue;
            }
            String last = parts.getLast();
            if ("pay".equals(last) || "nopay".equals(last)) {
                parts.removeLast();
            }
            if (parts.size() < 6) {
                throw new IllegalArgumentException("Unexpected credential listing line");
            }
            String credentialId = parts.get(1);
            String userId = parts.get(parts.size() - 3);
            String type = parts.get(parts.size() - 2);
            if (!RESIDENT_KEY_TYPES.contains(type) || !STANDARD_BASE64.matcher(credentialId).matches() || !STANDARD_BASE64.matcher(userId).matches()) {
                throw new IllegalArgumentException("Unexpected credential listing line");
            }
            credentials.add(
                    new ResidentCredential(B64URL.encodeToString(Base64.getDecoder().decode(credentialId)),
                            B64URL.encodeToString(Base64.getDecoder().decode(userId))));
        }
        return credentials;
    }

    /**
     * Parses {@code fido2-assert -G} output: client data hash, rpId, CBOR wrapped
     * authenticator data, signature, and the user id when the credential is
     * resident. Older tool versions omit the first two echo lines.
     */
    static Assertion parseAssertionOutput(String stdout, String rpId, byte[] clientDataJson,
            String credentialId)
            throws Fido2Exception {
        List<String> lines = new ArrayList<>(Arrays.asList(stdout.trim()
                .split("\n")));
        if (lines.size() > 2 && lines.get(1).equals(rpId)) {
            lines = lines.subList(2, lines.size());
        }
        if (lines.size() < 2) {
            throw new Fido2Exception(Failure.OTHER, "Unexpected fido2-assert output");
        }
        try {
            byte[] authData = unwrapCborByteString(Base64.getDecoder()
                    .decode(lines.get(0)
                                 .trim()));
            byte[] signature = Base64.getDecoder().decode(lines.get(1)
                    .trim());
            String userHandle = lines.size() >= 3 && !lines.get(2).isBlank()
                    ? B64URL.encodeToString(Base64.getDecoder()
                            .decode(lines.get(2)
                                         .trim()))
                    : null;
            return new Assertion(credentialId, B64URL.encodeToString(authData), B64URL.encodeToString(clientDataJson),
                    B64URL.encodeToString(signature), userHandle);
        } catch (IllegalArgumentException e) {
            throw new Fido2Exception(Failure.OTHER, "Unexpected fido2-assert output");
        }
    }

    /** Unwraps a CBOR major type 2 (byte string) item into its payload. */
    static byte[] unwrapCborByteString(byte[] cbor) {
        if (cbor.length == 0 || (cbor[0] & 0xE0) != 0x40) {
            throw new IllegalArgumentException("Not a CBOR byte string");
        }
        int additional = cbor[0] & 0x1F;
        int offset;
        long length;
        if (additional < 24) {
            offset = 1;
            length = additional;
        } else if (additional == 24) {
            offset = 2;
            length = cbor[1] & 0xFF;
        } else if (additional == 25) {
            offset = 3;
            length = ((cbor[1] & 0xFF) << 8) | (cbor[2] & 0xFF);
        } else if (additional == 26) {
            offset = 5;
            length = ((long) (cbor[1] & 0xFF) << 24)
                     | ((cbor[2] & 0xFF) << 16)
                     | ((cbor[3] & 0xFF) << 8)
                     | (cbor[4] & 0xFF);
        } else {
            throw new IllegalArgumentException("Unsupported CBOR length encoding");
        }
        if (offset + length > cbor.length) {
            throw new IllegalArgumentException("Truncated CBOR byte string");
        }
        return Arrays.copyOfRange(cbor, offset, (int) (offset + length));
    }

    /**
     * clientDataJSON per the WebAuthn spec; key order is type, challenge,
     * origin, crossOrigin.
     */
    static byte[] clientDataJson(String challengeBase64Url, String origin) {
        String json = "{\"type\":\"webauthn.get\",\"challenge\":\"" + challengeBase64Url + "\",\"origin\":\"" + origin + "\",\"crossOrigin\":false}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The WebAuthn rpId rule: the relying party id must equal the origin's host
     * or be a registrable parent of it.
     */
    public static boolean rpIdMatchesOrigin(String rpId, String origin) {
        if (rpId == null || rpId.isBlank() || origin == null) {
            return false;
        }
        String host;
        try {
            host = URI.create(origin).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null) {
            return false;
        }
        String id = rpId.toLowerCase(Locale.ROOT);
        host = host.toLowerCase(Locale.ROOT);
        return host.equals(id) || (host.endsWith("." + id) && id.indexOf('.') > 0);
    }

    static Fido2Exception failureFrom(String stderr, String tool) {
        String text = stderr == null ? "" : stderr;
        if (text.contains("FIDO_ERR_NO_CREDENTIALS")) {
            return new Fido2Exception(Failure.NO_CREDENTIALS, "This security key holds no passkey for this account.");
        }
        if (text.contains("FIDO_ERR_PIN_INVALID") || text.contains("FIDO_ERR_PIN_AUTH_INVALID")) {
            return new Fido2Exception(Failure.PIN_INVALID, "Wrong PIN.");
        }
        if (text.contains("FIDO_ERR_PIN_BLOCKED") || text.contains("FIDO_ERR_PIN_AUTH_BLOCKED") || text.contains("FIDO_ERR_UV_BLOCKED")) {
            return new Fido2Exception(Failure.PIN_BLOCKED, "The security key PIN is blocked. Unplug and reinsert the key, or reset it.");
        }
        if (text.contains("FIDO_ERR_PIN_NOT_SET")) {
            return new Fido2Exception(Failure.UNSUPPORTED, "This security key has no PIN set. Set a PIN on it first.");
        }
        if (text.contains("FIDO_ERR_ACTION_TIMEOUT") || text.contains("FIDO_ERR_USER_ACTION_TIMEOUT") || text.contains("FIDO_ERR_TIMEOUT")) {
            return new Fido2Exception(Failure.TIMEOUT, "The security key timed out waiting for a touch.");
        }
        if (text.contains("FIDO_ERR_OPERATION_DENIED") || text.contains("FIDO_ERR_KEEPALIVE_CANCEL")) {
            return new Fido2Exception(Failure.CANCELLED, "The security key operation was cancelled.");
        }
        return new Fido2Exception(Failure.OTHER, tool + " failed: " + text.trim());
    }

    private void throwIfCancelled() throws Fido2Exception {
        if (cancelled.get()) {
            throw new Fido2Exception(Failure.CANCELLED, "The security key operation was cancelled.");
        }
    }

    /**
     * Runs one fido2 tool. Parameters go to stdin first; a PIN is written only
     * once the tool prints its "Enter PIN for" prompt on stderr, which avoids a
     * race the tools otherwise report as an invalid PIN length. The tool is
     * started under setsid so it has no controlling terminal and reads the PIN
     * from stdin instead of /dev/tty, which matters for runs from a terminal.
     */
    private CliResult run(List<String> command, List<String> inputLines, String pin,
                          long timeoutMs)
            throws Fido2Exception {
        throwIfCancelled();
        List<String> fullCommand = new ArrayList<>();
        if (ExecutableLocator.findOnPath("setsid").isPresent()) {
            fullCommand.add("setsid");
        }
        fullCommand.addAll(command);
        Process process;
        try {
            process = new ProcessBuilder(fullCommand).start();
        } catch (IOException e) {
            throw new Fido2Exception(Failure.OTHER, "Could not start " + command.getFirst() + ": " + e.getMessage());
        }
        current = process;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread outReader = Thread.ofVirtual().start(() -> copy(process.getInputStream(), stdout, null, null));
        Thread errReader = Thread.ofVirtual().start(() -> copy(process.getErrorStream(), stderr, process, pin));
        try {
            OutputStream stdin = process.getOutputStream();
            try {
                if (!inputLines.isEmpty()) {
                    stdin.write((String.join("\n", inputLines) + "\n").getBytes(StandardCharsets.UTF_8));
                    stdin.flush();
                }
                if (pin == null) {
                    stdin.close();
                }
            } catch (IOException _) {
                // The tool exited before reading its input; the exit code below says why.
            }
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
            outReader.join(1_000);
            errReader.join(1_000);
            throwIfCancelled();
            if (!finished) {
                throw new Fido2Exception(Failure.TIMEOUT, "The security key timed out waiting for a touch.");
            }
            return new CliResult(process.exitValue(), stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new Fido2Exception(Failure.CANCELLED, "Interrupted");
        } finally {
            current = null;
        }
    }

    private static void copy(InputStream input, ByteArrayOutputStream sink, Process process,
            String pin) {
        boolean pinWritten = false;
        try (input) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (sink.size() < MAX_OUTPUT_BYTES) {
                    sink.write(buffer, 0, Math.min(read, MAX_OUTPUT_BYTES - sink.size()));
                }
                if (!pinWritten && pin != null && sink.toString(StandardCharsets.UTF_8).contains("Enter PIN for")) {
                    pinWritten = true;
                    OutputStream stdin = process.getOutputStream();
                    stdin.write((pin.trim() + "\n").getBytes(StandardCharsets.UTF_8));
                    stdin.flush();
                    stdin.close();
                }
            }
        } catch (IOException _) {
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toStandardBase64(String base64Url) {
        return Base64.getEncoder().encodeToString(B64URL_DECODER.decode(base64Url));
    }

    /** The stdin protocol is line based: strip control characters and cap length. */
    static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("[\\x00-\\x1f\\x7f]", "");
        return cleaned.length() > 500 ? cleaned.substring(0, 500) : cleaned;
    }
}
