package day06;

import day05.AeroServer;
import java.io.File;

public class AeroKVServerApp {
    // Config resolution order (highest priority first): CLI args, env vars,
    // built-in defaults. Nothing about the deployment target (port, cache
    // size, WAL location) is baked into source anymore.
    public static void main(String[] args) {

        int port = intConfig(arg(args, 0), "AEROKV_PORT", 8080);
        int cacheCapacity = intConfig(arg(args, 1), "AEROKV_CAPACITY", 1000);
        int lockStripes = intConfig(arg(args, 2), "AEROKV_STRIPES", 16);
        String logFilePath = strConfig(arg(args, 3), "AEROKV_LOG_PATH", defaultLogPath());

        System.out.println("Starting AeroKV with port=" + port + ", capacity=" + cacheCapacity
                + ", stripes=" + lockStripes + ", logFilePath=" + logFilePath);

        try {
            AeroServer server = new AeroServer(port, cacheCapacity, lockStripes, logFilePath);
            server.start();
        } catch (Exception e) {
            System.err.println("Failed to start AeroKV server: " + e.getMessage());
        }
    }

    private static String defaultLogPath() {
        return System.getProperty("java.io.tmpdir", ".") + File.separator + "aerokv.log";
    }

    private static String arg(String[] args, int idx) {
        return idx < args.length ? args[idx] : null;
    }

    private static int intConfig(String argValue, String envVar, int fallback) {
        String raw = argValue != null ? argValue : System.getenv(envVar);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            System.err.println("Invalid value for " + envVar + " (\"" + raw + "\"), using default " + fallback);
            return fallback;
        }
    }

    private static String strConfig(String argValue, String envVar, String fallback) {
        String raw = argValue != null ? argValue : System.getenv(envVar);
        return (raw == null || raw.isBlank()) ? fallback : raw.trim();
    }
}
