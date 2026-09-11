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
import org.alaurie.jw365.auth.OAuthCallback;
import org.alaurie.jw365.auth.OAuthClient;
import org.alaurie.jw365.rdp.SessionEvent;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.Objects;
import javafx.util.Duration;
/** Resolves FreeRDP Entra ID redirects using a persistent SSO WebView session. */
public final class SessionAuthDialog extends Stage {

    private static final URI CALLBACK_URI = URI.create(OAuthClient.REDIRECT_URI);
    private final String authUrl;
    private final Consumer<String> submitRedirectUrl;
    private final String resourceTitle;
    private final String expectedState;
    private final URI expectedRedirectUri;
    private final Runnable onCancel;
    private final Runnable onComplete;
    private final WebView webView;
    private final WebEngine webEngine;
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private Timeline displayFallbackTimer;
    private boolean isUiConstructed = false;

    public SessionAuthDialog(Window owner, String resourceTitle, SessionEvent.AuthRequired authReq) {
        this(owner, resourceTitle, authReq, null, null, null);
    }

    public SessionAuthDialog(Window owner, String resourceTitle, SessionEvent.AuthRequired authReq, Runnable onCancel) {
        this(owner, resourceTitle, authReq, onCancel, null, null);
    }

    public SessionAuthDialog(Window owner, String resourceTitle, SessionEvent.AuthRequired authReq, Runnable onCancel, String loginHint) {
        this(owner, resourceTitle, authReq, onCancel, loginHint, null);
    }

    public SessionAuthDialog(
        Window owner,
        String resourceTitle,
        SessionEvent.AuthRequired authReq,
        Runnable onCancel,
        String loginHint,
        Runnable onComplete
    ) {
        Objects.requireNonNull(authReq, "authReq must not be null");
        this.authUrl = appendLoginHintIfMissing(validateInitialAuthUrl(authReq.authUrl()), loginHint);
        this.submitRedirectUrl = Objects.requireNonNull(authReq.submitRedirectUrl(), "submitRedirectUrl must not be null");
        this.resourceTitle = resourceTitle == null ? "Cloud PC" : resourceTitle;
        this.onCancel = onCancel;
        this.onComplete = onComplete;
        this.expectedState = OAuthCallback.parse(this.authUrl).map(OAuthCallback::state)
            .filter(state -> !state.isBlank()).orElse(null);
        this.expectedRedirectUri = extractRedirectUri(this.authUrl).orElse(CALLBACK_URI);
        if (owner != null) initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        setTitle("Authorizing " + this.resourceTitle);
        this.webView = new WebView();
        this.webEngine = webView.getEngine();
        this.webEngine.setJavaScriptEnabled(true);
        this.webEngine.setUserAgent(AuthDialog.BROWSER_USER_AGENT);
        try {
            this.webEngine.setUserDataDirectory(org.alaurie.jw365.config.XdgPaths.webViewDataDir().toFile());
        } catch (Exception e) {
            System.err.println("Warning: Could not configure WebEngine userDataDirectory: " + e.getMessage());
        }
        this.webEngine.locationProperty().addListener((obs, oldLoc, newLoc) -> {
            if (newLoc != null) checkLocationForRedirect(newLoc);
        });
        setOnCloseRequest(e -> completeAndClose());
        setOnHidden(e -> completeAndClose());
    }

