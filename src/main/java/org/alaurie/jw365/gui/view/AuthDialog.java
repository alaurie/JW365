package org.alaurie.jw365.gui.view;

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
import org.alaurie.jw365.auth.OAuthClient;
import org.alaurie.jw365.auth.PkceChallenge;
import org.alaurie.jw365.config.ClientConfig;
import org.alaurie.jw365.gui.state.AppState;

import java.net.URI;
import java.util.Objects;
import org.alaurie.jw365.auth.OAuthCallback;

/**
 * JavaFX WebView dialog for Entra ID OAuth 2.0 PKCE sign-in.
 */
public final class AuthDialog extends Stage {

    private static final URI CALLBACK_URI = URI.create(OAuthClient.REDIRECT_URI);
    private final AppState state;
    private final PkceChallenge challenge;
    private final WebView webView;
    private final ProgressBar progressBar;
    private final Label statusLabel;
    private boolean codeIntercepted = false;

    public AuthDialog(Window owner, AppState state) {
        this.state = state;
        this.challenge = PkceChallenge.create();

        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setTitle("Sign in to Windows 365 / AVD");
        setMinWidth(620);
        setMinHeight(720);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("dialog-container");

        // Top Status Header
        VBox topBox = new VBox(6);
        topBox.setPadding(new Insets(10, 16, 10, 16));

        HBox banner = new HBox(12);
        banner.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("Microsoft Sign-in");
        title.getStyleClass().add("brand-title");

        Label badge = new Label("Work or School Account");
        badge.getStyleClass().add("brand-badge");

        HBox.setHgrow(title, Priority.ALWAYS);
        banner.getChildren().addAll(title, badge);

        progressBar = new ProgressBar();
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(true);

        statusLabel = new Label("Loading Microsoft authentication service...");
        statusLabel.getStyleClass().add("status-bar-text");

        topBox.getChildren().addAll(banner, progressBar, statusLabel);
        root.setTop(topBox);

        // Center WebView
        webView = new WebView();
        WebEngine webEngine = webView.getEngine();
        webEngine.setJavaScriptEnabled(true);
        webEngine.setUserAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0");
        root.setCenter(webView);

        // Build Authorize URL with nativeclient redirect
        ClientConfig config = state.getConfigManager().get();
        URI authUri = state.getOauthClient().buildAuthorizeUrl(config.defaultTenant(), challenge, OAuthClient.REDIRECT_URI, null);

        // Wire WebEngine listeners to auto-intercept redirect
        progressBar.progressProperty().bind(webEngine.getLoadWorker().progressProperty());

        webEngine.locationProperty().addListener((obs, oldLoc, newLoc) -> {
            if (newLoc != null) {
                checkLocationForAuthCode(newLoc);
            }
        });

        webEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                progressBar.setVisible(false);
                if (!codeIntercepted) {
                    statusLabel.setText("Please enter your work or school credentials");
                }
            } else if (newState == javafx.concurrent.Worker.State.RUNNING) {
                progressBar.setVisible(true);
            } else if (newState == javafx.concurrent.Worker.State.FAILED) {
                progressBar.setVisible(false);
                statusLabel.setText("Connection failed. Retrying...");
            }
        });

        // Load auth URL
        webEngine.load(authUri.toString());

        Scene scene = new Scene(root, 650, 750);
        scene.getStylesheets().add(Objects.requireNonNull(
            getClass().getResource("/org/alaurie/jw365/gui/styles.css"),
            "Missing stylesheet resource"
        ).toExternalForm());
        setScene(scene);
    }

    private void checkLocationForAuthCode(String url) {
        if (codeIntercepted || !isExpectedRedirect(url)) return;

        OAuthCallback callback = OAuthCallback.parse(url).orElse(null);
        if (callback == null) return;

        if (!callback.matchesState(challenge.state())) {
            codeIntercepted = true;
            showCallbackFailure("Invalid Microsoft sign-in response");
            return;
        }
        if (callback.hasError()) {
            codeIntercepted = true;
            showCallbackFailure("Microsoft sign-in failed: " + callback.errorMessage());
            return;
        }
        if (callback.isSuccess()) {
            codeIntercepted = true;
            handleAuthorizationCode(callback.code());
        }
    }

    private static boolean isExpectedRedirect(String url) {
        return OAuthCallback.isRedirect(url, CALLBACK_URI);
    }
    private void showCallbackFailure(String message) {
        Platform.runLater(() -> {
            statusLabel.setText(message);
            progressBar.setVisible(false);
            webView.setDisable(false);
        });
    }
    private void handleAuthorizationCode(String code) {
        if (code == null || code.isBlank()) return;

        Platform.runLater(() -> {
            statusLabel.setText("Authentication confirmed! Retrieving your workspaces...");
            progressBar.setVisible(true);
            webView.setDisable(true);
        });

        state.signInWithCode(code, challenge.codeVerifier(), OAuthClient.REDIRECT_URI, () -> {
            Platform.runLater(this::close);
        }, error -> {
            Platform.runLater(() -> {
                codeIntercepted = false;
                statusLabel.setText("Authentication failed: " + error);
                progressBar.setVisible(false);
                webView.setDisable(false);
            });
        });
    }

}
