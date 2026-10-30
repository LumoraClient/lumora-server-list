package dev.lumora.client.mixin;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The screen the singleplayer screen returns to (Lumora world list). */
@Mixin(SelectWorldScreen.class)
public interface SelectWorldScreenAccessor {
    @Accessor("lastScreen")
    Screen lumora$lastScreen();
}
