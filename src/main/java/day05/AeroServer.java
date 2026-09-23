package day05;

import day04.AeroConcurrentLRU;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AeroServer {
    // Values larger than this are rejected outright rather than accepted
    // into memory unbounded — protects the JVM heap from a single client
    // sending an oversized payload.
    private static final int MAX_VALUE_BYTES = 5 * 1024 * 1024; // 5 MB

    private final ServerSocket serverSocket;
    private final AeroConcurrentLRU cache;
    private final AeroWAL wal;
    // One thread stays pinned to a connection for that connection's entire
    // lifetime (see handleClient's while loop) — this is a thread-per-
    // connection server, not an event-driven one. That means the pool size
    // is a hard ceiling on concurrent connections, not just a performance
    // tuning knob: if more clients connect at once than there are threads,
    // the extra ones queue up, and combined with the idle-socket timeout
    // above, a client that queues too long can have its own still-live
    // connection closed by the server before ever getting serviced. Size
    // this at least as large as the maximum number of clients you expect
    // to have connected at the same time.
    private final ExecutorService threadPool;
    private volatile boolean running;
    // null/blank means "no password required" — every connection starts
    // pre-authenticated, same as before this feature existed.
    private final String requiredPassword;

    public AeroServer(int port, int capacity, int numStripes, String logFilePath) throws IOException {
        this(port, capacity, numStripes, logFilePath, 0, null, 200);
    }

    public AeroServer(int port, int capacity, int numStripes, String logFilePath,
                       long maxTotalBytes, String requiredPassword) throws IOException {
        this(port, capacity, numStripes, logFilePath, maxTotalBytes, requiredPassword, 200);
    }

    public AeroServer(int port, int capacity, int numStripes, String logFilePath,
                       long maxTotalBytes, String requiredPassword, int threadPoolSize) throws IOException {
        this.serverSocket = new ServerSocket(port);
        this.cache = new AeroConcurrentLRU(capacity, numStripes, maxTotalBytes);
        this.wal = new AeroWAL(logFilePath);
        this.threadPool = Executors.newFixedThreadPool(threadPoolSize);
        this.running = true;
        this.requiredPassword = (requiredPassword == null || requiredPassword.isBlank()) ? null : requiredPassword;
        // Order matters: recover() and compact() must both finish reading
        // and rewriting the log file before the async writer thread opens
        // it for append (start()), otherwise compaction can race the
        // writer for the file handle.
        this.wal.recover(this.cache);
        this.wal.compact(this.cache);
        this.wal.start();
    }

    public void start() {
        System.out.println("AeroKV started on port " + serverSocket.getLocalPort());
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                // Idle timeout: an unused connection is closed to free its
                // worker thread back to the pool eventually. 1 second (the
                // original value) was far too aggressive: a client that is
                // simply waiting its turn under load (e.g. many clients
                // connecting to a small thread pool at once) could have its
                // connection killed by the server before it ever got to
                // send its next command, even though it was still a live,
                // well-behaved client. 60 seconds is generous enough for a
                // connection-pooling client to sit briefly idle between
                // requests without being punished for it.
                clientSocket.setSoTimeout(60_000);
                threadPool.submit(() -> handleClient(clientSocket));
            } catch (IOException e) {
                if (!running) break;
            }
        }
    }

    private void handleClient(Socket socket) {
        // Each connection starts unauthenticated unless no password is
        // configured at all, in which case auth is effectively off.
        boolean authenticated = (requiredPassword == null);

        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
        ) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                int firstComma = line.indexOf(',');
                if (firstComma == -1) {
                    if ("PING".equalsIgnoreCase(line)) {
                        out.println("PONG");
                    } else {
                        out.println("ERR_INVALID_FORMAT");
                    }
                    out.flush();
                    continue;
                }

                String command = line.substring(0, firstComma).trim().toUpperCase();

                if ("AUTH".equals(command)) {
                    String suppliedPassword = line.substring(firstComma + 1).trim();
                    if (requiredPassword != null && requiredPassword.equals(suppliedPassword)) {
                        authenticated = true;
                        out.println("OK");
                    } else {
                        out.println("ERR_AUTH_FAILED");
                    }
                    out.flush();
                    continue;
                }

                if (!authenticated) {
                    out.println("ERR_NOT_AUTHENTICATED");
                    out.flush();
                    continue;
                }

                // 🛡️ Delimiter Fix: Parse key, payload, and optional TTL safely
                if ("SET".equals(command) || "PUT".equals(command)) {
                    int secondComma = line.indexOf(',', firstComma + 1);
                    if (secondComma == -1) {
                        out.println("ERR_SYNTAX_ERROR");
                        out.flush();
                        continue;
                    }

                    String key = line.substring(firstComma + 1, secondComma).trim();
                    int lastComma = line.lastIndexOf(',');

                    String val;
                    long ttl = 0;

                    if (lastComma > secondComma) {
                        String possibleTTL = line.substring(lastComma + 1).trim();
                        try {
                            ttl = Long.parseLong(possibleTTL);
                            val = line.substring(secondComma + 1, lastComma).trim();
                        } catch (NumberFormatException e) {
                            // Last token is not a number; whole trailing text is the value
                            val = line.substring(secondComma + 1).trim();
                        }
                    } else {
                        val = line.substring(secondComma + 1).trim();
                    }

                    if (val.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
                        out.println("ERR_VALUE_TOO_LARGE");
                        out.flush();
                        continue;
                    }

                    AeroTTLEntry entry = new AeroTTLEntry(val, ttl);
                    cache.put(key, entry);
                    wal.logPut(key, val);
                    out.println("OK");
                    out.flush();
                }
                else if ("DEL".equals(command) || "DELETE".equals(command)) {
                    String key = line.substring(firstComma + 1).trim();
                    cache.remove(key);
                    wal.logDelete(key);
                    out.println("OK");
                    out.flush();
                }
                else if ("GET".equals(command)) {
                    String key = line.substring(firstComma + 1).trim();
                    Object rawEntry = cache.get(key);

                    if (rawEntry instanceof AeroTTLEntry) {
                        AeroTTLEntry entry = (AeroTTLEntry) rawEntry;
                        if (entry.isExpired()) {
                            cache.remove(key);
                            out.println("ERR_EXPIRED");
                        } else {
                            out.println("VALUE, " + entry.getValue());
                        }
                    } else {
                        out.println("ERR_NOT_FOUND");
                    }
                    out.flush();
                } 
                else {
                    out.println("ERR_UNKNOWN_COMMAND");
                    out.flush();
                }
            }
        } catch (SocketTimeoutException e) {
            // Idle client timed out: connection closes so thread returns to pool
        } catch (IOException e) {
            // Client disconnected
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    public void stop() throws IOException {
        this.running = false;
        this.serverSocket.close();
        this.wal.shutdown();
        this.threadPool.shutdown();
    }
}