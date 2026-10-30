package dev.lumora.core.browser;

import dev.lumora.api.input.Keys;
import dev.lumora.api.render.HudGraphics;
import dev.lumora.api.render.TextureRef;
import dev.lumora.core.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the Lumora server and world lists through a fake renderer (Minecraft font approximated: 6 px per char). */
class BrowserViewTest {
    static final int W = 427;
    static final int H = 240;

    /** Records the drawn texts with their position. */
    static final class FakeGraphics implements HudGraphics {
        record Text(String text, int x, int y) {
        }

        final List<Text> texts = new ArrayList<>();

        @Override
        public int textWidth(String text) {
            return ServerPinger.Motd.strip(text).length() * 6;
        }

        @Override
        public int lineHeight() {
            return 9;
        }

        @Override
        public void text(String text, int x, int y, int color, boolean shadow) {
            texts.add(new Text(text, x, y));
        }

        @Override
        public void fill(int x1, int y1, int x2, int y2, int color) {
        }

        @Override
        public void texture(TextureRef texture, int x, int y, int width, int height, float u, float v, int rw, int rh, int tw, int th) {
        }

        @Override
        public void push() {
        }

        @Override
        public void pop() {
        }

        @Override
        public void translate(float x, float y) {
        }

        @Override
        public void scale(float x, float y) {
        }

        @Override
        public void enableScissor(int x1, int y1, int x2, int y2) {
        }

        @Override
        public void disableScissor() {
        }

        @Override
        public <T> Optional<T> unwrap(Class<T> type) {
            return Optional.empty();
        }

        Text find(String label) {
            return texts.stream().filter(t -> t.text().equals(label)).findFirst().orElse(null);
        }

        /** The last drawn text with this label (a dialog is drawn after the screen below it). */
        Text findLast(String label) {
            Text found = null;
            for (Text t : texts) {
                if (t.text().equals(label)) {
                    found = t;
                }
            }
            return found;
        }
    }

    static final class FakeGame implements BrowserPlatform {
        final List<String> calls = new ArrayList<>();

        @Override
        public void join(String address, String name) {
            calls.add("join " + address + " " + name);
        }

        @Override
        public void playWorld(String folder, String name) {
            calls.add("play " + folder + " " + name);
        }

        @Override
        public void createWorld() {
            calls.add("create");
        }

        @Override
        public void openClassic(boolean multiplayer) {
            calls.add("classic " + multiplayer);
        }

        @Override
        public int protocolVersion() {
            return 774;
        }
    }

    /** Clipboard of the fake views platform. */
    static final String[] CLIP = {""};
    static final dev.lumora.core.screen.ViewManager.Platform VIEWS = new dev.lumora.core.screen.ViewManager.Platform() {
        @Override
        public void show(dev.lumora.api.screen.AddonView view) {
        }

        @Override
        public void close(dev.lumora.api.screen.AddonView view) {
        }

        @Override
        public String clipboard() {
            return CLIP[0];
        }

        @Override
        public void setClipboard(String text) {
            CLIP[0] = text;
        }

        @Override
        public void openAddonSettings(String addonId) {
        }
    };

    /** A Platform with a config dir, German language and nothing else (every other call returns a default). */
    static Platform platform(Path config) {
        Object translations = Proxy.newProxyInstance(BrowserViewTest.class.getClassLoader(),
                new Class<?>[]{dev.lumora.api.i18n.Translations.class}, (p, m, a) -> m.getName().equals("language") ? "de_de" : defaults(p, m, a));
        InvocationHandler h = (proxy, m, args) -> switch (m.getName()) {
            case "configDir" -> config;
            case "translations" -> translations;
            case "minecraftVersion" -> "1.21.11";
            case "modLoaded" -> false;
            case "views" -> VIEWS;
            default -> defaults(proxy, m, args);
        };
        return (Platform) Proxy.newProxyInstance(BrowserViewTest.class.getClassLoader(), new Class<?>[]{Platform.class}, h);
    }

