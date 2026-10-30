package dev.lumora.core.browser;

import dev.lumora.api.input.Keys;
import dev.lumora.api.render.HudGraphics;
import dev.lumora.api.render.TextureRef;
import dev.lumora.api.screen.AddonView;
import dev.lumora.core.Platform;
import dev.lumora.core.social.GameSession;
import dev.lumora.core.social.SocialView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Shared layout of the Lumora multiplayer and singleplayer screens: logo and account card at the top, a toolbar with
 * search, a scrolling card list, the button bar at the bottom and modal dialogs. Drawn only with {@link HudGraphics},
 * so it is the same on every Minecraft version.
 */
abstract class BrowserView implements AddonView {
    static final int TEXT = 0xFFECE8F4;
    static final int MUTED = 0xFF9D95AE;
    static final int DIM = 0xFF6B7280;
    static final int SURFACE = 0xD017131F;
    static final int SURFACE_2 = 0xFF221C2D;
    static final int ROW = 0xB0221C2D;
    static final int ROW_HOVER = 0xD02B2340;
    static final int ROW_SELECTED = 0xE02E2152;
    static final int BORDER = 0xFF2F2741;
    static final int ACCENT = 0xFF8B5CF6;
    static final int ACCENT_HOVER = 0xFFA27BFA;
    static final int OK = 0xFF3DD68C;
    static final int WARN = 0xFFF5B342;
    static final int FAIL = 0xFFF2555A;
    static final TextureRef BACKGROUND = new TextureRef("lumora", "textures/gui/background_menu.png");
    static final TextureRef LOGO = new TextureRef("lumora", "icon.png");
    static final int ROW_H = 36;
    static final int ROW_GAP = 4;

    enum Style { PRIMARY, SECONDARY, DANGER, GHOST }

    record Hit(int x1, int y1, int x2, int y2, Runnable action) {
        boolean contains(double x, double y) {
            return x >= x1 && x < x2 && y >= y1 && y < y2;
        }
    }

    /**
     * Single-line text input with a selection (from {@code anchor} to {@code cursor}): click to place the cursor,
     * drag or Shift+arrows to select, Ctrl+A/C/X/V, typing replaces the selection.
     */
    static final class Field {
        final StringBuilder text = new StringBuilder();
        int cursor;
        int anchor;
        final int max;
        final String placeholder;
        /** Screen x of each cursor position as last drawn (for clicks); null before the first frame. */
        int[] xs;

        Field(int max, String placeholder) {
            this.max = max;
            this.placeholder = placeholder;
        }

        Field set(String s) {
            text.setLength(0);
            cursor = 0;
            anchor = 0;
            insert(s == null ? "" : s);
            return this;
        }

        boolean hasSelection() {
            return anchor != cursor;
        }

        int selStart() {
            return Math.min(anchor, cursor);
        }

        int selEnd() {
            return Math.max(anchor, cursor);
        }

        String selected() {
            return text.substring(selStart(), selEnd());
        }

        void deleteSelection() {
            if (hasSelection()) {
                int s = selStart();
                text.delete(s, selEnd());
                cursor = s;
                anchor = s;
            }
        }

        void insert(String s) {
            deleteSelection();
            for (int i = 0; i < s.length() && text.length() < max; i++) {
                char c = s.charAt(i);
                if (c >= 32 && c != 127 && c != '§') {
                    text.insert(cursor++, c);
                }
            }
            anchor = cursor;
        }

        void backspace(boolean word) {
            if (hasSelection()) {
                deleteSelection();
                return;
            }
            int from = word ? wordLeft() : Math.max(0, cursor - 1);
            text.delete(from, cursor);
            cursor = from;
            anchor = from;
        }

        void delete(boolean word) {
            if (hasSelection()) {
                deleteSelection();
                return;
            }
            int to = word ? wordRight() : Math.min(text.length(), cursor + 1);
            text.delete(cursor, to);
            anchor = cursor;
        }

