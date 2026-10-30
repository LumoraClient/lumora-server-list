package dev.lumora.core.browser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The saved server list ({@code .minecraft/servers.dat}), the same file the vanilla multiplayer screen uses. Entries
 * keep every tag Minecraft stores (resource pack choice, cached icon, ...) when the list is written back; hidden
 * entries (quick play) are kept but not listed. Writing goes through a temporary file and keeps the previous file as
 * {@code servers.dat_old}, like vanilla.
 */
public final class Servers {
    /** One server entry; {@code tags} are the original NBT tags (null for a new entry). */
    public static final class Entry {
        public String name;
        public String address;
        final Map<String, Object> tags;

        public Entry(String name, String address) {
            this(name, address, null);
        }

        Entry(String name, String address, Map<String, Object> tags) {
            this.name = name;
            this.address = address;
            this.tags = tags;
        }

        boolean hidden() {
            return tags != null && tags.get("hidden") instanceof Byte b && b != 0;
        }

        /** The icon Minecraft cached for this server (PNG bytes) or null. */
        public byte[] cachedIcon() {
            if (tags != null && tags.get("icon") instanceof String s) {
                try {
                    return Base64.getDecoder().decode(s);
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
            return null;
        }
    }

    private final Path file;
    private final List<Entry> entries = new ArrayList<>();
    private final List<Entry> hidden = new ArrayList<>();
    private long loadedMtime = -1;

    public Servers(Path file) {
        this.file = file;
    }

    /** Reads the file; a missing or broken file gives an empty list (and is not overwritten until saved). */
    public Servers load() {
        entries.clear();
        hidden.clear();
        loadedMtime = mtime();
        if (!Files.isRegularFile(file)) {
            return this;
        }
        try (InputStream in = Files.newInputStream(file)) {
            Map<String, Object> root = Nbt.read(in);
            if (root.get("servers") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> tags = (Map<String, Object>) m;
                        String name = tags.get("name") instanceof String s ? s : "";
                        String ip = tags.get("ip") instanceof String s ? s : "";
                        Entry e = new Entry(name, ip, tags);
                        (e.hidden() ? hidden : entries).add(e);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            entries.clear();
            hidden.clear();
        }
        return this;
    }

    public List<Entry> entries() {
        return entries;
    }

    public void save() throws IOException {
        Nbt.TagList list = new Nbt.TagList(10);
        for (Entry e : entries) {
            list.add(tags(e));
        }
        for (Entry e : hidden) {
            list.add(tags(e));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("servers", list);
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "servers", ".dat");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                Nbt.write(out, root);
            }
            if (Files.isRegularFile(file)) {
                Files.copy(file, dir.resolve(file.getFileName() + "_old"), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            loadedMtime = mtime();
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Whether another screen (vanilla list, ViaFabricPlus) changed the file since it was loaded or saved. */
    public boolean changedOnDisk() {
        return mtime() != loadedMtime;
    }

    private long mtime() {
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    private static Map<String, Object> tags(Entry e) {
        Map<String, Object> t = e.tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(e.tags);
        t.put("name", e.name);
        t.put("ip", e.address);
        return t;
    }

    /** Server addresses Minecraft accepts: host or host:port, no spaces or control characters. */
    public static boolean validAddress(String address) {
        String a = address == null ? "" : address.strip();
        return !a.isEmpty() && a.length() <= 255 && a.chars().noneMatch(c -> c <= ' ' || c == 127 || c == '/');
    }
}
