package org.alaurie.jw365.gui.view;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.alaurie.jw365.rdp.SessionEvent;
import org.alaurie.jw365.config.XdgPaths;

import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.Objects;

/**
 * Resolves FreeRDP Entra ID redirects using persisted WebView session data.
 * Shows a modal WebView when silent authentication does not complete.
 */
public final class SessionAuthDialog extends Stage {

    private final String authUrl;
    private final Consumer<String> submitRedirectUrl;
    private final String resourceTitle;
    private final WebView webView;
    private final WebEngine webEngine;
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private Timeline displayFallbackTimer;
    private boolean isUiConstructed = false;

    public SessionAuthDialog(Window owner, String resourceTitle, SessionEvent.AuthRequired authReq) {
        this.authUrl = authReq.authUrl();
        this.submitRedirectUrl = authReq.submitRedirectUrl();
        this.resourceTitle = resourceTitle;

        if (owner != null) {
            initOwner(owner);
        }
        initModality(Modality.APPLICATION_MODAL);
        setTitle("Authorizing " + resourceTitle);

        // Create WebView in memory
        this.webView = new WebView();
        this.webEngine = webView.getEngine();
        try {
            Path webViewData = XdgPaths.dataDir().resolve("webview");
            Files.createDirectories(webViewData);
            try {
                Files.setPosixFilePermissions(webViewData, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ignored) {
            }
            this.webEngine.setUserDataDirectory(webViewData.toFile());
        } catch (Exception e) {
            System.err.println("Warning: Could not configure persistent WebView storage: " + e.getMessage());
        }

        // Listen for OAuth redirect code
        this.webEngine.locationProperty().addListener((obs, oldLoc, newLoc) -> {
            if (newLoc != null) {
                checkLocationForRedirect(newLoc);
            }
        });

        // Clean up on manual window close
        setOnCloseRequest(e -> cancelTimer());
    }

    /**
     * Starts the authorization flow: silent first, with fallback to visible window only if needed.
     */
    public void startSilentOrShow() {
        Platform.runLater(() -> {
            // Allow persisted SSO cookies time to complete silent authentication before showing UI.
            displayFallbackTimer = new Timeline(new KeyFrame(Duration.seconds(3), e -> {
                if (!completed.get() && !isShowing()) {
                    constructAndShowWindow();
                }
            }));
            displayFallbackTimer.setCycleCount(1);
            displayFallbackTimer.play();

            // Load auth URL
            webEngine.load(authUrl);
        });
    }

    private void checkLocationForRedirect(String url) {
        if (completed.get()) return;

        if (url.contains("code=") && (url.contains("nativeclient") || url.contains("login.microsoftonline.com"))) {
            if (completed.compareAndSet(false, true)) {
                cancelTimer();

                // Submit redirect URL back to FreeRDP's stdin
                submitRedirectUrl.accept(url);

                // If window was visible, close it
                Platform.runLater(() -> {
                    if (isShowing()) {
                        close();
                    }
                });
            }
        }
    }

    private void constructAndShowWindow() {
        if (isUiConstructed || completed.get()) return;
        isUiConstructed = true;

        BorderPane root = new BorderPane();
        root.getStyleClass().add("dialog-container");

        // Header
        VBox topBox = new VBox(6);
        topBox.setPadding(new Insets(10, 16, 10, 16));

        HBox banner = new HBox(12);
        banner.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("Authorizing " + resourceTitle);
        title.getStyleClass().add("brand-title");

        Label badge = new Label("Microsoft SSO");
        badge.getStyleClass().add("brand-badge");

        HBox.setHgrow(title, Priority.ALWAYS);
        banner.getChildren().addAll(title, badge);

        ProgressBar progressBar = new ProgressBar();
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.progressProperty().bind(webEngine.getLoadWorker().progressProperty());

        Label statusLabel = new Label("Please complete any sign-in prompt below...");
        statusLabel.getStyleClass().add("status-bar-text");

        topBox.getChildren().addAll(banner, progressBar, statusLabel);
        root.setTop(topBox);

        // Center WebView
        root.setCenter(webView);

        Scene scene = new Scene(root, 620, 680);
        scene.getStylesheets().add(Objects.requireNonNull(
            getClass().getResource("/org/alaurie/jw365/gui/styles.css"),
            "Missing stylesheet resource"
        ).toExternalForm());
        setScene(scene);

        setMinWidth(580);
        setMinHeight(640);
        show();
    }

    private void cancelTimer() {
        if (displayFallbackTimer != null) {
            displayFallbackTimer.stop();
            displayFallbackTimer = null;
        }
    }
}
