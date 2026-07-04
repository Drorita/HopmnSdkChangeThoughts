package io.hopmonsdk.seed;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.*;

/**
 * Instrumented tests for {@link SeedDiscovery}.
 *
 * Each test starts a real local HTTP server (MockWebServer) that the SDK talks
 * to instead of Cloudflare / the real seed / API servers.
 *
 * Test matrix:
 *  1.  Happy path GET  – full DoH → /v1/seeds → API call succeeds
 *  2.  Happy path POST – same flow but with POST
 *  3.  Failover        – first API IP returns 500, second returns 200
 *  4.  Failover        – connection refused on first IP, second returns 200
 *  5.  All IPs fail    – triggers full seed refresh, then succeeds
 *  6.  All IPs fail AND refresh fails → onFailure
 *  7.  DoH returns no IPs → onFailure
 *  8.  /v1/seeds returns only invalid lines → onFailure
 *  9.  Cache is fresh  – second call does NOT hit DoH again
 * 10.  Invalid lines in /v1/seeds are silently skipped
 * 11.  domain= parameter is present in every API call URL
 */
@RunWith(AndroidJUnit4.class)
public class SeedDiscoveryTest {

    private static final int TIMEOUT_SEC = 15;
    private static final String FAKE_FQDN = "seed1.example.com";
    private static final String LOCALHOST  = "127.0.0.1";

    private MockWebServer server;

    // -------------------------------------------------------------------------
    // Setup / teardown
    // -------------------------------------------------------------------------

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Creates a SeedDiscovery wired to the local MockWebServer. */
    private SeedDiscovery makeDiscovery() {
        SeedDiscovery sd = new SeedDiscovery(Collections.singletonList(FAKE_FQDN));
        sd.testPort  = server.getPort();
        sd.dohBaseUrl = "http://" + LOCALHOST + ":" + server.getPort();
        return sd;
    }

    /** DoH JSON response carrying a single A-record pointing to LOCALHOST. */
    private MockResponse dohResponse(String ip) {
        String body = "{\"Status\":0,\"Answer\":["
                + "{\"type\":1,\"TTL\":300,\"data\":\"" + ip + "\"}"
                + "]}";
        return new MockResponse().setBody(body).setResponseCode(200);
    }

    /** DoH JSON with Status != 0 (resolution failure). */
    private MockResponse dohFailureResponse() {
        return new MockResponse()
                .setBody("{\"Status\":2,\"Answer\":[]}")
                .setResponseCode(200);
    }

    /** Waits for the callback and returns the result string, or "TIMEOUT". */
    private String await(SeedDiscovery sd, String path, boolean post) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("TIMEOUT");

        SeedDiscovery.StringCallback cb = new SeedDiscovery.StringCallback() {
            @Override public void onSuccess(String response) {
                result.set(response);
                latch.countDown();
            }
            @Override public void onFailure(String reason) {
                result.set("FAILURE:" + reason);
                latch.countDown();
            }
        };

