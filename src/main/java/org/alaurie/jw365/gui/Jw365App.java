package org.alaurie.jw365.gui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.alaurie.jw365.auth.PersistentCookieManager;
import org.alaurie.jw365.gui.state.AppState;
import org.alaurie.jw365.gui.view.MainView;
import org.alaurie.jw365.gui.view.SignInView;

import java.io.InputStream;
import java.net.CookieHandler;
import java.util.Objects;

/**
 * Main JavaFX Application entrypoint for the JW365 Windows 365 & AVD Linux Client.
 */
public final class Jw365App extends Application {

    private AppState state;
    private Stage primaryStage;
    private StackPane rootContainer;
    private SignInView signInView;
    private MainView mainView;
    private PersistentCookieManager cookieManager;

    @Override
    public void init() {
        // Setup persistent cookie manager for JavaFX WebEngine / SSO
        try {
            cookieManager = new PersistentCookieManager();
            CookieHandler.setDefault(cookieManager);
        } catch (Exception e) {
            System.err.println("Warning: Failed to initialize cookie manager: " + e.getMessage());
        }

        // Prune old session logs (retain latest 10 logs)
        org.alaurie.jw365.config.XdgPaths.pruneOldLogs(10);

        state = new AppState();

        // Register JVM shutdown hook for clean process termination on SIGTERM/exit
        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup, "jw365-shutdown-hook"));
    }

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;

        Platform.setImplicitExit(true);

        rootContainer = new StackPane();
        rootContainer.getStyleClass().add("main-window-bg");

        signInView = new SignInView(state);
        mainView = new MainView(state);

        // Bind root view to authentication state
        state.authenticatedProperty().addListener((obs, oldVal, isAuth) -> {
            Platform.runLater(() -> updateActiveView(isAuth));
        });
        updateActiveView(state.authenticatedProperty().get());

        Scene scene = new Scene(rootContainer, 1050, 720);
        String cssPath = Objects.requireNonNull(getClass().getResource("/org/alaurie/jw365/gui/styles.css")).toExternalForm();
        scene.getStylesheets().add(cssPath);

        primaryStage.setTitle("JW365 - Windows 365 & AVD Client");
        primaryStage.setMinWidth(750);
        primaryStage.setMinHeight(550);
        primaryStage.setScene(scene);

        // Load multiple icon resolutions so Wayland / X11 window managers select the crispest match
        for (int size : new int[]{16, 32, 48, 64, 128, 256}) {
            try (InputStream is = getClass().getResourceAsStream("/org/alaurie/jw365/gui/icons/icon_" + size + ".png")) {
                if (is != null) {
                    primaryStage.getIcons().add(new Image(is));
                }
            } catch (Exception ignored) {
            }
        }

        // Lifecycle hooks
        primaryStage.setOnCloseRequest(e -> cleanup());

        // Initialize state & load cached data
        state.initialize();

        primaryStage.show();
    }

    @Override
    public void stop() {
        cleanup();
    }

    private void cleanup() {
        if (state != null) {
            state.getRdpSupervisor().stopAllSessions();
        }
        if (cookieManager != null) {
            cookieManager.persistCookies();
        }
    }

    private void updateActiveView(boolean isAuthenticated) {
        rootContainer.getChildren().clear();
        if (isAuthenticated) {
            rootContainer.getChildren().add(mainView);
        } else {
            rootContainer.getChildren().add(signInView);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
