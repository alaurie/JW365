package org.alaurie.jw365.auth;

import com.microsoft.aad.msal4j.AuthorizationCodeParameters;
import com.microsoft.aad.msal4j.AuthorizationRequestUrlParameters;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.microsoft.aad.msal4j.IAccount;
import com.microsoft.aad.msal4j.ITokenCacheAccessAspect;
import com.microsoft.aad.msal4j.ITokenCacheAccessContext;
import com.microsoft.aad.msal4j.PublicClientApplication;
import com.microsoft.aad.msal4j.SilentParameters;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.Objects;
import java.io.IOException;
import org.alaurie.jw365.config.XdgPaths;
/**
 * HTTP client for Entra ID OAuth 2.0 authentication.
 */
public final class OAuthClient {

    /**
     * Official Microsoft Remote Desktop / AVD public Client ID.
     */
    public static final String DEFAULT_CLIENT_ID = "a85cf173-4192-42f8-81fa-777a763e6e2c";
    public static final String DEFAULT_TENANT = "organizations";
    public static final String REDIRECT_URI = "https://login.microsoftonline.com/common/oauth2/nativeclient";
    public static final String DEFAULT_SCOPE = "https://www.wvd.microsoft.com/.default offline_access openid profile";
    private static final String LOGIN_BASE = "https://login.microsoftonline.com/%s/oauth2/v2.0";
    private final String clientId;
    private final String scope;
    private final PublicClientApplication msalApplication;
    private final FileTokenCacheAspect tokenCacheAspect;
    private final Path signedIdentityFile;
    public OAuthClient() {
        this(DEFAULT_CLIENT_ID, DEFAULT_SCOPE, null);
    }

    public OAuthClient(String clientId, String scope, HttpClient ignoredHttpClient) {
        this(clientId, scope, ignoredHttpClient, XdgPaths.dataDir().resolve("msal-cache.enc"));
    }

    OAuthClient(String clientId, String scope, HttpClient ignoredHttpClient, Path msalCacheFile) {
        this.clientId = Objects.requireNonNull(clientId, "clientId must not be null");
        this.scope = Objects.requireNonNull(scope, "scope must not be null");
        this.tokenCacheAspect = new FileTokenCacheAspect(Objects.requireNonNull(msalCacheFile, "msalCacheFile must not be null"));
        this.signedIdentityFile = msalCacheFile.resolveSibling(msalCacheFile.getFileName() + ".identity");
        this.msalApplication = createMsalApplication(clientId, DEFAULT_TENANT, tokenCacheAspect);
    }

    public URI buildAuthorizeUrl(String tenant, PkceChallenge challenge, String redirectUri, String loginHint) {
        try {
            var parameters = AuthorizationRequestUrlParameters.builder(redirectUri, Set.of(scope.split("\\s+")))
                .codeChallenge(challenge.codeChallenge())
                .codeChallengeMethod("S256")
                .state(challenge.state())
                .loginHint(loginHint)
                .build();
            URI authorizationUri = applicationFor(tenant).getAuthorizationRequestUrl(parameters).toURI();
            return URI.create(authorizationUri.toString().replace("response_mode=form_post", "response_mode=query"));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build Microsoft authorization URL", e);
        }
    }
    public synchronized AuthResult exchangeAuthorizationCode(String tenant, String code, String verifier, String redirectUri) {
        try {
            AuthorizationCodeParameters parameters = AuthorizationCodeParameters.builder(code, URI.create(redirectUri))
                .scopes(Set.of(scope.split("\\s+")))
                .codeVerifier(verifier)
                .tenant(tenant)
                .build();
            return toAuthResult(applicationFor(tenant).acquireToken(parameters).join());
        } catch (Exception e) {
            return new AuthResult.Failure("authorization_code_failed", e.getMessage(), e);
        }
    }

