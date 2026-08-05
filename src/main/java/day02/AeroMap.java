package day02;

import day01.ValStore;


public class AeroMap extends ValStore{
    private AeroNode[] arr;
    private int capacity;
    private int size;
    private final double LOAD_FACTOR_THRESHOLD=0.75;

    public AeroMap(){
        this.capacity=16;
        this.arr=new AeroNode[this.capacity];
        this.size=0;
    }


    public int getIndex(String key){
        int hash = key.hashCode();
        return hash & (capacity-1);
    }

    @Override
    public void put(String key, Object val){
        //to check, if we need to scale up the size of an array before adding new element!
        if((double)(size+1)/capacity > LOAD_FACTOR_THRESHOLD)
            rehash();

        int idx = getIndex(key);

        //case A: if bucket is empty, inserting new node!
        if(arr[idx]==null){
            arr[idx]=new AeroNode(key, val);
            size++;
            return;
        }

        //case B: Collision chain exists, travesrse it!
        AeroNode curr = arr[idx];
        while (true) { 

            //checling if key already exists, update its value
            if(curr.key.equals(key)){
                curr.val=val;
                return;
            }

            //reached the end of chain, no key found, appending new node here
            if(curr.nextNode==null){
                curr.nextNode = new AeroNode(key, val);
                size++;
                return;
            }
            curr=curr.nextNode;
        }
    }


    @Override
    public Object get(String key){
        int idx=getIndex(key);
        AeroNode curr = arr[idx];

        //traversing the L.L at current bucket idx
        while(curr!=null){
            if(curr.key.equals(key)){
                return curr.val; //key found returning the value
            }
            curr=curr.nextNode;  //changing curr to the next node
        }
        return null; //key not found in that L.L
    }





    private void rehash(){
        int oldCapacity=this.capacity;
        AeroNode[] oldArr=this.arr;

        this.capacity=oldCapacity*2;  //doubling the old capacity
        this.arr=new AeroNode[this.capacity];
        this.size=0;  //reseting the size as put() will increment during migration

        //migrating older nodes to the new larger bucket structure
        for(int i=0; i<oldCapacity; i++){
            AeroNode curr=oldArr[i];
            while(curr!=null){
                put(curr.key, curr.val);
                curr=curr.nextNode;
            }
        }
    }
}



