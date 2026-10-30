package dev.lumora.core.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserTest {
    private static Map<String, Object> server(String name, String ip, boolean hidden) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("ip", ip);
        m.put("acceptTextures", (byte) 1);
        m.put("icon", "aWNvbg==");
        if (hidden) {
            m.put("hidden", (byte) 1);
        }
        return m;
    }

    @SafeVarargs
    private static void writeServers(Path file, Map<String, Object>... entries) throws IOException {
        Nbt.TagList list = new Nbt.TagList(10);
        for (Map<String, Object> e : entries) {
            list.add(e);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("servers", list);
        try (OutputStream out = Files.newOutputStream(file)) {
            Nbt.write(out, root);
        }
    }

    @Test
    void nbtRoundTripKeepsAllTagTypes() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("b", (byte) 3);
        root.put("s", (short) 4);
        root.put("i", 5);
        root.put("l", 6L);
        root.put("f", 1.5f);
        root.put("d", 2.5);
        root.put("ba", new byte[]{1, 2});
        root.put("str", "Grüße");
        root.put("ia", new int[]{7, 8});
        root.put("la", new long[]{9L});
        root.put("empty", new Nbt.TagList(8));
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("x", "y");
        root.put("c", inner);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Nbt.write(out, root);
        Map<String, Object> back = Nbt.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals((byte) 3, back.get("b"));
        assertEquals(6L, back.get("l"));
        assertEquals("Grüße", back.get("str"));
        assertArrayEquals(new byte[]{1, 2}, (byte[]) back.get("ba"));
        assertArrayEquals(new int[]{7, 8}, (int[]) back.get("ia"));
        assertEquals(8, ((Nbt.TagList) back.get("empty")).elementType);
        assertEquals("y", ((Map<?, ?>) back.get("c")).get("x"));
        // byte identical when written again
        ByteArrayOutputStream again = new ByteArrayOutputStream();
        Nbt.write(again, back);
        assertArrayEquals(out.toByteArray(), again.toByteArray());
    }

    @Test
    @SuppressWarnings("unchecked")
    void serversKeepTagsHiddenEntriesAndBackup(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("servers.dat");
        writeServers(file, server("A", "a.example", false), server("Quick", "q.example", true), server("B", "b.example:25570", false));
        Servers s = new Servers(file).load();
        assertEquals(2, s.entries().size());
        assertEquals("A", s.entries().get(0).name);
        assertArrayEquals("icon".getBytes(), s.entries().get(0).cachedIcon());
        s.entries().get(0).name = "A2";
        s.entries().add(new Servers.Entry("New", "n.example"));
        s.save();
        assertTrue(Files.isRegularFile(dir.resolve("servers.dat_old")));
        Servers again = new Servers(file).load();
        assertEquals(List.of("A2", "B", "New"), again.entries().stream().map(e -> e.name).toList());
        Map<String, Object> root = Nbt.read(Files.newInputStream(file));
        List<Map<String, Object>> list = (List<Map<String, Object>>) root.get("servers");
        assertEquals(4, list.size()); // hidden entry kept
        assertEquals((byte) 1, list.get(0).get("acceptTextures"));
        assertTrue(list.stream().anyMatch(m -> "q.example".equals(m.get("ip"))));
    }

    @Test
    void brokenServersFileGivesEmptyListAndStaysUntouched(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("servers.dat");
        Files.write(file, new byte[]{1, 2, 3});
        assertTrue(new Servers(file).load().entries().isEmpty());
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(file));
    }

    @Test
    void addressValidation() {
        assertTrue(Servers.validAddress("play.example.net"));
        assertTrue(Servers.validAddress("127.0.0.1:25570"));
        assertFalse(Servers.validAddress(""));
        assertFalse(Servers.validAddress("a b"));
        assertFalse(Servers.validAddress("http://x"));
    }

    @Test
    void splitAddresses() {
        assertArrayEquals(new String[]{"example.net", "25565"}, ServerPinger.split("example.net"));
        assertArrayEquals(new String[]{"example.net", "1234"}, ServerPinger.split(" example.net:1234 "));
        assertArrayEquals(new String[]{"::1", "25566"}, ServerPinger.split("[::1]:25566"));
        assertArrayEquals(new String[]{"::1", "25565"}, ServerPinger.split("[::1]"));
    }

    @Test
    void parsesStatusJson() {
        String json = "{\"version\":{\"name\":\"Paper 1.21.4\",\"protocol\":769},\"players\":{\"max\":100,\"online\":12},"
                + "\"description\":{\"text\":\"\",\"extra\":[{\"text\":\"Hallo \",\"color\":\"gold\",\"bold\":true},"
                + "{\"text\":\"Welt\",\"color\":\"#55FF55\"}]},\"favicon\":\"data:image/png;base64,aWNvbg==\"}";
        ServerPinger.Status s = ServerPinger.parse(json, 42);
        assertTrue(s.ok());
        assertEquals(769, s.protocol());
        assertEquals(12, s.online());
        assertEquals(100, s.max());
        assertEquals(42, s.pingMs());
        assertEquals("§6§lHallo §aWelt", s.motd());
        assertArrayEquals("icon".getBytes(), s.favicon());
        assertEquals("plain", ServerPinger.parse("{\"description\":\"plain\"}", 1).motd());
        assertFalse(ServerPinger.parse("not json", 1).ok());
    }

    @Test
    void pingsALocalServer() throws Exception {
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            int[] handshakeProtocol = new int[1];
            Thread t = new Thread(() -> {
                try (java.net.Socket c = server.accept()) {
                    DataInputStream in = new DataInputStream(c.getInputStream());
                    DataOutputStream out = new DataOutputStream(c.getOutputStream());
                    ServerPinger.readVarInt(in); // handshake length
                    ServerPinger.readVarInt(in); // id 0
                    handshakeProtocol[0] = ServerPinger.readVarInt(in);
                    in.readFully(new byte[ServerPinger.readVarInt(in)]); // host
                    in.readUnsignedShort();
                    ServerPinger.readVarInt(in); // next state
                    ServerPinger.readVarInt(in); // status request length
                    ServerPinger.readVarInt(in); // id 0
                    byte[] json = "{\"version\":{\"name\":\"Test\",\"protocol\":47},\"players\":{\"max\":5,\"online\":2},\"description\":\"Hi\"}"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    DataOutputStream b = new DataOutputStream(body);
                    ServerPinger.varInt(b, 0);
                    ServerPinger.varInt(b, json.length);
                    b.write(json);
                    ServerPinger.varInt(out, body.size());
                    out.write(body.toByteArray());
                    out.flush();
                    ServerPinger.readVarInt(in); // ping length
                    ServerPinger.readVarInt(in); // id 1
                    long payload = in.readLong();
                    ServerPinger.varInt(out, 9);
                    ServerPinger.varInt(out, 1);
                    out.writeLong(payload);
                    out.flush();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            t.start();
            ServerPinger.Status s = ServerPinger.ping("127.0.0.1:" + server.getLocalPort(), 47);
            t.join(5000);
            assertTrue(s.ok(), s.error());
            assertEquals(47, handshakeProtocol[0]);
            assertEquals(2, s.online());
            assertEquals("Hi", s.motd());
            assertTrue(s.pingMs() >= 0);
        }
        assertFalse(ServerPinger.ping("127.0.0.1:1", 47).ok());
    }

    @Test
    void varIntRoundTrip() throws IOException {
        for (int v : new int[]{0, 1, 127, 128, 25565, 769, Integer.MAX_VALUE, -1}) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            ServerPinger.varInt(new DataOutputStream(b), v);
            assertEquals(v, ServerPinger.readVarInt(new DataInputStream(new ByteArrayInputStream(b.toByteArray()))));
        }
    }

    @Test
    void motdCarryAndPingColors() {
        assertEquals("§6§l", ServerBrowserView.carry("§6§lHallo"));
        assertEquals("§a", ServerBrowserView.carry("§lX§aY"));
        assertEquals("", ServerBrowserView.carry("plain"));
        assertEquals("§a", ServerBrowserView.pingColor(20));
        assertEquals("§c", ServerBrowserView.pingColor(900));
    }

    @Test
    void premiumFromUuidVersion() {
        assertTrue(BrowserView.premium("069a79f4-44e9-4726-a5be-fca90e38aaf5"));
        assertFalse(BrowserView.premium("8667ba71-b85a-3004-af54-457a9734eed7")); // OfflinePlayer:Steve (v3)
        assertFalse(BrowserView.premium(""));
    }

    @Test
    void readsWorldsNewestFirstAndDeletesOnlyInsideSaves(@TempDir Path dir) throws IOException {
        Path saves = dir.resolve("saves");
        level(saves.resolve("old"), "Alte Welt", 1000L, 0, false);
        level(saves.resolve("new"), "Neue Welt", 5000L, 1, true);
        Files.createDirectories(saves.resolve("broken"));
        List<Worlds.World> list = Worlds.list(saves);
        assertEquals(2, list.size());
        assertEquals("Neue Welt", list.get(0).name());
        assertEquals(1, list.get(0).gameMode());
        assertTrue(list.get(0).cheats());
        assertEquals("1.21.4", list.get(0).version());
        assertNull(list.get(0).icon());
        assertThrows(IOException.class, () -> Worlds.delete(saves, ".."));
        assertThrows(IOException.class, () -> Worlds.delete(saves, ""));
        Worlds.delete(saves, "old");
        assertFalse(Files.exists(saves.resolve("old")));
        assertTrue(Files.exists(dir));
        assertNotNull(Worlds.read(saves.resolve("new")));
    }

    private static void level(Path dir, String name, long lastPlayed, int mode, boolean cheats) throws IOException {
        Files.createDirectories(dir);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("LevelName", name);
        data.put("LastPlayed", lastPlayed);
        data.put("GameType", mode);
        data.put("allowCommands", (byte) (cheats ? 1 : 0));
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("Name", "1.21.4");
        data.put("Version", version);
        data.put("DataPacks", new LinkedHashMap<>());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Data", data);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(dir.resolve("level.dat")))) {
            Nbt.write(out, root);
        }
    }
}
