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
    private Thread writerThread;
    private volatile boolean running;

    public AeroWAL(String logFilePath){
        this.logFilePath=logFilePath;
        this.logQueue=new LinkedBlockingQueue<>();
        this.running=true;
        // Writer thread is NOT started here: it must not open the log file
        // for append until after recover()/compact() have finished reading
        // and rewriting it. Call start() once those are done.
    }

    /**
     * Begins asynchronously flushing queued writes to disk. Must be called
     * after recover() and compact(), so the append-mode file handle is only
     * opened once the log file has reached its final startup state.
     */
    public void start(){
        if(writerThread != null) return;
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
                if(parts.length==0) continue;
                String op = parts[0].trim();

                if(parts.length>=3 && ("PUT".equalsIgnoreCase(op) || "SET".equalsIgnoreCase(op))){
                    String key=parts[1].trim();
                    String val=parts[2].trim();

                    cache.put(key, new AeroTTLEntry(val, 0));
                    count++;
                }
                else if(parts.length>=2 && "DEL".equalsIgnoreCase(op)){
                    String key=parts[1].trim();
                    cache.remove(key);
                    count++;
                }
            }
            System.out.println("Recovery Complete! Replayed " + count + " log entries into cache.");
        }
        catch(IOException e){
            System.err.println("Warning: Error during WAL recovery: " + e.getMessage());
        }
    }

    /**
     * Rewrites the log file to contain only the current live (non-expired)
     * key-value pairs as a single SET per key, discarding the accumulated
     * history of overwrites, deletes, and expired entries. Must run after
     * recover() and before start(), while no writer thread holds the file.
     */
    public void compact(AeroConcurrentLRU cache){
        File logFile=new File(logFilePath);
        if(!logFile.exists()) return;

        File tmpFile=new File(logFilePath + ".compact.tmp");
        long before = logFile.length();
        int written = 0;

        try(BufferedWriter writer=new BufferedWriter(new FileWriter(tmpFile, false))){
            for(java.util.Map.Entry<String, Object> e : cache.snapshotLiveEntries()){
                Object rawVal = e.getValue();
                String val = (rawVal instanceof AeroTTLEntry)
                        ? String.valueOf(((AeroTTLEntry) rawVal).getValue())
                        : String.valueOf(rawVal);
                writer.write("SET," + e.getKey() + "," + val + "\n");
                written++;
            }
        }
        catch(IOException e){
            System.err.println("Warning: WAL compaction failed, keeping existing log: " + e.getMessage());
            tmpFile.delete();
            return;
        }

        if(!logFile.delete() || !tmpFile.renameTo(logFile)){
            System.err.println("Warning: Could not swap in compacted WAL file; keeping pre-compaction log.");
            tmpFile.delete();
            return;
        }

        long after = logFile.length();
        System.out.println("WAL compacted: " + written + " live entries, " + before + " -> " + after + " bytes.");
    }


    public void logPut(String key, Object value){
        if(!running) return;

        String logLine="SET," + key + "," + value + "\n";
        logQueue.offer(logLine);
    }

    public void logDelete(String key){
        if(!running) return;

        String logLine="DEL," + key + "\n";
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
