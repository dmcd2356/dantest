
class SimpleTest {

    public SimpleTest() {
    }
 
    public void dummy() {
    }
 
    public void addNums() {
        int x = 7;
        int y = 11;
        int z = x + y;
    }
 
    public int testSimpleReturn() {
        return 31;
    }
 
    public void testSimpleParameter(int x) {
    }
 
    public int testParameterAndReturn(int x) {
        int y = -1;
        int z = x + y;
        return z;
    }
 
    public void testNestedCall(SimpleTest t) {
        t.addNums();
    }
 
    public static void main(String[] args) {
        System.out.println("!EXPECTED+ II SimpleTest.main SimpleTest.dummy");
        System.out.println("!EXPECTED+ II SimpleTest.main SimpleTest.addNums");
        System.out.println("!EXPECTED+ II SimpleTest.main SimpleTest.testSimpleReturn");
        System.out.println("!EXPECTED+ II SimpleTest.main SimpleTest.testSimpleParameter");
        System.out.println("!EXPECTED  II SimpleTest.main SimpleTest.testParameterAndReturn");

        SimpleTest t = new SimpleTest();
        t.dummy();
        t.addNums();
        t.testSimpleReturn();
        t.testSimpleParameter(3);
        t.testParameterAndReturn(1);

        System.out.println("!TESTEXIT");
    }
}
