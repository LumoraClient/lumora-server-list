package dev.lumora.client.api;

import dev.lumora.core.browser.BrowserModule;
import dev.lumora.core.browser.BrowserPlatform;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/** Starting servers and worlds for the Lumora server and world lists (1.20.4). */
final class McBrowsers implements BrowserPlatform {
    @Override
    public void join(String address, String name) {
        Minecraft mc = Minecraft.getInstance();
        ConnectScreen.startConnecting(mc.screen, mc, ServerAddress.parseString(address), serverData(mc, address, name), false);
    }

    /** The saved entry (keeps its resource pack choice) or a new one for direct connect. */
    private static ServerData serverData(Minecraft mc, String address, String name) {
        ServerList list = new ServerList(mc);
        list.load();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).ip.equalsIgnoreCase(address)) {
                return list.get(i);
            }
        }
        return new ServerData(name, address, ServerData.Type.OTHER);
    }

    @Override
    public void playWorld(String folder, String name) {
        Minecraft mc = Minecraft.getInstance();
        Screen back = mc.screen;
        mc.createWorldOpenFlows().checkForBackupAndLoad(folder, () -> mc.setScreen(back));
    }

    @Override
    public void createWorld() {
        Minecraft mc = Minecraft.getInstance();
        Screen back = mc.screen;
        CreateWorldScreen.openFresh(mc, back);
    }

    @Override
    public void openClassic(boolean multiplayer) {
        Minecraft mc = Minecraft.getInstance();
        BrowserModule.bypassOnce();
        mc.setScreen(multiplayer ? new JoinMultiplayerScreen(mc.screen) : new SelectWorldScreen(mc.screen));
    }

    @Override
    public int protocolVersion() {
        return SharedConstants.getProtocolVersion();
    }
}
