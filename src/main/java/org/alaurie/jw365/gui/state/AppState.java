package org.alaurie.jw365.gui.state;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.scene.image.Image;
import org.alaurie.jw365.auth.AuthResult;
import org.alaurie.jw365.auth.BrowserInfo;
import org.alaurie.jw365.auth.BrowserLocator;
import org.alaurie.jw365.auth.OAuthClient;
import org.alaurie.jw365.auth.JwtClaimsParser;
import org.alaurie.jw365.auth.TokenResponse;
import org.alaurie.jw365.auth.TokenStore;
import org.alaurie.jw365.auth.UserClaims;
import org.alaurie.jw365.config.ClientConfig;
import org.alaurie.jw365.config.ConfigManager;
import org.alaurie.jw365.config.WorkspaceCache;
import org.alaurie.jw365.config.XdgPaths;
import org.alaurie.jw365.feed.TenantFeed;
import org.alaurie.jw365.feed.Workspace;
import org.alaurie.jw365.feed.WorkspaceFeedClient;
import org.alaurie.jw365.feed.WorkspaceResource;
import org.alaurie.jw365.rdp.FreeRdpInfo;
import org.alaurie.jw365.rdp.FreeRdpLocator;
import org.alaurie.jw365.rdp.RdpProcessSupervisor;
import org.alaurie.jw365.rdp.RdpSessionConfig;
import org.alaurie.jw365.rdp.SessionEvent;
import org.alaurie.jw365.rdp.SessionStatus;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Reactive state management engine connecting JavaFX UI components to the Core domain services.
 */
public final class AppState {

