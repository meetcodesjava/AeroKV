package day04;

import day03.AeroLRU;

public class AeroConcurrentLRU {

    // Internal wrapper to support Time-To-Live (TTL)
    private static class CacheEntry {
        final Object value;
        final long expiresAtMillis;

        CacheEntry(Object value, long ttlMillis) {
            this.value = value;
            this.expiresAtMillis = (ttlMillis > 0) ? (System.currentTimeMillis() + ttlMillis) : -1;
        }

        boolean isExpired() {
            return expiresAtMillis != -1 && System.currentTimeMillis() > expiresAtMillis;
        }
    }

    private final AeroLRU cache;
    private final AeroLockManager lockManager;

    public AeroConcurrentLRU(int capacity, int numStripes) {
        this.cache = new AeroLRU(capacity);
        this.lockManager = new AeroLockManager(numStripes);
    }

    /**
     * Thread-safe get with lazy TTL expiration check.
     */
    public Object get(String key) {
        Object lock = lockManager.getLock(key);
        synchronized (lock) {
            CacheEntry entry = (CacheEntry) cache.get(key);
            if (entry == null) {
                return null;
            }
            if (entry.isExpired()) {
                cache.remove(key);
                return null;
            }
            return entry.value;
        }
    }

    /**
     * Standard put without expiration (lives until evicted by LRU).
     */
    public void put(String key, Object val) {
        put(key, val, -1);
    }

    /**
     * Put with specific Time-To-Live in milliseconds.
     */
    public void put(String key, Object val, long ttlMillis) {
        Object lock = lockManager.getLock(key);
        synchronized (lock) {
            cache.put(key, new CacheEntry(val, ttlMillis));
        }
    }

    /**
     * Atomic put-if-absent (returns true if acquired, false if already taken).
     */
    public boolean putIfAbsent(String key, Object val, long ttlMillis) {
        Object lock = lockManager.getLock(key);
        synchronized (lock) {
            CacheEntry current = (CacheEntry) cache.get(key);
            if (current != null && !current.isExpired()) {
                return false; // Key is already actively held
            }
            cache.put(key, new CacheEntry(val, ttlMillis));
            return true;
        }
    }

    /**
     * Explicit removal of a key.
     */
    public void remove(String key) {
        Object lock = lockManager.getLock(key);
        synchronized (lock) {
            cache.remove(key);
        }
    }

    /**
     * Snapshot of all live (non-expired) entries as key -> raw stored value,
     * for WAL compaction. Only safe to call while traffic is quiesced
     * (startup/shutdown), since it does not hold every stripe lock at once.
     */
    public java.util.List<java.util.Map.Entry<String, Object>> snapshotLiveEntries() {
        java.util.List<java.util.Map.Entry<String, Object>> out = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Object> e : cache.snapshotEntries()) {
            CacheEntry ce = (CacheEntry) e.getValue();
            if (ce == null || ce.isExpired()) continue;
            out.add(java.util.Map.entry(e.getKey(), ce.value));
        }
        return out;
    }
}