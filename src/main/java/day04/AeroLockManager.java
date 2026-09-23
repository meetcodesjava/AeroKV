package day04;

public class AeroLockManager {
    private final Object[] locks;
    private final int numStripes;

    public AeroLockManager(int numStripes) {
        this.numStripes = numStripes;
        this.locks = new Object[numStripes];
        for (int i = 0; i < numStripes; i++) {
            this.locks[i] = new Object();
        }
    }

    /**
     * Maps a given key to a specific lock stripe using its hash code.
     */
    public Object getLock(String key) {
        int hash = key.hashCode();
        int stripeIndex = (hash % numStripes + numStripes) % numStripes;
        return locks[stripeIndex];
    }
}