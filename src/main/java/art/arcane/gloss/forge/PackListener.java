package art.arcane.gloss.forge;

import art.arcane.gloss.Gloss;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Serves retained pack ZIPs on their hashed routes. This is the only inbound socket
 * Gloss opens, so it is opt-in, answers only known hashes, reads the file per request rather than
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
    private final int threads;
    private final int backlog;
    private final PackArtifactStore artifacts;
    private PackArtifactStore.Lease currentLease;

    private volatile HttpServer server;
    private volatile ThreadPoolExecutor pool;
    private volatile PackArtifact artifact;

    public PackListener(String bind, int port) {
        this(new Options(bind, port, THREADS, BACKLOG));
    }

    public PackListener(Options options) {
        this(options, new PackArtifactStore());
    }

    public PackListener(Options options, PackArtifactStore artifacts) {
        this.artifacts = artifacts;
        String bind = options.bind();
        this.threads = Math.clamp(options.threads(), 1, 32);
        this.backlog = Math.clamp(options.backlog(), 1, 4096);
        this.bind = bind == null || bind.isBlank() ? "0.0.0.0" : bind.trim();
        this.port = options.port();
    }

    public record Options(String bind, int port, int threads, int backlog) {
    }

    public synchronized boolean start(PackArtifact current) {
        if (server != null) {
            publish(current);
            return true;
        }
        publish(current);
        AtomicInteger counter = new AtomicInteger();
        try {
            HttpServer created = HttpServer.create(new InetSocketAddress(bind, port), backlog);
            ThreadPoolExecutor executor = new ThreadPoolExecutor(threads, threads, IDLE_SECONDS, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(backlog), runnable -> {
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
            publish(null);
            Gloss.logExceptionStack(false, failure, "forge: the pack listener could not bind %s:%d.", bind, port);
            return false;
        }
        if ("0.0.0.0".equals(bind)) {
            Gloss.log(Level.INFO, "forge: pack listener on 0.0.0.0:%d; set [forge] url to an address players reach.",
                port());
        }
        return true;
    }

    public synchronized void publish(PackArtifact current) {
        PackArtifactStore.Lease previous = currentLease;
        currentLease = artifacts.retain(current);
        artifact = current;
        if (previous != null) {
            previous.close();
        }
    }

    public synchronized void stop() {
        HttpServer current = server;
        ThreadPoolExecutor executor = pool;
        server = null;
        pool = null;
        publish(null);
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
        return url(artifact);
    }

    public String url(PackArtifact current) {
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
            if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(METHOD_NOT_ALLOWED, -1);
                return;
            }
            try (PackArtifactStore.Lease lease = artifacts.acquireRoute(exchange.getRequestURI().getPath())) {
                if (lease == null || !Files.isRegularFile(lease.artifact().zip(), LinkOption.NOFOLLOW_LINKS)) {
                    exchange.sendResponseHeaders(NOT_FOUND, -1);
                    return;
                }
                serve(exchange, lease.artifact().zip());
            }
        }
    }

    private void serve(HttpExchange exchange, Path zip) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
        try (FileChannel input = FileChannel.open(zip, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long length = input.size();
            exchange.getResponseHeaders().set("Content-Length", Long.toString(length));
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(OK, -1);
                return;
            }
            exchange.sendResponseHeaders(OK, length);
            try (OutputStream body = exchange.getResponseBody()) {
                ByteBuffer buffer = ByteBuffer.allocate(65536);
                long remaining = length;
                while (remaining > 0) {
                    buffer.clear().limit((int) Math.min(buffer.capacity(), remaining));
                    int read = input.read(buffer);
                    if (read < 0) {
                        throw new IOException("Pack artifact was truncated during download");
                    }
                    body.write(buffer.array(), 0, read);
                    remaining -= read;
                }
            }
        }
    }
}
