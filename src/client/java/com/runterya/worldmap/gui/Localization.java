package com.runterya.worldmap.gui;

import com.runterya.worldmap.WorldMapConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Small helper for the mod's client-side translation keys. */
public final class Localization {
    private static final String ENGLISH = "en_us";
    private static final String TURKISH = "tr_tr";
    private static final ConcurrentMap<String, Map<String, String>> LANGUAGES = new ConcurrentHashMap<>();

    private Localization() {}

    public static MutableComponent component(String key, Object... arguments) {
        String locale = selectedLocale();
        String translationKey = "worldmap." + key;
        String translation = language(locale).get(translationKey);
        if (translation == null) translation = language(ENGLISH).getOrDefault(translationKey, key);
        return Component.literal(format(translation, arguments));
    }

    public static String text(String key, Object... arguments) {
        return component(key, arguments).getString();
    }

    private static String selectedLocale() {
        return switch (WorldMapConfig.languagePreference()) {
            case ENGLISH -> ENGLISH;
            case TURKISH -> TURKISH;
            case MINECRAFT -> {
                String minecraftLocale = Minecraft.getInstance().getLanguageManager().getSelected()
                    .toLowerCase(Locale.ROOT);
                yield language(minecraftLocale).isEmpty() ? ENGLISH : minecraftLocale;
            }
        };
    }

    private static Map<String, String> language(String locale) {
        return LANGUAGES.computeIfAbsent(locale, Localization::loadLanguage);
    }

    private static Map<String, String> loadLanguage(String locale) {
        Identifier id = Identifier.fromNamespaceAndPath("worldmap", "lang/" + locale + ".json");
        var resource = Minecraft.getInstance().getResourceManager().getResource(id);
        if (resource.isEmpty()) return Map.of();

        try (var reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            Map<String, String> translations = new ConcurrentHashMap<>();
            json.entrySet().forEach(entry -> {
                if (entry.getValue().isJsonPrimitive()) {
                    translations.put(entry.getKey(), entry.getValue().getAsString());
                }
            });
            return Map.copyOf(translations);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private static String format(String pattern, Object... arguments) {
        if (arguments == null || arguments.length == 0) return pattern;
        StringBuilder result = new StringBuilder(pattern.length() + arguments.length * 8);
        int from = 0;
        for (Object argument : arguments) {
            int placeholder = pattern.indexOf("%s", from);
            if (placeholder < 0) break;
            result.append(pattern, from, placeholder);
            result.append(argument instanceof Component component ? component.getString() : String.valueOf(argument));
            from = placeholder + 2;
        }
        result.append(pattern, from, pattern.length());
        return result.toString();
    }
}
