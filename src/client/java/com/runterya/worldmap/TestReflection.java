package com.runterya.worldmap;
import net.minecraft.client.multiplayer.ClientLevel;
import java.lang.reflect.Method;

public class TestReflection {
    public static void test() {
        for (Method m : ClientLevel.class.getDeclaredMethods()) {
            System.out.println("METHOD: " + m.getName() + " " + java.util.Arrays.toString(m.getParameterTypes()));
        }
    }
}
