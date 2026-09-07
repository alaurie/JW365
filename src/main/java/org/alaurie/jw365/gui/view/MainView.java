package org.alaurie.jw365.gui.view;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.alaurie.jw365.auth.UserClaims;
import org.alaurie.jw365.feed.Workspace;
import org.alaurie.jw365.feed.WorkspaceResource;
import org.alaurie.jw365.gui.state.AppState;
import org.alaurie.jw365.rdp.FreeRdpInfo;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Main application view showing the workspace grid, search bar, header, and status bar.
 */
public final class MainView extends BorderPane {

    private final AppState state;
    private final VBox workspaceContainer;
    private final TextField searchField;
    private final Label userPillLabel;
    private final Label resourceCountLabel;
    private final Label statusMessageLabel;
    private final Label rdpEngineLabel;
    private final Label lastSyncedLabel;
    private final ProgressIndicator refreshIndicator;
    private final java.util.Map<String, ResourceCard> cardCache = new java.util.HashMap<>();
    public MainView(AppState state) {
        this.state = state;

        getStyleClass().add("main-window-bg");

        // 1. Top Header Bar
        HBox headerBar = new HBox(12);
        headerBar.getStyleClass().add("header-bar");
        headerBar.setAlignment(Pos.CENTER_LEFT);

        Label brandTitle = new Label("JW365");
        brandTitle.getStyleClass().add("brand-title");

        Label brandBadge = new Label("Cloud PC");
        brandBadge.getStyleClass().add("brand-badge");

        searchField = new TextField();
        searchField.getStyleClass().add("search-field");
        searchField.setPromptText("Search Cloud PCs and Apps...");
        searchField.textProperty().addListener((obs, oldV, newV) -> updateWorkspaceGrid());

        refreshIndicator = new ProgressIndicator();
        refreshIndicator.setMaxSize(16, 16);
        refreshIndicator.visibleProperty().bind(state.loadingProperty());

        Button refreshBtn = new Button("Refresh");
        refreshBtn.getStyleClass().add("btn-secondary");
        refreshBtn.setOnAction(e -> state.refreshWorkspacesAsync(false));

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // User profile pill
        HBox userPill = new HBox(8);
        userPill.getStyleClass().add("user-pill");
        userPillLabel = new Label("User");
        userPillLabel.getStyleClass().add("user-pill-text");
        userPill.getChildren().add(userPillLabel);

        Button settingsBtn = new Button("Settings");
        settingsBtn.getStyleClass().add("btn-icon");
        settingsBtn.setOnAction(e -> {
            SettingsDialog dialog = new SettingsDialog(getScene().getWindow(), state);
            dialog.showAndWait();
        });

        Button signOutBtn = new Button("Sign Out");
        signOutBtn.getStyleClass().add("btn-icon");
        signOutBtn.setOnAction(e -> handleSignOut());

        headerBar.getChildren().addAll(
            brandTitle,
            brandBadge,
            searchField,
            refreshBtn,
            refreshIndicator,
            spacer,
            userPill,
            settingsBtn,
            signOutBtn
        );
        setTop(headerBar);

        // 2. Center Workspace Grid
        workspaceContainer = new VBox(24);
        workspaceContainer.setPadding(new Insets(24));

        ScrollPane scrollPane = new ScrollPane(workspaceContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        setCenter(scrollPane);

        // 3. Bottom Status Bar Footer
        HBox statusBar = new HBox(16);
        statusBar.getStyleClass().add("status-bar");
        statusBar.setAlignment(Pos.CENTER_LEFT);

        resourceCountLabel = new Label("0 resources");
        resourceCountLabel.getStyleClass().add("status-bar-text");

        statusMessageLabel = new Label();
        statusMessageLabel.getStyleClass().add("status-bar-text");
        statusMessageLabel.textProperty().bind(state.statusMessageProperty());

        HBox footerSpacer = new HBox();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);

        rdpEngineLabel = new Label("FreeRDP: Checking...");
        rdpEngineLabel.getStyleClass().add("status-bar-text");

        lastSyncedLabel = new Label("Not synced");
        lastSyncedLabel.getStyleClass().add("status-bar-text");

        statusBar.getChildren().addAll(
            resourceCountLabel,
            statusMessageLabel,
            footerSpacer,
            rdpEngineLabel,
            lastSyncedLabel
        );
        setBottom(statusBar);

        // Wire State Listeners
        state.currentUserProperty().addListener((obs, oldVal, newVal) -> updateUserInfo(newVal));
        updateUserInfo(state.currentUserProperty().get());

        state.getWorkspaces().addListener((javafx.collections.ListChangeListener<Workspace>) c -> updateWorkspaceGrid());
        updateWorkspaceGrid();

        state.detectedFreeRdpProperty().addListener((obs, oldVal, newVal) -> updateFreeRdpLabel(newVal));
        updateFreeRdpLabel(state.detectedFreeRdpProperty().get());

        state.lastSyncedProperty().addListener((obs, oldVal, newVal) -> updateLastSyncedLabel(newVal));
    }

