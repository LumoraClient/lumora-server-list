package dev.lumora.client.mixin;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The screen the singleplayer screen returns to (Lumora world list). */
@Mixin(SelectWorldScreen.class)
public interface SelectWorldScreenAccessor {
    @Accessor("parent")
    Screen lumora$lastScreen();
}
