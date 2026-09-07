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
import org.alaurie.jw365.rdp.SessionEvent;

import java.net.URI;
import java.util.function.Consumer;

/**
 * Modal dialog that loads FreeRDP's AAD authorization URL in WebEngine,
 * automatically captures the final redirect URL containing code=..., and submits it to FreeRDP.
 */
public final class SessionAuthDialog extends Stage {

    private final String authUrl;
    private final Consumer<String> submitRedirectUrl;
    private final WebView webView;
    private final WebEngine webEngine;
    private final ProgressBar progressBar;
    private final Label statusLabel;
    private boolean codeSubmitted = false;

    public SessionAuthDialog(Window owner, String resourceTitle, SessionEvent.AuthRequired authReq) {
        this.authUrl = authReq.authUrl();
        this.submitRedirectUrl = authReq.submitRedirectUrl();

        if (owner != null) {
            initOwner(owner);
        }
        initModality(Modality.APPLICATION_MODAL);
        setTitle("Connecting to " + resourceTitle);
        setMinWidth(580);
        setMinHeight(640);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("dialog-container");

        // Top Status Header
        VBox topBox = new VBox(6);
        topBox.setPadding(new Insets(10, 16, 10, 16));

        HBox banner = new HBox(12);
        banner.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("Authorizing Cloud PC");
        title.getStyleClass().add("brand-title");

        Label badge = new Label("Microsoft SSO");
        badge.getStyleClass().add("brand-badge");

        HBox.setHgrow(title, Priority.ALWAYS);
        banner.getChildren().addAll(title, badge);

        progressBar = new ProgressBar();
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(true);

        statusLabel = new Label("Connecting to Microsoft identity gateway...");
        statusLabel.getStyleClass().add("status-bar-text");

        topBox.getChildren().addAll(banner, progressBar, statusLabel);
        root.setTop(topBox);

        // Center WebView
        webView = new WebView();
        webEngine = webView.getEngine();
        webEngine.setJavaScriptEnabled(true);
        webEngine.setUserAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0");
        root.setCenter(webView);

        // Wire WebEngine listeners to auto-intercept redirect
        progressBar.progressProperty().bind(webEngine.getLoadWorker().progressProperty());

        webEngine.locationProperty().addListener((obs, oldLoc, newLoc) -> {
            if (newLoc != null) {
                checkLocationForRedirect(newLoc);
            }
        });

        webEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                progressBar.setVisible(false);
                if (!codeSubmitted) {
                    statusLabel.setText("Authorizing with your work profile...");
                }
            } else if (newState == javafx.concurrent.Worker.State.RUNNING) {
                progressBar.setVisible(true);
            }
        });

        // Load FreeRDP's auth URL
        webEngine.load(authUrl);

        Scene scene = new Scene(root, 620, 680);
        scene.getStylesheets().add(getClass().getResource("/org/alaurie/jw365/gui/styles.css").toExternalForm());
        setScene(scene);
    }

    private void checkLocationForRedirect(String url) {
        if (codeSubmitted) return;

        if (url.contains("code=") && (url.contains("nativeclient") || url.contains("login.microsoftonline.com"))) {
            codeSubmitted = true;
            Platform.runLater(() -> {
                statusLabel.setText("Session authorized! Launching desktop...");
                progressBar.setVisible(true);
                webView.setDisable(true);
            });

            // Submit redirect URL back to FreeRDP's stdin
            submitRedirectUrl.accept(url);

            // Close dialog after short moment
            Platform.runLater(this::close);
        }
    }
}
