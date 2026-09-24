package org.alaurie.jw365.gui.view;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import netscape.javascript.JSObject;
import org.alaurie.jw365.auth.Fido2Cli;
import org.alaurie.jw365.auth.Fido2Cli.Assertion;
import org.alaurie.jw365.auth.Fido2Cli.Failure;
import org.alaurie.jw365.auth.Fido2Cli.Fido2Exception;
import org.alaurie.jw365.auth.Fido2Cli.Request;
import org.alaurie.jw365.auth.Fido2Cli.Target;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Gives a sign-in WebView passkey support. The JavaFX WebView has no WebAuthn,
 * so a polyfill is injected into the Microsoft sign-in pages and its
 * navigator.credentials.get() calls land here, where a USB security key is
 * driven through fido2-tools. Does nothing when fido2-tools is not installed.
 */
public final class WebAuthnBridge {

    static final Set<String> ALLOWED_ORIGINS = Set.of("https://login.microsoftonline.com", "https://login.microsoft.com", "https://login.live.com");
    private static final String POLYFILL = loadPolyfill();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Supplier<Window> owner;
    private Fido2Cli active;

    private WebAuthnBridge(Supplier<Window> owner) {
        this.owner = owner;
    }

    /**
     * Installs the polyfill on every document the engine loads. The owner
     * supplier is read when a prompt is shown, so a window that is not yet
     * showing can pass null until it is.
     */
    public static Runnable attach(WebEngine engine, Supplier<Window> owner) {
        if (!Fido2Cli.isAvailable()) {
            return () -> {};
        }
        // The listener keeps the bridge reachable; the JS side only holds it weakly.
        WebAuthnBridge bridge = new WebAuthnBridge(owner);
        engine.documentProperty().addListener((obs, oldDoc, doc) -> {
            if (doc != null) {
                bridge.inject(engine);
            }
        });
        return bridge::cancelActive;
    }

    /** Aborts a ceremony in flight, for the owning dialog's close path. */
    public void cancelActive() {
        synchronized (this) {
            if (active != null) {
                active.cancel();
            }
        }
    }

    @SuppressWarnings("removal")
    private void inject(WebEngine engine) {
        try {
            JSObject window = (JSObject) engine.executeScript("window");
            window.setMember("jw365WebAuthn", this);
            engine.executeScript(POLYFILL);
        } catch (Exception e) {
            System.err.println("Warning: Could not install WebAuthn polyfill: " + e.getMessage());
        }
    }

    /**
     * Called from page JavaScript on the FX thread; the ceremony runs on a
     * virtual thread.
     */
    @SuppressWarnings("removal")
    public void getAssertion(String requestJson, JSObject callback) {
        Thread.ofVirtual().start(() -> {
            String result = runCeremony(requestJson);
            Platform.runLater(() -> {
                try {
                    callback.call("call", null, result);
                } catch (Exception e) {
                    System.err.println("Warning: WebAuthn callback failed: " + e.getMessage());
                }
            });
        });
    }

    private String runCeremony(String requestJson) {
        Request request;
        try {
            request = parseRequest(requestJson);
        } catch (Exception e) {
            return errorJson("SecurityError", "Invalid WebAuthn request.");
        }
        if (!ALLOWED_ORIGINS.contains(request.origin()) || !Fido2Cli.rpIdMatchesOrigin(request.rpId(), request.origin())) {
            return errorJson("SecurityError", "Origin not allowed for passkey sign-in.");
        }
        System.err.println("WebAuthn: assertion requested, rpId="
                + request.rpId()
                + ", allowCredentials="
                + request.allowCredentials().size()
                + ", userVerification="
                + request.userVerification());

        Fido2Cli fido2 = new Fido2Cli();
        synchronized (this) {
            if (active != null) {
                active.cancel();
            }
            active = fido2;
        }
        try {
            List<String> devices = fido2.findDevices();
            if (devices.isEmpty()) {
                return errorJson("NotAllowedError", "No security key found. Plug in your security key and try again.");
            }
            Target target = fido2.selectTarget(request, devices);
            boolean needsPin = request.requiresUserVerification() || target.credentialIds().isEmpty();
            String pin = null;
            String pinError = null;
            while (true) {
                if (needsPin) {
                    pin = promptPin(pinError).orElse(null);
                    if (pin == null) {
                        return errorJson("NotAllowedError", "The user cancelled the operation.");
                    }
                }
                Runnable closeTouchPrompt = showTouchPrompt(fido2);
                try {
                    Assertion assertion = fido2.getAssertion(request, target, pin);
                    System.err.println("WebAuthn: assertion completed");
                    return assertionJson(assertion);
                } catch (Fido2Exception e) {
                    if (e.failure() == Failure.PIN_INVALID && needsPin) {
                        pinError = e.getMessage();
                        continue;
                    }
                    System.err.println("WebAuthn: assertion failed: " + e.failure());
                    return errorJson("NotAllowedError", e.getMessage());
                } finally {
                    closeTouchPrompt.run();
                }
            }
        } catch (Fido2Exception e) {
            System.err.println("WebAuthn: key selection failed: " + e.failure());
            return errorJson("NotAllowedError", e.getMessage());
        } finally {
            synchronized (this) {
                if (active == fido2) {
                    active = null;
                }
            }
        }
    }