    private void updateUserInfo(UserClaims claims) {
        Platform.runLater(() -> {
            if (claims != null) {
                userPillLabel.setText(claims.displayIdentity());
            } else {
                userPillLabel.setText("Signed Out");
            }
        });
    }

    private void updateFreeRdpLabel(FreeRdpInfo info) {
        Platform.runLater(() -> {
            if (info != null) {
                rdpEngineLabel.setText("RDP Engine: " + info.displayName());
            } else {
                rdpEngineLabel.setText("RDP Engine: Not Detected (FreeRDP required)");
            }
        });
    }

    private void updateLastSyncedLabel(Instant syncedTime) {
        Platform.runLater(() -> {
            if (syncedTime != null) {
                long minutes = Duration.between(syncedTime, Instant.now()).toMinutes();
                if (minutes == 0) {
                    lastSyncedLabel.setText("Synced just now");
                } else {
                    lastSyncedLabel.setText("Synced " + minutes + "m ago");
                }
            } else {
                lastSyncedLabel.setText("Not synced");
            }
        });
    }

    private void updateWorkspaceGrid() {
        Platform.runLater(() -> {
            workspaceContainer.getChildren().clear();

            String query = searchField.getText() != null ? searchField.getText().trim().toLowerCase(Locale.ROOT) : "";
            List<Workspace> allWorkspaces = state.getWorkspaces();

            // Collect active resource IDs to clean up orphaned cards
            java.util.Set<String> activeIds = new java.util.HashSet<>();
            for (Workspace ws : allWorkspaces) {
                for (WorkspaceResource r : ws.resources()) {
                    activeIds.add(r.id());
                }
            }
            cardCache.entrySet().removeIf(entry -> {
                if (!activeIds.contains(entry.getKey())) {
                    entry.getValue().cleanup();
                    return true;
                }
                return false;
            });

            int matchedResources = 0;

            for (Workspace ws : allWorkspaces) {
                List<WorkspaceResource> filtered = ws.resources().stream()
                    .filter(res -> query.isEmpty() ||
                                   res.title().toLowerCase(Locale.ROOT).contains(query) ||
                                   res.displaySubtitle().toLowerCase(Locale.ROOT).contains(query) ||
                                   res.id().toLowerCase(Locale.ROOT).contains(query))
                    .toList();

                if (!filtered.isEmpty()) {
                    matchedResources += filtered.size();

                    VBox section = new VBox(12);

                    Label sectionHeading = new Label(ws.tenantDisplayName());
                    sectionHeading.getStyleClass().add("workspace-heading");

                    FlowPane flowPane = new FlowPane();
                    flowPane.setHgap(16);
                    flowPane.setVgap(16);
                    flowPane.setPrefWrapLength(800);

                    for (WorkspaceResource res : filtered) {
                        ResourceCard card = cardCache.computeIfAbsent(res.id(), id -> new ResourceCard(res, state));
                        flowPane.getChildren().add(card);
                    }

                    section.getChildren().addAll(sectionHeading, flowPane);
                    workspaceContainer.getChildren().add(section);
                }
            }

            resourceCountLabel.setText(matchedResources + (matchedResources == 1 ? " resource" : " resources"));

            if (matchedResources == 0) {
                VBox emptyBox = new VBox(12);
                emptyBox.setAlignment(Pos.CENTER);
                emptyBox.setPadding(new Insets(60, 20, 60, 20));

                Label emptyTitle = new Label(query.isEmpty() ? "No Cloud PCs or Apps Found" : "No matches for \"" + query + "\"");
                emptyTitle.getStyleClass().add("signin-title");

                Label emptySubtitle = new Label(query.isEmpty() ? "Click Refresh to check for available Windows 365 or AVD resources." : "Try adjusting your search terms.");
                emptySubtitle.getStyleClass().add("signin-subtitle");

                emptyBox.getChildren().addAll(emptyTitle, emptySubtitle);
                workspaceContainer.getChildren().add(emptyBox);
            }
        });
    }

    private void handleSignOut() {
        Alert alert = new Alert(
            Alert.AlertType.CONFIRMATION,
            "Are you sure you want to sign out of Windows 365 / AVD?",
            ButtonType.YES,
            ButtonType.NO
        );
        alert.setTitle("Sign Out");
        alert.setHeaderText("Sign Out Confirmation");
        alert.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.YES) {
                state.signOut();
            }
        });
    }
}