    static String validateInitialAuthUrl(String value) {
        try {
            URI uri = URI.create(Objects.requireNonNull(value, "authUrl must not be null"));
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || uri.getHost() == null
                || (!uri.getHost().equalsIgnoreCase("login.microsoftonline.com") && !uri.getHost().toLowerCase(java.util.Locale.ROOT).endsWith(".microsoftonline.com"))
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw new IllegalArgumentException("Refusing non-Microsoft OAuth authorization URL");
            }
            if (uri.getRawQuery() == null || uri.getRawQuery().isBlank()) {
                throw new IllegalArgumentException("OAuth authorization URL is missing query parameters");
            }
            return uri.toString();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed OAuth authorization URL", e);
        }
    }
    static String appendLoginHintIfMissing(String authUrl, String loginHint) {
        if (authUrl == null || authUrl.isBlank()) return authUrl;
        try {
            URI uri = URI.create(authUrl);
            String rawQuery = uri.getRawQuery();
            boolean hasLoginHint = false;
            boolean hasSelectAccount = false;
            if (rawQuery != null && !rawQuery.isBlank()) {
                for (String param : rawQuery.split("&")) {
                    int eq = param.indexOf('=');
                    String key = eq > 0 ? param.substring(0, eq) : param;
                    String val = eq > 0 ? param.substring(eq + 1) : "";
                    if ("login_hint".equalsIgnoreCase(key)) {
                        hasLoginHint = true;
                    }
                    if ("prompt".equalsIgnoreCase(key) && "select_account".equalsIgnoreCase(val)) {
                        hasSelectAccount = true;
                    }
                }
            }
            String result = authUrl;
            if (hasSelectAccount && loginHint != null && !loginHint.isBlank()) {
                result = result.replace("prompt=select_account&", "")
                               .replace("&prompt=select_account", "")
                               .replace("?prompt=select_account", "?");
                if (result.endsWith("?")) {
                    result = result.substring(0, result.length() - 1);
                }
            }
            if (!hasLoginHint && loginHint != null && !loginHint.isBlank()) {
                String encodedHint = java.net.URLEncoder.encode(loginHint.trim(), java.nio.charset.StandardCharsets.UTF_8);
                int hashIdx = result.indexOf('#');
                String beforeHash = hashIdx >= 0 ? result.substring(0, hashIdx) : result;
                String fragment = hashIdx >= 0 ? result.substring(hashIdx) : "";
                char sep = beforeHash.contains("?") ? '&' : '?';
                result = beforeHash + sep + "login_hint=" + encodedHint + fragment;
            }
            return result;
        } catch (Exception e) {
            return authUrl;
        }
    }


    static java.util.Optional<URI> extractRedirectUri(String authUrl) {
        try {
            URI uri = URI.create(authUrl);
            String query = uri.getRawQuery();
            if (query == null) return java.util.Optional.empty();
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String key = java.net.URLDecoder.decode(pair.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8);
                    if ("redirect_uri".equalsIgnoreCase(key)) {
                        String val = java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
                        return java.util.Optional.of(URI.create(val.trim()));
                    }
                }
            }
        } catch (Exception ignored) { }
        return java.util.Optional.empty();
    }

    /**
     * Starts the authorization flow: silent first, with fallback to visible window only if needed.
     */
    public void startSilentOrShow() {
        Platform.runLater(() -> {
            if (completed.get()) return;
            displayFallbackTimer = new Timeline(new KeyFrame(Duration.seconds(3), e -> {
                if (!completed.get() && !isShowing()) constructAndShowWindow();
            }));
            displayFallbackTimer.setCycleCount(1);
            displayFallbackTimer.play();
            webEngine.load(authUrl);
        });
    }

    static boolean isCallbackAcceptable(String url, String expectedState, URI expectedRedirectUri) {
        if (url == null || url.isBlank()) return false;
        boolean matchesExpected = expectedRedirectUri != null && OAuthCallback.isRedirect(url, expectedRedirectUri);
        boolean matchesDefault = OAuthCallback.isRedirect(url, CALLBACK_URI);
        if (!matchesExpected && !matchesDefault) {
            return false;
        }

        OAuthCallback callback = OAuthCallback.parse(url).orElse(null);
        if (callback == null || (!callback.isSuccess() && !callback.hasError())) return false;
        if (expectedState != null && !expectedState.isBlank() && !expectedState.equals(callback.state())) {
            return false;
        }
        return true;
    }

    private void checkLocationForRedirect(String url) {
        if (completed.get() || !isCallbackAcceptable(url, expectedState, expectedRedirectUri)) return;

        if (completed.compareAndSet(false, true)) {
            cancelTimer();
            try { submitRedirectUrl.accept(url); }
            catch (RuntimeException e) { System.err.println("Warning: Could not submit OAuth redirect: " + e.getMessage()); }
            if (java.net.CookieHandler.getDefault() instanceof org.alaurie.jw365.auth.PersistentCookieManager pcm) {
                pcm.persistCookies();
            }
            if (onComplete != null) {
                try { onComplete.run(); }
                catch (Exception e) { System.err.println("Warning: Error in SessionAuthDialog onComplete: " + e.getMessage()); }
            }
            Platform.runLater(() -> { if (isShowing()) close(); });
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

    public void completeAndClose() {
        if (completed.compareAndSet(false, true)) {
            cancelTimer();
            if (webEngine.getLoadWorker().isRunning()) {
                webEngine.getLoadWorker().cancel();
            }
            if (onCancel != null) {
                try {
                    onCancel.run();
                } catch (Exception e) {
                    System.err.println("Warning: Error in SessionAuthDialog onCancel: " + e.getMessage());
                }
            }
            Platform.runLater(() -> { if (isShowing()) close(); });
        }
    }
}
