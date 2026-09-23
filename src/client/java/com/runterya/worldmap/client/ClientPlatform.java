package com.runterya.worldmap.client;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Bridges small vanilla client API differences between the supported game versions. */
public final class ClientPlatform {
    private static final String SOURCE_NAMESPACE = "official";
    private static final MappingResolver MAPPINGS = FabricLoader.getInstance().getMappingResolver();

    private ClientPlatform() {}

    public static KeyMapping createKeyMapping(String translationKey, boolean mapKey, KeyMapping.Category category) {
        try {
            int keyCode = getInputConstant(mapKey ? "KEY_M" : "KEY_B");
            for (Constructor<?> constructor : KeyMapping.class.getConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length == 3 && parameters[0] == String.class && parameters[1] == int.class
                    && parameters[2] == KeyMapping.Category.class) {
                    return KeyMappingHelper.registerKeyMapping((KeyMapping) constructor.newInstance(translationKey, keyCode, category));
                }
                if (parameters.length == 4 && parameters[0] == String.class && parameters[2] == int.class
                    && parameters[3] == KeyMapping.Category.class && parameters[1].isEnum()) {
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    Object keyboardType = Enum.valueOf((Class<? extends Enum>) parameters[1].asSubclass(Enum.class), "KEYSYM");
                    return KeyMappingHelper.registerKeyMapping((KeyMapping) constructor.newInstance(translationKey, keyboardType, keyCode, category));
                }
            }
            throw new NoSuchMethodException("No supported KeyMapping constructor was found");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not create a key mapping for this Minecraft version", e);
        }
    }

    private static int getInputConstant(String fieldName) throws ReflectiveOperationException {
        String className = "com.mojang.blaze3d.platform.InputConstants";
        Class<?> inputConstants = Class.forName(MAPPINGS.mapClassName(SOURCE_NAMESPACE, className));
        String runtimeField = MAPPINGS.mapFieldName(SOURCE_NAMESPACE, className, fieldName, "I");
        Field field = inputConstants.getField(runtimeField);
        return field.getInt(null);
    }

    public static void setScreen(Minecraft minecraft, Screen screen) {
        try {
            Class<?> screenClass = mappedClass("net.minecraft.client.gui.screens.Screen");
            String screenDescriptor = "(Lnet/minecraft/client/gui/screens/Screen;)V";

            try {
                Method setScreen = minecraft.getClass().getMethod(mappedMethod(
                    "net.minecraft.client.Minecraft", "setScreen", screenDescriptor
                ), screenClass);
                setScreen.invoke(minecraft, screen);
                return;
            } catch (NoSuchMethodException ignored) {
                // 26.2 moved screen management onto Minecraft.gui.
            }

            String minecraftName = "net.minecraft.client.Minecraft";
            String guiName = "net.minecraft.client.gui.Gui";
            String guiDescriptor = "Lnet/minecraft/client/gui/Gui;";
            Field guiField = minecraft.getClass().getField(MAPPINGS.mapFieldName(
                SOURCE_NAMESPACE, minecraftName, "gui", guiDescriptor
            ));
            Object gui = guiField.get(minecraft);
            Method setScreen = mappedClass(guiName).getMethod(
                mappedMethod(guiName, "setScreen", screenDescriptor), screenClass
            );
            setScreen.invoke(gui, screen);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not change screens for this Minecraft version", e);
        }
    }

    private static Class<?> mappedClass(String officialName) throws ClassNotFoundException {
        return Class.forName(MAPPINGS.mapClassName(SOURCE_NAMESPACE, officialName));
    }

    private static String mappedMethod(String owner, String name, String descriptor) {
        return MAPPINGS.mapMethodName(SOURCE_NAMESPACE, owner, name, descriptor);
    }
}
