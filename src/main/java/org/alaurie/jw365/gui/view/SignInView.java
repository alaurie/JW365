package org.alaurie.jw365.gui.view;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.alaurie.jw365.gui.state.AppState;

/**
 * Landing view presented when no active user session is authenticated.
 */
public final class SignInView extends StackPane {

    private final AppState state;
    private final ProgressIndicator progressIndicator;
    private final Label statusLabel;
    private final Button signInBtn;

    public SignInView(AppState state) {
        this.state = state;

        getStyleClass().add("signin-container");

        VBox card = new VBox(20);
        card.getStyleClass().add("signin-card");
        card.setAlignment(Pos.CENTER);

        // App Header
        VBox headerBox = new VBox(6);
        headerBox.setAlignment(Pos.CENTER);

        Label title = new Label("JW365");
        title.getStyleClass().add("signin-title");

        Label badge = new Label("Windows 365 & AVD for Linux");
        badge.getStyleClass().add("brand-badge");

        Label subtitle = new Label("Connect to your Cloud PCs and Virtual Desktops from Linux");
        subtitle.getStyleClass().add("signin-subtitle");

        headerBox.getChildren().addAll(title, badge, subtitle);

        // Direct Sign-in Button (Opens embedded login window with 0 copying)
        signInBtn = new Button("Sign in with Microsoft");
        signInBtn.getStyleClass().add("btn-primary");
        signInBtn.setPrefWidth(280);
        signInBtn.setPrefHeight(44);
        signInBtn.setStyle("-fx-font-size: 14px; -fx-padding: 10 20 10 20;");

        signInBtn.setOnAction(e -> {
            AuthDialog dialog = new AuthDialog(getScene().getWindow(), state);
            dialog.showAndWait();
        });

        // Settings Button
        Button settingsBtn = new Button("Settings");
        settingsBtn.getStyleClass().add("btn-secondary");
        settingsBtn.setPrefWidth(280);
        settingsBtn.setOnAction(e -> {
            SettingsDialog dialog = new SettingsDialog(getScene().getWindow(), state);
            dialog.showAndWait();
        });

        // Status & Progress Indicator
        progressIndicator = new ProgressIndicator();
        progressIndicator.setMaxSize(24, 24);
        progressIndicator.visibleProperty().bind(state.loadingProperty());

        statusLabel = new Label();
        statusLabel.getStyleClass().add("status-bar-text");
        statusLabel.textProperty().bind(state.statusMessageProperty());

        HBox statusBox = new HBox(8, progressIndicator, statusLabel);
        statusBox.setAlignment(Pos.CENTER);

        card.getChildren().addAll(
            headerBox,
            signInBtn,
            settingsBtn,
            statusBox
        );
        getChildren().add(card);
    }
}
