package art.arcane.gloss.forge;

import art.arcane.gloss.Gloss;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Serves the current pack zip on one hashed route and nothing else. This is the only inbound socket
 * Gloss opens, so it is opt-in, answers exactly one path, reads the file per request rather than
 * holding it in memory, and stops with the plugin.
 */
public final class PackListener {
    public static final int THREADS = 4;
    public static final int BACKLOG = 32;
    private static final int STOP_SECONDS = 1;
    private static final long IDLE_SECONDS = 30L;
    private static final String CONTENT_TYPE = "application/zip";
    private static final int NOT_FOUND = 404;
    private static final int METHOD_NOT_ALLOWED = 405;
    private static final int OK = 200;

    private final String bind;
    private final int port;

    private volatile HttpServer server;
    private volatile ThreadPoolExecutor pool;
    private volatile PackArtifact artifact;

    public PackListener(String bind, int port) {
        this.bind = bind == null || bind.isBlank() ? "0.0.0.0" : bind.trim();
        this.port = port;
    }

    public synchronized boolean start(PackArtifact current) {
        if (server != null) {
            publish(current);
            return true;
        }
        artifact = current;
        AtomicInteger counter = new AtomicInteger();
        try {
            HttpServer created = HttpServer.create(new InetSocketAddress(bind, port), BACKLOG);
            ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(THREADS, runnable -> {
                Thread thread = new Thread(runnable, "Gloss-Pack-HTTP-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            executor.setKeepAliveTime(IDLE_SECONDS, TimeUnit.SECONDS);
            executor.allowCoreThreadTimeOut(true);
            created.setExecutor(executor);
            created.createContext("/", this::handle);
            created.start();
            server = created;
            pool = executor;
        } catch (IOException failure) {
            Gloss.logExceptionStack(false, failure, "forge: the pack listener could not bind %s:%d.", bind, port);
            return false;
        }
        if ("0.0.0.0".equals(bind)) {
            Gloss.log(Level.INFO, "forge: pack listener on 0.0.0.0:%d; set [forge] url to an address players reach.",
                port());
        }
        return true;
    }

    public void publish(PackArtifact current) {
        artifact = current;
    }

    public synchronized void stop() {
        HttpServer current = server;
        ThreadPoolExecutor executor = pool;
        server = null;
        pool = null;
        artifact = null;
        if (current != null) {
            current.stop(STOP_SECONDS);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public boolean running() {
        return server != null;
    }

    /** The bound port, which is what the operator needs when {@code servePort} was zero. */
    public int port() {
        HttpServer current = server;
        return current == null ? 0 : current.getAddress().getPort();
    }

    /**
     * The download URL for the current artifact. A wildcard bind has no reachable address of its
     * own, so this falls back to the host's address and the operator is told to set {@code url}.
     */
    public String url() {
        PackArtifact current = artifact;
        if (current == null || !running()) {
            return "";
        }
        return "http://" + host() + ":" + port() + current.route();
    }

    private String host() {
        if (!"0.0.0.0".equals(bind)) {
            return bind;
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (IOException unknown) {
            return "127.0.0.1";
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            PackArtifact current = artifact;
            if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(METHOD_NOT_ALLOWED, -1);
                return;
            }
            if (current == null || !current.route().equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(NOT_FOUND, -1);
                return;
            }
            Path zip = current.zip();
            if (!Files.isRegularFile(zip)) {
                exchange.sendResponseHeaders(NOT_FOUND, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
            long length = Files.size(zip);
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(OK, -1);
                return;
            }
            exchange.sendResponseHeaders(OK, length);
            try (OutputStream body = exchange.getResponseBody()) {
                Files.copy(zip, body);
            }
        }
    }
}
