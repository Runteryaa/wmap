package com.runterya.worldmap.gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

public class TestFields {
    public static void test() {
        for (Method m : net.minecraft.client.input.MouseButtonEvent.class.getDeclaredMethods()) {
            System.out.println("MBE METHOD: " + m.getName() + " " + m.getReturnType());
        }
    }
}