    private static Object defaults(Object proxy, Method m, Object[] args) throws Throwable {
        if (m.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, m, args);
        }
        Class<?> r = m.getReturnType();
        if (r == boolean.class) {
            return false;
        }
        if (r == int.class) {
            return 0;
        }
        if (r == String.class) {
            return "";
        }
        return null;
    }

    private static void click(BrowserView v, FakeGraphics.Text t) {
        assertNotNull(t);
        v.mouseClicked(t.x() + 2, t.y() + 3, 0);
    }

    private static FakeGraphics render(BrowserView v) {
        FakeGraphics g = new FakeGraphics();
        v.render(g, W, H, -1, -1);
        return g;
    }

    @Test
    void serverListFitsAndAddsServers(@TempDir Path dir) throws IOException {
        Path config = Files.createDirectories(dir.resolve("config"));
        Servers seed = new Servers(dir.resolve("servers.dat"));
        seed.entries().add(new Servers.Entry("Test", "127.0.0.1:1"));
        seed.save();
        FakeGame game = new FakeGame();
        ServerBrowserView v = new ServerBrowserView(platform(config), game);
        FakeGraphics g = render(v);
        assertNotNull(g.find("Mehrspieler"));
        // the bottom bar never reaches the "Zurück" button
        FakeGraphics.Text back = g.find("Zurück");
        assertNotNull(back);
        int barY = back.y();
        for (FakeGraphics.Text t : g.texts) {
            if (t.y() == barY && t != back) {
                assertTrue(t.x() + g.textWidth(t.text()) < back.x() - 8, t.text() + " overlaps Zurück");
            }
        }
        // add a server through the dialog
        FakeGraphics.Text add = g.find("Hinzufügen") != null ? g.find("Hinzufügen") : g.find("Neu");
        click(v, add);
        assertNotNull(v.dialog);
        for (int i = 0; i < 30; i++) {
            v.keyPressed(Keys.BACKSPACE, false, false);
        }
        "Mein Server".chars().forEach(c -> v.charTyped((char) c));
        v.keyPressed(Keys.TAB, false, false);
        "127.0.0.1:2".chars().forEach(c -> v.charTyped((char) c));
        v.keyPressed(Keys.ENTER, false, false);
        assertNull(v.dialog);
        Servers saved = new Servers(dir.resolve("servers.dat")).load();
        assertEquals(List.of("Test", "Mein Server"), saved.entries().stream().map(e -> e.name).toList());
        assertEquals("127.0.0.1:2", saved.entries().get(1).address);
        // invalid address keeps the dialog open
        render(v);
        click(v, g.find("Hinzufügen") != null ? g.find("Hinzufügen") : g.find("Neu"));
        v.keyPressed(Keys.TAB, false, false);
        "bad address".chars().forEach(c -> v.charTyped((char) c));
        v.keyPressed(Keys.ENTER, false, false);
        assertNotNull(v.dialog);
        assertEquals("Ungültige Adresse", v.dialog.error);
        v.closeRequested();
        assertNull(v.dialog);
        // Enter on the selected row joins it
        v.keyPressed(Keys.DOWN, false, false);
        v.keyPressed(Keys.ENTER, false, false);
        assertEquals(List.of("join 127.0.0.1:1 Test"), game.calls);
        // search filters
        "mein".chars().forEach(c -> v.charTyped((char) c));
        assertEquals(1, v.rowCount());
    }

    /** Everything with the mouse, like a player: dialog buttons and fields, delete, cancel, toolbar while scrolled. */
    @Test
    void dialogsAndToolbarWorkWithTheMouse(@TempDir Path dir) throws IOException {
        Path config = Files.createDirectories(dir.resolve("config"));
        Servers seed = new Servers(dir.resolve("servers.dat"));
        for (int i = 1; i <= 10; i++) {
            seed.entries().add(new Servers.Entry("Server " + i, "127.0.0.1:" + i));
        }
        seed.save();
        FakeGame game = new FakeGame();
        ServerBrowserView v = new ServerBrowserView(platform(config), game);
        FakeGraphics g = render(v);
        click(v, g.find("Hinzufügen") != null ? g.find("Hinzufügen") : g.find("Neu"));
        assertNotNull(v.dialog);
        g = render(v);
        // click into the address field (its placeholder) and type, then click the dialog's "Hinzufügen"
        click(v, g.findLast("play.example.net"));
        "127.0.0.1:99".chars().forEach(c -> v.charTyped((char) c));
        click(v, g.findLast("Hinzufügen"));
        assertNull(v.dialog, "the save button of the dialog closes it");
        assertEquals("127.0.0.1:99", new Servers(dir.resolve("servers.dat")).load().entries().get(10).address);
        // cancel with the mouse
        g = render(v);
        click(v, g.find("Hinzufügen") != null ? g.find("Hinzufügen") : g.find("Neu"));
        g = render(v);
        click(v, g.findLast("Abbrechen"));
        assertNull(v.dialog);
        assertEquals(11, new Servers(dir.resolve("servers.dat")).load().entries().size(), "cancel saves nothing");
        // delete the first server: hover the row, click its delete button, confirm with the mouse
        g = new FakeGraphics();
        v.render(g, W, H, W / 2, v.listTop + 10);
        FakeGraphics.Text del = g.find("§c✕");
        assertNotNull(del, "row actions on hover");
        v.mouseClicked(del.x() + 2, del.y() + 3, 0);
        assertNotNull(v.dialog);
        g = render(v);
        click(v, g.findLast("Entfernen"));
        assertNull(v.dialog);
        assertEquals("Server 2", new Servers(dir.resolve("servers.dat")).load().entries().get(0).name);
        // scrolled so that a row is cut off at the top: the toolbar still gets its clicks
        v.mouseScrolled(10, 100, -1.35); // first row starts 32 px above the list, its play button lies under the toolbar
        g = render(v);
        boolean before = ServerBrowserView.compatibleOnly;
        click(v, g.find("Alle Versionen ▾"));
        assertEquals(!before, ServerBrowserView.compatibleOnly, "version filter works over a cut-off row");
        ServerBrowserView.compatibleOnly = before;
        g = render(v);
        boolean online = ServerBrowserView.onlineOnly;
        click(v, g.find("Nur online"));
        assertEquals(!online, ServerBrowserView.onlineOnly);
        ServerBrowserView.onlineOnly = online;
        assertTrue(game.calls.isEmpty(), "no server was joined by clicking the toolbar");
    }

    /** Text fields: select with Shift, mouse and Ctrl+A; copy, cut, paste; typing replaces the selection. */
    @Test
    void textFieldsSelectCopyAndPaste(@TempDir Path dir) throws IOException {
        Path config = Files.createDirectories(dir.resolve("config"));
        ServerBrowserView v = new ServerBrowserView(platform(config), new FakeGame());
        render(v);
        "hello world".chars().forEach(c -> v.charTyped((char) c));
        assertEquals("hello world", v.search.value());
        // Shift+Ctrl+Left selects the last word, typing replaces it
        v.keyPressed(Keys.LEFT, true, true);
        assertEquals("world", v.search.selected());
        "lumora".chars().forEach(c -> v.charTyped((char) c));
        assertEquals("hello lumora", v.search.value());
        // Ctrl+A, Ctrl+C, Ctrl+X, Ctrl+V
        v.keyPressed(Keys.A, true, false);
        v.keyPressed(Keys.C, true, false);
        assertEquals("hello lumora", CLIP[0]);
        v.keyPressed(Keys.X, true, false);
        assertEquals("", v.search.value());
        v.keyPressed(Keys.V, true, false);
        v.keyPressed(Keys.V, true, false);
        assertEquals("hello lumorahello lumora", v.search.value());
        // Shift+Home selects to the start, Delete removes it
        v.keyPressed(Keys.HOME, false, true);
        assertEquals(24, v.search.selected().length());
        v.keyPressed(Keys.DELETE, false, false);
        assertEquals("", v.search.value());
        // the mouse: click places the cursor, dragging selects, double click selects all
        "abcdef".chars().forEach(c -> v.charTyped((char) c));
        render(v);
        int[] xs = v.search.xs;
        v.mouseClicked(xs[2], v.listTop - 20, 0);
        assertEquals(2, v.search.cursor);
        assertFalse(v.search.hasSelection());
        v.mouseDragged(xs[5], v.listTop - 20, 0);
        v.mouseReleased(xs[5], v.listTop - 20, 0);
        assertEquals("cde", v.search.selected());
        v.mouseClicked(xs[1], v.listTop - 20, 0);
        v.mouseClicked(xs[1], v.listTop - 20, 0);
        assertEquals("abcdef", v.search.selected(), "double click selects everything");
        // Backspace removes the selection
        v.keyPressed(Keys.BACKSPACE, false, false);
        assertEquals("", v.search.value());
    }

    @Test
    void worldListPlaysAndCreates(@TempDir Path dir) throws Exception {
        Path config = Files.createDirectories(dir.resolve("config"));
        Path world = Files.createDirectories(dir.resolve("saves").resolve("w1"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("LevelName", "Meine Welt");
        data.put("LastPlayed", 1000L);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Data", data);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(world.resolve("level.dat")))) {
            Nbt.write(out, root);
        }
        FakeGame game = new FakeGame();
        WorldBrowserView v = new WorldBrowserView(platform(config), game);
        for (int i = 0; i < 100 && v.rowCount() == 0; i++) {
            Thread.sleep(20); // loaded in the background
        }
        assertEquals(1, v.rowCount());
        FakeGraphics g = render(v);
        assertNotNull(g.find("§lMeine Welt"));
        v.keyPressed(Keys.DOWN, false, false);
        v.keyPressed(Keys.ENTER, false, false);
        click(v, g.find("Neue Welt") != null ? g.find("Neue Welt") : g.find("Neu"));
        click(v, g.find("Klassisch"));
        assertEquals(List.of("play w1 Meine Welt", "create", "classic false"), game.calls);
    }
}
