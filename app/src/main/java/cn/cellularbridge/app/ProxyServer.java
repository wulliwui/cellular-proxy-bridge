package cn.cellularbridge.app;

import android.net.Network;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

final class ProxyServer implements AutoCloseable {
    static final String TAG = "CellularBridge";
    private static final int MAX_SESSIONS = 16;
    private static final int HANDSHAKE_TIMEOUT_MS = 7_000;
    private static final int TARGET_TIMEOUT_MS = 12_000;
    private static final int ADDRESS_TIMEOUT_MS = 4_000;
    private static final long IDLE_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(120);
    private static final byte[] ESTABLISHED =
            "HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private final BridgeConfig config;
    private final Runnable onChange;
    private final ExecutorService workers = Executors.newFixedThreadPool(MAX_SESSIONS);
    private final ExecutorService relays = Executors.newFixedThreadPool(MAX_SESSIONS);
    private final ExecutorService dns = new ThreadPoolExecutor(0, MAX_SESSIONS, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>());
    private final ScheduledExecutorService idleCheck = Executors.newSingleThreadScheduledExecutor();
    private final Set<Session> sessions = new HashSet<>();
    private ServerSocket listener;
    private Network cellular;
    private int active;
    private long completed;
    private long failed;
    private boolean stopped;

    ProxyServer(BridgeConfig config, Runnable onChange) {
        this.config = config;
        this.onChange = onChange;
        idleCheck.scheduleWithFixedDelay(this::expireIdleTunnels, 1, 1, TimeUnit.SECONDS);
    }

