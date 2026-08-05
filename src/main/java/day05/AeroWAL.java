package day05;

import day04.AeroConcurrentLRU;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;

public class AeroWAL {
    private final LinkedBlockingQueue<String> logQueue;
    private final String logFilePath;
    private final Thread writerThread;
    private volatile boolean running;

    public AeroWAL(String logFilePath){
        this.logFilePath=logFilePath;
        this.logQueue=new LinkedBlockingQueue<>();
        this.running=true;
        this.writerThread=new Thread(this::processLogQueue);
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }


    public void recover(AeroConcurrentLRU cache){
        File logFile=new File(logFilePath);
        if(!logFile.exists()){
            System.out.println("No Existing log file found. Staring fresh...");
            return;
        }

        System.out.println("Recovering data from Write-Ahead Log (" + logFilePath + ")...");
        int count=0;


        try(BufferedReader reader=new BufferedReader(new FileReader(logFile))){
            String line;
            while((line=reader.readLine()) != null){
                String[] parts=line.split(",");
                if(parts.length>=3 && ("PUT".equalsIgnoreCase(parts[0].trim()) || "SET".equalsIgnoreCase(parts[0].trim()))){
                    String key=parts[1].trim();
                    String val=parts[2].trim();

                    cache.put(key, new AeroTTLEntry(val, 0));
                    count++;
                }
            }
            System.out.println("Recovery Complete! Re-populated " + count + " items into cache.");
        }
        catch(IOException e){
            System.err.println("Warning: Error during WAL recovery: " + e.getMessage());
        }
    }


    public void logPut(String key, Object value){
        if(!running) return;

        String logLine="SET," + key + "," + value + "\n";
        logQueue.offer(logLine);
    }


    private void processLogQueue(){
        try(BufferedWriter writer=new BufferedWriter(new FileWriter(logFilePath, true))){
            while (running||!logQueue.isEmpty()) {
                String logLine=logQueue.take();
                writer.write(logLine);
                writer.flush();
            }
        }
        catch(IOException|InterruptedException e){
            Thread.currentThread().interrupt();
        }
    }


    public void shutdown(){
        this.running=false;
        logQueue.offer("SHUTDOWN\n");
    }
}
