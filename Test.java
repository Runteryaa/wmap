import net.minecraft.client.KeyMapping;
public class Test {
    public static void main(String[] args) {
        for (java.lang.reflect.Constructor<?> c : KeyMapping.class.getConstructors()) {
            System.out.println(c);
        }
    }
}
