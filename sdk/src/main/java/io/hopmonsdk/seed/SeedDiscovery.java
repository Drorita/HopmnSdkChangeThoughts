package io.hopmonsdk.seed;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import io.hopmonsdk.util.LogUtils;

/**
 * Handles seed-based API server discovery.
 *
 * Seed entries passed to the constructor can be in any of three forms:
 *   (a) A full URL, e.g. {@code https://165.227.118.144/v1/seeds}
 *       — used as-is to fetch the API IP list.
 *   (b) A raw IPv4 address, e.g. {@code 165.227.118.144}
 *       — the default {@code /v1/seeds} path is appended automatically.
 *   (c) An FQDN, e.g. {@code seed1.example.com}
 *       — resolved to IPs via Cloudflare DoH, then {@code /v1/seeds} is appended.
 *
 * Flow:
 *  1. Resolve every seed entry to a full seed endpoint URL (see above).
 *  2. Fetch the API server IP list from the first responding seed URL.
 *  3. Cache the API IPs (TTL = 5 minutes).
 *  4. Expose executeGet / executePost that try each API IP with failover;
 *     on total failure, force-refresh the IP list and retry once.
 *
 * All public callbacks fire on the main (UI) thread.
 */
public class SeedDiscovery {

    public static final String TAG = "SeedDiscovery";

    private static final String DOH_URL_TEMPLATE =
            "https://cloudflare-dns.com/dns-query?name=%s&type=A";
    private static final String SEEDS_PATH = "/v1/seeds";

    /**
     * Postconsent fallback: domain-based endpoints that return the current API server IPs for a
     * given publisher, one IP per line (same format as {@code /v1/seeds}). Used only when the seed
     * servers fail to yield any API IPs. Reached via normal system DNS (no DoH), on infrastructure
     * separate from the raw seed IP, so it survives the seed box being down / its IP changing.
     */
    private static final String[] POSTCONSENT_URLS = {
            "https://pubs.abnetworks.io/postconsent",
            "https://pubs.myrc.xyz/postconsent"
    };

    /** Master API key for the postconsent endpoint (works for any publisher). Rotate periodically. */
    private static final String POSTCONSENT_KEY =
            "9221346234fa4fb880caf324effcff004025a41a51d672a7";

    /** Default cache TTL for both seed IPs and API IPs (5 minutes). */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS    = 10_000;

    // -------------------------------------------------------------------------
    // Public callback interfaces
    // -------------------------------------------------------------------------

    public interface StringCallback {
        void onSuccess(String response);
        void onFailure(String reason);
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final List<String> seedFqdns;

    /**
     * Publisher used for the postconsent fallback query ({@code ?pub=...}). When null/empty the
     * fallback is disabled and only the seed servers are used.
     */
    private final String publisher;

    /**
     * Single-threaded executor so that concurrent callers (registration + config
     * sync) are serialised – prevents double refresh races.
     */
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // -------------------------------------------------------------------------
    // Test hooks  (package-private so the test class in the same package can
    // override them; invisible to callers outside the package)
    // -------------------------------------------------------------------------

    /** When > 0 the SDK uses plain HTTP on this port instead of HTTPS port 443.
     *  Set to MockWebServer.getPort() in tests. */
    int testPort = -1;

    /** Base URL used for DoH resolution.
     *  Override to MockWebServer base URL in tests. */
    String dohBaseUrl = "https://cloudflare-dns.com";

    private final Object cacheLock = new Object();
    private List<String> cachedSeedIps = Collections.emptyList();
    private List<String> cachedApiIps  = Collections.emptyList();

    /** For telemetry / logging only. */
    private String lastSuccessfulSeedIp;
    private String lastSuccessfulApiIp;
    private long   lastRefreshMs = 0;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public SeedDiscovery(List<String> seedFqdns) {
        this(seedFqdns, null);
    }