    public synchronized AuthResult refreshTokenWithMsal(String tenant) {
        try {
            String expectedIdentity = null;
            if (Files.exists(signedIdentityFile)) {
                try {
                    expectedIdentity = Files.readString(signedIdentityFile, StandardCharsets.UTF_8).trim();
                } catch (IOException _) {
                }
            }

            PublicClientApplication app = applicationFor(tenant);
            Set<IAccount> accounts = app.getAccounts().join();
            if (accounts.isEmpty()) {
                return new AuthResult.Failure("no_cached_account", "No cached Microsoft account found", null);
            }

            final String targetIdentity = expectedIdentity;
            IAccount targetAccount = null;
            if (targetIdentity != null && !targetIdentity.isBlank()) {
                targetAccount = accounts.stream()
                    .filter(a -> targetIdentity.equalsIgnoreCase(a.username())
                              || (a.homeAccountId() != null && targetIdentity.equalsIgnoreCase(a.homeAccountId())))
                    .findFirst()
                    .orElse(null);
            }
            if (targetAccount == null) {
                targetAccount = accounts.iterator().next();
            }

            SilentParameters parameters = SilentParameters.builder(Set.of(scope.split("\\s+")), targetAccount)
                .tenant(tenant)
                .forceRefresh(true)
                .build();
            return toAuthResult(app.acquireTokenSilently(parameters).join());
        } catch (Exception e) {
            return new AuthResult.Failure("token_refresh_failed", e.getMessage(), e);
        }
    }

    /** Removes all MSAL accounts and the encrypted serialized cache. */
    public synchronized void clearCacheAndAccounts() {
        try {
            for (IAccount account : msalApplication.getAccounts().join()) {
                msalApplication.removeAccount(account).join();
            }
        } catch (Exception e) {
            System.err.println("Warning: Could not clear Microsoft authentication cache: " + e.getMessage());
        } finally {
            tokenCacheAspect.clear();
            try { Files.deleteIfExists(signedIdentityFile); } catch (IOException _) { }
        }
    }

    private AuthResult toAuthResult(IAuthenticationResult result) {
        try {
            if (result.account() != null && result.account().username() != null) {
                Files.writeString(signedIdentityFile, result.account().username(), StandardCharsets.UTF_8);
            }
        } catch (IOException _) { }
        long expiresIn = Math.max(0, (result.expiresOnDate().getTime() - System.currentTimeMillis()) / 1000);
        TokenResponse tokens = new TokenResponse(result.accessToken(), null, result.idToken(), "Bearer", expiresIn, scope, System.currentTimeMillis() / 1000);
        return new AuthResult.Success(tokens, JwtClaimsParser.parseIdToken(result.idToken()));
    }
    private PublicClientApplication createMsalApplication(String applicationId, String tenant, FileTokenCacheAspect cacheAspect) {
        try {
            return PublicClientApplication.builder(applicationId)
                .authority(LOGIN_BASE.formatted(normalizeTenant(tenant)))
                .setTokenCacheAccessAspect(cacheAspect)
                .build();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize Microsoft authentication", e);
        }
    }
    private PublicClientApplication applicationFor(String tenant) {
        return createMsalApplication(clientId, normalizeTenant(tenant), tokenCacheAspect);
    }

    private static String normalizeTenant(String tenant) {
        return tenant == null || tenant.isBlank() ? DEFAULT_TENANT : tenant;
    }


    private static final class FileTokenCacheAspect implements ITokenCacheAccessAspect {
        private final Path file;

        private FileTokenCacheAspect(Path file) {
            this.file = file;
        }

        @Override
        public synchronized void beforeCacheAccess(ITokenCacheAccessContext context) {
            try {
                if (Files.exists(file) && Files.size(file) > 0) {
                    context.tokenCache().deserialize(new String(MachineBoundCrypto.decrypt(Files.readAllBytes(file)), StandardCharsets.UTF_8));
                }
            } catch (Exception _) {
            }
        }

        @Override
        public synchronized void afterCacheAccess(ITokenCacheAccessContext context) {
            if (!context.hasCacheChanged()) return;
            try {
                Path parent = file.getParent();
                if (parent != null) Files.createDirectories(parent);
                byte[] encrypted = MachineBoundCrypto.encrypt(context.tokenCache().serialize().getBytes(StandardCharsets.UTF_8));
                Path tempFile = file.resolveSibling(file.getFileName() + ".tmp." + System.nanoTime());
                try {
                    Files.write(tempFile, encrypted);
                    try {
                        Files.setPosixFilePermissions(tempFile, PosixFilePermissions.fromString("rw-------"));
                    } catch (UnsupportedOperationException _) {
                    }
                    try {
                        Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (java.nio.file.AtomicMoveNotSupportedException _) {
                        Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(tempFile);
                }
            } catch (Exception _) {
            }
        }

        private synchronized void clear() {
            try {
                Files.deleteIfExists(file);
            } catch (Exception _) {
            }
        }
    }
}
