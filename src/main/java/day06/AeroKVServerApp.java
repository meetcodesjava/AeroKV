package day06;

import day05.AeroServer;

public class AeroKVServerApp {
    public static void main(String[] args) {
        
        int port=8080;
        String logFilePath="D:\\AeroKV_logs";
        int cacheCapacity=1000;
        int lockStripes=16;

        try {
            AeroServer server=new AeroServer(port, cacheCapacity, lockStripes, logFilePath);

            server.start();
        } catch (Exception e) {
            // TODO: handle exception
            System.err.println("Failed to start AeroKV server: " + e.getMessage());
        }
    }
}
