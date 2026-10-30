package dev.lumora.client.mixin;

import dev.lumora.api.screen.AddonView;
import dev.lumora.client.gui.AddonViewScreen;
import dev.lumora.core.browser.BrowserModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shows the Lumora server and world lists where Minecraft opens its multiplayer or singleplayer screen (module
 * "Server- & Weltliste"). Exact class check, so screens of other mods that extend them stay as they are; "Klassisch"
 * opens the vanilla screen once.
 */
@Mixin(MinecraftClient.class)
public abstract class BrowserScreenMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void lumora$browser(Screen screen, CallbackInfo ci) {
        if (screen == null) {
            return;
        }
        boolean multiplayer = screen.getClass() == MultiplayerScreen.class;
        if (!multiplayer && screen.getClass() != SelectWorldScreen.class) {
            return;
        }
        AddonView view;
        try {
            view = BrowserModule.replace(multiplayer);
        } catch (RuntimeException | LinkageError e) {
            view = null; // a broken list must never block the vanilla screen
        }
        if (view == null) {
            return;
        }
        Screen parent = multiplayer ? ((JoinMultiplayerScreenAccessor) screen).lumora$lastScreen()
                : ((SelectWorldScreenAccessor) screen).lumora$lastScreen();
        ci.cancel();
        ((MinecraftClient) (Object) this).setScreen(AddonViewScreen.create(parent != null ? parent : new TitleScreen(), view));
    }
}
