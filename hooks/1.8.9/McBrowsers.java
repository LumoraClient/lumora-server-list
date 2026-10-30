package dev.lumora.client.api;

import dev.lumora.core.browser.BrowserModule;
import dev.lumora.core.browser.BrowserPlatform;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;

/** Starting servers and worlds for the Lumora server and world lists (1.8.9). */
final class McBrowsers implements BrowserPlatform {
    private static final int PROTOCOL = 47;

    @Override
    public void join(String address, String name) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.setScreen(new ConnectScreen(mc.currentScreen, mc, serverInfo(mc, address, name)));
    }

    /** The saved entry (keeps its resource pack choice) or a new one for direct connect. */
    private static ServerInfo serverInfo(MinecraftClient mc, String address, String name) {
        ServerList list = new ServerList(mc);
        list.loadFile();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).address.equalsIgnoreCase(address)) {
                return list.get(i);
            }
        }
        return new ServerInfo(name, address, false);
    }

    @Override
    public void playWorld(String folder, String name) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getCurrentSave().levelExists(folder)) {
            mc.startIntegratedServer(folder, name, null);
        }
    }

    @Override
    public void createWorld() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.setScreen(new CreateWorldScreen(mc.currentScreen));
    }

    @Override
    public void openClassic(boolean multiplayer) {
        MinecraftClient mc = MinecraftClient.getInstance();
        BrowserModule.bypassOnce();
        mc.setScreen(multiplayer ? new MultiplayerScreen(mc.currentScreen) : new SelectWorldScreen(mc.currentScreen));
    }

    @Override
    public int protocolVersion() {
        return PROTOCOL;
    }
}
