package dev.lumora.core.browser;

import dev.lumora.api.render.HudGraphics;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Server favicons and world icons as small pixel grids drawn with fills (no textures, so it works the same on every
 * Minecraft version). Images are decoded and scaled down (box filter) off the client thread.
 */
final class Icons {
    static final int MAX_BYTES = 512 * 1024;
    private static final int[] NONE = new int[0];
    private static final Map<String, int[]> CACHE = new ConcurrentHashMap<>();
    static final ExecutorService POOL = pool("Lumora browser", 4);

    private Icons() {
    }

    static ExecutorService pool(String name, int threads) {
        ThreadPoolExecutor p = new ThreadPoolExecutor(threads, threads, 20, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
        p.allowCoreThreadTimeOut(true);
        return p;
    }

    /** The icon for {@code key}, decoding it in the background on first use; null until ready or if invalid. */
    static int[] get(String key, int size, java.util.function.Supplier<byte[]> png) {
        String k = key + "@" + size;
        int[] px = CACHE.get(k);
        if (px == null) {
            CACHE.put(k, NONE);
            POOL.execute(() -> CACHE.put(k, decode(png.get(), size)));
            return null;
        }
        return px.length == 0 ? null : px;
    }

    static void forget(String keyPrefix) {
        CACHE.keySet().removeIf(k -> k.startsWith(keyPrefix));
    }

    static byte[] read(Path file) {
        try {
            if (file == null || Files.size(file) > MAX_BYTES) {
                return null;
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            return null;
        }
    }

    /** PNG to {@code size x size} ARGB pixels; empty array if it is not a readable image. */
    static int[] decode(byte[] png, int size) {
        if (png == null || png.length == 0 || png.length > MAX_BYTES) {
            return NONE;
        }
        try (InputStream in = new ByteArrayInputStream(png)) {
            BufferedImage img = ImageIO.read(in);
            if (img == null || img.getWidth() > 1024 || img.getHeight() > 1024) {
                return NONE;
            }
            return scale(img, size);
        } catch (IOException | RuntimeException e) {
            return NONE;
        }
    }

    static int[] scale(BufferedImage img, int size) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] out = new int[size * size];
        for (int y = 0; y < size; y++) {
            int y0 = y * h / size;
            int y1 = Math.max(y0 + 1, (y + 1) * h / size);
            for (int x = 0; x < size; x++) {
                int x0 = x * w / size;
                int x1 = Math.max(x0 + 1, (x + 1) * w / size);
                long a = 0;
                long r = 0;
                long g = 0;
                long b = 0;
                int n = 0;
                for (int sy = y0; sy < y1; sy++) {
                    for (int sx = x0; sx < x1; sx++) {
                        int c = img.getRGB(sx, sy);
                        int ca = c >>> 24;
                        a += ca;
                        r += (long) ((c >> 16) & 0xFF) * ca;
                        g += (long) ((c >> 8) & 0xFF) * ca;
                        b += (long) (c & 0xFF) * ca;
                        n++;
                    }
                }
                int pa = (int) (a / n);
                out[y * size + x] = a == 0 ? 0 : (pa << 24) | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
            }
        }
        return out;
    }

    /** Draws the pixels 1:1 in GUI pixels; equal neighbours in a row become one fill. */
    static void draw(HudGraphics g, int[] px, int size, int x, int y) {
        for (int row = 0; row < size; row++) {
            int col = 0;
            while (col < size) {
                int c = px[row * size + col];
                int end = col + 1;
                while (end < size && px[row * size + end] == c) {
                    end++;
                }
                if ((c >>> 24) != 0) {
                    g.fill(x + col, y + row, x + end, y + row + 1, c);
                }
                col = end;
            }
        }
    }
}
