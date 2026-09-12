package org.alaurie.jw365.gui.view;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.alaurie.jw365.feed.WorkspaceResource;
import org.alaurie.jw365.gui.state.AppState;
import org.alaurie.jw365.config.AppVersion;
import org.alaurie.jw365.rdp.SessionStatus;

/**
 * Visual tile for a Cloud PC or RemoteApp workspace resource.
 */
public final class ResourceCard extends VBox {

    private final WorkspaceResource resource;
    private final AppState state;
    private final Label statusBadge;
    private final Button actionButton;
    private static final javafx.scene.image.Image DEFAULT_ICON;
    static {
        javafx.scene.image.Image img = null;
        try (var is = ResourceCard.class.getResourceAsStream("/org/alaurie/jw365/gui/icons/icon_48.png")) {
            if (is != null) img = new javafx.scene.image.Image(is);
        } catch (Exception _) { }
        DEFAULT_ICON = img;
    }
    private final javafx.collections.MapChangeListener<String, SessionStatus> statusListener;
    public ResourceCard(WorkspaceResource resource, AppState state) {
        this.resource = resource;
        this.state = state;

        getStyleClass().add("resource-card");
        setAlignment(Pos.TOP_CENTER);
        setSpacing(10);
        setPrefWidth(220);
        setMinWidth(200);
        setMaxWidth(260);

        // Icon Container
        StackPane iconContainer = new StackPane();
        iconContainer.getStyleClass().add("resource-icon-container");
        iconContainer.setPrefSize(64, 64);
        iconContainer.setMaxSize(64, 64);

        ImageView iconView = new ImageView();
        iconView.setFitWidth(48);
        iconView.setFitHeight(48);
        iconView.setPreserveRatio(true);
        if (DEFAULT_ICON != null) {
            iconView.setImage(DEFAULT_ICON);
        }
        iconContainer.getChildren().add(iconView);

        // Load icon via AppState
        state.loadResourceIcon(resource, img -> {
            if (img != null && !img.isError()) {
                iconView.setImage(img);
            }
        });
        // Badges Row (Type badge & Status badge)
        HBox badgesBox = new HBox(6);
        badgesBox.setAlignment(Pos.CENTER);

        Label typeBadge = new Label(resource.type().isDesktop() ? "Cloud PC" : "RemoteApp");
        typeBadge.getStyleClass().add("badge-type");

        statusBadge = new Label("Idle");
        statusBadge.getStyleClass().add("badge-status-idle");

        badgesBox.getChildren().addAll(typeBadge, statusBadge);

        // Title and Subtitle
        Label titleLabel = new Label(resource.title());
        titleLabel.getStyleClass().add("resource-title");
        titleLabel.setWrapText(true);
        titleLabel.setMaxWidth(200);

        Label subtitleLabel = new Label(resource.displaySubtitle());
        subtitleLabel.getStyleClass().add("resource-subtitle");
        subtitleLabel.setWrapText(false);

        VBox textContainer = new VBox(2, titleLabel, subtitleLabel);
        textContainer.setAlignment(Pos.CENTER);
        VBox.setVgrow(textContainer, Priority.ALWAYS);

        // Action Button
        actionButton = new Button("Connect");
        actionButton.getStyleClass().add("btn-primary");
        actionButton.setMaxWidth(Double.MAX_VALUE);
        actionButton.setOnAction(e -> handleActionClick());

        getChildren().addAll(iconContainer, badgesBox, textContainer, actionButton);

        // Setup context menu
        setupContextMenu();

        // Mouse interaction
        setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                handleActionClick();
            }
        });

        // Listen for session status changes
        this.statusListener = change -> {
            if (resource.identityKey().equals(change.getKey())) {
                updateStatus(change.getValueAdded());
            }
        };
        state.getSessionStatuses().addListener(this.statusListener);

        // Initialize status
        SessionStatus currentStatus = state.getSessionStatuses().get(resource.identityKey());
        updateStatus(currentStatus != null ? currentStatus : SessionStatus.IDLE);
    }

    public void cleanup() {
        if (statusListener != null) {
            state.getSessionStatuses().removeListener(statusListener);
        }
    }


    private void handleActionClick() {
        SessionStatus current = state.getSessionStatuses().get(resource.identityKey());
        if (current != null && current.isActive()) {
            state.disconnectResource(resource);
        } else {
            state.connectResource(resource, error -> Platform.runLater(() -> {
                javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.ERROR,
                    error,
                    javafx.scene.control.ButtonType.OK
                );
                alert.setHeaderText("Connection Failed");
                alert.setTitle("JW365");
                alert.showAndWait();
            }));
        }
    }

    private void updateStatus(SessionStatus status) {
        Platform.runLater(() -> {
            statusBadge.getStyleClass().removeAll(
                "badge-status-idle",
                "badge-status-connecting",
                "badge-status-connected",
                "badge-status-failed"
            );

            if (status == null || status == SessionStatus.IDLE || status == SessionStatus.DISCONNECTED) {
                statusBadge.setText("Idle");
                statusBadge.getStyleClass().add("badge-status-idle");
                actionButton.setText("Connect");
                actionButton.setDisable(false);
                actionButton.getStyleClass().removeAll("btn-secondary");
                if (!actionButton.getStyleClass().contains("btn-primary")) {
                    actionButton.getStyleClass().add("btn-primary");
                }
            } else if (status == SessionStatus.STARTING || status == SessionStatus.CONNECTING || status == SessionStatus.RECONNECTING || status == SessionStatus.DISCONNECTING) {
                statusBadge.setText(status.getLabel());
                statusBadge.getStyleClass().add("badge-status-connecting");
                actionButton.setText(status == SessionStatus.DISCONNECTING ? "Disconnecting..." : status == SessionStatus.RECONNECTING ? "Reconnecting..." : "Connecting...");
                actionButton.setDisable(status == SessionStatus.DISCONNECTING);
            } else if (status == SessionStatus.CONNECTED) {
                statusBadge.setText("Connected");
                statusBadge.getStyleClass().add("badge-status-connected");
                actionButton.setText("Disconnect");
                actionButton.setDisable(false);
                actionButton.getStyleClass().removeAll("btn-primary");
                if (!actionButton.getStyleClass().contains("btn-secondary")) {
                    actionButton.getStyleClass().add("btn-secondary");
                }
            } else if (status == SessionStatus.FAILED) {
                statusBadge.setText("Failed");
                statusBadge.getStyleClass().add("badge-status-failed");
                actionButton.setText("Retry");
                actionButton.setDisable(false);
                actionButton.getStyleClass().removeAll("btn-secondary");
                if (!actionButton.getStyleClass().contains("btn-primary")) {
                    actionButton.getStyleClass().add("btn-primary");
                }
            }
        });
    }

    private void setupContextMenu() {
        ContextMenu menu = new ContextMenu();

        MenuItem connectDefault = new MenuItem("Connect");
        connectDefault.setOnAction(e -> handleActionClick());

        MenuItem connectFullscreen = new MenuItem("Connect (Fullscreen)");
        connectFullscreen.setOnAction(e -> state.connectResource(resource, AppState.DisplayMode.FULLSCREEN, this::showError));

        MenuItem connectWindowed = new MenuItem("Connect (Windowed)");
        connectWindowed.setOnAction(e -> state.connectResource(resource, AppState.DisplayMode.WINDOWED, this::showError));

        MenuItem connectMultiMon = new MenuItem("Connect (Multi-Monitor)");
        connectMultiMon.setOnAction(e -> state.connectResource(resource, AppState.DisplayMode.MULTIMON, this::showError));

        MenuItem restartItem = new MenuItem("Restart Session");
        MenuItem retryItem = new MenuItem("Retry Connection");
        retryItem.setOnAction(e -> state.connectResource(resource, this::showError));
        restartItem.setOnAction(e -> state.restartResource(resource, this::showError));

        MenuItem disconnectItem = new MenuItem("Disconnect");
        disconnectItem.setOnAction(e -> state.disconnectResource(resource));

        MenuItem viewLogItem = new MenuItem("View Latest Session Log");
        viewLogItem.setOnAction(e -> openLatestLog());

        MenuItem openRdpItem = new MenuItem("Open .RDP File");
        openRdpItem.setOnAction(e -> openRdpFile());

        MenuItem copyIdItem = new MenuItem("Copy Resource ID");
        copyIdItem.setOnAction(e -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(resource.id());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        });

        MenuItem controlsItem = new MenuItem("Fullscreen Controls...");
        controlsItem.setOnAction(e -> showSessionControls());

        MenuItem diagnosticsItem = new MenuItem("Copy Diagnostics");
        diagnosticsItem.setOnAction(e -> copyDiagnostics());

        menu.getItems().addAll(
            connectDefault,
            connectFullscreen,
            connectWindowed,
            connectMultiMon,
            new javafx.scene.control.SeparatorMenuItem(),
            retryItem,
            disconnectItem,
            new javafx.scene.control.SeparatorMenuItem(),
            controlsItem,
            diagnosticsItem,
            new javafx.scene.control.SeparatorMenuItem(),
            viewLogItem,
            openRdpItem,
            new javafx.scene.control.SeparatorMenuItem(),
            copyIdItem
        );

        setOnContextMenuRequested(e -> menu.show(this, e.getScreenX(), e.getScreenY()));
    }

    private void showSessionControls() {
        Alert alert = new Alert(
            Alert.AlertType.INFORMATION,
            "F12  Disconnect\nF11  Minimize\nF10  Toggle fullscreen\nCtrl + Alt + Enter  Toggle FreeRDP fullscreen",
            ButtonType.OK
        );
        alert.setHeaderText("Fullscreen Session Controls");
        alert.setTitle("JW365");
        alert.showAndWait();
    }

    private void copyDiagnostics() {
        SessionStatus status = state.getSessionStatuses().get(resource.identityKey());
        var engine = state.detectedFreeRdpProperty().get();
        String diagnostics = "JW365 " + AppVersion.VERSION + "\n"
            + "Resource: " + resource.title() + " (" + resource.id() + ")\n"
            + "Status: " + (status != null ? status.getLabel() : "Idle") + "\n"
            + "RDP engine: " + (engine != null ? engine.displayName() : "Not detected") + "\n"
            + "Session controls: Right Ctrl+F12 disconnect; Right Ctrl+F10 fullscreen toggle";
        ClipboardContent content = new ClipboardContent();
        content.putString(diagnostics);
        Clipboard.getSystemClipboard().setContent(content);
    }
    private void showError(String error) {
        Platform.runLater(() -> {
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.ERROR,
                error,
                javafx.scene.control.ButtonType.OK
            );
            alert.setHeaderText("Connection Error");
            alert.setTitle("JW365");
            alert.showAndWait();
        });
    }

    private void openLatestLog() {
        try {
            java.nio.file.Path logDir = org.alaurie.jw365.config.XdgPaths.logsDir();
            try (var stream = java.nio.file.Files.list(logDir)) {
                java.util.Optional<java.nio.file.Path> latest = stream
                    .filter(p -> p.getFileName().toString().contains(resource.sanitizedFileName()) && p.getFileName().toString().endsWith(".log"))
                    .max(java.util.Comparator.comparingLong(p -> {
                        try {
                            return java.nio.file.Files.getLastModifiedTime(p).toMillis();
                        } catch (Exception e) {
                            return 0L;
                        }
                    }));

                if (latest.isPresent() && java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(latest.get().toFile());
                } else {
                    showError("No session log found yet for this resource.");
                }
            }
        } catch (Exception e) {
            showError("Could not open session log: " + e.getMessage());
        }
    }

    private void openRdpFile() {
        try {
            java.nio.file.Path rdpFile = org.alaurie.jw365.config.XdgPaths.rdpFeedDir().resolve(resource.sanitizedFileName() + ".rdp");
            if (java.nio.file.Files.exists(rdpFile) && java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(rdpFile.toFile());
            } else {
                showError("RDP file has not been downloaded yet. Connect first.");
            }
        } catch (Exception e) {
            showError("Could not open RDP file: " + e.getMessage());
        }
    }
}