        /** Moves the cursor; with {@code select} the selection grows, otherwise it is dropped. */
        void move(int to, boolean select) {
            cursor = Math.max(0, Math.min(text.length(), to));
            if (!select) {
                anchor = cursor;
            }
        }

        int wordLeft() {
            int i = cursor;
            while (i > 0 && text.charAt(i - 1) == ' ') {
                i--;
            }
            while (i > 0 && text.charAt(i - 1) != ' ') {
                i--;
            }
            return i;
        }

        int wordRight() {
            int i = cursor;
            while (i < text.length() && text.charAt(i) == ' ') {
                i++;
            }
            while (i < text.length() && text.charAt(i) != ' ') {
                i++;
            }
            return i;
        }

        void selectAll() {
            anchor = 0;
            cursor = text.length();
        }

        /** Cursor position closest to the screen x of a click. */
        int indexAt(double x) {
            if (xs == null) {
                return text.length();
            }
            int best = 0;
            for (int i = 1; i < xs.length; i++) {
                if (Math.abs(xs[i] - x) < Math.abs(xs[best] - x)) {
                    best = i;
                }
            }
            return Math.min(best, text.length());
        }

        String value() {
            return text.toString();
        }
    }

    /** Modal dialog with optional text fields. */
    final class Dialog {
        final String title;
        final String message;
        final List<Field> fields = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final String okLabel;
        final boolean danger;
        final Consumer<Dialog> onOk;
        String error;
        int focused;

        Dialog(String title, String message, String okLabel, boolean danger, Consumer<Dialog> onOk) {
            this.title = title;
            this.message = message;
            this.okLabel = okLabel;
            this.danger = danger;
            this.onOk = onOk;
        }

        Dialog field(String label, Field f) {
            labels.add(label);
            fields.add(f);
            return this;
        }

        String value(int i) {
            return fields.get(i).value().strip();
        }
    }

    final Platform platform;
    final BrowserPlatform game;
    final boolean de;
    final List<Hit> hits = new ArrayList<>();
    final Field search;
    Field focus;
    Dialog dialog;
    int selected = -1;
    double scroll;
    int listTop;
    int listBottom;
    private int contentHeight;
    private long lastClick;
    private int lastClickRow = -1;
    private boolean draggingBar;

    BrowserView(Platform platform, BrowserPlatform game) {
        this.platform = platform;
        this.game = game;
        String lang = platform.translations() == null ? "" : platform.translations().language();
        this.de = lang != null && lang.toLowerCase(Locale.ROOT).startsWith("de");
        this.search = new Field(64, t("Suchen…", "Search…"));
        this.focus = search;
    }

    final String t(String german, String english) {
        return de ? german : english;
    }

    // --- what the two screens fill in ---

    abstract String title();

    /** Toolbar items right of the search field, drawn right to left; returns the x where the search field ends. */
    abstract int toolbar(HudGraphics g, int left, int right, int y, int h, int mx, int my);

    abstract int rowCount();

    abstract void drawRow(HudGraphics g, int index, int x, int y, int w, int h, boolean hover, int mx, int my);

    /** Play / join the row (play button, double click, Enter). */
    abstract void activate(int index);

    /** Buttons of the bottom bar left of "Zurück"; returns the x after the last one. */
    abstract void bottomButtons(HudGraphics g, int x, int y, int h, int maxX, int mx, int my);

    abstract String emptyText();

    /** Delete key on the selected row. */
    void deleteRow(int index) {
    }

    // --- AddonView ---

