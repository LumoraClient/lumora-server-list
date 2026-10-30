package dev.lumora.core.browser;

import dev.lumora.api.render.HudGraphics;
import dev.lumora.core.Platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Lumora multiplayer screen: the saved servers as cards with favicon, name, MOTD, players, version and ping, search,
 * "online only" and version filter, add / edit / delete / reorder and direct connect. Uses Minecraft's servers.dat, so
 * the vanilla screen (button "Klassisch", or ViaFabricPlus) shows the same list.
 */
public final class ServerBrowserView extends BrowserView {
    private static final ExecutorService PINGS = Icons.pool("Lumora server ping", 6);
    private static final int ICON = 24;
    private static boolean onlineOnly;
    private static boolean compatibleOnly;
    private static String lastDirect = "";

    private final Servers servers;
    private final Map<String, ServerPinger.Status> status = new ConcurrentHashMap<>();
    private final List<Servers.Entry> visible = new ArrayList<>();
    private String saveError;
    private long lastDiskCheck;

    public ServerBrowserView(Platform platform, BrowserPlatform game) {
        super(platform, game);
        Path file = platform.configDir().toAbsolutePath().getParent().resolve("servers.dat");
        this.servers = new Servers(file).load();
        for (Servers.Entry e : servers.entries()) {
            ping(e.address);
        }
    }

    private static String key(String address) {
        return address.strip().toLowerCase(Locale.ROOT);
    }

    private void ping(String address) {
        String k = key(address);
        if (status.containsKey(k) && status.get(k).error() == null && status.get(k).protocol() == Integer.MIN_VALUE) {
            return; // already running
        }
        status.put(k, pending());
        int protocol = game.protocolVersion();
        PINGS.execute(() -> status.put(k, ServerPinger.ping(address, protocol)));
    }

    private static ServerPinger.Status pending() {
        return new ServerPinger.Status("", Integer.MIN_VALUE, 0, 0, "", null, -1, null);
    }

    private static boolean isPending(ServerPinger.Status s) {
        return s == null || (s.error() == null && s.protocol() == Integer.MIN_VALUE);
    }

    private void refresh() {
        servers.load();
        status.clear();
        Icons.forget("srv:");
        for (Servers.Entry e : servers.entries()) {
            ping(e.address);
        }
    }

    private void save() {
        try {
            servers.save();
            saveError = null;
        } catch (IOException e) {
            saveError = t("Serverliste konnte nicht gespeichert werden", "Could not save the server list");
        }
    }

    @Override
    String title() {
        return t("Mehrspieler", "Multiplayer");
    }

    @Override
    int toolbar(HudGraphics g, int left, int right, int y, int h, int mx, int my) {
        int x = right;
        if (platform.modLoaded("viafabricplus")) {
            int w = g.textWidth("ViaFabricPlus") + 16;
            button(g, x - w, y, w, h, "ViaFabricPlus", Style.SECONDARY, mx, my, () -> game.openClassic(true));
            x -= w + 6;
        }
        String version = compatibleOnly ? platform.minecraftVersion() + " ✓" : t("Alle Versionen", "All versions");
        int vw = g.textWidth(version) + 24;
        button(g, x - vw, y, vw, h, version + " ▾", Style.SECONDARY, mx, my, () -> {
            compatibleOnly = !compatibleOnly;
            selected = -1;
        });
        x -= vw + 6;
        x -= toggle(g, x, y, h, t("Nur online", "Online only"), onlineOnly, mx, my, () -> {
            onlineOnly = !onlineOnly;
            selected = -1;
        }) + 6;
        return x;
    }

    @Override
    int rowCount() {
        long now = System.currentTimeMillis();
        if (now - lastDiskCheck > 1000 && dialog == null) {
            lastDiskCheck = now;
            if (servers.changedOnDisk()) {
                servers.load();
                for (Servers.Entry e : servers.entries()) {
                    if (!status.containsKey(key(e.address))) {
                        ping(e.address);
                    }
                }
            }
        }
        visible.clear();
        int protocol = game.protocolVersion();
        for (Servers.Entry e : servers.entries()) {
            ServerPinger.Status s = status.get(key(e.address));
            if (onlineOnly && (s == null || !s.ok() || isPending(s))) {
                continue;
            }
            if (compatibleOnly && (s == null || s.protocol() != protocol)) {
                continue;
            }
            if (!matches(e.name, e.address, s == null ? null : s.motd())) {
                continue;
            }
            visible.add(e);
        }
        if (selected >= visible.size()) {
            selected = visible.size() - 1;
        }
        return visible.size();
    }

