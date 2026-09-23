package day04;

import day03.AeroLRU;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

public class AeroConcurrentLRU {

    // Internal wrapper to support Time-To-Live (TTL) and total-memory accounting
    private static class CacheEntry {
        final Object value;
        final long expiresAtMillis;
        final long sizeBytes;

        CacheEntry(Object value, long ttlMillis) {
            this.value = value;
            this.expiresAtMillis = (ttlMillis > 0) ? (System.currentTimeMillis() + ttlMillis) : -1;
            this.sizeBytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8).length;
        }

        boolean isExpired() {
            return expiresAtMillis != -1 && System.currentTimeMillis() > expiresAtMillis;
        }
    }

    private final AeroLRU cache;
    private final AeroLockManager lockManager;

    // <=0 means "no byte budget enforced" (entry-count capacity still applies).
    private final long maxTotalBytes;
    private final AtomicLong totalBytes = new AtomicLong(0);

    // Guards total-byte accounting and byte-budget eviction only. Note: the
    // per-key stripe locks (lockManager) do NOT protect the single shared
    // AeroLRU's internal linked-list structure across different stripes —
    // that is a pre-existing concurrency gap in this cache, unrelated to
    // memory accounting, and out of scope for this change. memoryLock is
    // nested inside the stripe lock here purely to keep totalBytes correct.
    private final Object memoryLock = new Object();

    public AeroConcurrentLRU(int capacity, int numStripes) {
        this(capacity, numStripes, 0);
    }

    public AeroConcurrentLRU(int capacity, int numStripes, long maxTotalBytes) {
        this.cache = new AeroLRU(capacity);
        this.lockManager = new AeroLockManager(numStripes);
        this.maxTotalBytes = maxTotalBytes;
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
                synchronized (memoryLock) {
                    cache.remove(key);
                    totalBytes.addAndGet(-entry.sizeBytes);
                }
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
     * Put with specific Time-To-Live in milliseconds. Enforces the total
     * byte budget (if configured) by evicting least-recently-used entries
     * first, on top of the existing entry-count capacity in AeroLRU.
     */
    public void put(String key, Object val, long ttlMillis) {
        Object lock = lockManager.getLock(key);
        synchronized (lock) {
            synchronized (memoryLock) {
                CacheEntry newEntry = new CacheEntry(val, ttlMillis);
                CacheEntry existing = (CacheEntry) cache.get(key);
                long oldSize = (existing != null) ? existing.sizeBytes : 0;

                if (maxTotalBytes > 0) {
                    long projected = totalBytes.get() - oldSize + newEntry.sizeBytes;
                    while (projected > maxTotalBytes) {
                        String victimKey = cache.peekTailKey();
                        if (victimKey == null || victimKey.equals(key)) break;
                        long victimSize = sizeOf(cache.peekTailValue());
                        cache.remove(victimKey);
                        totalBytes.addAndGet(-victimSize);
                        projected -= victimSize;
                    }
                }

                // AeroLRU also evicts by entry count internally; account for
                // that eviction too so totalBytes never drifts out of sync.
                if (existing == null && cache.getSize() >= cache.getCapacity()) {
                    String victimKey = cache.peekTailKey();
                    if (victimKey != null && !victimKey.equals(key)) {
                        long victimSize = sizeOf(cache.peekTailValue());
                        cache.remove(victimKey);
                        totalBytes.addAndGet(-victimSize);
                    }
                }

                cache.put(key, newEntry);
                totalBytes.addAndGet(newEntry.sizeBytes - oldSize);
            }
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
            synchronized (memoryLock) {
                long oldSize = (current != null) ? current.sizeBytes : 0;
                CacheEntry newEntry = new CacheEntry(val, ttlMillis);
                cache.put(key, newEntry);
                totalBytes.addAndGet(newEntry.sizeBytes - oldSize);
            }
            return true;
        }
    }

    /**
     * Explicit removal of a key.
     */
    public void remove(String key) {
        Object lock = lockManager.getLock(key);
        synchronized (lock) {
            synchronized (memoryLock) {
                Object raw = cache.get(key);
                if (raw instanceof CacheEntry) {
                    totalBytes.addAndGet(-((CacheEntry) raw).sizeBytes);
                }
                cache.remove(key);
            }
        }
    }

    /** Current best-effort total size, in bytes, of all live values. */
    public long getTotalBytes() {
        return totalBytes.get();
    }

    private static long sizeOf(Object raw) {
        return (raw instanceof CacheEntry) ? ((CacheEntry) raw).sizeBytes : 0;
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
