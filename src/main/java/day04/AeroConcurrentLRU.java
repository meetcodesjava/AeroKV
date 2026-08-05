package day04;

import day03.AeroLRU;

public class AeroConcurrentLRU {

    private final AeroLRU cache;
    private final AeroLockManager lockManager;

    public AeroConcurrentLRU(int capacity, int numStripes){
        this.cache=new AeroLRU(capacity);
        this.lockManager=new AeroLockManager(numStripes);
    }


    public Object get(String key){
        Object lock=lockManager.getLock(key);

        synchronized (lock) {
            return cache.get(key);
        }
    }


    public void put(String key, Object val){
        Object lock=lockManager.getLock(key);

        synchronized (lock) {
            cache.put(key, val);
        }
    }
}
