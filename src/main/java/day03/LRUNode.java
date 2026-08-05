package day03;

public class LRUNode {
    String key;
    Object val;
    LRUNode prev;
    LRUNode next;

    public LRUNode(String key, Object val) {
        this.key = key;
        this.val = val;
        this.prev = null;
        this.next = null;
    }
    
    
}
