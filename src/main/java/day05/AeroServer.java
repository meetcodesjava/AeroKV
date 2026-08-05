package day05;

import day04.AeroConcurrentLRU;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
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


    public void start(){
        System.out.println("AeroKV started on port " + serverSocket.getLocalPort());
        while (running) {
            try {
                Socket clienSocket=serverSocket.accept();
                threadPool.submit(() -> handleClient(clienSocket)); 
            } catch (IOException e) {
                if(!running) break;
            }
        }
    }


    private void handleClient(Socket socket){
        try(
            BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out=new PrintWriter(socket.getOutputStream(), true);
        ){
            String line;
            while((line=in.readLine())!=null){
                System.out.println("📩 Server received command: " + line);
                String[] parts=line.split(",");
                String command=parts[0].toUpperCase();

                if ("SET".equals(command) && parts.length>=4) {
                    String key = parts[1].trim();
                    String val = parts[2].trim();
                    long ttl = Long.parseLong(parts[3].trim());

                    AeroTTLEntry entry=new AeroTTLEntry(val,ttl);
                    cache.put(key,entry);
                    wal.logPut(key, val);
                    out.println("OK");
                }
                else if("GET".equals(command) && parts.length>=2){
                    String key = parts[1].trim();
                    Object rawEntry=cache.get(key);

                    if(rawEntry instanceof AeroTTLEntry){
                        AeroTTLEntry entry=(AeroTTLEntry) rawEntry;

                        if(entry.isExpired()){
                            cache.put(key, null);
                            out.println("ERR_EXPIRED");
                        }
                        else{
                            out.println("VALUE, " + entry.getValue());
                        }
                    }
                    else{
                        out.println("ERR_NOT_FOUND");
                    }
                }
                else{
                    out.println("ERR_UNKNOWN_COMMAND");
                }
            }
        }
        catch(IOException e){
            //client disconnected
        }
        finally{
            try {
                socket.close();
            } 
            catch (IOException ignored) {
            }
        }
    }


    public void stop() throws IOException{
        this.running=false;
        this.serverSocket.close();
        this.wal.shutdown();
        this.threadPool.shutdown();
    }
}
