package dev.lumora.core.browser;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Minimal reader and writer for Minecraft's NBT format (level.dat, servers.dat). Compounds become maps, lists become
 * {@link TagList}s, numbers, strings and arrays their Java types, so a file read and written again keeps every tag.
 * Sizes are capped so a broken file cannot exhaust memory.
 */
final class Nbt {
    private static final int MAX_DEPTH = 64;
    private static final int MAX_LIST = 1 << 20;

    /** A list tag; keeps its element type so empty lists are written back unchanged. */
    static final class TagList extends ArrayList<Object> {
        private static final long serialVersionUID = 1L;
        final int elementType;

        TagList(int elementType) {
            this.elementType = elementType;
        }
    }

    private Nbt() {
    }

    /** Reads a gzip-compressed NBT file (like level.dat) and returns its root compound. */
    static Map<String, Object> readGzip(InputStream in) throws IOException {
        return read(new GZIPInputStream(in));
    }

    /** Reads an uncompressed NBT file (like servers.dat) and returns its root compound. */
    static Map<String, Object> read(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(in);
        int type = d.readUnsignedByte();
        if (type != 10) {
            throw new IOException("NBT root is not a compound");
        }
        d.readUTF();
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) read(d, 10, 0);
        return root;
    }

    /** Writes an uncompressed NBT file with an unnamed root compound. */
    static void write(OutputStream out, Map<String, Object> root) throws IOException {
        DataOutputStream d = new DataOutputStream(out);
        d.writeByte(10);
        d.writeUTF("");
        write(d, root, 0);
        d.flush();
    }

    private static Object read(DataInputStream d, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT too deep");
        }
        switch (type) {
            case 1: return d.readByte();
            case 2: return d.readShort();
            case 3: return d.readInt();
            case 4: return d.readLong();
            case 5: return d.readFloat();
            case 6: return d.readDouble();
            case 7: {
                byte[] b = new byte[len(d)];
                d.readFully(b);
                return b;
            }
            case 8: return d.readUTF();
            case 9: {
                int inner = d.readUnsignedByte();
                int n = len(d);
                TagList list = new TagList(inner);
                for (int i = 0; i < n; i++) {
                    list.add(read(d, inner, depth + 1));
                }
                return list;
            }
            case 10: {
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    int t = d.readUnsignedByte();
                    if (t == 0) {
                        return map;
                    }
                    String name = d.readUTF();
                    map.put(name, read(d, t, depth + 1));
                }
            }
            case 11: {
                int[] a = new int[len(d)];
                for (int i = 0; i < a.length; i++) {
                    a[i] = d.readInt();
                }
                return a;
            }
            case 12: {
                long[] a = new long[len(d)];
                for (int i = 0; i < a.length; i++) {
                    a[i] = d.readLong();
                }
                return a;
            }
            default: throw new IOException("unknown NBT tag " + type);
        }
    }

    static int typeOf(Object v) {
        if (v instanceof Byte) {
            return 1;
        }
        if (v instanceof Short) {
            return 2;
        }
        if (v instanceof Integer) {
            return 3;
        }
        if (v instanceof Long) {
            return 4;
        }
        if (v instanceof Float) {
            return 5;
        }
        if (v instanceof Double) {
            return 6;
        }
        if (v instanceof byte[]) {
            return 7;
        }
        if (v instanceof String) {
            return 8;
        }
        if (v instanceof List) {
            return 9;
        }
        if (v instanceof Map) {
            return 10;
        }
        if (v instanceof int[]) {
            return 11;
        }
        if (v instanceof long[]) {
            return 12;
        }
        throw new IllegalArgumentException("not an NBT value: " + v);
    }

    private static void write(DataOutputStream d, Object v, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT too deep");
        }
        switch (typeOf(v)) {
            case 1 -> d.writeByte((Byte) v);
            case 2 -> d.writeShort((Short) v);
            case 3 -> d.writeInt((Integer) v);
            case 4 -> d.writeLong((Long) v);
            case 5 -> d.writeFloat((Float) v);
            case 6 -> d.writeDouble((Double) v);
            case 7 -> {
                byte[] b = (byte[]) v;
                d.writeInt(b.length);
                d.write(b);
            }
            case 8 -> d.writeUTF((String) v);
            case 9 -> {
                List<?> list = (List<?>) v;
                int inner = list instanceof TagList t ? t.elementType : list.isEmpty() ? 0 : typeOf(list.get(0));
                d.writeByte(inner);
                d.writeInt(list.size());
                for (Object o : list) {
                    if (typeOf(o) != inner) {
                        throw new IOException("mixed NBT list");
                    }
                    write(d, o, depth + 1);
                }
            }
            case 10 -> {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    d.writeByte(typeOf(e.getValue()));
                    d.writeUTF((String) e.getKey());
                    write(d, e.getValue(), depth + 1);
                }
                d.writeByte(0);
            }
            case 11 -> {
                int[] a = (int[]) v;
                d.writeInt(a.length);
                for (int x : a) {
                    d.writeInt(x);
                }
            }
            case 12 -> {
                long[] a = (long[]) v;
                d.writeInt(a.length);
                for (long x : a) {
                    d.writeLong(x);
                }
            }
            default -> throw new IOException("unknown NBT value");
        }
    }

    private static int len(DataInputStream d) throws IOException {
        int n = d.readInt();
        if (n < 0 || n > MAX_LIST) {
            throw new IOException("NBT list too long");
        }
        return n;
    }
}
