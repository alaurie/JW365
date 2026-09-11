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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
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
    private static final int MAX_ICON_MEMORY_ENTRIES = 256;
    private final Map<String, Image> iconMemoryCache = Collections.synchronizedMap(
        new LinkedHashMap<>(MAX_ICON_MEMORY_ENTRIES, 0.75f, true));
    private final java.util.concurrent.atomic.AtomicBoolean autoConnectTriggered = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final AtomicBoolean refreshInProgress = new AtomicBoolean(false);
    private final AtomicLong operationGeneration = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object authOperationLock = new Object();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jw365-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final ThreadPoolExecutor iconExecutor = new ThreadPoolExecutor(
        4, 4, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.AbortPolicy());
    private final Set<String> iconJobs = ConcurrentHashMap.newKeySet();
    private final Map<String, List<Consumer<Image>>> iconCallbacks = new ConcurrentHashMap<>();
    private final Set<Future<?>> iconFutures = ConcurrentHashMap.newKeySet();
    private final Map<String, org.alaurie.jw365.gui.view.SessionAuthDialog> activeAuthDialogs = new ConcurrentHashMap<>();
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
                    if (closed.get() || !authenticated.get()) return;
                    sessionStatuses.put(sc.sessionId(), sc.newStatus());
                    if (sc.newStatus() == SessionStatus.CONNECTED) {
                        closeAuthDialog(sc.sessionId());
                        statusMessage.set("Connected to Cloud PC");
                    } else if (sc.newStatus() == SessionStatus.DISCONNECTED) {
                        closeAuthDialog(sc.sessionId());
                        statusMessage.set("Session disconnected");
                    } else if (sc.newStatus() == SessionStatus.FAILED) {
                        closeAuthDialog(sc.sessionId());
                        statusMessage.set(sc.message() != null ? sc.message() : "Connection failed");
                    } else if (sc.newStatus() == SessionStatus.CONNECTING || sc.newStatus() == SessionStatus.STARTING) {
                        statusMessage.set(sc.message() != null ? sc.message() : "Connecting to Cloud PC...");
                    } else if (sc.newStatus() == SessionStatus.RECONNECTING) {
                        statusMessage.set(sc.message() != null ? sc.message() : "Reconnecting to Cloud PC...");
                    } else if (sc.newStatus() == SessionStatus.DISCONNECTING) {
                        statusMessage.set("Disconnecting from Cloud PC...");
                    }
                });
            } else if (event instanceof SessionEvent.Exited ex) {
                runOnFxThread(() -> {
                    if (closed.get() || !authenticated.get()) return;
                    closeAuthDialog(ex.sessionId());
                    SessionStatus current = sessionStatuses.get(ex.sessionId());
                    if (current != SessionStatus.FAILED) {
                        if (ex.exitCode() != 0 && ex.exitCode() != 143 && ex.exitCode() != 130 && ex.exitCode() != 129) {
                            sessionStatuses.put(ex.sessionId(), SessionStatus.FAILED);
                            statusMessage.set(ex.message() != null ? ex.message() : "Session exited with error code " + ex.exitCode());
                        } else {
                            sessionStatuses.put(ex.sessionId(), SessionStatus.DISCONNECTED);
                            statusMessage.set("Session ended");
                        }
                    }
                });
            } else if (event instanceof SessionEvent.AuthRequired ar) {
                runOnFxThread(() -> {
                    if (!closed.get() && authenticated.get()) handleSessionAuth(ar);
                });
            }
        });
    }

    private void closeAuthDialog(String sessionId) {
        if (sessionId == null) return;
        org.alaurie.jw365.gui.view.SessionAuthDialog dialog = activeAuthDialogs.remove(sessionId);
        if (dialog != null) {
            dialog.completeAndClose();
            Platform.runLater(() -> {
                if (dialog.isShowing()) dialog.close();
            });
        }
    }

    private void handleSessionAuth(SessionEvent.AuthRequired ar) {
        String title = "Cloud PC";
        for (Workspace ws : workspaces) {
            for (WorkspaceResource res : ws.resources()) {
                if (res.identityKey().equals(ar.sessionId())) {
                    title = res.title();
                    break;
                }
            }
        }
        UserClaims claims = currentUser.get();
        String loginHint = claims != null ? claims.rdpUsername() : null;
        if (loginHint == null || loginHint.isBlank()) {
            loginHint = claims != null ? claims.displayIdentity() : null;
        }

        // Close any existing active dialog for this session (e.g. prior auth prompt)
        org.alaurie.jw365.gui.view.SessionAuthDialog existing = activeAuthDialogs.remove(ar.sessionId());
        if (existing != null) {
            existing.completeAndClose();
        }

        try {
            org.alaurie.jw365.gui.view.SessionAuthDialog dialog = new org.alaurie.jw365.gui.view.SessionAuthDialog(
                null,
                title,
                ar,
                () -> {
                    activeAuthDialogs.remove(ar.sessionId());
                    runOnFxThread(() -> {
                        sessionStatuses.put(ar.sessionId(), SessionStatus.FAILED);
                        statusMessage.set("Authentication cancelled");
                    });
                    rdpSupervisor.stopSession(ar.sessionId());
                },
                loginHint,
                () -> activeAuthDialogs.remove(ar.sessionId())
            );
            activeAuthDialogs.put(ar.sessionId(), dialog);
            dialog.setOnHidden(e -> {
                activeAuthDialogs.remove(ar.sessionId(), dialog);
                dialog.completeAndClose();
            });
            dialog.startSilentOrShow();
        } catch (Exception e) {
            activeAuthDialogs.remove(ar.sessionId());
            System.err.println("Warning: Could not open SessionAuthDialog: " + e.getMessage());
            runOnFxThread(() -> {
                sessionStatuses.put(ar.sessionId(), SessionStatus.FAILED);
                statusMessage.set("Authentication failed: " + e.getMessage());
            });
            rdpSupervisor.stopSession(ar.sessionId());
        }
    }
    public void initialize() {
        if (closed.get()) return;
        ClientConfig config = configManager.get();
        String source = XdgPaths.isFlatpak() ? "BUNDLED" : config.freerdpSource().name();
        Optional<FreeRdpInfo> rdpInfo = FreeRdpLocator.locate(source, config.preferredFreeRdpPath());
        runOnFxThread(() -> detectedFreeRdp.set(rdpInfo.orElse(null)));


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
        setLoadingIfCurrent(generation, true, "Authenticating with Microsoft Entra ID...");
        Thread.ofVirtual().start(() -> {
            AuthResult result = oauthClient.exchangeAuthorizationCode(configManager.get().defaultTenant(), authorizationCode, codeVerifier, redirectUri);
            if (result instanceof AuthResult.Success(var tokens, var claims)) {
                synchronized (authOperationLock) {
                    if (generation != operationGeneration.get()) return;
                    try {
                        tokenStore.save(tokens);
                    } catch (Exception e) {
                        if (generation != operationGeneration.get()) return;
                        runIfCurrent(generation, () -> {
                            setLoading(false, "Authentication failed: " + e.getMessage());
                            if (onError != null) onError.accept(e.getMessage());
                        });
                        return;
                    }
                    runIfCurrent(generation, () -> {
                        currentUser.set(claims);
                        authenticated.set(true);
                        setLoading(false, "Signed in as " + claims.displayIdentity());
                        if (onSuccess != null) onSuccess.run();
                        refreshWorkspacesAsync(false);
                    });
                }
            } else if (result instanceof AuthResult.Failure failure) {
                if (generation != operationGeneration.get()) return;
                runIfCurrent(generation, () -> {
                    setLoading(false, "Authentication failed: " + failure.errorMessage());
                    if (onError != null) onError.accept(failure.errorMessage());
                });
            }
        });
    }

    /**
     * Opens the nativeclient PKCE flow in the embedded WebView. The bundled
     * Microsoft client registration does not permit MSAL4J's dynamic
     * localhost redirect used by external-browser interactive auth.
     */
    public void signInWithEmbeddedWebView() {
        runOnFxThread(() -> {
            setLoading(false, "Opening embedded Microsoft sign-in...");
            new org.alaurie.jw365.gui.view.AuthDialog(null, this).show();
        });
    }

    /** Clears local credentials, MSAL accounts, and in-flight authentication state. */
    public void signOut() {
        boolean tokenCleared;
        synchronized (authOperationLock) {
            operationGeneration.incrementAndGet();
            tokenCleared = tokenStore.clear();
            oauthClient.clearCacheAndAccounts();
            workspaceCache.clear();
            iconMemoryCache.clear();
            iconCallbacks.clear();
            iconJobs.clear();
            iconFutures.forEach(future -> future.cancel(true));
            iconFutures.clear();
        }
        refreshInProgress.set(false);
        rdpSupervisor.stopAllSessions();
        String message = tokenCleared ? "Signed out" : "Signed out locally; secure credential cleanup failed";
        runOnFxThread(() -> {
            authenticated.set(false);
            currentUser.set(null);
            workspaces.clear();
            sessionStatuses.clear();
            statusMessage.set(message);
        });
    }

    public void shutdown() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (authOperationLock) { operationGeneration.incrementAndGet(); }
        refreshInProgress.set(false);
        scheduler.shutdownNow();
        iconExecutor.shutdownNow();
        iconFutures.forEach(future -> future.cancel(true));
        iconFutures.clear();
        iconCallbacks.clear();
        iconJobs.clear();
    }

    public void refreshWorkspacesAsync(boolean forceTokenRefresh) {
        if (closed.get() || !authenticated.get() || !refreshInProgress.compareAndSet(false, true)) return;
        long generation = operationGeneration.get();
        runIfCurrent(generation, () -> setLoading(true, "Discovering Windows 365 workspaces..."));
        Thread.ofVirtual().name("jw365-feed-refresh").start(() -> {
            try {
                Optional<TokenResponse> tokenOpt = tokenStore.load();
                if (tokenOpt.isEmpty()) {
                    if (generation != operationGeneration.get()) return;
                    long invalidationGeneration;
                    synchronized (authOperationLock) {
                        if (generation != operationGeneration.get()) return;
                        invalidationGeneration = operationGeneration.incrementAndGet();
                    }
                    expireAuthentication("Session expired, please sign in again", invalidationGeneration);
                    return;
                }

                TokenResponse tokens = tokenOpt.get();
                ClientConfig config = configManager.get();
                if (forceTokenRefresh || tokens.isExpiringSoon()) {
                    AuthResult refreshResult = oauthClient.refreshTokenWithMsal(config.defaultTenant());
                    if (refreshResult instanceof AuthResult.Success(var newTokens, var claims)) {
                        synchronized (authOperationLock) {
                            if (generation != operationGeneration.get()) return;
                            tokens = mergeTokenResponses(tokens, newTokens);
                            tokenStore.save(tokens);
                        }
                        runIfCurrent(generation, () -> currentUser.set(claims));
                    } else if (refreshResult instanceof AuthResult.Failure f) {
                        System.err.println("Warning: Token refresh failed: " + f.errorMessage());
                        if (generation != operationGeneration.get()) return;
                        long invalidationGeneration;
                        synchronized (authOperationLock) {
                            if (generation != operationGeneration.get()) return;
                            invalidationGeneration = operationGeneration.incrementAndGet();
                        }
                        expireAuthentication("Session expired, please sign in again", invalidationGeneration);
                        return;
                    }
                }

                if (!isCurrentGeneration(generation) || closed.get()) return;
                String accessToken = tokens.accessToken();
                List<TenantFeed> feeds = feedClient.discoverTenantFeeds(accessToken);
                if (!isCurrentGeneration(generation) || closed.get()) return;
                List<Workspace> newWorkspaces = feedClient.fetchAllWorkspaces(accessToken, feeds);
                synchronized (authOperationLock) {
                    if (!isCurrentGeneration(generation) || closed.get()) return;
                    workspaceCache.saveWorkspaces(newWorkspaces);
                }
                runIfCurrent(generation, () -> {
                    workspaces.setAll(newWorkspaces);
                    lastSynced.set(Instant.now());
                    int totalResources = newWorkspaces.stream().mapToInt(w -> w.resources().size()).sum();
                    setLoading(false, "Discovered " + totalResources + " resources across " + newWorkspaces.size() + " workspaces");
                    if (config.autoConnect() && !autoConnectTriggered.getAndSet(true)) {
                        List<WorkspaceResource> allDesktops = newWorkspaces.stream()
                            .flatMap(w -> w.resources().stream()).filter(r -> r.type().isDesktop()).toList();
                        if (allDesktops.size() == 1) connectResource(allDesktops.getFirst(), null);
                    }
                });
                for (Workspace ws : newWorkspaces) {
                    for (WorkspaceResource res : ws.resources()) {
                        if (res.iconUrl() != null && !workspaceCache.hasCachedIcon(res)) enqueueIconJob(res, accessToken, generation);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                runIfCurrent(generation, () -> setLoading(false, "Feed refresh cancelled"));
            } catch (Exception e) {
                runIfCurrent(generation, () -> setLoading(false, "Feed refresh error: " + e.getMessage()));
            } finally {
                refreshInProgress.set(false);
            }
        });
    }

    private static TokenResponse mergeTokenResponses(TokenResponse previous, TokenResponse refreshed) {
        String refreshToken = refreshed.hasRefreshToken()
            ? refreshed.refreshToken()
            : previous.hasRefreshToken() ? previous.refreshToken() : null;
        return new TokenResponse(
            refreshed.accessToken(),
            refreshToken,
            refreshed.idToken(),
            refreshed.tokenType(),
            refreshed.expiresIn(),
            refreshed.scope(),
            refreshed.obtainedEpochSec()
        );
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
        if (resource == null || closed.get()) return;
        long generation = operationGeneration.get();
        Optional<FreeRdpInfo> rdpOpt = Optional.ofNullable(detectedFreeRdp.get());
        if (rdpOpt.isEmpty()) {
            runIfCurrent(generation, () -> {
                sessionStatuses.put(resource.identityKey(), SessionStatus.FAILED);
                statusMessage.set("FreeRDP is not installed or detected");
            });
            if (onError != null) onError.accept("FreeRDP is not installed or detected on your system. Please install FreeRDP or configure a custom binary in Settings.");
            return;
        }
        FreeRdpInfo freeRdp = rdpOpt.get();
        runIfCurrent(generation, () -> sessionStatuses.put(resource.identityKey(), SessionStatus.STARTING));
        Thread.ofVirtual().name("jw365-connect-" + resource.sanitizedFileName()).start(() -> {
            try {
                if (!isCurrentGeneration(generation) || closed.get()) return;
                Optional<TokenResponse> tokenOpt = tokenStore.load();
                if (tokenOpt.isEmpty()) {
                    runIfCurrent(generation, () -> {
                        sessionStatuses.put(resource.identityKey(), SessionStatus.FAILED);
                        if (onError != null) onError.accept("Not authenticated");
                    });
                    return;
                }

                if (!isCurrentGeneration(generation) || closed.get()) return;
                TokenResponse tokens = tokenOpt.get();
                ClientConfig config = configManager.get();
                if (!isCurrentGeneration(generation) || closed.get()) return;
                runIfCurrent(generation, () -> statusMessage.set("Downloading remote desktop profile..."));
                if (resource.rdpUrl() == null) {
                    throw new IOException("Workspace resource does not specify an RDP profile URL");
                }
                Path rdpFilePath = XdgPaths.rdpFeedDir().resolve(resource.sanitizedFileName() + ".rdp");
                feedClient.downloadRdpFile(tokens.accessToken(), resource.rdpUrl(), rdpFilePath);
                if (!isCurrentGeneration(generation) || closed.get()) return;
                // Build session config
                UserClaims claims = currentUser.get();
                String username = claims != null ? claims.rdpUsername() : "";
                DisplayMode effectiveMode = displayMode != null ? displayMode : DisplayMode.DEFAULT;
                boolean multiMon = (effectiveMode == DisplayMode.MULTIMON) || (effectiveMode == DisplayMode.DEFAULT && config.multiMonitor());
                boolean fullscreen = (effectiveMode == DisplayMode.FULLSCREEN) || (effectiveMode == DisplayMode.DEFAULT && config.fullscreen()) || multiMon;
                RdpSessionConfig sessionConfig = new RdpSessionConfig(rdpFilePath, username, fullscreen,
                    config.scalePercent(), config.sound(), config.microphone(), multiMon, config.ignoreCert(), config.clipboard(),
                    config.dynamicResolution(), config.gfxProgressive(), config.asyncUpdate(), config.autoReconnect(),
                    config.usbRedirection(), config.smartcard(), config.extraArgs());
                synchronized (authOperationLock) {
                    if (!isCurrentGeneration(generation) || closed.get()) return;
                    runIfCurrent(generation, () -> statusMessage.set("Starting FreeRDP session..."));
                    rdpSupervisor.launch(freeRdp, resource, sessionConfig, null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                closeAuthDialog(resource.identityKey());
                runIfCurrent(generation, () -> {
                    sessionStatuses.put(resource.identityKey(), SessionStatus.FAILED);
                    statusMessage.set("Connection failed: " + e.getMessage());
                    if (onError != null) onError.accept("Failed to start session: " + e.getMessage());
                });
            }
        });
    }

    public void disconnectResource(WorkspaceResource resource) {
        if (resource == null) return;
        closeAuthDialog(resource.identityKey());
        runIfCurrent(operationGeneration.get(), () -> sessionStatuses.put(resource.identityKey(), SessionStatus.DISCONNECTING));
        rdpSupervisor.stopSession(resource.identityKey());
    }

    public void loadResourceIcon(WorkspaceResource resource, Consumer<Image> callback) {
        if (resource == null || callback == null || closed.get()) return;
        Image cachedImg = iconMemoryCache.get(resource.identityKey());
        if (cachedImg != null && !cachedImg.isError()) {
            runOnFxThread(() -> callback.accept(cachedImg));
            return;
        }
        enqueueIconJob(resource, null, operationGeneration.get(), callback);
    }

    public void restartResource(WorkspaceResource resource, Consumer<String> onError) {
        if (resource == null || closed.get()) return;
        long generation = operationGeneration.get();
        disconnectResource(resource);
        Thread.ofVirtual().name("jw365-restart-" + resource.sanitizedFileName()).start(() -> {
            try { Thread.sleep(600); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            if (isCurrentGeneration(generation) && !closed.get()) connectResource(resource, DisplayMode.DEFAULT, onError);
        });
    }

    private void enqueueIconJob(WorkspaceResource resource, String accessToken, long generation) {
        enqueueIconJob(resource, accessToken, generation, null);
    }
    private void enqueueIconJob(WorkspaceResource resource, String accessToken, long generation, Consumer<Image> callback) {
        if (callback != null) {
            iconCallbacks.computeIfAbsent(resource.identityKey(), k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(callback);
        }
        if (!iconJobs.add(resource.identityKey())) return;
        iconFutures.removeIf(Future::isDone);
        try {
            Future<?> future = iconExecutor.submit(() -> {
                try {
                    if (!isCurrentGeneration(generation)) return;
                    Optional<byte[]> diskBytes = workspaceCache.loadIconBytes(resource);
                    if (diskBytes.isPresent()) {
                        Image img = new Image(new ByteArrayInputStream(diskBytes.get()));
                        if (!img.isError() && img.getWidth() > 0) {
                            cacheIcon(resource.identityKey(), img);
                            notifyIconCallbacks(resource.identityKey(), img, generation);
                            return;
                        }
                    }
                    if (resource.iconUrl() == null || !isCurrentGeneration(generation)) {
                        notifyIconCallbacks(resource.identityKey(), null, generation);
                        return;
                    }
                    String token = accessToken;
                    if (token == null) {
                        token = tokenStore.load().map(TokenResponse::accessToken).orElse(null);
                    }
                    if (!isCurrentGeneration(generation)) return;
                    byte[] downloaded = feedClient.downloadIconBytes(token, resource.iconUrl());
                    if (downloaded == null || downloaded.length == 0 || !isCurrentGeneration(generation)) {
                        notifyIconCallbacks(resource.identityKey(), null, generation);
                        return;
                    }
                    Image img = new Image(new ByteArrayInputStream(downloaded));
                    if (!img.isError() && img.getWidth() > 0) {
                        workspaceCache.saveIcon(resource, downloaded);
                        cacheIcon(resource.identityKey(), img);
                        notifyIconCallbacks(resource.identityKey(), img, generation);
                    } else {
                        notifyIconCallbacks(resource.identityKey(), null, generation);
                    }
                } catch (Exception e) {
                    notifyIconCallbacks(resource.identityKey(), null, generation);
                } finally {
                    iconJobs.remove(resource.identityKey());
                }
            });
            iconFutures.add(future);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            iconJobs.remove(resource.identityKey());
            notifyIconCallbacks(resource.identityKey(), null, generation);
        }
    }

    private void notifyIconCallbacks(String identityKey, Image image, long generation) {
        List<Consumer<Image>> listeners = iconCallbacks.remove(identityKey);
        if (listeners != null && !listeners.isEmpty()) {
            runIfCurrent(generation, () -> {
                for (Consumer<Image> listener : listeners) {
                    try {
                        listener.accept(image);
                    } catch (Exception ignored) { }
                }
            });
        }
    }

    private void cacheIcon(String resourceId, Image image) {
        synchronized (iconMemoryCache) {
            iconMemoryCache.put(resourceId, image);
            while (iconMemoryCache.size() > MAX_ICON_MEMORY_ENTRIES) {
                iconMemoryCache.remove(iconMemoryCache.keySet().iterator().next());
            }
        }
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
    private void expireAuthentication(String message, long generation) {
        synchronized (authOperationLock) {
            if (!isCurrentGeneration(generation) || closed.get()) return;
            tokenStore.clear();
            oauthClient.clearCacheAndAccounts();
            workspaceCache.clear();
            operationGeneration.incrementAndGet();
        }
        rdpSupervisor.stopAllSessions();
        runOnFxThread(() -> {
            authenticated.set(false);
            currentUser.set(null);
            setLoading(false, message);
            if (!closed.get()) new org.alaurie.jw365.gui.view.AuthDialog(null, this).show();
        });
    }

    private void setLoading(boolean isLoading, String message) {
        runOnFxThread(() -> {
            this.loading.set(isLoading);
            if (message != null) {
                this.statusMessage.set(message);
            }
        });
    }

    private void setLoadingIfCurrent(long generation, boolean isLoading, String message) {
        runIfCurrent(generation, () -> setLoading(isLoading, message));
    }

    private boolean isCurrentGeneration(long generation) {
        return !closed.get() && generation == operationGeneration.get();
    }

    private void runIfCurrent(long generation, Runnable action) {
        runOnFxThread(() -> {
            if (isCurrentGeneration(generation)) {
                action.run();
            }
        });
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
