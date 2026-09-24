package com.runterya.worldmap;

import com.runterya.worldmap.gui.WorldMapConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public final class WorldMapModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return WorldMapConfigScreen::new;
    }
}