    synchronized void listen(Inet4Address address, Runnable onListenerFailure) throws IOException {
        if (stopped) {
            return;
        }
        detachListener();
        if (address == null) {
            return;
        }
        ServerSocket server = new ServerSocket();
        try {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(address, config.listenPort), 24);
        } catch (IOException | RuntimeException error) {
            closeQuietly(server);
            throw error;
        }
        listener = server;
        new Thread(() -> acceptConnections(server, onListenerFailure), "bridge-listener").start();
        Log.i(TAG, "Local-only Wi-Fi listener ready on " + address.getHostAddress() + ":" + config.listenPort);
    }

    synchronized void setCellular(Network network) {
        if (stopped || java.util.Objects.equals(cellular, network)) {
            return;
        }
        Network previous = cellular;
        cellular = network;
        if (previous != null) {
            for (Session session : sessions) {
                if (previous.equals(session.route)) {
                    session.close();
                }
            }
        }
        Log.i(TAG, network == null ? "Cellular network lost" : "Cellular network available: " + network);
        onChange.run();
    }

    synchronized int activeCount() {
        return active;
    }

    private void acceptConnections(ServerSocket server, Runnable onListenerFailure) {
        while (!server.isClosed()) {
            Socket client;
            try {
                client = server.accept();
            } catch (IOException error) {
                synchronized (this) {
                    if (listener != server) {
                        return;
                    }
                    detachListener();
                }
                Log.w(TAG, "Listener accept failed: " + error.getClass().getSimpleName());
                onListenerFailure.run();
                return;
            }
            synchronized (this) {
                if (listener != server) {
                    closeQuietly(client);
                    return;
                }
                if (sessions.size() < MAX_SESSIONS) {
                    Session session = new Session(client);
                    sessions.add(session);
                    workers.execute(() -> handle(session));
                    continue;
                }
            }
            try (client) {
                reply(client, 503);
            } catch (IOException ignored) {
                // The client may already have closed the connection.
            }
        }
    }

    private void handle(Session session) {
        boolean established = false;
        try {
            BufferedInputStream fromClient = new BufferedInputStream(session.client.getInputStream());
            ConnectRequest request = ConnectRequest.read(session.client, fromClient, config,
                    HANDSHAKE_TIMEOUT_MS);
            Network route = selectRoute(session);
            if (route == null) {
                throw new ConnectRequest.Rejected(503);
            }
            Socket upstream = connect(session, route, request);
            session.client.setSoTimeout(0);
            activate(session, route);
            established = true;
            session.client.getOutputStream().write(ESTABLISHED);
            Log.i(TAG, "CONNECT established via cellular network " + route
                    + ", local=" + upstream.getLocalAddress().getHostAddress());
            relay(session, upstream, fromClient);
        } catch (ConnectRequest.Rejected rejection) {
            fail(session, rejection.status);
        } catch (SocketTimeoutException timeout) {
            if (!established) {
                fail(session, session.route == null ? 408 : 504);
            }
        } catch (IOException error) {
            if (!established) {
                fail(session, session.route == null ? 400 : 502);
            } else if (!session.closed) {
                Log.d(TAG, "Tunnel I/O ended: " + error.getClass().getSimpleName());
            }
        } finally {
            session.close();
            finish(session);
        }
    }

    private synchronized Network selectRoute(Session session) {
        session.route = cellular;
        return cellular;
    }

    private Socket connect(Session session, Network route, ConnectRequest request)
            throws IOException, ConnectRequest.Rejected {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TARGET_TIMEOUT_MS);
        InetAddress[] addresses = resolve(session, route, request.host, deadline);
        boolean attempted = false;
        boolean timedOut = false;
        for (InetAddress address : addresses) {
            if (!isPublicTarget(address)) {
                continue;
            }
            attempted = true;
            int timeout = Math.min(ADDRESS_TIMEOUT_MS, ConnectRequest.remainingMillis(deadline));
            Socket socket = route.getSocketFactory().createSocket();
            session.attach(socket);
            try {
                socket.connect(new InetSocketAddress(address, request.port), timeout);
                return socket;
            } catch (SocketTimeoutException error) {
                timedOut = true;
                closeQuietly(socket);
            } catch (IOException error) {
                closeQuietly(socket);
                if (session.closed) {
                    throw error;
                }
            }
        }
        if (!attempted) {
            throw new ConnectRequest.Rejected(403);
        }
        throw new ConnectRequest.Rejected(timedOut ? 504 : 502);
    }

    private InetAddress[] resolve(Session session, Network route, String host, long deadline)
            throws IOException, ConnectRequest.Rejected {
        Future<InetAddress[]> lookup;
        try {
            lookup = dns.submit(() -> route.getAllByName(host));
        } catch (RejectedExecutionException error) {
            throw new ConnectRequest.Rejected(503);
        }
        session.trackLookup(lookup);
        try {
            return lookup.get(ConnectRequest.remainingMillis(deadline), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            throw new SocketTimeoutException();
        } catch (ExecutionException error) {
            throw new IOException("Cellular DNS failed", error.getCause());
        } catch (CancellationException error) {
            throw new IOException("Cellular DNS cancelled");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Cellular DNS interrupted");
        } finally {
            lookup.cancel(true);
            session.trackLookup(null);
        }
    }

    private static boolean isPublicTarget(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 255;
        if (address instanceof Inet4Address) {
            return first != 0 && first < 224
                    && (first != 100 || (bytes[1] & 192) != 64);
        }
        return (first & 254) != 252;
    }

    private synchronized void activate(Session session, Network route) throws IOException {
        if (session.closed || !route.equals(cellular)) {
            throw new IOException("Cellular network changed");
        }
        session.lastActivity.set(System.nanoTime());
        session.established = true;
        active++;
        onChange.run();
    }

    private void relay(Session session, Socket upstream, InputStream fromClient) throws IOException {
        InputStream fromUpstream = upstream.getInputStream();
        CountDownLatch downstreamDone = new CountDownLatch(1);
        try {
            relays.execute(() -> {
                try {
                    copy(session, fromUpstream, session.client);
                } finally {
                    downstreamDone.countDown();
                }
            });
        } catch (RejectedExecutionException error) {
            throw new IOException("Relay stopped");
        }
        copy(session, fromClient, upstream);
        try {
            downstreamDone.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private void copy(Session session, InputStream input, Socket destination) {
        try {
            OutputStream output = destination.getOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int length;
            while ((length = input.read(buffer)) != -1) {
                session.lastActivity.set(System.nanoTime());
                output.write(buffer, 0, length);
                session.lastActivity.set(System.nanoTime());
            }
            destination.shutdownOutput();
        } catch (IOException error) {
            if (!session.closed) {
                Log.d(TAG, "Tunnel relay ended: " + error.getClass().getSimpleName());
                session.close();
            }
        }
    }

    private synchronized void expireIdleTunnels() {
        long now = System.nanoTime();
        for (Session session : sessions) {
            if (session.established && !session.closed
                    && now - session.lastActivity.get() >= IDLE_TIMEOUT_NS) {
                Log.i(TAG, "Idle tunnel timed out");
                session.close();
            }
        }
    }

    private void fail(Session session, int status) {
        if (session.closed) {
            return;
        }
        synchronized (this) {
            failed++;
        }
        Log.d(TAG, "CONNECT rejected: " + status);
        try {
            reply(session.client, status);
        } catch (IOException ignored) {
            // No response can be sent after the peer disconnects.
        }
    }

    private static void reply(Socket client, int status) throws IOException {
        String reason = switch (status) {
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 405 -> "Method Not Allowed";
            case 407 -> "Proxy Authentication Required";
            case 408 -> "Request Timeout";
            case 431 -> "Request Header Fields Too Large";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            case 505 -> "HTTP Version Not Supported";
            default -> throw new IllegalArgumentException("Unexpected HTTP status");
        };
        String extra = switch (status) {
            case 405 -> "Allow: CONNECT\r\n";
            case 407 -> "Proxy-Authenticate: Basic realm=\"Cellular Bridge\"\r\n";
            default -> "";
        };
        client.getOutputStream().write(("HTTP/1.1 " + status + " " + reason + "\r\n" + extra
                + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
    }

    private synchronized void finish(Session session) {
        sessions.remove(session);
        if (session.established) {
            active--;
            completed++;
            Log.d(TAG, "Tunnel closed; active=" + active + ", closed=" + completed + ", rejected=" + failed);
            onChange.run();
        }
    }

    private void detachListener() {
        ServerSocket previous = listener;
        listener = null;
        closeQuietly(previous);
        for (Session session : sessions) {
            session.close();
        }
    }

    @Override
    public synchronized void close() {
        stopped = true;
        detachListener();
        cellular = null;
        idleCheck.shutdownNow();
        dns.shutdownNow();
        relays.shutdownNow();
        workers.shutdownNow();
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception ignored) {
                // Closing one resource must not prevent closing the others.
            }
        }
    }

    private static final class Session {
        final Socket client;
        final AtomicLong lastActivity = new AtomicLong();
        volatile boolean closed;
        Network route;
        boolean established;
        private Socket upstream;
        private Future<?> dnsLookup;

        Session(Socket client) {
            this.client = client;
        }

        synchronized void attach(Socket socket) throws IOException {
            if (closed) {
                closeQuietly(socket);
                throw new IOException("Tunnel closed");
            }
            upstream = socket;
        }

        synchronized void trackLookup(Future<?> lookup) {
            dnsLookup = lookup;
            if (closed && lookup != null) {
                lookup.cancel(true);
            }
        }

        synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (dnsLookup != null) {
                dnsLookup.cancel(true);
            }
            closeQuietly(upstream);
            closeQuietly(client);
        }
    }
}