    private final OAuthClient oauthClient;
    private final TokenStore tokenStore;
    private final ConfigManager configManager;
    private final WorkspaceCache workspaceCache;
    private final WorkspaceFeedClient feedClient;
    private final RdpProcessSupervisor rdpSupervisor;
    private final BooleanProperty authenticated = new SimpleBooleanProperty(false);
    private final ObjectProperty<UserClaims> currentUser = new SimpleObjectProperty<>(null);
    private final ObservableList<Workspace> workspaces = FXCollections.observableArrayList();
    private final ObservableMap<String, SessionStatus> sessionStatuses = FXCollections.observableHashMap();
    private final BooleanProperty loading = new SimpleBooleanProperty(false);
    private final StringProperty statusMessage = new SimpleStringProperty("Ready");
    private final StringProperty searchFilter = new SimpleStringProperty("");
    private final ObjectProperty<Instant> lastSynced = new SimpleObjectProperty<>(null);
    private final ObjectProperty<FreeRdpInfo> detectedFreeRdp = new SimpleObjectProperty<>(null);
    private final ObjectProperty<BrowserInfo> detectedEdge = new SimpleObjectProperty<>(null);
    private final java.util.concurrent.atomic.AtomicBoolean autoConnectTriggered = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final AtomicBoolean refreshInProgress = new AtomicBoolean(false);
    private final AtomicLong operationGeneration = new AtomicLong();
    private final Map<String, Image> iconMemoryCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jw365-scheduler");
        t.setDaemon(true);
        return t;
    });

    public AppState() {
        this(new OAuthClient(), new TokenStore(), new ConfigManager(), new WorkspaceCache(), new WorkspaceFeedClient(), new RdpProcessSupervisor());
    }
    public AppState(
        OAuthClient oauthClient,
        TokenStore tokenStore,
        ConfigManager configManager,
        WorkspaceCache workspaceCache,
        WorkspaceFeedClient feedClient,
        RdpProcessSupervisor rdpSupervisor
    ) {
        this.oauthClient = oauthClient != null ? oauthClient : new OAuthClient();
        this.tokenStore = tokenStore != null ? tokenStore : new TokenStore();
        this.configManager = configManager != null ? configManager : new ConfigManager();
        this.workspaceCache = workspaceCache != null ? workspaceCache : new WorkspaceCache();
        this.feedClient = feedClient != null ? feedClient : new WorkspaceFeedClient();
        this.rdpSupervisor = rdpSupervisor != null ? rdpSupervisor : new RdpProcessSupervisor();
        this.rdpSupervisor.addGlobalListener(event -> {
            if (event instanceof SessionEvent.StatusChanged sc) {
                runOnFxThread(() -> {
                    sessionStatuses.put(sc.sessionId(), sc.newStatus());
                    if (sc.newStatus() == SessionStatus.CONNECTED) {
                        statusMessage.set("Connected to Cloud PC");
                    } else if (sc.newStatus() == SessionStatus.DISCONNECTED) {
                        statusMessage.set("Session disconnected");
                    }
                });
            } else if (event instanceof SessionEvent.Exited ex) {
                runOnFxThread(() -> {
                    sessionStatuses.put(ex.sessionId(), SessionStatus.DISCONNECTED);
                    statusMessage.set("Session ended");
                });
            } else if (event instanceof SessionEvent.AuthRequired ar) {
                runOnFxThread(() -> handleSessionAuth(ar));
            }
        });
    }

    private void handleSessionAuth(SessionEvent.AuthRequired ar) {
        String title = "Cloud PC";
        for (Workspace ws : workspaces) {
            for (WorkspaceResource res : ws.resources()) {
                if (res.id().equals(ar.sessionId())) {
                    title = res.title();
                    break;
                }
            }
        }
        try {
            org.alaurie.jw365.gui.view.SessionAuthDialog dialog = new org.alaurie.jw365.gui.view.SessionAuthDialog(null, title, ar);
            dialog.startSilentOrShow();
        } catch (Exception e) {
            System.err.println("Warning: Could not open SessionAuthDialog: " + e.getMessage());
        }
    }

    public void initialize() {
        // 1. Locate FreeRDP and Edge Browser
        ClientConfig config = configManager.get();
        String source = XdgPaths.isFlatpak() ? "BUNDLED" : config.freerdpSource().name();
        Optional<FreeRdpInfo> rdpInfo = FreeRdpLocator.locate(source, config.preferredFreeRdpPath());
        runOnFxThread(() -> detectedFreeRdp.set(rdpInfo.orElse(null)));

        Optional<BrowserInfo> edgeInfo = BrowserLocator.findEdge();
        runOnFxThread(() -> detectedEdge.set(edgeInfo.orElse(null)));

        // 2. Load cached workspaces to immediately populate UI
        List<Workspace> cached = workspaceCache.loadWorkspaces();
        if (!cached.isEmpty()) {
            runOnFxThread(() -> workspaces.setAll(cached));
        }

        // 3. Check for existing token
        Optional<TokenResponse> cachedTokens = tokenStore.load();
        if (cachedTokens.isPresent()) {
            TokenResponse tokens = cachedTokens.get();
            try {
                UserClaims claims = JwtClaimsParser.parseIdToken(tokens.idToken());
                runOnFxThread(() -> {
                    currentUser.set(claims);
                    authenticated.set(true);
                    refreshWorkspacesAsync(tokens.isExpiringSoon());
                });
            } catch (Exception e) {
                System.err.println("Warning: Invalid cached token: " + e.getMessage());
            }
        }
        // 4. Setup periodic auto-refresh
        int refreshMin = Math.max(config.autoRefreshMinutes(), 5);
        scheduler.scheduleAtFixedRate(() -> {
            if (authenticated.get()) {
                refreshWorkspacesAsync(false);
            }
        }, refreshMin, refreshMin, TimeUnit.MINUTES);
    }


    public void signInWithCode(String authorizationCode, String codeVerifier, String redirectUri, Runnable onSuccess, Consumer<String> onError) {
        long generation = operationGeneration.get();
        setLoading(true, "Authenticating with Microsoft Entra ID...");

        Thread.ofVirtual().name("jw365-auth-worker").start(() -> {
            try {
                ClientConfig config = configManager.get();
                AuthResult result = oauthClient.exchangeCodeForTokens(config.defaultTenant(), authorizationCode, codeVerifier, redirectUri);

                switch (result) {
                    case AuthResult.Success(var tokens, var claims) -> {
                        if (generation != operationGeneration.get()) {
                            return;
                        }
                        tokenStore.save(tokens);
                        runOnFxThread(() -> {
                            currentUser.set(claims);
                            authenticated.set(true);
                            setLoading(false, "Signed in as " + claims.displayIdentity());
                            if (onSuccess != null) {
                                onSuccess.run();
                            }
                            refreshWorkspacesAsync(false);
                        });
                    }
                    case AuthResult.Failure(var code, var msg, _) -> runOnFxThread(() -> {
                        setLoading(false, "Authentication failed");
                        if (onError != null) {
                            onError.accept(msg != null ? msg : code);
                        }
                    });
                }
            } catch (Exception e) {
                runOnFxThread(() -> {
                    setLoading(false, "Error: " + e.getMessage());
                    if (onError != null) {
                        onError.accept(e.getMessage());
                    }
                });
            }
        });
    }


    public void signOut() {
        operationGeneration.incrementAndGet();
        rdpSupervisor.stopAllSessions();
        tokenStore.clear();
        workspaceCache.clear();
        iconMemoryCache.clear();

        runOnFxThread(() -> {
            authenticated.set(false);
            currentUser.set(null);
            workspaces.clear();
            sessionStatuses.clear();
            statusMessage.set("Signed out");
        });
    }

    public void shutdown() {
        operationGeneration.incrementAndGet();
        scheduler.shutdownNow();
        rdpSupervisor.stopAllSessions();
    }

    public void refreshWorkspacesAsync(boolean forceTokenRefresh) {
        if (!authenticated.get() || !refreshInProgress.compareAndSet(false, true)) {
            return;
        }
        long generation = operationGeneration.get();

        runOnFxThread(() -> setLoading(true, "Discovering Windows 365 workspaces..."));

        Thread.ofVirtual().name("jw365-feed-refresh").start(() -> {
            try {
                Optional<TokenResponse> tokenOpt = tokenStore.load();
                if (tokenOpt.isEmpty()) {
                    if (generation != operationGeneration.get()) {
                        return;
                    }
                    runOnFxThread(() -> {
                        authenticated.set(false);
                        setLoading(false, "Session expired, please sign in again");
                    });
                    return;
                }

                TokenResponse tokens = tokenOpt.get();
                ClientConfig config = configManager.get();

                if (forceTokenRefresh || tokens.isExpiringSoon()) {
                    runOnFxThread(() -> statusMessage.set("Refreshing authentication token..."));
                    AuthResult refreshResult = oauthClient.refreshToken(config.defaultTenant(), tokens.refreshToken());
                    if (refreshResult instanceof AuthResult.Success(var newTokens, var claims)) {
                        tokenStore.save(newTokens);
                        tokens = newTokens;
                        if (generation == operationGeneration.get()) {
                            runOnFxThread(() -> currentUser.set(claims));
                        }
                    } else if (refreshResult instanceof AuthResult.Failure f) {
                        System.err.println("Warning: Token refresh failed: " + f.errorMessage());
                    }
                }

                String accessToken = tokens.accessToken();
                List<TenantFeed> feeds = feedClient.discoverTenantFeeds(accessToken);
                List<Workspace> newWorkspaces = feedClient.fetchAllWorkspaces(accessToken, feeds);
                workspaceCache.saveWorkspaces(newWorkspaces);

                if (generation != operationGeneration.get()) {
                    return;
                }
                runOnFxThread(() -> {
                    if (generation != operationGeneration.get()) {
                        return;
                    }
                    workspaces.setAll(newWorkspaces);
                    lastSynced.set(Instant.now());
                    int totalResources = newWorkspaces.stream().mapToInt(w -> w.resources().size()).sum();
                    setLoading(false, "Discovered " + totalResources + " resources across " + newWorkspaces.size() + " workspaces");

                    if (config.autoConnect() && !autoConnectTriggered.getAndSet(true)) {
                        List<WorkspaceResource> allDesktops = newWorkspaces.stream()
                            .flatMap(w -> w.resources().stream())
                            .filter(r -> r.type().isDesktop())
                            .toList();
                        if (allDesktops.size() == 1) {
                            connectResource(allDesktops.getFirst(), null);
                        }
                    }
                });

                for (Workspace ws : newWorkspaces) {
                    for (WorkspaceResource res : ws.resources()) {
                        if (res.iconUrl() != null && !workspaceCache.hasCachedIcon(res)) {
                            Thread.ofVirtual().start(() -> {
                                byte[] bytes = feedClient.downloadIconBytes(accessToken, res.iconUrl());
                                if (bytes != null) {
                                    workspaceCache.saveIcon(res, bytes);
                                }
                            });
                        }
                    }
                }
            } catch (Exception e) {
                if (generation == operationGeneration.get()) {
                    runOnFxThread(() -> setLoading(false, "Feed refresh error: " + e.getMessage()));
                }
            } finally {
                refreshInProgress.set(false);
            }
        });
    }

    public enum DisplayMode {
        DEFAULT,
        FULLSCREEN,
        WINDOWED,
        MULTIMON
    }

    public void connectResource(WorkspaceResource resource, Consumer<String> onError) {
        connectResource(resource, DisplayMode.DEFAULT, onError);
    }

    public void connectResource(WorkspaceResource resource, DisplayMode displayMode, Consumer<String> onError) {
        Optional<FreeRdpInfo> rdpOpt = Optional.ofNullable(detectedFreeRdp.get());
        if (rdpOpt.isEmpty()) {
            if (onError != null) {
                onError.accept("FreeRDP is not installed or detected on your system. Please install FreeRDP (e.g. 'sudo apt install freerdp3-sdl' or 'freerdp3-x11') or configure a custom binary in Settings.");
            }
            return;
        }

        FreeRdpInfo freeRdp = rdpOpt.get();
        runOnFxThread(() -> sessionStatuses.put(resource.id(), SessionStatus.STARTING));

        Thread.ofVirtual().name("jw365-connect-" + resource.sanitizedFileName()).start(() -> {
            try {
                Optional<TokenResponse> tokenOpt = tokenStore.load();
                if (tokenOpt.isEmpty()) {
                    runOnFxThread(() -> {
                        sessionStatuses.put(resource.id(), SessionStatus.FAILED);
                        if (onError != null) onError.accept("Not authenticated");
                    });
                    return;
                }

                TokenResponse tokens = tokenOpt.get();
                ClientConfig config = configManager.get();

                // Download RDP file
                runOnFxThread(() -> statusMessage.set("Downloading remote desktop profile..."));
                Path rdpFilePath = XdgPaths.rdpFeedDir().resolve(resource.sanitizedFileName() + ".rdp");
                feedClient.downloadRdpFile(tokens.accessToken(), resource.rdpUrl(), rdpFilePath);

                // Build session config
                UserClaims claims = currentUser.get();
                String username = claims != null ? claims.rdpUsername() : "";

                DisplayMode effectiveMode = displayMode != null ? displayMode : DisplayMode.DEFAULT;
                boolean multiMon = (effectiveMode == DisplayMode.MULTIMON) || (effectiveMode == DisplayMode.DEFAULT && config.multiMonitor());
                boolean fullscreen = (effectiveMode == DisplayMode.FULLSCREEN) || (effectiveMode == DisplayMode.DEFAULT && config.fullscreen()) || multiMon;

                RdpSessionConfig sessionConfig = new RdpSessionConfig(
                    rdpFilePath,
                    username,
                    fullscreen,
                    config.scalePercent(),
                    config.sound(),
                    config.microphone(),
                    multiMon,
                    config.ignoreCert(),
                    config.clipboard(),
                    config.dynamicResolution(),
                    config.gfxProgressive(),
                    config.asyncUpdate(),
                    config.autoReconnect(),
                    config.usbRedirection(),
                    config.smartcard(),
                    config.extraArgs()
                );

                // Launch session
                runOnFxThread(() -> statusMessage.set("Starting FreeRDP session..."));
                rdpSupervisor.launch(freeRdp, resource, sessionConfig, null);
            } catch (Exception e) {
                runOnFxThread(() -> {
                    sessionStatuses.put(resource.id(), SessionStatus.FAILED);
                    statusMessage.set("Connection failed");
                    if (onError != null) {
                        onError.accept("Failed to start session: " + e.getMessage());
                    }
                });
            }
        });
    }

    public void restartResource(WorkspaceResource resource, Consumer<String> onError) {
        if (resource == null) return;
        disconnectResource(resource);
        Thread.ofVirtual().name("jw365-restart-" + resource.sanitizedFileName()).start(() -> {
            try {
                Thread.sleep(600);
            } catch (InterruptedException ignored) {
            }
            connectResource(resource, DisplayMode.DEFAULT, onError);
        });
    }
    public void disconnectResource(WorkspaceResource resource) {
        if (resource == null) {
            return;
        }
        runOnFxThread(() -> sessionStatuses.put(resource.id(), SessionStatus.DISCONNECTING));
        rdpSupervisor.stopSession(resource.id());
    }

    public void loadResourceIcon(WorkspaceResource resource, Consumer<Image> callback) {
        if (resource == null) return;

        // 1. Check memory cache
        Image cachedImg = iconMemoryCache.get(resource.id());
        if (cachedImg != null) {
            callback.accept(cachedImg);
            return;
        }

        Thread.ofVirtual().name("jw365-icon-" + resource.sanitizedFileName()).start(() -> {
            // 2. Check disk cache
            Optional<byte[]> diskBytes = workspaceCache.loadIconBytes(resource);
            if (diskBytes.isPresent()) {
                Image img = new Image(new ByteArrayInputStream(diskBytes.get()));
                iconMemoryCache.put(resource.id(), img);
                runOnFxThread(() -> callback.accept(img));
                return;
            }

            // 3. Download from network
            if (resource.iconUrl() != null) {
                Optional<TokenResponse> tokenOpt = tokenStore.load();
                String token = tokenOpt.map(TokenResponse::accessToken).orElse(null);
                byte[] downloaded = feedClient.downloadIconBytes(token, resource.iconUrl());
                if (downloaded != null) {
                    workspaceCache.saveIcon(resource, downloaded);
                    Image img = new Image(new ByteArrayInputStream(downloaded));
                    iconMemoryCache.put(resource.id(), img);
                    runOnFxThread(() -> callback.accept(img));
                }
            }
        });
    }

    public void updateConfig(ClientConfig newConfig) {
        try {
            configManager.save(newConfig);
            String source = XdgPaths.isFlatpak() ? "BUNDLED" : newConfig.freerdpSource().name();
            Optional<FreeRdpInfo> rdpInfo = FreeRdpLocator.locate(source, newConfig.preferredFreeRdpPath());
            runOnFxThread(() -> detectedFreeRdp.set(rdpInfo.orElse(null)));
        } catch (IOException e) {
            System.err.println("Warning: Failed to save config: " + e.getMessage());
        }
    }

    public OAuthClient getOauthClient() {
        return oauthClient;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }


    public BooleanProperty authenticatedProperty() {
        return authenticated;
    }

    public ObjectProperty<UserClaims> currentUserProperty() {
        return currentUser;
    }

    public ObservableList<Workspace> getWorkspaces() {
        return workspaces;
    }

    public ObservableMap<String, SessionStatus> getSessionStatuses() {
        return sessionStatuses;
    }

    public BooleanProperty loadingProperty() {
        return loading;
    }

    public StringProperty statusMessageProperty() {
        return statusMessage;
    }

    public StringProperty searchFilterProperty() {
        return searchFilter;
    }

    public ObjectProperty<Instant> lastSyncedProperty() {
        return lastSynced;
    }

    public ObjectProperty<FreeRdpInfo> detectedFreeRdpProperty() {
        return detectedFreeRdp;
    }


    private void setLoading(boolean isLoading, String message) {
        runOnFxThread(() -> {
            this.loading.set(isLoading);
            if (message != null) {
                this.statusMessage.set(message);
            }
        });
    }

    public static void runOnFxThread(Runnable action) {
        if (action == null) return;
        try {
            if (Platform.isFxApplicationThread()) {
                action.run();
            } else {
                Platform.runLater(action);
            }
        } catch (IllegalStateException ignored) {
            // JavaFX toolkit is shutting down; discard late UI work.
        }
    }
}
