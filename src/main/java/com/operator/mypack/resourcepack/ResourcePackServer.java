package com.operator.mypack.resourcepack;

import com.operator.mypack.config.Settings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hosts the resource pack with the JDK's built-in HTTP server so no external web server is needed. The pack is served
 * from memory at {@code /mypack/<sha1>.zip} (long-lived caching, the hash makes every version a distinct URL) and
 * {@code /mypack/latest.zip}. Only {@code GET} and {@code HEAD} are accepted and there is no file system access, so
 * there is nothing to traverse. The two most recent builds stay available so that a download that started before a
 * rebuild still completes.
 */
public final class ResourcePackServer implements AutoCloseable {

    /** URL prefix of every served file. */
    public static final String BASE_PATH = "/mypack/";

    private static final int WORKER_THREADS = 4;

    private final Logger log;
    private final Object lock = new Object();
    private final Map<String, byte[]> builds = new LinkedHashMap<>();
    private volatile String currentHash;
    private HttpServer server;
    private ExecutorService executor;

    public ResourcePackServer(Logger log) {
        this.log = log;
    }

    /**
     * Starts listening.
     *
     * @param port {@code 0} picks a free port (used by tests)
     * @throws IOException when the address is already in use or cannot be bound
     */
    public synchronized void start(String bindAddress, int port) throws IOException {
        if (server != null) {
            throw new IllegalStateException("already started");
        }
        HttpServer created = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        ExecutorService pool = Executors.newFixedThreadPool(WORKER_THREADS, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "MyPack-HTTP-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        created.setExecutor(pool);
        created.createContext("/", this::handle);
        created.start();
        this.server = created;
        this.executor = pool;
        log.info("Resource pack server listening on " + bindAddress + ":" + created.getAddress().getPort());
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    /** The port the server is actually bound to. */
    public synchronized int port() {
        if (server == null) {
            throw new IllegalStateException("server is not running");
        }
        return server.getAddress().getPort();
    }

    /** Makes {@code zip} the current pack; the previous build stays downloadable. */
    public void publish(String sha1Hex, byte[] zip) {
        synchronized (lock) {
            builds.remove(sha1Hex);
            builds.put(sha1Hex, zip);
            while (builds.size() > 2) {
                String oldest = builds.keySet().iterator().next();
                builds.remove(oldest);
            }
            currentHash = sha1Hex;
        }
    }

    public String currentHash() {
        return currentHash;
    }

    /** Stops accepting connections and releases the worker threads. Safe to call repeatedly. */
    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        synchronized (lock) {
            builds.clear();
            currentHash = null;
        }
    }

    @Override
    public void close() {
        stop();
    }

    // ------------------------------------------------------------------ request handling

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String hash = hashForPath(path);
            byte[] body;
            synchronized (lock) {
                body = hash == null ? null : builds.get(hash);
            }
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            boolean latest = path.equals(BASE_PATH + "latest.zip");
            String etag = "\"" + hash + "\"";
            exchange.getResponseHeaders().set("ETag", etag);
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"mypack.zip\"");
            exchange.getResponseHeaders().set("Cache-Control", latest ? "no-cache" : "public, max-age=31536000, immutable");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.sendResponseHeaders(304, -1);
                return;
            }
            if (method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (IOException e) {
            // client disconnected mid-download: nothing to report
            log.log(Level.FINE, "resource pack download aborted", e);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "resource pack request failed", e);
            try {
                exchange.sendResponseHeaders(500, -1);
            } catch (IOException ignored) {
                // response already started
            }
        } finally {
            exchange.close();
        }
    }

    /** Maps a request path to a build hash, or {@code null} when the path is not served. */
    private String hashForPath(String path) {
        if (path == null || !path.startsWith(BASE_PATH) || !path.endsWith(".zip")) {
            return null;
        }
        String name = path.substring(BASE_PATH.length(), path.length() - ".zip".length());
        if (name.equals("latest")) {
            return currentHash;
        }
        if (name.length() != 40) {
            return null;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return null;
            }
        }
        return name;
    }

    // ------------------------------------------------------------------ public URL

    /**
     * URL clients use to download the pack. {@code publicHost} comes from the configuration; when it is empty the first
     * non-loopback IPv4 address of this machine is used (which only works for players on the same network).
     */
    public static String publicUrl(Settings.Http http, int boundPort, String sha1Hex) {
        String host = http.publicHost().isBlank() ? detectLocalAddress() : http.publicHost();
        int port = http.publicPort() > 0 ? http.publicPort() : boundPort;
        boolean defaultPort = (http.publicScheme().equals("http") && port == 80) || (http.publicScheme().equals("https") && port == 443);
        return http.publicScheme() + "://" + host + (defaultPort ? "" : ":" + port) + BASE_PATH + sha1Hex + ".zip";
    }

    /** First non-loopback IPv4 address of this machine, or the loopback address as a last resort. */
    public static String detectLocalAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (NetworkInterface nic : Collections.list(interfaces)) {
                    if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                        continue;
                    }
                    for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                        if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                            return address.getHostAddress();
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // fall through to the loopback address
        }
        return InetAddress.getLoopbackAddress().getHostAddress();
    }
}
