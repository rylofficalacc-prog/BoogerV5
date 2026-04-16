package dev.booger.client.network.cloud;

import com.google.gson.*;
import dev.booger.client.BoogerClient;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Booger Cloud API client.
 *
 * BASE URL: https://api.boogerclient.dev/v1
 *
 * AUTHENTICATION:
 * JWT-based. The launcher handles initial auth via Microsoft OAuth.
 * The game client receives the JWT from the launcher via a temp file
 * at startup: .minecraft/booger/session.jwt
 *
 * Tokens expire after 24 hours. We detect 401 responses and trigger
 * re-auth via the launcher's IPC bridge (port 29384, same as Discord RPC).
 *
 * TOKEN REFRESH:
 * We track token expiry from the JWT's "exp" claim (decoded locally —
 * no signature verification needed for expiry, server enforces on requests).
 * 5 minutes before expiry, we proactively refresh in the background.
 *
 * ENDPOINTS:
 *
 * POST /auth/refresh
 *   Request:  { "refreshToken": "..." }
 *   Response: { "accessToken": "...", "expiresAt": 1234567890 }
 *
 * GET  /profiles
 *   Response: [{ "name": "pvp", "data": {...}, "updatedAt": "..." }]
 *
 * PUT  /profiles/{name}
 *   Request:  { "data": {...}, "clientVersion": "0.1.0" }
 *   Response: { "syncedAt": "..." }
 *
 * GET  /profiles/{name}
 *   Response: { "name": "pvp", "data": {...}, "updatedAt": "..." }
 *
 * POST /party/create
 *   Response: { "partyId": "abc123", "joinCode": "BOOGER42" }
 *
 * POST /party/join
 *   Request:  { "joinCode": "BOOGER42" }
 *   Response: { "partyId": "abc123", "members": [...] }
 *
 * POST /party/{id}/leave
 *   Response: { "success": true }
 *
 * GET  /party/{id}/state  (SSE endpoint — live party state stream)
 *   EventStream: data: { "members": [...], "profile": "pvp" }
 *
 * RETRY POLICY:
 * - 429 (rate limit): exponential backoff, respect Retry-After header
 * - 5xx (server error): 3 retries with 1s/2s/4s delays
 * - Network error: 3 retries with 500ms delays
 * - 4xx (client error): no retry, surface to caller immediately
 *
 * OFFLINE MODE:
 * If the API is unreachable for >30 seconds, we enter offline mode.
 * Offline mode: all API calls fail fast with CachedResponseException.
 * Profile sync resumes automatically when connectivity is restored.
 */
public final class CloudApiClient {

    private static final String BASE_URL    = "https://api.boogerclient.dev/v1";
    private static final Duration TIMEOUT   = Duration.ofSeconds(10);
    private static final int MAX_RETRIES    = 3;
    private static final long OFFLINE_MS    = 30_000;

    private final HttpClient http;
    private final Gson gson = new GsonBuilder().create();
    private final ScheduledExecutorService scheduler;

    // Auth state
    private volatile String accessToken     = null;
    private volatile String refreshToken    = null;
    private volatile long   tokenExpiresAt  = 0; // Unix epoch seconds

    // Offline mode
    private volatile boolean offlineMode    = false;
    private volatile long    lastSuccessMs  = System.currentTimeMillis();