        if (post) sd.executePost(path, cb);
        else      sd.executeGet(path, cb);

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS);
        return result.get();
    }

    // -------------------------------------------------------------------------
    // 1. Happy path – GET
    // -------------------------------------------------------------------------

    @Test
    public void test1_happyPath_get_returnsResponse() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest req) {
                String path = req.getPath();
                if (path != null && path.startsWith("/dns-query")) {
                    return dohResponse(LOCALHOST);
                } else if ("/v1/seeds".equals(path)) {
                    return new MockResponse().setBody(LOCALHOST + "\n");
                } else {
                    return new MockResponse().setBody("config:proxy.example.com,8080,");
                }
            }
        });

        String result = await(makeDiscovery(), "/?domain=example.com&get=1&cc=US&pub=test&uid=abc&ver=1.0&foreground=true", false);
        assertEquals("config:proxy.example.com,8080,", result);
    }

    // -------------------------------------------------------------------------
    // 2. Happy path – POST
    // -------------------------------------------------------------------------

    @Test
    public void test2_happyPath_post_sendsPostAndReturnsResponse() throws Exception {
        AtomicReference<String> capturedMethod = new AtomicReference<>();

        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest req) {
                String path = req.getPath();
                if (path != null && path.startsWith("/dns-query")) {
                    return dohResponse(LOCALHOST);
                } else if ("/v1/seeds".equals(path)) {
                    return new MockResponse().setBody(LOCALHOST + "\n");
                } else {
                    capturedMethod.set(req.getMethod());
                    return new MockResponse().setBody("IL");
                }
            }
        });

        String result = await(makeDiscovery(), "/?domain=example.com&regcc=1&pub=test&uid=abc&ver=1.0&foreground=true", true);
        assertEquals("IL", result);
        assertEquals("POST", capturedMethod.get());
    }

    // -------------------------------------------------------------------------
    // 3. Failover – first API IP returns 500, second returns 200
    // -------------------------------------------------------------------------

    @Test
    public void test3_failover_firstIp500_secondIpSucceeds() throws Exception {
        // /v1/seeds returns LOCALHOST twice so tryRequest has two entries to iterate
        AtomicReference<Integer> apiCallCount = new AtomicReference<>(0);

        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest req) {
                String path = req.getPath();
                if (path != null && path.startsWith("/dns-query")) {
                    return dohResponse(LOCALHOST);
                } else if ("/v1/seeds".equals(path)) {
                    // Two identical IPs — tryRequest will retry the same host
                    return new MockResponse().setBody(LOCALHOST + "\n" + LOCALHOST + "\n");
                } else {
                    int count = apiCallCount.updateAndGet(c -> c + 1);
                    if (count == 1) {
                        return new MockResponse().setResponseCode(500);
                    }
                    return new MockResponse().setBody("config:1.2.3.4,9999,");
                }
            }
        });

        String result = await(makeDiscovery(), "/?domain=example.com&get=1&cc=US&pub=test&uid=abc&ver=1.0&foreground=true", false);
        assertEquals("config:1.2.3.4,9999,", result);
        assertEquals(2, (int) apiCallCount.get()); // exactly two API attempts
    }

    // -------------------------------------------------------------------------
    // 4. Failover – connection error on first, success on second
    // -------------------------------------------------------------------------

    @Test
    public void test4_failover_connectionError_secondIpSucceeds() throws Exception {
        AtomicReference<Integer> apiCallCount = new AtomicReference<>(0);

        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest req) {
                String path = req.getPath();
                if (path != null && path.startsWith("/dns-query")) {
                    return dohResponse(LOCALHOST);
                } else if ("/v1/seeds".equals(path)) {
                    return new MockResponse().setBody(LOCALHOST + "\n" + LOCALHOST + "\n");
                } else {
                    int count = apiCallCount.updateAndGet(c -> c + 1);
                    if (count == 1) {
                        // Simulate connection drop
                        return new MockResponse().setSocketPolicy(
                                okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START);
                    }
                    return new MockResponse().setBody("config:5.6.7.8,1234,");
                }
            }
        });

        String result = await(makeDiscovery(), "/?domain=example.com&get=1&cc=US&pub=test&uid=abc&ver=1.0&foreground=true", false);
        assertEquals("config:5.6.7.8,1234,", result);
    }

    // -------------------------------------------------------------------------
    // 5. All cached IPs fail → full refresh → success
    // -------------------------------------------------------------------------

    @Test
    public void test5_allIpsFail_triggersRefresh_thenSucceeds() throws Exception {
        // Phase 1: cached IPs return 500 → triggers refresh
        // Phase 2: after refresh, new call succeeds
        AtomicReference<Integer> seedCallCount   = new AtomicReference<>(0);
        AtomicReference<Integer> apiCallCount    = new AtomicReference<>(0);

        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest req) {
                String path = req.getPath();
                if (path != null && path.startsWith("/dns-query")) {
                    return dohResponse(LOCALHOST);
                } else if ("/v1/seeds".equals(path)) {
                    seedCallCount.updateAndGet(c -> c + 1);
                    return new MockResponse().setBody(LOCALHOST + "\n");
                } else {
                    int count = apiCallCount.updateAndGet(c -> c + 1);
                    // First API call (from initial IPs) fails
                    if (count == 1) return new MockResponse().setResponseCode(500);
                    // Second API call (after refresh) succeeds
                    return new MockResponse().setBody("config:refreshed.host,4444,");
                }
            }
        });

        String result = await(makeDiscovery(), "/?domain=example.com&get=1&cc=US&pub=test&uid=abc&ver=1.0&foreground=true", false);
        assertEquals("config:refreshed.host,4444,", result);
        assertEquals(2, (int) seedCallCount.get()); // /v1/seeds called twice (initial + refresh)
    }

    // -------------------------------------------------------------------------
    // 6. All IPs fail AND refresh also fails → onFailure
    // -------------------------------------------------------------------------

    @Test
    public void test6_allIpsFail_refreshAlsoFails_callbackIsFailure() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest req) {
                String path = req.getPath();
                if (path != null && path.startsWith("/dns-query")) {
                    return dohResponse(LOCALHOST);
                } else if ("/v1/seeds".equals(path)) {
                    return new MockResponse().setBody(LOCALHOST + "\n");
                } else {
                    return new MockResponse().setResponseCode(500);
                }
            }
        });

        String result = await(makeDiscovery(), "/?domain=example.com&get=1&cc=US&pub=test&uid=abc&ver=1.0&foreground=true", false);
        assertTrue("Expected failure, got: " + result, result.startsWith("FAILURE:"));
    }

    // -------------------------------------------------------------------------
    // 7. DoH returns no IPs → onFailure
    // -------------------------------------------------------------------------

    @Test
    public void test7_dohReturnsNoIps_callbackIsFailure() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest req) {
                // DoH returns Status != 0 — no IPs extracted
                return dohFailureResponse();
            }
        });

        String result = await(makeDiscovery(), "/?domain=example.com&get=1", false);
        assertTrue("Expected failure, got: " + result, result.startsWith("FAILURE:"));
    }

    // -------------------------------------------------------------------------
    // 8. /v1/seeds returns only invalid lines → onFailure
    // -------------------------------------------------------------------------

    @Test
    public void test8_seedServerReturnsInvalidLines_callbackIsFailure() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest req) {
                String path = req.getPath();
                if (path != null && path.startsWith("/dns-query")) {
                    return dohResponse(LOCALHOST);
                } else if ("/v1/seeds".equals(path)) {
                    // All lines are invalid — no valid IPv4s
                    return new MockResponse().setBody("not-an-ip\nhostname.example.com\n\n  \n");
                }
                return new MockResponse().setBody("config:x,1,");
            }
        });

        String result = await(makeDiscovery(), "/?domain=example.com&get=1", false);
        assertTrue("Expected failure, got: " + result, result.startsWith("FAILURE:"));
    }

    // -------------------------------------------------------------------------
    // 9. Cache is fresh – second call does NOT hit DoH again
    // -------------------------------------------------------------------------

    @Test
    public void test9_freshCache_doesNotRediscoverOnSecondCall() throws Exception {
        AtomicReference<Integer> dohCallCount = new AtomicReference<>(0);

        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest req) {
                String path = req.getPath();
                if (path != null && path.startsWith("/dns-query")) {
                    dohCallCount.updateAndGet(c -> c + 1);
                    return dohResponse(LOCALHOST);
                } else if ("/v1/seeds".equals(path)) {
                    return new MockResponse().setBody(LOCALHOST + "\n");
                }
                return new MockResponse().setBody("config:cached.host,9090,");
            }
        });

        SeedDiscovery sd = makeDiscovery();

        // First call — populates cache
        String r1 = await(sd, "/?domain=example.com&get=1&cc=IL&pub=t&uid=1&ver=1&foreground=false", false);
        assertEquals("config:cached.host,9090,", r1);

        // Second call — cache should still be fresh, no DoH hit
        String r2 = await(sd, "/?domain=example.com&get=1&cc=IL&pub=t&uid=1&ver=1&foreground=false", false);
        assertEquals("config:cached.host,9090,", r2);

        assertEquals("DoH should only be called once (cache reused)", 1, (int) dohCallCount.get());
    }

    // -------------------------------------------------------------------------
    // 10. Invalid lines in /v1/seeds are silently skipped
    // -------------------------------------------------------------------------

    @Test
    public void test10_seedResponse_invalidLinesSkipped_validOnesUsed() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest req) {
                String path = req.getPath();
                if (path != null && path.startsWith("/dns-query")) {
                    return dohResponse(LOCALHOST);
                } else if ("/v1/seeds".equals(path)) {
                    // Mix of valid and invalid entries
                    return new MockResponse().setBody(
                            "not-an-ip\n"
                            + LOCALHOST + "\n"    // valid
                            + "\n"
                            + "hostname.com\n"
                            + "  \n"
                    );
                }
                return new MockResponse().setBody("config:valid.path,7777,");
            }
        });

        String result = await(makeDiscovery(), "/?domain=example.com&get=1", false);
        assertEquals("config:valid.path,7777,", result);
    }

    // -------------------------------------------------------------------------
    // 11. domain= parameter is present in every API call URL
    // -------------------------------------------------------------------------

    @Test
    public void test11_domainParamPresentInApiCallUrl() throws Exception {
        AtomicReference<String> capturedPath = new AtomicReference<>();

        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest req) {
                String path = req.getPath();
                if (path != null && path.startsWith("/dns-query")) {
                    return dohResponse(LOCALHOST);
                } else if ("/v1/seeds".equals(path)) {
                    return new MockResponse().setBody(LOCALHOST + "\n");
                } else {
                    capturedPath.set(path);
                    return new MockResponse().setBody("config:x,1,");
                }
            }
        });

        await(makeDiscovery(), "/?domain=stupidthings.online&get=1&cc=IL&pub=test&uid=abc&ver=1.0&foreground=true", false);

        assertNotNull("API path should have been captured", capturedPath.get());
        assertTrue(
                "URL must contain domain= param, was: " + capturedPath.get(),
                capturedPath.get().contains("domain=stupidthings.online")
        );
    }
}
