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

    // Without this, code that measures entries by their default toString()
    // (e.g. AeroConcurrentLRU's byte-budget accounting) sees the wrapper
    // object's identity string instead of the actual stored content.
    @Override
    public String toString(){
        return String.valueOf(value);
    }
}
