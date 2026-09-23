package day05;

import day04.AeroConcurrentLRU;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AeroServer {
    private final ServerSocket serverSocket;
    private final AeroConcurrentLRU cache;
    private final AeroWAL wal;
    private final ExecutorService threadPool;
    private volatile boolean running;

    public AeroServer(int port, int capacity, int numStripes, String logFilePath) throws IOException {
        this.serverSocket = new ServerSocket(port);
        this.cache = new AeroConcurrentLRU(capacity, numStripes);
        this.wal = new AeroWAL(logFilePath);
        this.threadPool = Executors.newFixedThreadPool(10);
        this.running = true;
        this.wal.recover(this.cache);
    }

    public void start() {
        System.out.println("AeroKV started on port " + serverSocket.getLocalPort());
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                // 🧵 Set idle timeout: idle sockets release worker threads after 1 second
                clientSocket.setSoTimeout(1000);
                threadPool.submit(() -> handleClient(clientSocket));
            } catch (IOException e) {
                if (!running) break;
            }
        }
    }

    private void handleClient(Socket socket) {
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

                    AeroTTLEntry entry = new AeroTTLEntry(val, ttl);
                    cache.put(key, entry);
                    wal.logPut(key, val);
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