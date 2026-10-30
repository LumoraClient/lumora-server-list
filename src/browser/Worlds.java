package dev.lumora.core.browser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** The worlds in a saves folder, read straight from level.dat (name, mode, last played) and icon.png. */
public final class Worlds {
    public record World(String folder, String name, long lastPlayed, int gameMode, boolean hardcore, boolean cheats,
                        String version, Path icon) {
    }

    private Worlds() {
    }

    /** Newest first; folders without a readable level.dat are skipped. */
    public static List<World> list(Path saves) {
        List<World> out = new ArrayList<>();
        if (saves == null || !Files.isDirectory(saves)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(saves)) {
            for (Path dir : ds) {
                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                World w = read(dir);
                if (w != null) {
                    out.add(w);
                }
            }
        } catch (IOException e) {
            return out;
        }
        out.sort(Comparator.comparingLong(World::lastPlayed).reversed());
        return out;
    }

    static World read(Path dir) {
        Path dat = dir.resolve("level.dat");
        if (!Files.isRegularFile(dat)) {
            return null;
        }
        String folder = dir.getFileName().toString();
        try (InputStream in = Files.newInputStream(dat)) {
            Map<String, Object> root = Nbt.readGzip(in);
            Object d = root.get("Data");
            if (!(d instanceof Map<?, ?> data)) {
                return null;
            }
            String name = data.get("LevelName") instanceof String s && !s.isBlank() ? s : folder;
            long last = data.get("LastPlayed") instanceof Long l ? l : Files.getLastModifiedTime(dat).toMillis();
            int mode = data.get("GameType") instanceof Integer i ? i : 0;
            boolean hardcore = data.get("hardcore") instanceof Byte b && b != 0;
            boolean cheats = data.get("allowCommands") instanceof Byte b && b != 0;
            String version = "";
            if (data.get("Version") instanceof Map<?, ?> v && v.get("Name") instanceof String vn) {
                version = vn;
            }
            Path icon = dir.resolve("icon.png");
            return new World(folder, name, last, mode, hardcore, cheats, version, Files.isRegularFile(icon) ? icon : null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Deletes a world folder below {@code saves} (never follows links, refuses paths outside saves).
     */
    public static void delete(Path saves, String folder) throws IOException {
        Path root = saves.toAbsolutePath().normalize();
        Path dir = root.resolve(folder).normalize();
        if (!dir.startsWith(root) || dir.equals(root) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("not a world folder: " + folder);
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
