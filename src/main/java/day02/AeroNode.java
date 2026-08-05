package day02;

public class AeroNode {
    String key;
    Object val;  //changed datatype from int to Object
    AeroNode nextNode;

    public AeroNode(String key, Object val){
        this.key=key;
        this.val=val;
        this.nextNode=null;
    }
}
