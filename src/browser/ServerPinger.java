package dev.lumora.core.browser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.lumora.core.io.Json;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Map;

/**
 * Minecraft "Server List Ping" (the status request every client sends for its server list, protocol 1.7+): server
 * version, players, MOTD, favicon and round-trip time. Version independent, so the same code serves every Lumora
 * version. SRV records (_minecraft._tcp) are honoured like in vanilla.
 */
public final class ServerPinger {
    static final int TIMEOUT_MS = 4000;
    static final int MAX_RESPONSE = 1 << 20;

    /** A ping result; {@code error} is set when the server could not be reached. */
    public record Status(String versionName, int protocol, int online, int max, String motd, byte[] favicon, long pingMs,
                         String error) {
        public boolean ok() {
            return error == null;
        }

        static Status failed(String error) {
            return new Status("", -1, 0, 0, "", null, -1, error);
        }
    }

    private ServerPinger() {
    }

    /** host[:port]; IPv6 in brackets. Default port 25565. */
    static String[] split(String address) {
        String a = address.strip();
        if (a.startsWith("[")) {
            int end = a.indexOf(']');
            String host = end > 0 ? a.substring(1, end) : a;
            String rest = end > 0 && a.length() > end + 2 && a.charAt(end + 1) == ':' ? a.substring(end + 2) : "25565";
            return new String[]{host, rest};
        }
        int colon = a.lastIndexOf(':');
        if (colon > 0 && a.indexOf(':') == colon) {
            return new String[]{a.substring(0, colon), a.substring(colon + 1)};
        }
        return new String[]{a, "25565"};
    }