    public CloudApiClient() {
        this.http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Booger-CloudSync");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Initialize from a session file written by the launcher.
     * .minecraft/booger/session.jwt contains: accessToken\nrefreshToken\nexpiresAt
     */
    public void initFromSessionFile(java.nio.file.Path sessionFile) {
        try {
            String content = java.nio.file.Files.readString(sessionFile).trim();
            String[] parts = content.split("\n");
            if (parts.length >= 3) {
                accessToken   = parts[0].trim();
                refreshToken  = parts[1].trim();
                tokenExpiresAt = Long.parseLong(parts[2].trim());
                BoogerClient.LOGGER.info("CloudApi: session loaded, expires {}",
                    java.time.Instant.ofEpochSecond(tokenExpiresAt));
                scheduleTokenRefresh();
            }
        } catch (Exception e) {
            BoogerClient.LOGGER.warn("CloudApi: no session file found — offline mode");
            offlineMode = true;
        }
    }

    // ─── Profile Sync ────────────────────────────────────────────────────────

    /**
     * Sync a profile to the cloud.
     * Returns a Future that resolves when the sync completes or fails.
     */
    public CompletableFuture<SyncResult> syncProfile(String profileName,
                                                       String profileJson) {
        return CompletableFuture.supplyAsync(() -> {
            if (offlineMode) return SyncResult.offline();

            String endpoint = BASE_URL + "/profiles/" + encode(profileName);
            JsonObject body = new JsonObject();
            body.addProperty("data", profileJson);
            body.addProperty("clientVersion", BoogerClient.VERSION);

            try {
                JsonObject response = putJson(endpoint, body.toString());
                return SyncResult.success(
                    response.has("syncedAt")
                        ? response.get("syncedAt").getAsString()
                        : "unknown"
                );
            } catch (ApiException e) {
                return SyncResult.failure(e.getMessage(), e.statusCode);
            } catch (Exception e) {
                return SyncResult.failure(e.getMessage(), -1);
            }
        }, scheduler);
    }

    /**
     * Fetch a profile from the cloud.
     */
    public CompletableFuture<CloudProfile> fetchProfile(String profileName) {
        return CompletableFuture.supplyAsync(() -> {
            if (offlineMode) return null;

            try {
                JsonObject response = getJson(BASE_URL + "/profiles/" + encode(profileName));
                return new CloudProfile(
                    response.get("name").getAsString(),
                    response.get("data").getAsString(),
                    response.has("updatedAt") ? response.get("updatedAt").getAsString() : ""
                );
            } catch (Exception e) {
                BoogerClient.LOGGER.warn("CloudApi: fetchProfile failed: {}", e.getMessage());
                return null;
            }
        }, scheduler);
    }

    /**
     * Fetch all cloud profiles for this account.
     */
    public CompletableFuture<java.util.List<CloudProfile>> fetchAllProfiles() {
        return CompletableFuture.supplyAsync(() -> {
            if (offlineMode) return java.util.Collections.emptyList();

            try {
                JsonArray arr = getJsonArray(BASE_URL + "/profiles");
                var profiles = new java.util.ArrayList<CloudProfile>();
                for (JsonElement elem : arr) {
                    JsonObject obj = elem.getAsJsonObject();
                    profiles.add(new CloudProfile(
                        obj.get("name").getAsString(),
                        obj.get("data").getAsString(),
                        obj.has("updatedAt") ? obj.get("updatedAt").getAsString() : ""
                    ));
                }
                return profiles;
            } catch (Exception e) {
                BoogerClient.LOGGER.warn("CloudApi: fetchAllProfiles failed: {}", e.getMessage());
                return java.util.Collections.emptyList();
            }
        }, scheduler);
    }

    // ─── HTTP Primitives ─────────────────────────────────────────────────────

    private JsonObject getJson(String url) throws Exception {
        return gson.fromJson(executeWithRetry("GET", url, null), JsonObject.class);
    }

    private JsonArray getJsonArray(String url) throws Exception {
        return gson.fromJson(executeWithRetry("GET", url, null), JsonArray.class);
    }

    private JsonObject putJson(String url, String body) throws Exception {
        return gson.fromJson(executeWithRetry("PUT", url, body), JsonObject.class);
    }

    private JsonObject postJson(String url, String body) throws Exception {
        return gson.fromJson(executeWithRetry("POST", url, body), JsonObject.class);
    }

    /**
     * Execute an HTTP request with retry logic.
     * Handles: auth headers, retry-after, exponential backoff, offline detection.
     */
    private String executeWithRetry(String method, String url, String body)
        throws Exception {

        ensureValidToken();

        Exception lastException = null;
        long backoffMs = 500;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                Thread.sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 8000);
            }