    private Optional<String> promptPin(String errorText) {
        CompletableFuture<Optional<String>> answer = new CompletableFuture<>();
        Platform.runLater(
                () -> {
                    Dialog<String> dialog = new Dialog<>();
                    Window ownerWindow = owner.get();
                    if (ownerWindow != null) {
                        dialog.initOwner(ownerWindow);
                    }
                    dialog.initModality(Modality.WINDOW_MODAL);
                    dialog.setTitle("Security key PIN");
                    dialog.setHeaderText(errorText == null ? "Enter the PIN of your security key" : errorText + " Try again.");
                    PasswordField pinField = new PasswordField();
                    pinField.setPromptText("PIN");
                    VBox content = new VBox(8, pinField);
                    content.setPadding(new Insets(10, 0, 0, 0));
                    dialog.getDialogPane().setContent(content);
                    dialog.getDialogPane()
                          .getButtonTypes()
                          .addAll(ButtonType.OK, ButtonType.CANCEL);
                    dialog.setResultConverter(button -> button == ButtonType.OK ? pinField.getText() : null);
                    Platform.runLater(pinField::requestFocus);
                    String pin = dialog.showAndWait().orElse(null);
                    answer.complete(pin == null || pin.isBlank()
                            ? Optional.empty()
                            : Optional.of(pin));
                });
        return answer.join();
    }

    /**
     * Shows the touch prompt and returns a closer. Stages must be created on
     * the FX thread.
     */
    private Runnable showTouchPrompt(Fido2Cli fido2) {
        CompletableFuture<Stage> shown = new CompletableFuture<>();
        Platform.runLater(
                () -> {
                    Stage stage = new Stage(StageStyle.UTILITY);
                    Window ownerWindow = owner.get();
                    if (ownerWindow != null) {
                        stage.initOwner(ownerWindow);
                    }
                    stage.setTitle("Security key");
                    stage.setResizable(false);
                    Label title = new Label("Touch your security key");
                    title.getStyleClass().add("brand-title");
                    Label hint = new Label("Confirm the sign-in on the key when it blinks.");
                    hint.getStyleClass().add("status-bar-text");
                    Button cancel = new Button("Cancel");
                    cancel.getStyleClass().add("btn-secondary");
                    cancel.setOnAction(e -> fido2.cancel());
                    VBox box = new VBox(12, title, hint, cancel);
                    box.setAlignment(Pos.CENTER);
                    box.setPadding(new Insets(24));
                    box.getStyleClass().add("dialog-container");
                    Scene scene = new Scene(box, 340, 160);
                    var stylesheet = getClass().getResource("/org/alaurie/jw365/gui/styles.css");
                    if (stylesheet != null) {
                        scene.getStylesheets().add(stylesheet.toExternalForm());
                    }
                    stage.setScene(scene);
                    stage.setOnCloseRequest(e -> fido2.cancel());
                    stage.show();
                    shown.complete(stage);
                });
        Stage stage = shown.join();
        return () -> Platform.runLater(stage::close);
    }

    static Request parseRequest(String json) {
        JsonNode node = MAPPER.readTree(json);
        List<String> allow = new ArrayList<>();
        node.path("allowCredentials").forEach(id -> allow.add(id.asString()));
        return new Request(
                node.path("origin").asString(),
                node.path("rpId").asString(),
                node.path("challenge").asString(),
                List.copyOf(allow),
                node.path("userVerification").asString("preferred"),
                node.path("timeout").asLong(0));
    }

    private static String assertionJson(Assertion assertion) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("credentialId", assertion.credentialId());
        node.put("authenticatorData", assertion.authenticatorData());
        node.put("clientDataJson", assertion.clientDataJson());
        node.put("signature", assertion.signature());
        node.put("userHandle", assertion.userHandle());
        return node.toString();
    }

    private static String errorJson(String name, String message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("error", name);
        node.put("message", message);
        return node.toString();
    }

    private static String loadPolyfill() {
        try (InputStream in = WebAuthnBridge.class.getResourceAsStream("/org/alaurie/jw365/auth/webauthn-polyfill.js")) {
            if (in == null) {
                throw new IllegalStateException("Missing webauthn-polyfill.js resource");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read webauthn-polyfill.js", e);
        }
    }
}