    public static Status ping(String address, int protocol) {
        String[] hp = split(address);
        String host = hp[0];
        int port;
        try {
            port = Integer.parseInt(hp[1]);
        } catch (NumberFormatException e) {
            return Status.failed("Ungültiger Port");
        }
        if (host.isEmpty() || port < 1 || port > 65535) {
            return Status.failed("Ungültige Adresse");
        }
        String connectHost = host;
        int connectPort = port;
        if (hp[1].equals("25565") && !address.contains(":")) {
            String[] srv = srv(host);
            if (srv != null) {
                connectHost = srv[0];
                connectPort = Integer.parseInt(srv[1]);
            }
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(connectHost, connectPort), TIMEOUT_MS);
            s.setSoTimeout(TIMEOUT_MS);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            // handshake (next state 1 = status) and status request
            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            DataOutputStream h = new DataOutputStream(hs);
            varInt(h, 0);
            varInt(h, protocol);
            string(h, host);
            h.writeShort(port);
            varInt(h, 1);
            packet(out, hs.toByteArray());
            packet(out, new byte[]{0});
            int len = readVarInt(in);
            if (len <= 0 || len > MAX_RESPONSE) {
                return Status.failed("Ungültige Antwort");
            }
            int id = readVarInt(in);
            if (id != 0) {
                return Status.failed("Ungültige Antwort");
            }
            int strLen = readVarInt(in);
            if (strLen < 0 || strLen > MAX_RESPONSE) {
                return Status.failed("Ungültige Antwort");
            }
            byte[] json = new byte[strLen];
            in.readFully(json);
            // ping for the latency
            long t0 = System.nanoTime();
            ByteArrayOutputStream pp = new ByteArrayOutputStream();
            DataOutputStream p = new DataOutputStream(pp);
            varInt(p, 1);
            p.writeLong(t0);
            packet(out, pp.toByteArray());
            long ms;
            try {
                readVarInt(in);
                readVarInt(in);
                in.readLong();
                ms = (System.nanoTime() - t0) / 1_000_000;
            } catch (IOException e) {
                ms = -1; // some proxies close after the status: no latency then
            }
            return parse(new String(json, StandardCharsets.UTF_8), ms);
        } catch (java.net.UnknownHostException e) {
            return Status.failed("Unbekannter Server");
        } catch (java.net.SocketTimeoutException e) {
            return Status.failed("Keine Antwort");
        } catch (IOException e) {
            return Status.failed("Nicht erreichbar");
        }
    }

    static Status parse(String json, long pingMs) {
        try {
            JsonObject o = Json.parse(json).getAsJsonObject();
            String vName = "";
            int proto = -1;
            if (o.has("version") && o.get("version").isJsonObject()) {
                JsonObject v = o.getAsJsonObject("version");
                vName = v.has("name") ? v.get("name").getAsString() : "";
                proto = v.has("protocol") ? v.get("protocol").getAsInt() : -1;
            }
            int online = 0;
            int max = 0;
            if (o.has("players") && o.get("players").isJsonObject()) {
                JsonObject pl = o.getAsJsonObject("players");
                online = pl.has("online") ? pl.get("online").getAsInt() : 0;
                max = pl.has("max") ? pl.get("max").getAsInt() : 0;
            }
            String motd = o.has("description") ? Motd.toLegacy(o.get("description")) : "";
            byte[] icon = null;
            if (o.has("favicon") && o.get("favicon").isJsonPrimitive()) {
                String f = o.get("favicon").getAsString();
                int comma = f.indexOf(',');
                if (f.startsWith("data:image/png;base64,") && comma > 0) {
                    try {
                        icon = Base64.getMimeDecoder().decode(f.substring(comma + 1));
                    } catch (IllegalArgumentException e) {
                        icon = null;
                    }
                }
            }
            return new Status(vName, proto, online, max, motd, icon, pingMs, null);
        } catch (RuntimeException e) {
            return Status.failed("Ungültige Antwort");
        }
    }

    /** _minecraft._tcp SRV record of a host: {target, port} or null. */
    static String[] srv(String host) {
        if (host.matches("[0-9.]+") || host.contains(":")) {
            return null; // IP literal
        }
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", "1500");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            javax.naming.directory.DirContext ctx = new javax.naming.directory.InitialDirContext(env);
            javax.naming.directory.Attribute a = ctx.getAttributes("_minecraft._tcp." + host, new String[]{"SRV"}).get("srv");
            ctx.close();
            if (a == null || a.size() == 0) {
                return null;
            }
            String[] parts = a.get(0).toString().split(" ");
            if (parts.length < 4) {
                return null;
            }
            String target = parts[3].endsWith(".") ? parts[3].substring(0, parts[3].length() - 1) : parts[3];
            return new String[]{target, parts[2]};
        } catch (Exception e) {
            return null;
        }
    }

    private static void packet(DataOutputStream out, byte[] body) throws IOException {
        varInt(out, body.length);
        out.write(body);
        out.flush();
    }

    private static void string(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        varInt(out, b.length);
        out.write(b);
    }

    static void varInt(DataOutputStream out, int value) throws IOException {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift > 35) {
                throw new IOException("VarInt too big");
            }
        }
    }

    /** Chat components of the MOTD and the version name as § formatted text (Minecraft's font renders it). */
    static final class Motd {
        private static final Map<String, Character> COLORS = Map.ofEntries(
                Map.entry("black", '0'), Map.entry("dark_blue", '1'), Map.entry("dark_green", '2'), Map.entry("dark_aqua", '3'),
                Map.entry("dark_red", '4'), Map.entry("dark_purple", '5'), Map.entry("gold", '6'), Map.entry("gray", '7'),
                Map.entry("dark_gray", '8'), Map.entry("blue", '9'), Map.entry("green", 'a'), Map.entry("aqua", 'b'),
                Map.entry("red", 'c'), Map.entry("light_purple", 'd'), Map.entry("yellow", 'e'), Map.entry("white", 'f'));
        private static final int[] RGB = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
                0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

        private Motd() {
        }

        static String toLegacy(JsonElement e) {
            StringBuilder sb = new StringBuilder();
            append(sb, e, null, false, 0);
            return sb.toString();
        }

        private static void append(StringBuilder sb, JsonElement e, String parentColor, boolean parentBold, int depth) {
            if (e == null || depth > 32 || sb.length() > 4096) {
                return;
            }
            if (e.isJsonPrimitive()) {
                sb.append(e.getAsString());
                return;
            }
            if (e.isJsonArray()) {
                for (JsonElement x : (JsonArray) e) {
                    append(sb, x, parentColor, parentBold, depth + 1);
                }
                return;
            }
            if (!e.isJsonObject()) {
                return;
            }
            JsonObject o = e.getAsJsonObject();
            String color = o.has("color") ? code(o.get("color").getAsString()) : parentColor;
            boolean bold = o.has("bold") ? o.get("bold").getAsBoolean() : parentBold;
            if (color != null || bold) {
                sb.append('§').append(color == null ? "r" : color);
                if (bold) {
                    sb.append("§l");
                }
            }
            if (o.has("text")) {
                sb.append(o.get("text").getAsString());
            } else if (o.has("translate")) {
                sb.append(o.get("translate").getAsString());
            }
            if (o.has("extra")) {
                append(sb, o.get("extra"), color, bold, depth + 1);
            }
        }

        /** Named colour or #RRGGBB (nearest of the 16 legacy colours). */
        static String code(String color) {
            Character c = COLORS.get(color.toLowerCase(Locale.ROOT));
            if (c != null) {
                return c.toString();
            }
            if (color.matches("#[0-9a-fA-F]{6}")) {
                int rgb = Integer.parseInt(color.substring(1), 16);
                int best = 15;
                long bestD = Long.MAX_VALUE;
                for (int i = 0; i < RGB.length; i++) {
                    long dr = ((rgb >> 16) & 0xFF) - ((RGB[i] >> 16) & 0xFF);
                    long dg = ((rgb >> 8) & 0xFF) - ((RGB[i] >> 8) & 0xFF);
                    long db = (rgb & 0xFF) - (RGB[i] & 0xFF);
                    long d = dr * dr + dg * dg + db * db;
                    if (d < bestD) {
                        bestD = d;
                        best = i;
                    }
                }
                return Integer.toHexString(best);
            }
            return null;
        }

        /** Text without § codes (for search and widths). */
        static String strip(String s) {
            return s.replaceAll("§.", "");
        }
    }
}
