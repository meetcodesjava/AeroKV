package day01;


public abstract class ValStore {
    /*
    This method inserts or updates a key-value pair in memory.
    @param key the text identifier for the data.
    @param val any Object data to store.
    */
    public abstract void put(String key, Object val);

    /*
    Retrieves the value associated with the given key.
    @param key the identifier.
    @param the stored Object or null if the key is missing.
    */
    public abstract Object get(String key);
}
