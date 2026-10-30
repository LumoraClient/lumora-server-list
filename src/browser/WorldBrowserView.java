package dev.lumora.core.browser;

import dev.lumora.api.render.HudGraphics;
import dev.lumora.core.Platform;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Lumora singleplayer screen: the worlds of {@code saves/} as cards with icon, name, last played, game mode and
 * version; search, sort, play, delete and "new world". Editing world options stays in the vanilla screen
 * ("Klassisch").
 */
public final class WorldBrowserView extends BrowserView {
    private static final int ICON = 24;
    private static boolean byName;

    private final Path saves;
    private volatile List<Worlds.World> worlds = List.of();
    private volatile boolean loading;
    private final List<Worlds.World> visible = new ArrayList<>();
    private String error;
    private long savesMtime = -2;
    private long lastDiskCheck;

    public WorldBrowserView(Platform platform, BrowserPlatform game) {
        super(platform, game);
        this.saves = platform.configDir().toAbsolutePath().getParent().resolve("saves");
        reload();
    }

    private void reload() {
        loading = true;
        savesMtime = savesMtime();
        Icons.forget("world:");
        CompletableFuture.supplyAsync(() -> Worlds.list(saves), Icons.POOL).whenComplete((list, ex) -> {
            worlds = list == null ? List.of() : list;
            loading = false;
        });
    }

    @Override
    String title() {
        return t("Einzelspieler", "Singleplayer");
    }

    @Override
    int toolbar(HudGraphics g, int left, int right, int y, int h, int mx, int my) {
        String sort = byName ? t("Sortierung: Name", "Sort: name") : t("Sortierung: Zuletzt gespielt", "Sort: last played");
        int w = g.textWidth(sort) + 24;
        button(g, right - w, y, w, h, sort + " ▾", Style.SECONDARY, mx, my, () -> {
            byName = !byName;
            selected = -1;
        });
        return right - w - 6;
    }

    private long savesMtime() {
        try {
            return java.nio.file.Files.isDirectory(saves) ? java.nio.file.Files.getLastModifiedTime(saves).toMillis() : -1;
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    @Override
    int rowCount() {
        long now = System.currentTimeMillis();
        if (now - lastDiskCheck > 1000 && dialog == null && !loading) {
            lastDiskCheck = now;
            if (savesMtime() != savesMtime) {
                reload(); // a world was created, copied or removed elsewhere
            }
        }
        visible.clear();
        for (Worlds.World w : worlds) {
            if (matches(w.name(), w.folder(), w.version())) {
                visible.add(w);
            }
        }
        if (byName) {
            visible.sort(Comparator.comparing(w -> w.name().toLowerCase(Locale.ROOT)));
        }
        if (selected >= visible.size()) {
            selected = visible.size() - 1;
        }
        return visible.size();
    }

    @Override
    String emptyText() {
        if (loading) {
            return t("Welten werden geladen…", "Loading worlds…");
        }
        return worlds.isEmpty() ? t("Noch keine Welten – erstelle eine mit „Neue Welt“", "No worlds yet – create one with \"New world\"")
                : t("Keine Welt passt zur Suche", "No world matches the search");
    }

    @Override
    void drawRow(HudGraphics g, int index, int x, int y, int w, int h, boolean hover, int mx, int my) {
        Worlds.World world = visible.get(index);
        int ix = x + 7;
        int iy = y + (h - ICON) / 2;
        int[] px = world.icon() == null ? null
                : Icons.get("world:" + world.folder() + ":" + world.lastPlayed(), ICON, () -> Icons.read(world.icon()));
        if (px != null) {
            Icons.draw(g, px, ICON, ix, iy);
        } else {
            // grass block placeholder
            g.roundedRect(ix, iy, ICON, ICON, 4, 0xFF6B4A2E);
            g.fill(ix, iy + 4, ix + ICON, iy + 9, 0xFF5DA03A);
            g.roundedRect(ix, iy, ICON, 8, 4, 0xFF5DA03A);
        }
        int right = x + w - 6;
        int play = 20;
        playButton(g, right - play, y + (h - play) / 2, play, true, mx, my, () -> activate(index));
        right -= play + 8;
        int colW;
        if (hover) {
            int size = 14;
            button(g, right - size, y + (h - size) / 2, size, size, "§c✕", Style.GHOST, mx, my, () -> delete(world));
            colW = size;
        } else {
            String v = world.version().isEmpty() ? "" : "§7" + world.version();
            g.rightText(v, right, y + 25, TEXT, false);
            colW = g.textWidth(v);
        }
        int tx = ix + ICON + 8;
        int room = Math.max(20, right - colW - 8 - tx);
        g.text(fit(g, "§l" + world.name(), room), tx, y + 5, TEXT, false);
        String date = DateTimeFormatter.ofPattern(de ? "dd.MM.yyyy HH:mm" : "yyyy-MM-dd HH:mm")
                .format(Instant.ofEpochMilli(world.lastPlayed()).atZone(ZoneId.systemDefault()));
        g.text(fit(g, world.folder() + " · " + date, room), tx, y + 15, DIM, false);
        g.text(fit(g, mode(world), room), tx, y + 25, MUTED, false);
    }

    private String mode(Worlds.World w) {
        String m;
        if (w.hardcore()) {
            m = "§cHardcore";
        } else {
            m = switch (w.gameMode()) {
                case 1 -> t("Kreativ", "Creative");
                case 2 -> t("Abenteuer", "Adventure");
                case 3 -> t("Zuschauer", "Spectator");
                default -> t("Überleben", "Survival");
            };
        }
        return w.cheats() ? m + "§7 · Cheats" : m;
    }

    @Override
    void activate(int index) {
        if (index >= 0 && index < visible.size()) {
            game.playWorld(visible.get(index).folder(), visible.get(index).name());
        }
    }

    @Override
    void deleteRow(int index) {
        if (index >= 0 && index < visible.size()) {
            delete(visible.get(index));
        }
    }

    private void delete(Worlds.World w) {
        open(new Dialog(t("Welt löschen", "Delete world"),
                t("„" + w.name() + "“ wird für immer gelöscht. Das kann nicht rückgängig gemacht werden.",
                        "\"" + w.name() + "\" will be deleted forever. This cannot be undone."),
                t("Löschen", "Delete"), true, d -> {
                    try {
                        Worlds.delete(saves, w.folder());
                        error = null;
                    } catch (IOException e) {
                        error = t("Welt konnte nicht gelöscht werden", "Could not delete the world");
                    }
                    selected = -1;
                    closeDialog();
                    reload();
                }));
    }

    @Override
    void bottomButtons(HudGraphics g, int x, int y, int h, int maxX, int mx, int my) {
        int bx = buttonRow(g, List.of(
                new BarButton(t("Neue Welt", "New world"), t("Neu", "New"), Style.PRIMARY, game::createWorld),
                new BarButton(t("Aktualisieren", "Refresh"), t("Laden", "Reload"), Style.SECONDARY, this::reload),
                new BarButton(t("Klassisch", "Classic"), t("Klassisch", "Classic"), Style.GHOST, () -> game.openClassic(false))),
                x, y, h, maxX, mx, my);
        if (error != null) {
            g.text(fit(g, error, Math.max(10, maxX - bx)), bx + 2, y + 6, FAIL, false);
        }
    }
}
