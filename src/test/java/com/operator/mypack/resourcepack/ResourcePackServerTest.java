package com.operator.mypack.resourcepack;

import com.operator.mypack.config.Settings;
import com.operator.mypack.utils.HashUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Random;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackServerTest {

    private ResourcePackServer server;
    private final HttpClient client = HttpClient.newHttpClient();
    private byte[] zip;
    private String sha1;

    @BeforeEach
    void start() throws IOException {
        server = new ResourcePackServer(Logger.getAnonymousLogger());
        server.start("127.0.0.1", 0);
        zip = new byte[300_000];
        new Random(5).nextBytes(zip);
        sha1 = HashUtils.sha1Hex(zip);
        server.publish(sha1, zip);
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    @DisplayName("serves the exact bytes at /mypack/<sha1>.zip; the client-side SHA-1 matches the advertised hash")
    void servesPack() throws Exception {
        HttpResponse<byte[]> response = get("/mypack/" + sha1 + ".zip");
        assertEquals(200, response.statusCode());
        assertArrayEquals(zip, response.body());
        assertEquals(sha1, HashUtils.sha1Hex(response.body()));
        assertEquals("application/zip", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("\"" + sha1 + "\"", response.headers().firstValue("ETag").orElseThrow());
        assertEquals(String.valueOf(zip.length), response.headers().firstValue("Content-Length").orElseThrow());
        assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("immutable"));
    }

    @Test
    @DisplayName("/mypack/latest.zip always follows the newest build and is never cached")
    void latestFollowsPublish() throws Exception {
        byte[] second = "second build".getBytes();
        String secondHash = HashUtils.sha1Hex(second);
        server.publish(secondHash, second);
        HttpResponse<byte[]> latest = get("/mypack/latest.zip");
        assertArrayEquals(second, latest.body());
        assertEquals("no-cache", latest.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(secondHash, server.currentHash());
        assertEquals(200, get("/mypack/" + sha1 + ".zip").statusCode(), "the previous build is still downloadable");
    }

    @Test
    @DisplayName("only the two newest builds are kept")
    void keepsTwoBuilds() throws Exception {
        for (int i = 0; i < 3; i++) {
            byte[] data = ("build " + i).getBytes();
            server.publish(HashUtils.sha1Hex(data), data);
        }
        assertEquals(404, get("/mypack/" + sha1 + ".zip").statusCode(), "the oldest build was evicted");
        assertEquals(200, get("/mypack/" + HashUtils.sha1Hex("build 1".getBytes()) + ".zip").statusCode());
    }

    @Test
    @DisplayName("anything else is 404: unknown hashes, other paths, traversal attempts, malformed hashes")
    void notFound() throws Exception {
        assertEquals(404, get("/").statusCode());
        assertEquals(404, get("/mypack/").statusCode());
        assertEquals(404, get("/mypack/" + "0".repeat(40) + ".zip").statusCode());
        assertEquals(404, get("/mypack/" + sha1.toUpperCase() + ".zip").statusCode(), "hashes are lower-case hex");
        assertEquals(404, get("/mypack/../../etc/passwd").statusCode());
        assertEquals(404, get("/mypack/%2e%2e/%2e%2e/etc/passwd").statusCode());
        assertEquals(404, get("/mypack/short.zip").statusCode());
        assertEquals(404, get("/other/" + sha1 + ".zip").statusCode());
    }

    @Test
    @DisplayName("only GET and HEAD are allowed")
    void methods() throws Exception {
        HttpResponse<Void> post = client.send(HttpRequest.newBuilder(uri("/mypack/" + sha1 + ".zip"))
                .POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(405, post.statusCode());
        assertEquals("GET, HEAD", post.headers().firstValue("Allow").orElseThrow());
        HttpResponse<Void> head = client.send(HttpRequest.newBuilder(uri("/mypack/" + sha1 + ".zip"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(200, head.statusCode());
        assertEquals("\"" + sha1 + "\"", head.headers().firstValue("ETag").orElseThrow());
    }

    @Test
    @DisplayName("conditional requests with a matching ETag get 304")
    void conditional() throws Exception {
        HttpResponse<Void> response = client.send(HttpRequest.newBuilder(uri("/mypack/" + sha1 + ".zip"))
                .header("If-None-Match", "\"" + sha1 + "\"").GET().build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(304, response.statusCode());
    }

    @Test
    @DisplayName("handles many concurrent downloads")
    void concurrentDownloads() throws Exception {
        java.util.List<java.util.concurrent.CompletableFuture<HttpResponse<byte[]>>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 24; i++) {
            futures.add(client.sendAsync(HttpRequest.newBuilder(uri("/mypack/" + sha1 + ".zip")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()));
        }
        for (var future : futures) {
            HttpResponse<byte[]> response = future.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(200, response.statusCode());
            assertEquals(zip.length, response.body().length);
        }
    }

    @Test
    @DisplayName("stop() releases the port: connections are refused afterwards, stop() is idempotent")
    void stopsCleanly() {
        int port = server.port();
        server.stop();
        server.stop();
        assertFalse(server.isRunning());
        assertThrows(IllegalStateException.class, () -> server.port());
        Exception e = assertThrows(Exception.class, () -> client.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/mypack/latest.zip")).GET().build(), HttpResponse.BodyHandlers.discarding()));
        assertTrue(e instanceof ConnectException || e.getCause() instanceof ConnectException, e.toString());
    }

    @Test
    @DisplayName("a second server on the same port fails with an IOException instead of crashing")
    void portInUse() {
        ResourcePackServer other = new ResourcePackServer(Logger.getAnonymousLogger());
        assertThrows(IOException.class, () -> other.start("127.0.0.1", server.port()));
        assertFalse(other.isRunning());
    }

    @Test
    @DisplayName("public URL comes from the configuration: scheme, host, advertised port; default ports are omitted")
    void publicUrl() {
        Settings.Http http = new Settings.Http(true, "0.0.0.0", 8123, "play.example.com", "http", 0);
        assertEquals("http://play.example.com:8123/mypack/abc.zip", ResourcePackServer.publicUrl(http, 8123, "abc"));
        Settings.Http proxied = new Settings.Http(true, "0.0.0.0", 8123, "cdn.example.com", "https", 443);
        assertEquals("https://cdn.example.com/mypack/abc.zip", ResourcePackServer.publicUrl(proxied, 8123, "abc"));
        Settings.Http blank = new Settings.Http(true, "0.0.0.0", 8123, "", "http", 0);
        String url = ResourcePackServer.publicUrl(blank, 8123, "abc");
        assertTrue(url.startsWith("http://") && url.endsWith(":8123/mypack/abc.zip"), url);
        assertFalse(url.contains("localhost"), "never hardcode localhost");
    }
}
