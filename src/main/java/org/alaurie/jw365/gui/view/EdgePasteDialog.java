package org.alaurie.jw365.gui.view;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.alaurie.jw365.auth.BrowserInfo;
import org.alaurie.jw365.auth.BrowserLocator;
import org.alaurie.jw365.auth.OAuthClient;
import org.alaurie.jw365.auth.PkceChallenge;
import org.alaurie.jw365.config.ClientConfig;
import org.alaurie.jw365.gui.state.AppState;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modal dialog that launches Microsoft Edge with work profile SSO and captures the final authorization code.
 */
public final class EdgePasteDialog extends Stage {

    private static final Pattern CODE_PATTERN = Pattern.compile("[?&]code=([^&]+)");

    private final AppState state;
    private final PkceChallenge challenge;
    private final TextField codeField;
    private final Label statusLabel;
    private final ProgressBar progressBar;
    private final ScheduledExecutorService clipboardWatcher;
    private boolean codeProcessed = false;

    public EdgePasteDialog(Window owner, AppState state, BrowserInfo browser) {
        this.state = state;
        this.challenge = PkceChallenge.create();

        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setTitle("Sign in with Microsoft Edge");
        setMinWidth(520);
        setMinHeight(360);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("dialog-container");

        VBox contentBox = new VBox(16);
        contentBox.setPadding(new Insets(24));
        contentBox.setAlignment(Pos.TOP_LEFT);

        Label title = new Label("Microsoft Edge Sign-in");
        title.getStyleClass().add("brand-title");

        Label instructions = new Label(
            "1. Microsoft Edge has opened with your work profile.\n" +
            "2. Complete any prompt in Edge until you see the confirmation page.\n" +
            "3. Copy the URL from Edge's address bar (or paste it below)."
        );
        instructions.getStyleClass().add("signin-subtitle");
        instructions.setStyle("-fx-line-spacing: 4px;");

        VBox inputSection = new VBox(8);
        Label inputLabel = new Label("Redirect URL / Authorization Code:");
        inputLabel.getStyleClass().add("form-label");

        HBox inputBox = new HBox(8);
        codeField = new TextField();
        codeField.setPromptText("Paste URL containing code= (or copy in Edge for auto-detection)");
        HBox.setHgrow(codeField, Priority.ALWAYS);

        Button pasteBtn = new Button("Paste");
        pasteBtn.getStyleClass().add("btn-secondary");
        pasteBtn.setOnAction(e -> {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            if (clipboard.hasString()) {
                codeField.setText(clipboard.getString());
                checkAndSubmitCode(clipboard.getString());
            }
        });

        inputBox.getChildren().addAll(codeField, pasteBtn);
        inputSection.getChildren().addAll(inputLabel, inputBox);

        progressBar = new ProgressBar();
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);

        statusLabel = new Label("Waiting for sign-in completion...");
        statusLabel.getStyleClass().add("status-bar-text");

        contentBox.getChildren().addAll(title, instructions, inputSection, progressBar, statusLabel);
        root.setCenter(contentBox);

        // Action Buttons
        HBox buttonBar = new HBox(12);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        buttonBar.setPadding(new Insets(12, 24, 16, 24));

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("btn-secondary");
        cancelBtn.setOnAction(e -> close());

        Button submitBtn = new Button("Complete Sign-in");
        submitBtn.getStyleClass().add("btn-primary");
        submitBtn.setOnAction(e -> checkAndSubmitCode(codeField.getText()));

        buttonBar.getChildren().addAll(cancelBtn, submitBtn);
        root.setBottom(buttonBar);

        // Build URL and launch Edge
        ClientConfig config = state.getConfigManager().get();
        URI authUri = state.getOauthClient().buildAuthorizeUrl(config.defaultTenant(), challenge, OAuthClient.REDIRECT_URI, null);

        try {
            BrowserInfo target = browser != null ? browser : BrowserLocator.findBestBrowser();
            BrowserLocator.launch(target, authUri, false);
        } catch (Exception e) {
            statusLabel.setText("Failed to launch browser: " + e.getMessage());
        }

        // Clipboard watcher: automatically detects if user copies the redirect URL in Edge
        clipboardWatcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jw365-clipboard-watcher");
            t.setDaemon(true);
            return t;
        });

        clipboardWatcher.scheduleAtFixedRate(() -> {
            if (codeProcessed) return;
            Platform.runLater(() -> {
                try {
                    Clipboard cb = Clipboard.getSystemClipboard();
                    if (cb.hasString()) {
                        String text = cb.getString();
                        if (text != null && text.contains("code=") && (text.contains("nativeclient") || text.contains("login.microsoftonline.com"))) {
                            codeField.setText(text);
                            checkAndSubmitCode(text);
                        }
                    }
                } catch (Exception ignored) {
                }
            });
        }, 1, 1, TimeUnit.SECONDS);

        setOnCloseRequest(e -> clipboardWatcher.shutdownNow());

        Scene scene = new Scene(root, 560, 400);
        scene.getStylesheets().add(getClass().getResource("/org/alaurie/jw365/gui/styles.css").toExternalForm());
        setScene(scene);
    }

    private void checkAndSubmitCode(String rawInput) {
        if (rawInput == null || rawInput.isBlank() || codeProcessed) {
            return;
        }

        String code = extractCode(rawInput.trim());
        if (code == null || code.isBlank()) {
            statusLabel.setText("Could not find authorization code in the provided text");
            return;
        }

        codeProcessed = true;
        clipboardWatcher.shutdownNow();

        Platform.runLater(() -> {
            statusLabel.setText("Exchanging code for tokens...");
            progressBar.setVisible(true);
            codeField.setDisable(true);
        });

        state.signInWithCode(code, challenge.codeVerifier(), OAuthClient.REDIRECT_URI, () -> {
            Platform.runLater(this::close);
        }, error -> {
            Platform.runLater(() -> {
                codeProcessed = false;
                statusLabel.setText("Authentication failed: " + error);
                progressBar.setVisible(false);
                codeField.setDisable(false);
            });
        });
    }

    private static String extractCode(String text) {
        Matcher m = CODE_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        if (!text.contains(" ") && !text.contains("http") && text.length() > 20) {
            return text;
        }
        return null;
    }
}