    @Override
    public void render(HudGraphics g, int width, int height, int mouseX, int mouseY) {
        hits.clear();
        int mx = dialog == null ? mouseX : -1000;
        int my = dialog == null ? mouseY : -1000;
        background(g, width, height);
        int pad = width < 420 ? 8 : 16;
        header(g, pad, width - pad, 8);
        int tbY = 40;
        int searchEnd = toolbar(g, pad, width - pad, tbY, 20, mx, my);
        field(g, search, pad, tbY, Math.max(60, searchEnd - pad), 20, focus == search && dialog == null, mx, my, () -> focus = search);
        listTop = tbY + 28;
        listBottom = height - 34;
        list(g, pad, listTop, width - 2 * pad, listBottom - listTop, mx, my);
        int by = height - 27;
        int bw = 80;
        bottomButtons(g, pad, by, 20, width - pad - bw - 8, mx, my);
        button(g, width - pad - bw, by, bw, 20, t("Zurück", "Back"), Style.SECONDARY, mx, my, this::back);
        if (dialog != null) {
            hits.clear();
            drawDialog(g, width, height, mouseX, mouseY);
        }
    }

    void back() {
        platform.views().close(this);
    }

    private void background(HudGraphics g, int w, int h) {
        float s = Math.max(w / 1920f, h / 1080f);
        int rw = Math.min(1920, Math.round(w / s));
        int rh = Math.min(1080, Math.round(h / s));
        g.texture(BACKGROUND, 0, 0, w, h, (1920 - rw) / 2f, (1080 - rh) / 2f, rw, rh, 1920, 1080);
        g.fill(0, 0, w, h, 0x780F0C17);
        g.fill(0, 0, w, 36, 0x500F0C17);
        g.fill(0, h - 36, w, h, 0x500F0C17);
    }

    private void header(HudGraphics g, int left, int right, int y) {
        g.texture(LOGO, left, y, 22, 22, 128, 128);
        g.push();
        g.translate(left + 28, y + 2);
        g.scale(1.5f);
        g.text("§lLUMORA", 0, 0, TEXT, true);
        g.pop();
        g.text(title(), left + 28, y + 16, ACCENT_HOVER, false);
        account(g, right, y - 2);
    }

    private void account(HudGraphics g, int right, int y) {
        Optional<GameSession> s = platform.session();
        String name = s.map(GameSession::name).orElse("Spieler");
        String uuid = s.map(GameSession::uuid).orElse("");
        boolean premium = premium(uuid);
        String status = premium ? "Premium" : t("Offline-Konto", "Offline account");
        int w = Math.max(g.textWidth(name), g.textWidth(status)) + 40;
        int x = right - w;
        g.card(x, y, w, 26, 5, SURFACE, BORDER);
        SocialView.face(g, uuid, name, x + 5, y + 5, 16);
        g.text(name, x + 26, y + 4, TEXT, false);
        g.text(status, x + 26, y + 14, premium ? OK : WARN, false);
    }

    /** Microsoft accounts have random (version 4) profile UUIDs; offline accounts name based (version 3). */
    static boolean premium(String uuid) {
        String u = uuid == null ? "" : uuid.replace("-", "");
        return u.length() == 32 && u.charAt(12) == '4';
    }

