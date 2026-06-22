import java.lang.reflect.Method;

public class PrintScreenMethods {
    public static void main(String[] args) throws Exception {
        System.out.println("====== SCREEN METHODS ======");
        for (Method m : net.minecraft.client.gui.screens.Screen.class.getMethods()) {
            if (m.getName().toLowerCase().contains("mouse") || m.getName().toLowerCase().contains("render")) {
                System.out.println(m.getName() + " : " + java.util.Arrays.toString(m.getParameterTypes()));
            }
        }
    }
}
