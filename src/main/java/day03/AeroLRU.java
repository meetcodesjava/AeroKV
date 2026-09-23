package day03;

import day02.AeroMap;

public class AeroLRU {
    private final int capacity;
    private int size;
    private final AeroMap map;
    private LRUNode head;
    private LRUNode tail;

    public AeroLRU(int capacity) {
        this.capacity = capacity;
        this.size = 0;
        this.map = new AeroMap();
    }

    public Object get(String key) {
        LRUNode node = (LRUNode) map.get(key);
        if (node == null) {
            return null;
        }
        moveToHead(node);
        return node.val;
    }

    public void put(String key, Object val) {
        LRUNode existingNode = (LRUNode) map.get(key);
        if (existingNode != null) {
            // Update existing node without changing size or creating duplicates
            existingNode.val = val;
            moveToHead(existingNode);
        } else {
            // Only runs when key is completely new
            if (size >= capacity) {
                evictTail();
            }
            LRUNode newNode = new LRUNode(key, val);
            addToHead(newNode);
            map.put(key, newNode);
            size++;
        }
    }

    public void remove(String key) {
        LRUNode node = (LRUNode) map.get(key);
        if (node != null) {
            removeNode(node);
            map.put(key, null);
            size--;
        }
    }

    private void addToHead(LRUNode node) {
        node.next = head;
        node.prev = null;

        if (head != null) {
            head.prev = node;
        }
        head = node;

        if (tail == null) {
            tail = head;
        }
    }

    private void removeNode(LRUNode node) {
        if (node.prev != null) {
            node.prev.next = node.next;
        } else {
            head = node.next;
        }

        if (node.next != null) {
            node.next.prev = node.prev;
        } else {
            tail = node.prev;
        }
    }

    private void moveToHead(LRUNode node) {
        removeNode(node);
        addToHead(node);
    }

    private void evictTail() {
        if (tail == null) {
            return;
        }
        map.put(tail.key, null);
        removeNode(tail);
        size--;
    }

    public int getSize() {
        return size;
    }

    /**
     * Snapshot of all live entries (key -> raw stored value), for WAL
     * compaction. Not safe to call concurrently with writers; callers must
     * only use this while traffic is quiesced (e.g. startup/shutdown).
     */
    public java.util.List<java.util.Map.Entry<String, Object>> snapshotEntries() {
        java.util.List<java.util.Map.Entry<String, Object>> list = new java.util.ArrayList<>(size);
        LRUNode curr = head;
        while (curr != null) {
            list.add(java.util.Map.entry(curr.key, curr.val));
            curr = curr.next;
        }
        return list;
    }
}