    private void list(HudGraphics g, int x, int y, int w, int h, int mx, int my) {
        g.card(x, y, w, h, 6, SURFACE, BORDER);
        int n = rowCount();
        int inner = h - 8;
        contentHeight = n * (ROW_H + ROW_GAP) - (n > 0 ? ROW_GAP : 0);
        int maxScroll = Math.max(0, contentHeight - inner);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        if (n == 0) {
            g.centeredText(emptyText(), x + w / 2, y + h / 2 - 4, MUTED, false);
            return;
        }
        boolean bar = maxScroll > 0;
        int rowW = w - 8 - (bar ? 6 : 0);
        g.enableScissor(x + 1, y + 4, x + w - 1, y + h - 4);
        boolean inList = mx >= x && mx < x + w && my >= y + 4 && my < y + h - 4;
        for (int i = 0; i < n; i++) {
            int ry = y + 4 + i * (ROW_H + ROW_GAP) - (int) scroll;
            if (ry + ROW_H < y || ry > y + h) {
                continue;
            }
            boolean hover = inList && mx >= x + 4 && mx < x + 4 + rowW && my >= ry && my < ry + ROW_H;
            g.roundedRect(x + 4, ry, rowW, ROW_H, 4, i == selected ? ROW_SELECTED : hover ? ROW_HOVER : ROW);
            if (i == selected) {
                g.fill(x + 4, ry + 4, x + 6, ry + ROW_H - 4, ACCENT);
            }
            int index = i;
            int rowsBefore = hits.size();
            drawRow(g, i, x + 4, ry, rowW, ROW_H, hover, mx, my);
            // buttons of a row that is partly scrolled out only count inside the list, never over the toolbar or
            // the bottom bar
            int top = y + 4;
            int bottom = y + h - 4;
            for (int k = hits.size() - 1; k >= rowsBefore; k--) {
                Hit b = hits.get(k);
                int y1 = Math.max(b.y1(), top);
                int y2 = Math.min(b.y2(), bottom);
                if (y1 >= y2) {
                    hits.remove(k);
                } else {
                    hits.set(k, new Hit(b.x1(), y1, b.x2(), y2, b.action()));
                }
            }
            // the row click goes below its buttons
            if (Math.max(ry, top) < Math.min(ry + ROW_H, bottom)) {
                hits.add(rowsBefore, new Hit(x + 4, Math.max(ry, top), x + 4 + rowW, Math.min(ry + ROW_H, bottom), () -> clickRow(index)));
            }
        }
        g.disableScissor();
        if (bar) {
            int trackH = h - 8;
            int thumb = Math.max(16, trackH * inner / contentHeight);
            int ty = y + 4 + (int) ((trackH - thumb) * (scroll / maxScroll));
            g.roundedRect(x + w - 7, y + 4, 3, trackH, 1, 0x40FFFFFF);
            g.roundedRect(x + w - 7, ty, 3, thumb, 1, ACCENT);
            hits.add(new Hit(x + w - 9, y + 4, x + w, y + h - 4, () -> draggingBar = true));
        }
    }

    private void clickRow(int index) {
        long now = System.currentTimeMillis();
        if (index == lastClickRow && now - lastClick < 350) {
            activate(index);
            lastClick = 0;
            return;
        }
        selected = index;
        lastClickRow = index;
        lastClick = now;
    }

    /** Scrolls so that row {@code i} is visible. */
    void reveal(int i) {
        int inner = listBottom - listTop - 8;
        int top = i * (ROW_H + ROW_GAP);
        if (top < scroll) {
            scroll = top;
        } else if (top + ROW_H > scroll + inner) {
            scroll = top + ROW_H - inner;
        }
    }

    // --- widgets ---

