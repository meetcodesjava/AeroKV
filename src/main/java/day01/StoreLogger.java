package day01;

public class StoreLogger{
    public void logInfo(String message){
        System.out.println("[AeroKV INFO] " + message);
    }
    public void logError(String message){
        System.out.println("[AeroKV ERROR] " + message);
    }
}