    @Override
    String emptyText() {
        if (servers.entries().isEmpty()) {
            return t("Noch keine Server – füge einen mit „Hinzufügen“ hinzu", "No servers yet – add one with \"Add\"");
        }
        return t("Kein Server passt zu den Filtern", "No server matches the filters");
    }

    @Override
    void drawRow(HudGraphics g, int index, int x, int y, int w, int h, boolean hover, int mx, int my) {
        Servers.Entry e = visible.get(index);
        ServerPinger.Status s = status.get(key(e.address));
        int ix = x + 7;
        int iy = y + (h - ICON) / 2;
        byte[] png = s != null && s.favicon() != null ? s.favicon() : e.cachedIcon();
        int[] px = png == null ? null : Icons.get("srv:" + key(e.address) + ":" + Arrays.hashCode(png), ICON, () -> png);
        if (px != null) {
            Icons.draw(g, px, ICON, ix, iy);
        } else {
            g.roundedRect(ix, iy, ICON, ICON, 4, 0xFF2E2152);
            String l = e.name.isEmpty() ? "?" : e.name.substring(0, 1).toUpperCase(Locale.ROOT);
            g.centeredText(l, ix + ICON / 2 + 1, iy + 8, TEXT, false);
        }

        int right = x + w - 6;
        int play = 20;
        playButton(g, right - play, y + (h - play) / 2, play, true, mx, my, () -> activate(index));
        right -= play + 8;

        int colW = 0;
        if (hover) {
            colW = rowActions(g, right, y + (h - 14) / 2, e, mx, my);
        } else if (s != null) {
            colW = info(g, right, y, s);
        }
        int tx = ix + ICON + 8;
        int room = Math.max(20, right - colW - 8 - tx);
        g.text(fit(g, "§l" + e.name, room), tx, y + 5, TEXT, false);
        if (isPending(s)) {
            g.text(t("Server wird abgefragt…", "Pinging server…"), tx, y + 16, DIM, false);
        } else if (!s.ok()) {
            g.text(s.error(), tx, y + 16, FAIL, false);
            g.text(fit(g, e.address, room), tx, y + 26, DIM, false);
        } else {
            String[] lines = s.motd().split("\n", -1);
            String l1 = lines.length > 0 ? lines[0].strip() : "";
            String l2 = lines.length > 1 ? carry(lines[0]) + lines[1].strip() : "";
            g.enableScissor(tx, y, tx + room, y + h);
            g.text(l1, tx, y + 15, MUTED, false);
            g.text(l2, tx, y + 25, MUTED, false);
            g.disableScissor();
        }
    }

    /** Players, ping and version, right aligned; returns the column width. */
    private int info(HudGraphics g, int right, int y, ServerPinger.Status s) {
        if (isPending(s) || !s.ok()) {
            String dot = isPending(s) ? "§8●●●" : "§c✕";
            g.rightText(dot, right, y + 14, DIM, false);
            return g.textWidth(dot);
        }
        String players = "§f" + s.online() + "§7/" + s.max();
        String ping = s.pingMs() < 0 ? "§7? ms" : pingColor(s.pingMs()) + s.pingMs() + " ms";
        boolean compatible = s.protocol() == game.protocolVersion();
        String version = (compatible ? "§7" : "§c") + ServerPinger.Motd.strip(s.versionName());
        version = fit(g, version, 90);
        g.rightText(players, right, y + 5, TEXT, false);
        g.rightText(ping, right, y + 15, TEXT, false);
        g.rightText(version, right, y + 25, TEXT, false);
        return Math.max(g.textWidth(players), Math.max(g.textWidth(ping), g.textWidth(version)));
    }

    static String pingColor(long ms) {
        return ms < 80 ? "§a" : ms < 150 ? "§e" : ms < 300 ? "§6" : "§c";
    }

    private int rowActions(HudGraphics g, int right, int y, Servers.Entry e, int mx, int my) {
        int size = 14;
        int x = right - 4 * (size + 2);
        int all = servers.entries().indexOf(e);
        button(g, x, y, size, size, "▲", Style.GHOST, mx, my, () -> move(all, -1));
        button(g, x + (size + 2), y, size, size, "▼", Style.GHOST, mx, my, () -> move(all, 1));
        button(g, x + 2 * (size + 2), y, size, size, "✎", Style.GHOST, mx, my, () -> edit(e));
        button(g, x + 3 * (size + 2), y, size, size, "§c✕", Style.GHOST, mx, my, () -> delete(e));
        return right - x;
    }

