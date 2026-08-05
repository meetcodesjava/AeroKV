package day04;

public class AeroLockManager {
    private final Object[] locks;
    private final int numStripes;

    public AeroLockManager(int numStripes){
        this.numStripes=numStripes;
        this.locks=new Object[numStripes];


        for(int i=0;i<numStripes;i++){
            locks[i]=new Object();
        }
    }

    public Object getLock(String key){
        if (key==null) {
            return locks[0];
        }


        int idx = Math.abs(key.hashCode()) % numStripes;
        return locks[idx];
    }
}