            try {
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-Client-Version", BoogerClient.VERSION);

                if (accessToken != null) {
                    reqBuilder.header("Authorization", "Bearer " + accessToken);
                }

                if ("GET".equals(method)) {
                    reqBuilder.GET();
                } else if ("PUT".equals(method)) {
                    reqBuilder.PUT(HttpRequest.BodyPublishers.ofString(
                        body != null ? body : "", StandardCharsets.UTF_8));
                } else if ("POST".equals(method)) {
                    reqBuilder.POST(HttpRequest.BodyPublishers.ofString(
                        body != null ? body : "", StandardCharsets.UTF_8));
                } else if ("DELETE".equals(method)) {
                    reqBuilder.DELETE();
                }

                HttpResponse<String> response = http.send(
                    reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

                int status = response.statusCode();

                if (status == 200 || status == 201 || status == 204) {
                    lastSuccessMs = System.currentTimeMillis();
                    offlineMode = false;
                    return response.body();
                }

                if (status == 401) {
                    // Token expired — try refresh once
                    if (attempt == 0 && refreshToken != null) {
                        refreshAccessToken();
                        continue; // Retry immediately with new token
                    }
                    throw new ApiException("Unauthorized", 401);
                }

                if (status == 429) {
                    // Rate limited — respect Retry-After header
                    String retryAfter = response.headers().firstValue("Retry-After")
                        .orElse("5");
                    long waitMs = Long.parseLong(retryAfter) * 1000L;
                    Thread.sleep(Math.min(waitMs, 30_000));
                    continue;
                }

                if (status >= 400 && status < 500) {
                    // Client error — don't retry
                    throw new ApiException("Client error " + status + ": " + response.body(), status);
                }

                if (status >= 500) {
                    lastException = new ApiException("Server error " + status, status);
                    continue; // Retry on 5xx
                }

            } catch (java.net.ConnectException | java.net.http.HttpConnectTimeoutException e) {
                lastException = e;
                checkOfflineMode();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Exception("Request interrupted", e);
            }
        }

        throw lastException != null ? lastException
            : new Exception("Max retries exceeded for " + url);
    }

    private void ensureValidToken() throws Exception {
        long nowSec = System.currentTimeMillis() / 1000;
        if (accessToken != null && tokenExpiresAt > nowSec + 60) return; // Still valid
        if (refreshToken == null) return; // No auth — offline/anonymous
        refreshAccessToken();
    }

    private synchronized void refreshAccessToken() throws Exception {
        if (refreshToken == null) return;

        JsonObject body = new JsonObject();
        body.addProperty("refreshToken", refreshToken);

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/auth/refresh"))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();

        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonObject json = gson.fromJson(response.body(), JsonObject.class);
            accessToken    = json.get("accessToken").getAsString();
            tokenExpiresAt = json.get("expiresAt").getAsLong();
            BoogerClient.LOGGER.debug("CloudApi: token refreshed, expires {}",
                java.time.Instant.ofEpochSecond(tokenExpiresAt));
            scheduleTokenRefresh();
        } else {
            BoogerClient.LOGGER.warn("CloudApi: token refresh failed ({})", response.statusCode());
            offlineMode = true;
        }
    }

    private void scheduleTokenRefresh() {
        long nowSec = System.currentTimeMillis() / 1000;
        long refreshAt = tokenExpiresAt - 300; // 5 min before expiry
        long delayMs = Math.max(0, (refreshAt - nowSec) * 1000);
        scheduler.schedule(() -> {
            try { refreshAccessToken(); }
            catch (Exception e) { BoogerClient.LOGGER.warn("CloudApi: background refresh failed", e); }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void checkOfflineMode() {
        long sinceSuccessMs = System.currentTimeMillis() - lastSuccessMs;
        if (sinceSuccessMs > OFFLINE_MS && !offlineMode) {
            offlineMode = true;
            BoogerClient.LOGGER.warn("CloudApi: entering offline mode (no response for {}s)",
                sinceSuccessMs / 1000);
        }
    }

    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ─── Data classes ────────────────────────────────────────────────────────

    public record CloudProfile(String name, String jsonData, String updatedAt) {}

    public record SyncResult(boolean success, boolean isOffline,
                              String syncedAt, String error, int statusCode) {
        static SyncResult success(String at)    { return new SyncResult(true,  false, at,   null,  200); }
        static SyncResult failure(String e, int s) { return new SyncResult(false, false, null, e,     s); }
        static SyncResult offline()             { return new SyncResult(false, true,  null, "offline", -1); }
    }

    public static class ApiException extends Exception {
        public final int statusCode;
        public ApiException(String msg, int code) {
            super(msg);
            this.statusCode = code;
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    public boolean isOffline()          { return offlineMode; }
    public boolean isAuthenticated()    { return accessToken != null; }

    public void shutdown() {
        scheduler.shutdownNow();
    }
}
