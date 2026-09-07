package org.alaurie.jw365.gui.view;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import org.alaurie.jw365.feed.WorkspaceResource;
import org.alaurie.jw365.gui.state.AppState;
import org.alaurie.jw365.rdp.SessionStatus;

/**
 * Modern visual tile representing an individual Cloud PC or RemoteApp in the workspace grid.
 */
public final class ResourceCard extends VBox {

    private final WorkspaceResource resource;
    private final AppState state;
    private final ImageView iconView;
    private final Label statusBadge;
    private final Button actionButton;
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

        iconView = new ImageView();
        iconView.setFitWidth(48);
        iconView.setFitHeight(48);
        iconView.setPreserveRatio(true);
        iconContainer.getChildren().add(iconView);

        // Load icon via AppState
        state.loadResourceIcon(resource, icon -> iconView.setImage(icon));

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
            if (resource.id().equals(change.getKey())) {
                updateStatus(change.getValueAdded());
            }
        };
        state.getSessionStatuses().addListener(this.statusListener);

        // Initialize status
        SessionStatus currentStatus = state.getSessionStatuses().get(resource.id());
        updateStatus(currentStatus != null ? currentStatus : SessionStatus.IDLE);
    }

    public void cleanup() {
        if (statusListener != null) {
            state.getSessionStatuses().removeListener(statusListener);
        }
    }

    public WorkspaceResource getResource() {
        return resource;
    }

    private void handleActionClick() {
        SessionStatus current = state.getSessionStatuses().get(resource.id());
        if (current != null && current.isActive()) {
            state.disconnectResource(resource);
        } else {
            state.connectResource(resource, error -> {
                Platform.runLater(() -> {
                    javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.ERROR,
                        error,
                        javafx.scene.control.ButtonType.OK
                    );
                    alert.setHeaderText("Connection Failed");
                    alert.setTitle("JW365");
                    alert.showAndWait();
                });
            });
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
                actionButton.getStyleClass().removeAll("btn-secondary");
                if (!actionButton.getStyleClass().contains("btn-primary")) {
                    actionButton.getStyleClass().add("btn-primary");
                }
            } else if (status == SessionStatus.STARTING || status == SessionStatus.CONNECTING) {
                statusBadge.setText(status.getLabel());
                statusBadge.getStyleClass().add("badge-status-connecting");
                actionButton.setText("Connecting...");
            } else if (status == SessionStatus.CONNECTED) {
                statusBadge.setText("Connected");
                statusBadge.getStyleClass().add("badge-status-connected");
                actionButton.setText("Disconnect");
                actionButton.getStyleClass().removeAll("btn-primary");
                if (!actionButton.getStyleClass().contains("btn-secondary")) {
                    actionButton.getStyleClass().add("btn-secondary");
                }
            } else if (status == SessionStatus.FAILED) {
                statusBadge.setText("Failed");
                statusBadge.getStyleClass().add("badge-status-failed");
                actionButton.setText("Retry");
                actionButton.getStyleClass().removeAll("btn-secondary");
                if (!actionButton.getStyleClass().contains("btn-primary")) {
                    actionButton.getStyleClass().add("btn-primary");
                }
            }
        });
    }

    private void setupContextMenu() {
        ContextMenu menu = new ContextMenu();

        MenuItem connectItem = new MenuItem("Connect");
        connectItem.setOnAction(e -> handleActionClick());

        MenuItem disconnectItem = new MenuItem("Disconnect");
        disconnectItem.setOnAction(e -> state.disconnectResource(resource));

        MenuItem copyIdItem = new MenuItem("Copy Resource ID");
        copyIdItem.setOnAction(e -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(resource.id());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        });

        menu.getItems().addAll(connectItem, disconnectItem, copyIdItem);
        setOnContextMenuRequested(e -> menu.show(this, e.getScreenX(), e.getScreenY()));
    }
}