    private void move(int index, int dir) {
        List<Servers.Entry> all = servers.entries();
        int to = index + dir;
        if (index < 0 || to < 0 || to >= all.size()) {
            return;
        }
        Collections.swap(all, index, to);
        selected = -1;
        save();
    }

    /** § codes still active at the end of a MOTD line (vanilla carries them to the second line). */
    static String carry(String line) {
        String codes = "";
        for (int i = 0; i + 1 < line.length(); i++) {
            if (line.charAt(i) == '§') {
                char c = Character.toLowerCase(line.charAt(i + 1));
                if ("0123456789abcdefr".indexOf(c) >= 0) {
                    codes = "§" + c;
                } else {
                    codes += "§" + c;
                }
                i++;
            }
        }
        return codes;
    }

    @Override
    void activate(int index) {
        if (index >= 0 && index < visible.size()) {
            Servers.Entry e = visible.get(index);
            game.join(e.address.strip(), e.name);
        }
    }

    @Override
    void deleteRow(int index) {
        if (index >= 0 && index < visible.size()) {
            delete(visible.get(index));
        }
    }

    @Override
    void bottomButtons(HudGraphics g, int x, int y, int h, int maxX, int mx, int my) {
        int bx = buttonRow(g, List.of(
                new BarButton(t("Hinzufügen", "Add"), t("Neu", "Add"), Style.PRIMARY, this::add),
                new BarButton(t("Direkt verbinden", "Direct connect"), t("Direkt", "Direct"), Style.SECONDARY, this::direct),
                new BarButton(t("Aktualisieren", "Refresh"), t("Laden", "Reload"), Style.SECONDARY, this::refresh),
                new BarButton(t("Klassisch", "Classic"), t("Klassisch", "Classic"), Style.GHOST, () -> game.openClassic(true))),
                x, y, h, maxX, mx, my);
        if (saveError != null) {
            g.text(fit(g, saveError, Math.max(10, maxX - bx)), bx + 2, y + 6, FAIL, false);
        }
    }

    private void add() {
        Field name = new Field(64, "Minecraft-Server").set(t("Minecraft-Server", "Minecraft Server"));
        Field address = new Field(255, "play.example.net");
        open(new Dialog(t("Server hinzufügen", "Add server"), null, t("Hinzufügen", "Add"), false, d -> {
            if (!Servers.validAddress(d.value(1))) {
                d.error = t("Ungültige Adresse", "Invalid address");
                return;
            }
            String n = d.value(0).isEmpty() ? d.value(1) : d.value(0);
            servers.entries().add(new Servers.Entry(n, d.value(1)));
            save();
            ping(d.value(1));
            closeDialog();
        }).field(t("Name", "Name"), name).field(t("Adresse", "Address"), address));
    }

    private void edit(Servers.Entry e) {
        Field name = new Field(64, "Minecraft-Server").set(e.name);
        Field address = new Field(255, "play.example.net").set(e.address);
        open(new Dialog(t("Server bearbeiten", "Edit server"), null, t("Speichern", "Save"), false, d -> {
            if (!Servers.validAddress(d.value(1))) {
                d.error = t("Ungültige Adresse", "Invalid address");
                return;
            }
            boolean moved = !key(e.address).equals(key(d.value(1)));
            e.name = d.value(0).isEmpty() ? d.value(1) : d.value(0);
            e.address = d.value(1);
            save();
            if (moved) {
                ping(e.address);
            }
            closeDialog();
        }).field(t("Name", "Name"), name).field(t("Adresse", "Address"), address));
    }

    private void delete(Servers.Entry e) {
        open(new Dialog(t("Server entfernen", "Remove server"),
                t("„" + e.name + "“ wird aus der Liste entfernt.", "\"" + e.name + "\" will be removed from the list."),
                t("Entfernen", "Remove"), true, d -> {
                    servers.entries().remove(e);
                    selected = -1;
                    save();
                    closeDialog();
                }));
    }

    private void direct() {
        Field address = new Field(255, "play.example.net").set(lastDirect);
        open(new Dialog(t("Direkt verbinden", "Direct connect"), null, t("Verbinden", "Join"), false, d -> {
            if (!Servers.validAddress(d.value(0))) {
                d.error = t("Ungültige Adresse", "Invalid address");
                return;
            }
            lastDirect = d.value(0);
            closeDialog();
            game.join(lastDirect, lastDirect);
        }).field(t("Serveradresse", "Server address"), address));
    }
}
