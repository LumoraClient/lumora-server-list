package dev.lumora.client.mixin;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The screen the multiplayer screen returns to (Lumora server list). */
@Mixin(MultiplayerScreen.class)
public interface JoinMultiplayerScreenAccessor {
    @Accessor("parent")
    Screen lumora$lastScreen();
}
