package day05;

public class AeroTTLEntry {
    private final Object value;
    private final long createdAt;
    private final long ttlMillis;


    public AeroTTLEntry(Object value, long ttlMillis){
        this.value=value;
        this.ttlMillis=ttlMillis;
        this.createdAt=System.currentTimeMillis();
    }


    public boolean isExpired(){
        if(ttlMillis<=0)
            return false;

        long timeElapsed=System.currentTimeMillis()-createdAt;
        return timeElapsed>ttlMillis;
    }

    public Object getValue(){
        return value;
    }

    public long getCreatedAt(){
        return createdAt;
    }

    public long getTtlMillis(){
        return ttlMillis;
    }
}