    final boolean button(HudGraphics g, int x, int y, int w, int h, String label, Style style, int mx, int my, Runnable action) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        switch (style) {
            case PRIMARY -> g.roundedRect(x, y, w, h, 4, hover ? ACCENT_HOVER : ACCENT);
            case DANGER -> g.roundedRect(x, y, w, h, 4, hover ? 0xFFF56F73 : FAIL);
            case SECONDARY -> g.card(x, y, w, h, 4, hover ? 0xF02B2340 : 0xE0221C2D, hover ? ACCENT : BORDER);
            case GHOST -> {
                if (hover) {
                    g.roundedRect(x, y, w, h, 3, 0x40FFFFFF);
                }
            }
        }
        int fg = style == Style.GHOST ? (hover ? TEXT : MUTED) : TEXT;
        g.text(label, x + (w - g.textWidth(label)) / 2 + (style == Style.GHOST ? 0 : 1), y + (h - 8) / 2, fg, false);
        hits.add(new Hit(x, y, x + w, y + h, action));
        return hover;
    }

    /** A button of the bottom bar; {@code shortLabel} is used when the window is narrow. */
    record BarButton(String label, String shortLabel, Style style, Runnable action) {
    }

    /**
     * Draws the buttons from {@code x}; when they do not fit before {@code maxX} the short labels are used, then the
     * last buttons are left out. Returns the x after the last drawn button.
     */
    final int buttonRow(HudGraphics g, List<BarButton> buttons, int x, int y, int h, int maxX, int mx, int my) {
        for (int mode = 0; mode <= buttons.size(); mode++) {
            boolean compact = mode >= 1;
            int n = Math.max(1, buttons.size() - Math.max(0, mode - 1));
            int total = 0;
            for (int i = 0; i < n; i++) {
                total += barWidth(g, buttons.get(i), compact) + (i > 0 ? 6 : 0);
            }
            if (x + total <= maxX || n == 1) {
                int bx = x;
                for (int i = 0; i < n; i++) {
                    BarButton b = buttons.get(i);
                    int w = barWidth(g, b, compact);
                    button(g, bx, y, w, h, compact ? b.shortLabel() : b.label(), b.style(), mx, my, b.action());
                    bx += w + 6;
                }
                return bx;
            }
        }
        return x;
    }

    private static int barWidth(HudGraphics g, BarButton b, boolean compact) {
        return compact ? g.textWidth(b.shortLabel()) + 14 : Math.max(64, g.textWidth(b.label()) + 18);
    }

    /** Round play button; returns its width. */
    final int playButton(HudGraphics g, int x, int y, int size, boolean enabled, int mx, int my, Runnable action) {
        boolean hover = enabled && mx >= x && mx < x + size && my >= y && my < y + size;
        g.roundedRect(x, y, size, size, size / 2, !enabled ? 0x60FFFFFF : hover ? ACCENT_HOVER : ACCENT);
        // triangle made of rows
        int th = size / 2;
        int tx = x + size / 2 - th / 4;
        int ty = y + (size - th) / 2;
        for (int i = 0; i < th; i++) {
            int len = Math.min(i, th - 1 - i) + 1;
            g.fill(tx, ty + i, tx + len, ty + i + 1, 0xFFFFFFFF);
        }
        if (enabled) {
            hits.add(new Hit(x, y, x + size, y + size, action));
        }
        return size;
    }

    /** Pill switch with a label; returns its width. */
    final int toggle(HudGraphics g, int right, int y, int h, String label, boolean on, int mx, int my, Runnable action) {
        int w = g.textWidth(label) + 34;
        int x = right - w;
        boolean hover = mx >= x && mx < right && my >= y && my < y + h;
        g.card(x, y, w, h, 4, hover ? 0xF02B2340 : 0xE0221C2D, BORDER);
        int sx = x + 6;
        int sy = y + (h - 10) / 2;
        g.roundedRect(sx, sy, 18, 10, 5, on ? ACCENT : 0xFF3A3248);
        g.roundedRect(on ? sx + 9 : sx + 1, sy + 1, 8, 8, 4, 0xFFFFFFFF);
        g.text(label, sx + 23, y + (h - 8) / 2, on ? TEXT : MUTED, false);
        hits.add(new Hit(x, y, right, y + h, action));
        return w;
    }

    final void field(HudGraphics g, Field f, int x, int y, int w, int h, boolean focused, int mx, int my, Runnable onFocus) {
        g.card(x, y, w, h, 4, 0xE0221C2D, focused ? ACCENT : BORDER);
        int tx = x + 6;
        int ty = y + (h - 8) / 2;
        int room = w - 24;
        boolean blink = focused && (System.currentTimeMillis() / 500) % 2 == 0;
        g.enableScissor(x + 2, y + 1, x + w - 14, y + h - 1);
        if (f.text.length() == 0) {
            g.text(f.placeholder, tx, ty, DIM, false);
            if (blink) {
                g.fill(tx, ty - 1, tx + 1, ty + 9, TEXT);
            }
            f.xs = new int[]{tx};
        } else {
            int cw = g.textWidth(f.text.substring(0, f.cursor));
            int shift = Math.max(0, cw - room);
            int[] xs = new int[f.text.length() + 1];
            for (int i = 0; i <= f.text.length(); i++) {
                xs[i] = tx - shift + (i == f.cursor ? cw : g.textWidth(f.text.substring(0, i)));
            }
            f.xs = xs;
            if (f.hasSelection()) {
                g.fill(xs[f.selStart()], ty - 1, xs[f.selEnd()], ty + 9, focused ? 0xA08B5CF6 : 0x608B5CF6);
            }
            g.text(f.value(), tx - shift, ty, TEXT, false);
            if (blink && !f.hasSelection()) {
                g.fill(tx - shift + cw, ty - 1, tx - shift + cw + 1, ty + 9, TEXT);
            }
        }
        g.disableScissor();
        hits.add(new Hit(x, y, x + w, y + h, () -> {
            onFocus.run();
            pressField(f);
        }));
    }

    // --- mouse in text fields ---

    private double pressX;
    private Field dragField;
    private Field lastPressed;
    private long lastPressTime;

    /** Click in a field: place the cursor, double click selects everything. */
    private void pressField(Field f) {
        long now = System.currentTimeMillis();
        if (f == lastPressed && now - lastPressTime < 350) {
            f.selectAll();
            lastPressed = null;
            dragField = null;
            return;
        }
        f.move(f.indexAt(pressX), false);
        lastPressed = f;
        lastPressTime = now;
        dragField = f;
    }

    static String fit(HudGraphics g, String s, int max) {
        if (g.textWidth(s) <= max) {
            return s;
        }
        String r = s;
        while (r.length() > 1 && g.textWidth(r + "…") > max) {
            r = r.substring(0, r.length() - 1);
        }
        return r + "…";
    }

    boolean matches(String... texts) {
        String q = search.value().strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return true;
        }
        for (String s : texts) {
            if (s != null && ServerPinger.Motd.strip(s).toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
        }
        return false;
    }

    // --- dialogs ---

    void open(Dialog d) {
        dialog = d;
        d.focused = 0;
        focus = d.fields.isEmpty() ? null : d.fields.get(0);
    }

    void closeDialog() {
        dialog = null;
        focus = search;
    }

    private void drawDialog(HudGraphics g, int width, int height, int mx, int my) {
        Dialog d = dialog;
        g.fill(0, 0, width, height, 0xA0000000);
        int w = Math.min(260, width - 32);
        List<String> msg = d.message == null ? List.of() : wrap(g, d.message, w - 24);
        int h = 30 + msg.size() * 11 + d.fields.size() * 36 + 34;
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        g.card(x, y, w, h, 7, 0xFA17131F, BORDER);
        // the dialog itself swallows clicks that hit nothing in it; added first, so its fields and buttons win
        hits.add(new Hit(x, y, x + w, y + h, () -> { }));
        g.text("§l" + d.title, x + 12, y + 12, TEXT, false);
        int cy = y + 28;
        for (String line : msg) {
            g.text(line, x + 12, cy, MUTED, false);
            cy += 11;
        }
        for (int i = 0; i < d.fields.size(); i++) {
            Field f = d.fields.get(i);
            g.text(d.labels.get(i), x + 12, cy + 2, MUTED, false);
            int idx = i;
            field(g, f, x + 12, cy + 12, w - 24, 20, focus == f, mx, my, () -> {
                focus = f;
                d.focused = idx;
            });
            cy += 36;
        }
        if (d.error != null) {
            g.text(d.error, x + 12, cy - 2, FAIL, false);
        }
        int bw = (w - 30) / 2;
        int by = y + h - 28;
        button(g, x + 12, by, bw, 20, t("Abbrechen", "Cancel"), Style.SECONDARY, mx, my, this::closeDialog);
        button(g, x + w - 12 - bw, by, bw, 20, d.okLabel, d.danger ? Style.DANGER : Style.PRIMARY, mx, my, () -> d.onOk.accept(d));
    }

    static List<String> wrap(HudGraphics g, String text, int max) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                String next = line.length() == 0 ? word : line + " " + word;
                if (g.textWidth(next) > max && line.length() > 0) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(next);
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    // --- input ---

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) {
            return false;
        }
        pressX = x;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.contains(x, y)) {
                if (dialog == null) {
                    focus = search;
                }
                h.action().run();
                return true;
            }
        }
        if (dialog != null) {
            closeDialog();
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button) {
        if (draggingBar) {
            int trackH = Math.max(1, listBottom - listTop - 8);
            int maxScroll = Math.max(0, contentHeight - trackH);
            scroll = Math.max(0, Math.min(maxScroll, (y - listTop - 4) / trackH * maxScroll));
            return true;
        }
        if (dragField != null && dragField == focus) {
            dragField.move(dragField.indexAt(x), true); // select by dragging
            lastPressed = null; // a drag is no first click of a double click
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        draggingBar = false;
        dragField = null;
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (dialog == null) {
            scroll -= delta * 24;
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, boolean ctrl, boolean shift) {
        if (dialog != null) {
            Dialog d = dialog;
            if (key == Keys.ENTER || key == 335) {
                d.onOk.accept(d);
                return true;
            }
            if (key == Keys.TAB && !d.fields.isEmpty()) {
                d.focused = (d.focused + (shift ? d.fields.size() - 1 : 1)) % d.fields.size();
                focus = d.fields.get(d.focused);
                return true;
            }
        } else {
            switch (key) {
                case Keys.ENTER, 335 -> {
                    if (selected >= 0 && selected < rowCount()) {
                        activate(selected);
                    }
                    return true;
                }
                case Keys.DOWN -> {
                    selected = Math.min(rowCount() - 1, selected + 1);
                    reveal(selected);
                    return true;
                }
                case Keys.UP -> {
                    selected = Math.max(0, selected - 1);
                    reveal(selected);
                    return true;
                }
                case Keys.DELETE -> {
                    if (search.text.length() == 0 && selected >= 0 && selected < rowCount()) {
                        deleteRow(selected);
                        return true;
                    }
                }
                default -> {
                }
            }
        }
        Field f = focus;
        if (f == null) {
            return false;
        }
        switch (key) {
            case Keys.BACKSPACE -> f.backspace(ctrl);
            case Keys.DELETE -> f.delete(ctrl);
            case Keys.LEFT -> f.move(ctrl ? f.wordLeft() : !shift && f.hasSelection() ? f.selStart() : f.cursor - 1, shift);
            case Keys.RIGHT -> f.move(ctrl ? f.wordRight() : !shift && f.hasSelection() ? f.selEnd() : f.cursor + 1, shift);
            case Keys.HOME -> f.move(0, shift);
            case Keys.END -> f.move(f.text.length(), shift);
            case Keys.A -> {
                if (!ctrl) {
                    return false;
                }
                f.selectAll();
            }
            case Keys.C, Keys.X -> {
                if (!ctrl) {
                    return false;
                }
                if (f.hasSelection()) {
                    platform.views().setClipboard(f.selected());
                    if (key == Keys.X) {
                        f.deleteSelection();
                    }
                }
            }
            case Keys.V -> {
                if (!ctrl) {
                    return false;
                }
                f.insert(platform.views().clipboard().replace('\n', ' ').replace('\r', ' '));
            }
            default -> {
                return false;
            }
        }
        if (f == search) {
            selected = -1;
        }
        return true;
    }

    @Override
    public boolean charTyped(char ch) {
        if (focus == null) {
            return false;
        }
        focus.insert(String.valueOf(ch));
        if (focus == search) {
            selected = -1;
            scroll = 0;
        }
        return true;
    }

    @Override
    public boolean closeRequested() {
        if (dialog != null) {
            closeDialog();
            return false;
        }
        return true;
    }
}