    /**
     * @param seedFqdns Seed entries (full URL / raw IPv4 / FQDN).
     * @param publisher Publisher for the postconsent fallback; when null/empty the fallback is
     *                  disabled and only seed servers are used.
     */
    public SeedDiscovery(List<String> seedFqdns, String publisher) {
        this.seedFqdns = new ArrayList<>(seedFqdns);
        this.publisher = publisher;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public boolean isCacheFresh() {
        synchronized (cacheLock) {
            return !cachedApiIps.isEmpty() &&
                   (System.currentTimeMillis() - lastRefreshMs) < CACHE_TTL_MS;
        }
    }

    public List<String> getCachedApiIps() {
        synchronized (cacheLock) {
            return new ArrayList<>(cachedApiIps);
        }
    }

    public String getLastSuccessfulApiIp()  { return lastSuccessfulApiIp; }
    public String getLastSuccessfulSeedIp() { return lastSuccessfulSeedIp; }

    /**
     * Executes an HTTP GET request against a randomly-selected (and shuffled)
     * API server IP.  Falls back to the next IP on failure; if every IP fails
     * it forces a full refresh and retries once.
     *
     * @param path     Full path + query string, e.g.
     *                 {@code /?domain=example.com&uid=...&get=1&cc=US}
     * @param callback Result delivered on the main thread.
     */
    public void executeGet(String path, StringCallback callback) {
        execute(path, false, callback);
    }

    /**
     * Same as {@link #executeGet} but sends an HTTP POST (empty body).
     *
     * @param path     Full path + query string, e.g.
     *                 {@code /?domain=example.com&uid=...&regcc=1}
     * @param callback Result delivered on the main thread.
     */
    public void executePost(String path, StringCallback callback) {
        execute(path, true, callback);
    }

    // -------------------------------------------------------------------------
    // Internal request dispatch
    // -------------------------------------------------------------------------

    private void execute(final String path, final boolean isPost,
                         final StringCallback callback) {
        bgExecutor.execute(() -> {
            // 1. Try with cached (or freshly fetched) IPs
            List<String> apiIps = getOrRefreshApiIps();
            if (!apiIps.isEmpty()) {
                String result = tryRequest(apiIps, path, isPost);
                if (result != null) {
                    postSuccess(callback, result);
                    return;
                }
            }

            // 2. All IPs failed (or no IPs) — force refresh and try once more
            LogUtils.w(TAG, "All API IPs failed, forcing cache refresh...");
            List<String> freshIps = forceRefresh();
            if (freshIps.isEmpty()) {
                postFailure(callback, "All API IPs failed and seed refresh returned no IPs");
                return;
            }
            String result = tryRequest(freshIps, path, isPost);
            if (result != null) {
                postSuccess(callback, result);
            } else {
                postFailure(callback, "All API IPs failed after seed refresh");
            }
        });
    }

    /**
     * Iterates over API IPs (already shuffled) and returns the first
     * successful response body, or {@code null} if every IP fails.
     *
     * Failures that trigger trying the next IP:
     *   - Connection / read timeout
     *   - TLS error
     *   - HTTP 5xx
     *   - Empty response body
     *
     * HTTP 4xx is NOT retried (bad request parameters would fail on every IP).
     */
    private String tryRequest(List<String> apiIps, String path, boolean isPost) {
        for (String ip : apiIps) {
            try {
                String urlStr = buildUrl(ip, path);
                LogUtils.d(TAG, "%s %s", isPost ? "POST" : "GET", urlStr);
                HttpURLConnection conn = openSmartConnection(urlStr);
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestMethod(isPost ? "POST" : "GET");
                if (isPost) {
                    conn.setDoOutput(true);
                    conn.getOutputStream().close(); // empty body
                }

                int status = conn.getResponseCode();
                if (status >= 500) {
                    LogUtils.w(TAG, "API IP %s returned HTTP %d, trying next", ip, status);
                    conn.disconnect();
                    continue;
                }
                if (status != 200) {
                    // 4xx – do not retry across IPs
                    LogUtils.w(TAG, "API IP %s returned HTTP %d (no retry)", ip, status);
                    conn.disconnect();
                    return null;
                }

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                String body = sb.toString().trim();
                if (body.isEmpty()) {
                    LogUtils.w(TAG, "API IP %s returned empty body, trying next", ip);
                    continue;
                }

                lastSuccessfulApiIp = ip;
                LogUtils.d(TAG, "Success from API IP %s", ip);
                return body;

            } catch (Exception e) {
                LogUtils.e(TAG, "Request to %s failed: %s", ip, e.getMessage());
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Cache management
    // -------------------------------------------------------------------------

    private List<String> getOrRefreshApiIps() {
        if (isCacheFresh()) {
            return getCachedApiIps();
        }
        return forceRefresh();
    }

    /**
     * Runs the full discovery chain synchronously (must be called from a
     * background thread).  Updates the internal cache on success.
     */
    private List<String> forceRefresh() {
        // Step 1 – Build a list of fully-resolved seed endpoint URLs.
        //
        // Each entry in seedFqdns falls into one of three categories:
        //   (a) Full URL (contains "://")  — use directly; host extracted for caching.
        //   (b) Raw IPv4 address           — append SEEDS_PATH; IP used for caching.
        //   (c) FQDN                       — resolve via Cloudflare DoH, then append SEEDS_PATH.
        List<String> seedUrls        = new ArrayList<>();
        List<String> seedHostsForCache = new ArrayList<>();

        for (String entry : seedFqdns) {
            if (entry.contains("://")) {
                // (a) Full URL — publisher already gave us the complete endpoint
                String host = extractHost(entry);
                seedUrls.add(entry);
                if (!TextUtils.isEmpty(host)) seedHostsForCache.add(host);
                LogUtils.d(TAG, "Seed entry is a full URL: %s (host=%s)", entry, host);
            } else if (isValidIpv4(entry)) {
                // (b) Raw IPv4 — build URL from IP + default path
                seedUrls.add(buildUrl(entry, SEEDS_PATH));
                seedHostsForCache.add(entry);
                LogUtils.d(TAG, "Seed %s is a direct IP, skipping DoH", entry);
            } else {
                // (c) FQDN — resolve via Cloudflare DoH
                List<String> resolved = dohResolve(entry);
                LogUtils.d(TAG, "DoH %s -> %s", entry, resolved);
                for (String ip : resolved) {
                    seedUrls.add(buildUrl(ip, SEEDS_PATH));
                    seedHostsForCache.add(ip);
                }
            }
        }

        // Step 2 – fetch API IP list from first responding seed endpoint.
        List<String> apiIps = new ArrayList<>();
        if (seedUrls.isEmpty()) {
            // No seed endpoints resolved (e.g. all FQDN DoH lookups failed on an old device).
            // Do NOT bail here — fall through to the postconsent fallback below, which is
            // domain-based and depends on neither DoH nor the seed servers.
            LogUtils.e(TAG, "No seed endpoints available for %s", seedFqdns);
        } else {
            synchronized (cacheLock) {
                cachedSeedIps = new ArrayList<>(seedHostsForCache);
            }
            for (String seedUrl : seedUrls) {
                List<String> fetched = fetchApiIpsFromUrl(seedUrl);
                if (!fetched.isEmpty()) {
                    lastSuccessfulSeedIp = extractHost(seedUrl);
                    apiIps.addAll(fetched);
                    break;
                }
            }
        }

        // Step 2b – postconsent fallback. Seeds are the primary source; only if they yield no API
        // IPs do we ask the domain-based postconsent endpoint for this publisher's current servers.
        // Same one-IP-per-line format, so fetchApiIpsFromUrl parses it unchanged.
        if (apiIps.isEmpty() && !TextUtils.isEmpty(publisher)) {
            LogUtils.w(TAG, "Seeds returned no API IPs; trying postconsent fallback for pub=%s", publisher);
            for (String base : POSTCONSENT_URLS) {
                String url = base + "?pub=" + publisher + "&key=" + POSTCONSENT_KEY;
                List<String> fetched = fetchApiIpsFromUrl(url);
                if (!fetched.isEmpty()) {
                    lastSuccessfulSeedIp = extractHost(base) + " (postconsent)";
                    apiIps.addAll(fetched);
                    LogUtils.i(TAG, "FALLBACK USED: postconsent %s returned %d API IPs for pub=%s",
                            extractHost(base), fetched.size(), publisher);
                    break;
                }
            }
        }

        if (apiIps.isEmpty()) {
            LogUtils.e(TAG, "No API IPs from seeds or postconsent fallback");
            return Collections.emptyList();
        }

        // Step 3 – shuffle and cache
        Collections.shuffle(apiIps);
        synchronized (cacheLock) {
            cachedApiIps = new ArrayList<>(apiIps);
            lastRefreshMs = System.currentTimeMillis();
        }
        LogUtils.d(TAG, "API IP cache refreshed: %s", apiIps);
        return new ArrayList<>(apiIps);
    }

    // -------------------------------------------------------------------------
    // DNS-over-HTTPS
    // -------------------------------------------------------------------------

    /**
     * Resolves {@code fqdn} via Cloudflare DoH and returns all A-record IPs.
     * Only responses with Status=0 and type=1 (A record) are accepted.
     */
    private List<String> dohResolve(String fqdn) {
        List<String> ips = new ArrayList<>();
        try {
            String urlStr = dohBaseUrl + "/dns-query?name=" + fqdn + "&type=A";
            HttpURLConnection conn = openSmartConnection(urlStr);
            conn.setRequestProperty("accept", "application/dns-json");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() != 200) {
                conn.disconnect();
                return ips;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            conn.disconnect();

            JSONObject json = new JSONObject(sb.toString());
            if (json.optInt("Status", -1) != 0) return ips;

            JSONArray answers = json.optJSONArray("Answer");
            if (answers == null) return ips;

            for (int i = 0; i < answers.length(); i++) {
                JSONObject answer = answers.getJSONObject(i);
                if (answer.optInt("type", 0) != 1) continue; // A records only
                String data = answer.optString("data", "").trim();
                if (isValidIpv4(data)) ips.add(data);
            }
        } catch (Exception e) {
            LogUtils.e(TAG, "DoH resolution failed for %s: %s", fqdn, e.getMessage());
        }
        return ips;
    }

    // -------------------------------------------------------------------------
    // Seed server endpoint fetch
    // -------------------------------------------------------------------------

    /**
     * GETs {@code seedUrl} and parses each response line as an IPv4 address.
     * {@code seedUrl} is already a fully-formed URL (e.g.
     * {@code https://165.227.118.144/v1/seeds}).
     */
    private List<String> fetchApiIpsFromUrl(String seedUrl) {
        List<String> ips = new ArrayList<>();
        try {
            HttpURLConnection conn = openSmartConnection(seedUrl);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");

            int status = conn.getResponseCode();
            if (status != 200) {
                LogUtils.w(TAG, "Seed %s returned HTTP %d", seedUrl, status);
                conn.disconnect();
                return ips;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (isValidIpv4(line)) ips.add(line);
            }
            reader.close();
            conn.disconnect();
            LogUtils.d(TAG, "Seed %s returned %d API IPs", seedUrl, ips.size());
        } catch (Exception e) {
            LogUtils.e(TAG, "Failed to fetch API IPs from seed %s: %s",
                    seedUrl, e.getMessage());
        }
        return ips;
    }

    // -------------------------------------------------------------------------
    // URL building  (uses testPort when set)
    // -------------------------------------------------------------------------

    /**
     * Builds the URL for seed-server and API-server calls.
     * In production  (testPort == -1): {@code https://<host><path>}
     * In tests (testPort > 0):         {@code http://<host>:<testPort><path>}
     */
    private String buildUrl(String host, String path) {
        if (testPort > 0) {
            return "http://" + host + ":" + testPort + path;
        }
        return "https://" + host + path;
    }

    // -------------------------------------------------------------------------
    // Connection helpers
    // -------------------------------------------------------------------------

    /**
     * Opens a connection to {@code urlStr}.
     * - HTTP  (tests via MockWebServer): plain {@link HttpURLConnection}.
     * - HTTPS (production):             trust-all SSL (needed for IP-based URLs
     *   that may lack an IP Subject Alternative Name in their certificate).
     *
     * TODO: Replace trust-all with certificate / public-key pinning before
     *       shipping to production.
     */
    private static HttpURLConnection openSmartConnection(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        if (conn instanceof HttpsURLConnection) {
            applyTrustAll((HttpsURLConnection) conn);
        }
        return conn;
    }

    @SuppressWarnings({"TrustAllX509TrustManager", "CustomX509TrustManager"})
    private static void applyTrustAll(HttpsURLConnection conn) throws Exception {
        TrustManager[] trustAll = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
            }
        };
        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(null, trustAll, new java.security.SecureRandom());
        conn.setSSLSocketFactory(sslCtx.getSocketFactory());
        conn.setHostnameVerifier((hostname, session) -> true);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Extracts the host portion from a URL string (e.g. {@code https://1.2.3.4/path} → {@code 1.2.3.4}). */
    private static String extractHost(String urlStr) {
        try {
            return new URL(urlStr).getHost();
        } catch (Exception e) {
            return urlStr;
        }
    }

    private static boolean isValidIpv4(String ip) {
        if (TextUtils.isEmpty(ip)) return false;
        return ip.matches("^(\\d{1,3}\\.){3}\\d{1,3}$");
    }

    private void postSuccess(StringCallback cb, String response) {
        mainHandler.post(() -> cb.onSuccess(response));
    }

    private void postFailure(StringCallback cb, String reason) {
        mainHandler.post(() -> cb.onFailure(reason));
    }
}
