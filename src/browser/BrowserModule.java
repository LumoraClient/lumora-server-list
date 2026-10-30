package dev.lumora.core.browser;

import dev.lumora.api.addon.AddonContext;
import dev.lumora.api.addon.LumoraAddon;
import dev.lumora.api.config.BooleanSetting;
import dev.lumora.api.config.SettingsGroup;
import dev.lumora.api.screen.AddonView;
import dev.lumora.core.Platform;

/**
 * Built-in module "Server- & Weltliste": Lumora's own multiplayer and singleplayer screens instead of Minecraft's
 * (layout like LiquidBounce's: account card, search, filters, server cards with ping). The version adapters call
 * {@link #replace(boolean)} where Minecraft opens its screen; null keeps the vanilla screen (module off, setting off,
 * or "Klassisch" was chosen).
 */
public final class BrowserModule implements LumoraAddon {
    private static volatile BrowserModule active;
    private static volatile boolean bypass;

    private final Platform platform;
    BooleanSetting multiplayer;
    BooleanSetting singleplayer;

    public BrowserModule(Platform platform) {
        this.platform = platform;
    }

    @Override
    public void onInitialize(AddonContext context) {
        SettingsGroup s = context.configuration().settings();
        multiplayer = (BooleanSetting) s.toggle("multiplayer", true).name("lumora.extras.browser.multiplayer");
        singleplayer = (BooleanSetting) s.toggle("singleplayer", true).name("lumora.extras.browser.singleplayer");
    }

    @Override
    public void onEnable(AddonContext context) {
        active = this;
    }

    @Override
    public void onDisable(AddonContext context) {
        active = null;
    }

    /** Whether the module is on and the version adapter supports it (smoke test). */
    public static boolean enabled() {
        BrowserModule m = active;
        return m != null && m.platform.browsers() != null && m.multiplayer.get() && m.singleplayer.get();
    }

    /**
     * Smoke test: runs one of the version-specific actions from the main menu ("classic", "create" or
     * "join:&lt;address&gt;"), so every version adapter is exercised once.
     */
    public static void smoke(String action) {
        BrowserModule m = active;
        BrowserPlatform game = m == null ? null : m.platform.browsers();
        if (game == null) {
            return;
        }
        if (action.equals("classic")) {
            game.openClassic(true);
        } else if (action.equals("create")) {
            game.createWorld();
        } else if (action.startsWith("join:")) {
            game.join(action.substring(5), "Lumora Smoke");
        }
    }

    /** The next vanilla multiplayer or singleplayer screen is shown as is (button "Klassisch"). */
    public static void bypassOnce() {
        bypass = true;
    }

    /** The Lumora view to show instead of Minecraft's multiplayer / singleplayer screen, or null for vanilla. */
    public static AddonView replace(boolean multiplayerScreen) {
        if (bypass) {
            bypass = false;
            return null;
        }
        BrowserModule m = active;
        if (m == null) {
            return null;
        }
        BrowserPlatform game = m.platform.browsers();
        if (game == null || !(multiplayerScreen ? m.multiplayer.get() : m.singleplayer.get())) {
            return null;
        }
        return multiplayerScreen ? new ServerBrowserView(m.platform, game) : new WorldBrowserView(m.platform, game);
    }
}
