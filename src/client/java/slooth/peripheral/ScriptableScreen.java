package slooth.peripheral;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Script-driven GUI screen. Open via {@code open_gui(layout)}, update via
 * {@code gui_update(id, **props)}, press H to open/close while script runs.
 *
 * <h2>Widget types</h2>
 * <pre>
 *   label        – text line.  props: text, color, shadow, align (left/center/right), w (wraps at width)
 *   section      – orange section header with thin underline.  props: text, color, w
 *   bar          – horizontal progress bar.  props: value (0–1), w, h, color, bg_color
 *   divider      – 1px horizontal rule.  props: w, color
 *   rect         – filled rectangle.  props: w, h, color, border (color, omit = no border)
 *   button       – native MC button.  fires event on click.  props: label, w, h
 *   flat_button  – styled flat button matching the panel theme.  fires event.  props: label, w, h, color, bg_color
 *   input        – text field.  props: w, h, placeholder, value.  read via gui_input(id)
 *   checkbox     – toggle box.  props: label, checked (bool), color.  fires event; read via gui_input(id)
 *   slider       – horizontal drag slider.  props: value, min, max, step, w, h, color, show_value.
 *                  fires event "{id}:{value}" on release.  read via gui_input(id)
 *   list         – scrollable row list.  props: items, row_h, w, h, selected, scroll, bg_color.
 *                  fires event "{id}:{row_index}" on click.  selected index via gui_input(id).
 *                  items = list of strings OR {text,color,dot,detail,row_color}
 *   textbox      – scrollable read-only multi-line text.  props: lines (list), text (newline-str),
 *                  w, h, color, line_h, bg_color.  scrollable with mouse wheel.
 * </pre>
 *
 * <h2>Panel options</h2>
 * <pre>
 *   title, w, h, bg_color, accent_color, title_color
 * </pre>
 *
 * <h2>Colors</h2>
 * Named: red green yellow blue white grey dark_grey orange purple pink cyan lime dark_red dark_green
 * Hex:   '#RRGGBB' or '#AARRGGBB'
 */
public class ScriptableScreen extends Screen {

    // ── Layout constants ──────────────────────────────────────────────────────
    private static final int TITLE_H = 15;
    private static final int PAD     = 6;

    // ── Default colours ───────────────────────────────────────────────────────
    private static final int C_BG_DEFAULT     = 0xEE0D0D0D;
    private static final int C_ACCENT_DEFAULT = 0xFF153020;
    private static final int C_TITLE_DEFAULT  = 0xDD0E1F11;

    // ── Shared state (HTTP thread writes, render thread reads) ─────────────────
    private static volatile String ownerScript = "";
    private static volatile String sTitle     = "Script GUI";
    private static volatile int    sW         = 300;
    private static volatile int    sH         = 200;
    private static volatile int    sBg        = C_BG_DEFAULT;
    private static volatile int    sAccent    = C_ACCENT_DEFAULT;
    private static volatile int    sTitleBg   = C_TITLE_DEFAULT;

    private static final List<String>            widgetOrder = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, JsonObject> widgetMap   = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean screenOpen = new AtomicBoolean(false);
    private static volatile ScriptableScreen instance = null;
    private static final int C_GREEN          = 0xFF00FFAA;
    private static final int C_WHITE          = 0xFFDDDDDD;
    private static final int C_GREY           = 0xFF888888;
    private static final int C_DIM            = 0xFF444444;
    private static final int C_ORANGE         = 0xFFFF8800;
    private static final int C_BAR_BG         = 0xFF2A2A2A;
    private static final int C_ROW_SEL        = 0xCC1A3A24;
    private static final int C_ROW_HOV        = 0x22FFFFFF;
    private static final int C_SCROLL         = 0xFF1A1A1A;
    private static final int C_SCROLL_THUMB   = 0xFF404040;

    // ── Instance fields ───────────────────────────────────────────────────────
    private int panelX, panelY;
    private final Map<String, Button>  buttons = new LinkedHashMap<>();
    private final Map<String, EditBox> inputs  = new HashMap<>();
    private String sliderDragId   = null;
    private String openDropdownId = null;

    // ══════════════════════════════════════════════════════════════════════════
    // Static API — called from PeripheralHttpServer on the HTTP thread
    // ══════════════════════════════════════════════════════════════════════════

    /** Open with the given layout JSON. Safe to call from any thread. */
    public static void openWithLayout(JsonObject layout, String script) {
        ownerScript = script == null ? "" : script;
        sTitle   = str(layout, "title",        "Script GUI");
        sW       = num(layout, "w",            300);
        sH       = num(layout, "h",            200);
        sBg      = color(layout, "bg_color",   C_BG_DEFAULT);
        sAccent  = color(layout, "accent_color", C_ACCENT_DEFAULT);
        sTitleBg = color(layout, "title_color", C_TITLE_DEFAULT);
        widgetOrder.clear();
        widgetMap.clear();
        events.clear();
        if (layout.has("widgets")) {
            for (JsonElement el : layout.getAsJsonArray("widgets")) {
                if (!el.isJsonObject()) continue;
                JsonObject w = el.getAsJsonObject().deepCopy();
                String id = str(w, "id", "");
                if (!id.isEmpty()) {
                    widgetOrder.add(id);
                    widgetMap.put(id, w);
                }
            }
        }
        Minecraft.getInstance().execute(() ->
            Minecraft.getInstance().setScreen(new ScriptableScreen()));
    }

    /** Close and wipe the layout if the given script owns it. */
    public static void clearIfOwner(String script) {
        if (script == null || script.isEmpty()) return;
        if (!script.equals(ownerScript)) return;
        ownerScript = "";
        synchronized (widgetOrder) {
            widgetOrder.clear();
            widgetMap.clear();
            events.clear();
        }
        closeScreen();
    }

    /** Update one widget's properties while the screen is open. Any thread. */
    public static void update(String id, JsonObject props) {
        synchronized (widgetOrder) {
            JsonObject w = widgetMap.get(id);
            if (w == null) return;
            for (Map.Entry<String, JsonElement> e : props.entrySet())
                w.add(e.getKey(), e.getValue());
        }
    }

    /** Pop the next event (button click, list row click, etc.), or null. */
    public static String pollEvent() { return events.poll(); }

    /** True if a ScriptableScreen is currently displayed. */
    public static boolean isOpen()    { return screenOpen.get(); }

    /** True if a layout has been loaded (re-opening makes sense). */
    public static boolean hasLayout() { return !widgetOrder.isEmpty(); }

    /** Close the screen if open. Safe from any thread. */
    public static void closeScreen() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> { if (mc.screen instanceof ScriptableScreen) mc.setScreen(null); });
    }

    /**
     * Return all readable widget values as id → string.
     * Covers: input (text), checkbox (true/false), list (selected index), slider (float).
     */
    public static Map<String, String> getInputValues() {
        ScriptableScreen scr = instance;
        Map<String, String> map = new LinkedHashMap<>();
        if (scr != null) scr.inputs.forEach((id, tf) -> map.put(id, tf.getValue()));
        synchronized (widgetOrder) {
            for (String id : widgetOrder) {
                JsonObject w = widgetMap.get(id);
                if (w == null) continue;
                switch (str(w, "type", "")) {
                    case "checkbox" -> map.put(id, String.valueOf(bool(w, "checked", false)));
                    case "list"     -> map.put(id, String.valueOf(num(w, "selected", -1)));
                    case "slider"   -> map.put(id, formatFloat(flt(w, "value", 0f)));
                    case "tabs"     -> map.put(id, str(w, "selected", ""));
                    case "dropdown" -> map.put(id, String.valueOf(num(w, "selected", 0)));
                }
            }
        }
        return map;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Screen lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    public ScriptableScreen() {
        super(Component.literal(sTitle));
        screenOpen.set(true);
        instance = this;
    }

    @Override
    protected void init() {
        panelX = (width  - sW) / 2;
        panelY = (height - sH) / 2;

        buttons.values().forEach(this::removeWidget);
        buttons.clear();
        inputs.values().forEach(this::removeWidget);
        inputs.clear();

        synchronized (widgetOrder) {
            for (String id : widgetOrder) {
                JsonObject w = widgetMap.get(id);
                if (w == null) continue;
                String type = str(w, "type", "");
                int wx = panelX + PAD + num(w, "x", 0);
                int wy = panelY + TITLE_H + num(w, "y", 0);

                switch (type) {
                    case "button" -> {
                        int bw = num(w, "w", 60);
                        int bh = num(w, "h", 14);
                        String fid = id;
                        Button btn = Button.builder(Component.literal(str(w, "label", id)),
                                b -> events.offer(fid))
                            .bounds(wx, wy, bw, bh).build();
                        buttons.put(id, btn);
                        addRenderableWidget(btn);
                    }
                    case "input" -> {
                        int iw = num(w, "w", 150);
                        int ih = num(w, "h", 14);
                        EditBox tf = new EditBox(font, wx, wy, iw, ih, Component.empty());
                        if (w.has("placeholder")) {
                            String hint = str(w, "placeholder", "");
                            tf.setSuggestion(hint);
                            tf.setResponder(text -> tf.setSuggestion(text.isEmpty() ? hint : ""));
                        }
                        if (w.has("value"))       tf.setValue(str(w, "value", ""));
                        inputs.put(id, tf);
                        addRenderableWidget(tf);
                    }
                }
            }
        }
        updateNativeVisibility();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Render
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        // Suppress blur — applyBlur() can only fire once per frame
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        // Dim world behind panel
        ctx.fill(0, 0, width, height, 0x77000000);

        // ── Panel background ──────────────────────────────────────────────────
        ctx.fill(panelX,        panelY,        panelX + sW,     panelY + sH,      sBg);

        // Outer border
        ctx.fill(panelX,        panelY,        panelX + sW,     panelY + 1,       sAccent);
        ctx.fill(panelX,        panelY + sH-1, panelX + sW,     panelY + sH,      sAccent);
        ctx.fill(panelX,        panelY,        panelX + 1,      panelY + sH,      sAccent);
        ctx.fill(panelX + sW-1, panelY,        panelX + sW,     panelY + sH,      sAccent);

        // Corner accent notches (like Peripheral's other screens)
        ctx.fill(panelX + 1,    panelY + 1,    panelX + 3,      panelY + 2,       sAccent);
        ctx.fill(panelX + 1,    panelY + 1,    panelX + 2,      panelY + 3,       sAccent);
        ctx.fill(panelX + sW-3, panelY + 1,    panelX + sW-1,   panelY + 2,       sAccent);
        ctx.fill(panelX + sW-2, panelY + 1,    panelX + sW-1,   panelY + 3,       sAccent);

        // ── Title bar ─────────────────────────────────────────────────────────
        if (!sTitle.isEmpty()) {
            ctx.fill(panelX + 1, panelY + 1, panelX + sW - 1, panelY + TITLE_H,     sTitleBg);
            ctx.fill(panelX + 1, panelY + TITLE_H - 1, panelX + sW - 1, panelY + TITLE_H, sAccent);
            ctx.text(font, Component.literal("§a◈ §f" + sTitle),
                panelX + 5, panelY + 4, C_GREEN, false);
        }

        // ── Widgets ───────────────────────────────────────────────────────────
        synchronized (widgetOrder) {
            Set<String> activeTabs = buildActiveTabs();
            for (String id : widgetOrder) {
                JsonObject w = widgetMap.get(id);
                if (w == null) continue;
                if (!isTabVisible(w, activeTabs)) continue;
                String type = str(w, "type", "");
                int wx = panelX + PAD + num(w, "x", 0);
                int wy = panelY + TITLE_H + num(w, "y", 0);
                renderWidget(ctx, mouseX, mouseY, type, w, wx, wy);
            }
        }

        super.extractRenderState(ctx, mouseX, mouseY, delta);

        // ── Open dropdown popup (rendered last so it floats above everything) ─
        if (openDropdownId != null) {
            synchronized (widgetOrder) {
                JsonObject w = widgetMap.get(openDropdownId);
                if (w != null)
                    renderDropdownPopup(ctx, mouseX, mouseY, w,
                        panelX + PAD + num(w, "x", 0),
                        panelY + TITLE_H + num(w, "y", 0));
            }
        }
    }

    private void renderWidget(GuiGraphicsExtractor ctx, int mx, int my,
                              String type, JsonObject w, int wx, int wy) {
        switch (type) {

            // ── label ─────────────────────────────────────────────────────────
            case "label" -> {
                String  text  = str(w, "text", "");
                int     col   = color(w, "color", C_WHITE);
                boolean shad  = bool(w, "shadow", false);
                String  align = str(w, "align", "left");
                int     lw    = num(w, "w", 0);
                if (lw > 0 && !text.isEmpty()) {
                    int ty = wy;
                    for (String line : wrapText(text, lw)) {
                        ctx.text(font, Component.literal(line),
                            alignX(wx, lw, font.width(line), align), ty, col, shad);
                        ty += 9;
                    }
                } else {
                    int tx = (lw > 0) ? alignX(wx, lw, font.width(text), align) : wx;
                    ctx.text(font, Component.literal(text), tx, wy, col, shad);
                }
            }

            // ── section header ────────────────────────────────────────────────
            case "section" -> {
                String text = str(w, "text", "");
                int    col  = color(w, "color", C_ORANGE);
                int    sw   = num(w, "w", sW - PAD * 2);
                ctx.text(font, Component.literal(text), wx, wy, col, false);
                ctx.fill(wx, wy + 11, wx + sw, wy + 12, col & 0x44FFFFFF | 0x44000000);
            }

            // ── bar ───────────────────────────────────────────────────────────
            case "bar" -> {
                int   bw  = num(w, "w", 100);
                int   bh  = num(w, "h", 5);
                float val = Math.max(0, Math.min(1, flt(w, "value", 0f)));
                int   col = color(w, "color", C_GREEN);
                int   bg  = color(w, "bg_color", C_BAR_BG);
                ctx.fill(wx,        wy, wx + bw,               wy + bh, bg);
                ctx.fill(wx,        wy, wx + (int)(val * bw),  wy + bh, col);
                // Thin border on bar container
                ctx.fill(wx,        wy,        wx + bw, wy + 1,  0x33FFFFFF);
                ctx.fill(wx,        wy + bh-1, wx + bw, wy + bh, 0x22000000);
            }

            // ── divider ───────────────────────────────────────────────────────
            case "divider" -> {
                int dw  = num(w, "w", sW - PAD * 2);
                int col = color(w, "color", C_DIM);
                ctx.fill(wx, wy, wx + dw, wy + 1, col);
            }

            // ── rect ──────────────────────────────────────────────────────────
            case "rect" -> {
                int rw  = num(w, "w", 50);
                int rh  = num(w, "h", 20);
                int col = color(w, "color", 0xFF1A1A1A);
                ctx.fill(wx, wy, wx + rw, wy + rh, col);
                if (w.has("border")) {
                    int bc = color(w, "border", sAccent);
                    ctx.fill(wx,       wy,       wx + rw, wy + 1,  bc);
                    ctx.fill(wx,       wy + rh-1,wx + rw, wy + rh, bc);
                    ctx.fill(wx,       wy,       wx + 1,  wy + rh, bc);
                    ctx.fill(wx + rw-1,wy,       wx + rw, wy + rh, bc);
                }
            }

            // ── checkbox ──────────────────────────────────────────────────────
            case "checkbox" -> {
                boolean checked = bool(w, "checked", false);
                String  label   = str(w, "label", "");
                int     col     = color(w, "color", C_WHITE);
                boolean hov     = mx >= wx && mx < wx + 11 && my >= wy && my < wy + 11;
                ctx.fill(wx, wy, wx + 11, wy + 11, 0xFF1A1A1A);
                if (hov) ctx.fill(wx, wy, wx + 11, wy + 11, 0x22FFFFFF);
                ctx.fill(wx,    wy,    wx + 11, wy + 1,  sAccent);
                ctx.fill(wx,    wy+10, wx + 11, wy + 11, sAccent);
                ctx.fill(wx,    wy,    wx + 1,  wy + 11, sAccent);
                ctx.fill(wx+10, wy,    wx + 11, wy + 11, sAccent);
                if (checked) {
                    // Checkmark fill
                    ctx.fill(wx + 2, wy + 2, wx + 9, wy + 9, C_GREEN);
                    ctx.fill(wx + 3, wy + 3, wx + 8, wy + 8, 0xFF007744);
                }
                if (!label.isEmpty())
                    ctx.text(font, Component.literal(label), wx + 15, wy + 2, col, false);
            }

            // ── slider ────────────────────────────────────────────────────────
            case "slider" -> {
                float val  = flt(w, "value", 0f);
                float min  = flt(w, "min", 0f);
                float max  = flt(w, "max", 1f);
                int   slw  = num(w, "w", 100);
                int   slh  = num(w, "h", 10);
                int   col  = color(w, "color", C_GREEN);
                float range = max - min;
                float t    = range > 0 ? Math.max(0, Math.min(1, (val - min) / range)) : 0;
                int   mid  = wy + slh / 2;
                int   fill = (int)(t * slw);
                // Track background
                ctx.fill(wx,       mid - 1, wx + slw, mid + 2, 0xFF242424);
                ctx.fill(wx,       mid - 1, wx + slw, mid,     0xFF111111);
                // Filled portion
                if (fill > 0) ctx.fill(wx, mid - 1, wx + fill, mid + 2, col & 0xAAFFFFFF | 0x55000000);
                // Handle
                int hx = wx + fill;
                ctx.fill(hx - 3, wy,     hx + 3, wy + slh,     0xFF333333);
                ctx.fill(hx - 2, wy + 1, hx + 2, wy + slh - 1, 0xFFAAAAAA);
                ctx.fill(hx - 1, wy + 2, hx + 1, wy + slh - 2, C_WHITE);
                // Value label
                if (bool(w, "show_value", false)) {
                    String sv = formatFloat(val);
                    ctx.text(font, Component.literal(sv), wx + slw + 5, wy + (slh - 8) / 2, C_GREY, false);
                }
            }

            // ── list ─────────────────────────────────────────────────────────
            case "list"    -> renderList(ctx, mx, my, w, wx, wy);

            // ── textbox ───────────────────────────────────────────────────────
            case "textbox" -> renderTextbox(ctx, mx, my, w, wx, wy);

            // ── flat_button ───────────────────────────────────────────────────
            case "flat_button" -> {
                int    bw  = num(w, "w", 60);
                int    bh  = num(w, "h", 14);
                String lbl = str(w, "label", "");
                int    col = color(w, "color", C_GREEN);
                int    bg  = color(w, "bg_color", 0xFF181818);
                boolean hov = mx >= wx && mx < wx + bw && my >= wy && my < wy + bh;
                ctx.fill(wx,      wy,      wx + bw,   wy + bh,   bg);
                if (hov) ctx.fill(wx, wy, wx + bw, wy + bh, 0x22FFFFFF);
                // Border
                ctx.fill(wx,       wy,      wx + bw, wy + 1,  col & 0x77FFFFFF | 0x77000000);
                ctx.fill(wx,       wy+bh-1, wx + bw, wy + bh, col & 0x33FFFFFF | 0x33000000);
                ctx.fill(wx,       wy,      wx + 1,  wy + bh, col & 0x33FFFFFF | 0x33000000);
                ctx.fill(wx + bw-1,wy,      wx + bw, wy + bh, col & 0x33FFFFFF | 0x33000000);
                // Label
                int lw2 = font.width(lbl);
                ctx.text(font, Component.literal(lbl), wx + (bw - lw2) / 2, wy + (bh - 8) / 2, col, false);
            }

            // ── tabs ──────────────────────────────────────────────────────────
            case "tabs" -> {
                JsonArray tabItems = w.has("items") ? w.getAsJsonArray("items") : new JsonArray();
                int n  = tabItems.size();
                if (n == 0) break;
                int tw  = num(w, "w", sW - PAD * 2);
                int th  = 14;
                int tabW = tw / n;
                String selected = str(w, "selected", "");
                for (int i = 0; i < n; i++) {
                    JsonObject tab = tabItems.get(i).getAsJsonObject();
                    String tabId  = str(tab, "id", String.valueOf(i));
                    String label  = str(tab, "label", tabId);
                    int tx = wx + i * tabW;
                    boolean sel = tabId.equals(selected);
                    boolean hov = mx >= tx && mx < tx + tabW && my >= wy && my < wy + th && !sel;
                    ctx.fill(tx, wy, tx + tabW, wy + th, sel ? 0xFF151515 : 0xFF0A0A0A);
                    if (hov) ctx.fill(tx, wy, tx + tabW, wy + th, C_ROW_HOV);
                    if (sel)  ctx.fill(tx, wy, tx + tabW, wy + 2, sAccent);
                    if (i > 0) ctx.fill(tx, wy + 2, tx + 1, wy + th - 1, C_DIM);
                    int lw2 = font.width(label);
                    ctx.text(font, Component.literal(label),
                        tx + (tabW - lw2) / 2, wy + 3, sel ? C_GREEN : C_GREY, false);
                    if (!sel) ctx.fill(tx, wy + th - 1, tx + tabW, wy + th, sAccent);
                }
                int usedW = tabW * n;
                if (usedW < tw) ctx.fill(wx + usedW, wy + th - 1, wx + tw, wy + th, sAccent);
            }

            // ── dropdown (closed state) ────────────────────────────────────────
            case "dropdown" -> {
                int    dw  = num(w, "w", 100);
                int    dh  = num(w, "h", 12);
                JsonArray opts = w.has("options") ? w.getAsJsonArray("options") : new JsonArray();
                int    sel = num(w, "selected", 0);
                String selText = (sel >= 0 && sel < opts.size()) ? opts.get(sel).getAsString() : "";
                String wid = str(w, "id", "");
                boolean open = wid.equals(openDropdownId);
                boolean hov  = mx >= wx && mx < wx + dw && my >= wy && my < wy + dh;
                ctx.fill(wx,        wy,       wx + dw,   wy + dh,   0xFF1A1A1A);
                if (hov || open) ctx.fill(wx, wy, wx + dw, wy + dh, 0x22FFFFFF);
                ctx.fill(wx,        wy,       wx + dw,   wy + 1,    sAccent);
                ctx.fill(wx,        wy + dh-1,wx + dw,   wy + dh,   sAccent);
                ctx.fill(wx,        wy,       wx + 1,    wy + dh,   sAccent);
                ctx.fill(wx + dw-1, wy,       wx + dw,   wy + dh,   sAccent);
                ctx.text(font, Component.literal(selText), wx + 4, wy + (dh - 8) / 2, C_WHITE, false);
                String arrow = open ? "▲" : "▼";
                int aw = font.width(arrow);
                ctx.text(font, Component.literal(arrow), wx + dw - aw - 3, wy + (dh - 8) / 2, C_GREY, false);
            }

            // ── item icon ─────────────────────────────────────────────────────
            case "item" -> {
                String itemId = str(w, "item", "");
                if (!itemId.isEmpty()) {
                    if (!itemId.contains(":")) itemId = "minecraft:" + itemId;
                    Identifier ident = Identifier.tryParse(itemId);
                    if (ident != null && BuiltInRegistries.ITEM.containsKey(ident))
                        ctx.item(new ItemStack(BuiltInRegistries.ITEM.getValue(ident)), wx, wy);
                }
                int count = num(w, "count", 0);
                if (count > 1) {
                    String cs = String.valueOf(count);
                    ctx.text(font, Component.literal(cs), wx + 17 - font.width(cs), wy + 9, C_WHITE, true);
                }
                String lbl = str(w, "label", "");
                if (!lbl.isEmpty())
                    ctx.text(font, Component.literal(lbl), wx + 20, wy + 4, C_GREY, false);
            }

            // "button" and "input" are drawn by MC via addRenderableWidget
        }
    }

    // ── Dropdown popup (rendered as floating overlay) ─────────────────────────

    private void renderDropdownPopup(GuiGraphicsExtractor ctx, int mx, int my,
                                     JsonObject w, int wx, int wy) {
        int dw  = num(w, "w", 100);
        int dh  = num(w, "h", 12);
        JsonArray opts = w.has("options") ? w.getAsJsonArray("options") : new JsonArray();
        int n   = opts.size();
        if (n == 0) return;
        int rowH = dh;
        int popH = n * rowH;
        int py   = wy + dh;
        if (py + popH > panelY + sH - PAD) py = wy - popH;
        int sel  = num(w, "selected", 0);

        ctx.fill(wx, py, wx + dw, py + popH, 0xFF111111);
        ctx.fill(wx, py, wx + dw, py + 1, sAccent);
        ctx.fill(wx, py + popH - 1, wx + dw, py + popH, sAccent);
        ctx.fill(wx, py, wx + 1, py + popH, sAccent);
        ctx.fill(wx + dw - 1, py, wx + dw, py + popH, sAccent);

        ctx.enableScissor(wx + 1, py + 1, wx + dw - 1, py + popH - 1);
        for (int i = 0; i < n; i++) {
            String opt = opts.get(i).getAsString();
            int ry = py + i * rowH;
            boolean hov = mx >= wx && mx < wx + dw && my >= ry && my < ry + rowH;
            if (i == sel) ctx.fill(wx + 1, ry, wx + dw - 1, ry + rowH, C_ROW_SEL);
            else if (hov) ctx.fill(wx + 1, ry, wx + dw - 1, ry + rowH, C_ROW_HOV);
            ctx.text(font, Component.literal(opt), wx + 4, ry + (rowH - 8) / 2,
                hov ? C_WHITE : C_GREY, false);
        }
        ctx.disableScissor();
    }

    // ── List widget ───────────────────────────────────────────────────────────

    private void renderList(GuiGraphicsExtractor ctx, int mx, int my,
                            JsonObject w, int wx, int wy) {
        int lw      = num(w, "w", 150);
        int lh      = num(w, "h", 80);
        int rowH    = num(w, "row_h", 16);
        int scroll  = num(w, "scroll", 0);
        int selected= num(w, "selected", -1);
        JsonArray items = w.has("items") ? w.getAsJsonArray("items") : new JsonArray();
        int total   = items.size();
        int vis     = Math.max(1, lh / rowH);
        scroll      = Math.max(0, Math.min(scroll, Math.max(0, total - vis)));

        int sbW     = (total > vis) ? 4 : 0;
        int innerW  = lw - 2 - sbW;

        // Background + border
        ctx.fill(wx,        wy,        wx + lw,     wy + lh,     color(w, "bg_color", 0xCC0C0C0C));
        ctx.fill(wx,        wy,        wx + lw,     wy + 1,      sAccent);
        ctx.fill(wx,        wy + lh-1, wx + lw,     wy + lh,     sAccent);
        ctx.fill(wx,        wy,        wx + 1,      wy + lh,     sAccent);
        ctx.fill(wx + lw-1, wy,        wx + lw,     wy + lh,     sAccent);

        ctx.enableScissor(wx + 1, wy + 1, wx + lw - 1 - sbW, wy + lh - 1);

        for (int i = scroll; i < Math.min(total, scroll + vis); i++) {
            int ry  = wy + (i - scroll) * rowH;
            boolean isSel = (i == selected);
            boolean isHov = mx >= wx + 1 && mx < wx + lw - sbW - 1
                         && my >= ry && my < ry + rowH;

            JsonElement el = items.get(i);
            int rowBg = 0;
            if (el.isJsonObject() && el.getAsJsonObject().has("row_color"))
                rowBg = color(el.getAsJsonObject(), "row_color", 0);

            // Row background
            if (isSel)         ctx.fill(wx + 1, ry, wx + 1 + innerW, ry + rowH, C_ROW_SEL);
            else if (rowBg != 0) ctx.fill(wx + 1, ry, wx + 1 + innerW, ry + rowH, rowBg);
            else if (isHov)    ctx.fill(wx + 1, ry, wx + 1 + innerW, ry + rowH, C_ROW_HOV);

            // Row content
            String text      = "";
            int    textColor = C_WHITE;
            String dot       = null;
            String detail    = null;

            if (el.isJsonObject()) {
                JsonObject item = el.getAsJsonObject();
                text      = str(item, "text", "");
                textColor = color(item, "color", C_WHITE);
                if (item.has("dot"))    dot    = item.get("dot").getAsString();
                if (item.has("detail")) detail = item.get("detail").getAsString();
            } else {
                text = el.getAsString();
            }

            // Dot indicator (like the Scripts tab running indicator)
            int textX = wx + 6;
            if (dot != null) {
                int dc  = parseColor(dot, C_GREY);
                int dy  = ry + rowH / 2 - 2;
                ctx.fill(wx + 4, dy, wx + 8, dy + 4, dc);
                textX = wx + 13;
            }

            // Selection tick on left edge
            if (isSel) ctx.fill(wx + 1, ry, wx + 2, ry + rowH, C_GREEN);

            ctx.text(font, Component.literal(text), textX, ry + (rowH - 8) / 2, textColor, false);

            // Detail label right-aligned
            if (detail != null) {
                int dw = font.width(detail);
                ctx.text(font, Component.literal(detail),
                    wx + 1 + innerW - dw - 3, ry + (rowH - 8) / 2, C_GREY, false);
            }
        }

        ctx.disableScissor();

        // Scrollbar
        if (total > vis) {
            int sbH  = lh - 2;
            int barH = Math.max(5, sbH * vis / total);
            float frac = (float) scroll / Math.max(1, total - vis);
            int barY = wy + 1 + (int)((sbH - barH) * frac);
            ctx.fill(wx + lw - sbW, wy + 1, wx + lw - 1, wy + lh - 1, C_SCROLL);
            ctx.fill(wx + lw - sbW, barY,   wx + lw - 1, barY + barH,  C_SCROLL_THUMB);
        }
    }

    // ── Textbox widget ────────────────────────────────────────────────────────

    private void renderTextbox(GuiGraphicsExtractor ctx, int mx, int my,
                               JsonObject w, int wx, int wy) {
        int tbw    = num(w, "w", 150);
        int tbh    = num(w, "h", 60);
        int lineH  = num(w, "line_h", 9);
        int scroll = num(w, "scroll", 0);
        int col    = color(w, "color", C_WHITE);

        List<String> lines = new ArrayList<>();
        if (w.has("lines")) {
            for (JsonElement el : w.getAsJsonArray("lines"))
                lines.add(el.getAsString());
        } else {
            for (String ln : str(w, "text", "").split("\n", -1))
                lines.add(ln);
        }

        int total = lines.size();
        int vis   = Math.max(1, tbh / lineH);
        scroll    = Math.max(0, Math.min(scroll, Math.max(0, total - vis)));
        int sbW   = (total > vis) ? 4 : 0;

        ctx.fill(wx,         wy,         wx + tbw,     wy + tbh,     color(w, "bg_color", 0xCC0C0C0C));
        ctx.fill(wx,         wy,         wx + tbw,     wy + 1,       sAccent);
        ctx.fill(wx,         wy + tbh-1, wx + tbw,     wy + tbh,     sAccent);
        ctx.fill(wx,         wy,         wx + 1,       wy + tbh,     sAccent);
        ctx.fill(wx + tbw-1, wy,         wx + tbw,     wy + tbh,     sAccent);

        ctx.enableScissor(wx + 1, wy + 1, wx + tbw - 1 - sbW, wy + tbh - 1);
        for (int i = scroll; i < Math.min(total, scroll + vis); i++) {
            ctx.text(font, Component.literal(lines.get(i)),
                wx + 3, wy + 2 + (i - scroll) * lineH, col, false);
        }
        ctx.disableScissor();

        // Scrollbar
        if (total > vis) {
            int sbH  = tbh - 2;
            int barH = Math.max(5, sbH * vis / total);
            float frac = (float) scroll / Math.max(1, total - vis);
            int barY = wy + 1 + (int)((sbH - barH) * frac);
            ctx.fill(wx + tbw - sbW, wy + 1, wx + tbw - 1, wy + tbh - 1, C_SCROLL);
            ctx.fill(wx + tbw - sbW, barY,   wx + tbw - 1, barY + barH,   C_SCROLL_THUMB);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Mouse input
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean focused) {
        if (click.button() != 0) return super.mouseClicked(click, focused);
        double mx = click.x(), my = click.y();

        // ── Handle open dropdown first ────────────────────────────────────────
        if (openDropdownId != null) {
            synchronized (widgetOrder) {
                JsonObject dw = widgetMap.get(openDropdownId);
                if (dw != null) {
                    int dx    = panelX + PAD + num(dw, "x", 0);
                    int dy    = panelY + TITLE_H + num(dw, "y", 0);
                    int dwid  = num(dw, "w", 100);
                    int dh    = num(dw, "h", 12);
                    JsonArray opts = dw.has("options") ? dw.getAsJsonArray("options") : new JsonArray();
                    int n     = opts.size();
                    int popH  = n * dh;
                    int py    = dy + dh;
                    if (py + popH > panelY + sH - PAD) py = dy - popH;
                    if (mx >= dx && mx < dx + dwid && my >= py && my < py + popH) {
                        int row = (int)((my - py) / dh);
                        if (row >= 0 && row < n) {
                            dw.addProperty("selected", row);
                            events.offer(openDropdownId + ":" + row);
                        }
                        openDropdownId = null;
                        return true;
                    }
                }
            }
            openDropdownId = null; // click outside → just close
        }

        synchronized (widgetOrder) {
            Set<String> activeTabs = buildActiveTabs();
            for (String id : widgetOrder) {
                JsonObject w = widgetMap.get(id);
                if (w == null) continue;
                if (!isTabVisible(w, activeTabs)) continue;
                String type = str(w, "type", "");
                int wx = panelX + PAD + num(w, "x", 0);
                int wy = panelY + TITLE_H + num(w, "y", 0);

                switch (type) {
                    case "tabs" -> {
                        JsonArray tabItems = w.has("items") ? w.getAsJsonArray("items") : new JsonArray();
                        int n   = tabItems.size();
                        int tw  = num(w, "w", sW - PAD * 2);
                        int th  = 14;
                        int tabW = n > 0 ? tw / n : tw;
                        if (mx >= wx && mx < wx + tw && my >= wy && my < wy + th) {
                            int idx = (int)((mx - wx) / tabW);
                            if (idx >= 0 && idx < n) {
                                JsonObject tab = tabItems.get(idx).getAsJsonObject();
                                String tabId  = str(tab, "id", String.valueOf(idx));
                                w.addProperty("selected", tabId);
                                events.offer(id + ":" + tabId);
                                updateNativeVisibility();
                            }
                            return true;
                        }
                    }
                    case "dropdown" -> {
                        int dwidth = num(w, "w", 100);
                        int dh     = num(w, "h", 12);
                        if (mx >= wx && mx < wx + dwidth && my >= wy && my < wy + dh) {
                            openDropdownId = id.equals(openDropdownId) ? null : id;
                            return true;
                        }
                    }
                    case "checkbox" -> {
                        if (mx >= wx && mx < wx + 11 && my >= wy && my < wy + 11) {
                            w.addProperty("checked", !bool(w, "checked", false));
                            events.offer(id);
                            return true;
                        }
                    }
                    case "slider" -> {
                        int slw = num(w, "w", 100);
                        int slh = num(w, "h", 10);
                        if (mx >= wx && mx < wx + slw && my >= wy && my < wy + slh) {
                            sliderDragId = id;
                            applySlider(w, wx, slw, mx);
                            return true;
                        }
                    }
                    case "list" -> {
                        int lw   = num(w, "w", 150);
                        int lh   = num(w, "h", 80);
                        int rowH = num(w, "row_h", 16);
                        int scr  = num(w, "scroll", 0);
                        if (mx >= wx && mx < wx + lw && my >= wy && my < wy + lh) {
                            int row = scr + (int)((my - wy) / rowH);
                            int total = w.has("items") ? w.getAsJsonArray("items").size() : 0;
                            if (row >= 0 && row < total) {
                                w.addProperty("selected", row);
                                events.offer(id + ":" + row);
                            }
                            return true;
                        }
                    }
                    case "flat_button" -> {
                        int bw = num(w, "w", 60);
                        int bh = num(w, "h", 14);
                        if (mx >= wx && mx < wx + bw && my >= wy && my < wy + bh) {
                            events.offer(id);
                            return true;
                        }
                    }
                    case "textbox" -> {
                        int tbw = num(w, "w", 150);
                        int tbh = num(w, "h", 60);
                        if (mx >= wx && mx < wx + tbw && my >= wy && my < wy + tbh)
                            return true; // absorb clicks inside textbox
                    }
                }
            }
        }
        return super.mouseClicked(click, focused);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dx, double dy) {
        if (click.button() == 0 && sliderDragId != null) {
            JsonObject w = widgetMap.get(sliderDragId);
            if (w != null) applySlider(w, panelX + PAD + num(w, "x", 0), num(w, "w", 100), click.x());
            return true;
        }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (click.button() == 0 && sliderDragId != null) {
            JsonObject w = widgetMap.get(sliderDragId);
            if (w != null) events.offer(sliderDragId + ":" + formatFloat(flt(w, "value", 0f)));
            sliderDragId = null;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        synchronized (widgetOrder) {
            for (String id : widgetOrder) {
                JsonObject w = widgetMap.get(id);
                if (w == null) continue;
                String type = str(w, "type", "");
                if (!type.equals("list") && !type.equals("textbox")) continue;
                int wx = panelX + PAD + num(w, "x", 0);
                int wy = panelY + TITLE_H + num(w, "y", 0);
                int ww = num(w, "w", 150);
                int wh = num(w, "h", 80);
                if (mx >= wx && mx < wx + ww && my >= wy && my < wy + wh) {
                    int rowH  = type.equals("list") ? num(w, "row_h", 16) : num(w, "line_h", 9);
                    int total = type.equals("list")
                        ? (w.has("items") ? w.getAsJsonArray("items").size() : 0)
                        : countTextLines(w);
                    int vis   = Math.max(1, wh / rowH);
                    int scr   = Math.max(0, Math.min(
                        num(w, "scroll", 0) - (int) vAmt,
                        Math.max(0, total - vis)));
                    w.addProperty("scroll", scr);
                    return true;
                }
            }
        }
        return super.mouseScrolled(mx, my, hAmt, vAmt);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void applySlider(JsonObject w, int wx, int slw, double mx) {
        float t    = (float)(mx - wx) / Math.max(1, slw);
        t          = Math.max(0, Math.min(1, t));
        float min  = flt(w, "min",  0f);
        float max  = flt(w, "max",  1f);
        float step = flt(w, "step", 0f);
        float val  = min + t * (max - min);
        if (step > 0) val = Math.round(val / step) * step;
        w.addProperty("value", Math.max(min, Math.min(max, val)));
    }

    private int countTextLines(JsonObject w) {
        if (w.has("lines")) return w.getAsJsonArray("lines").size();
        if (w.has("text"))  return str(w, "text", "").split("\n", -1).length;
        return 0;
    }

    private List<String> wrapText(String text, int maxW) {
        List<String> result = new ArrayList<>();
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String test = line.length() > 0 ? line + " " + word : word;
            if (font.width(test) > maxW && line.length() > 0) {
                result.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(test);
            }
        }
        if (line.length() > 0) result.add(line.toString());
        return result.isEmpty() ? List.of("") : result;
    }

    private int alignX(int wx, int w, int textW, String align) {
        return switch (align) {
            case "center" -> wx + (w - textW) / 2;
            case "right"  -> wx + w - textW;
            default       -> wx;
        };
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Screen overrides
    // ══════════════════════════════════════════════════════════════════════════

    @Override public void removed()            { screenOpen.set(false); instance = null; }
    @Override public boolean isPauseScreen()   { return false; }

    // ══════════════════════════════════════════════════════════════════════════
    // Static helpers
    // ══════════════════════════════════════════════════════════════════════════

    private static String formatFloat(float v) {
        return v == (int) v ? String.valueOf((int) v) : String.format("%.2f", v);
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) ? o.get(key).getAsString() : def;
    }
    private static int num(JsonObject o, String key, int def) {
        return o.has(key) ? o.get(key).getAsInt() : def;
    }
    private static float flt(JsonObject o, String key, float def) {
        return o.has(key) ? o.get(key).getAsFloat() : def;
    }
    private static boolean bool(JsonObject o, String key, boolean def) {
        return o.has(key) ? o.get(key).getAsBoolean() : def;
    }
    private static int color(JsonObject o, String key, int def) {
        return o.has(key) ? parseColor(o.get(key).getAsString().trim(), def) : def;
    }
    private static int parseColor(String s, int def) {
        try {
            if (s.startsWith("#")) {
                long v = Long.parseLong(s.substring(1), 16);
                return s.length() > 7 ? (int) v : (int)(0xFF000000L | v);
            }
            return switch (s.toLowerCase()) {
                case "red"                   -> 0xFFFF4444;
                case "green"                 -> 0xFF00FFAA;
                case "yellow"                -> 0xFFFFAA00;
                case "blue"                  -> 0xFF00CCFF;
                case "white"                 -> 0xFFDDDDDD;
                case "grey",   "gray"        -> 0xFF888888;
                case "dark_grey","dark_gray" -> 0xFF444444;
                case "orange"                -> 0xFFFF8800;
                case "purple"                -> 0xFFAA66FF;
                case "pink"                  -> 0xFFFF88BB;
                case "cyan"                  -> 0xFF00FFFF;
                case "lime"                  -> 0xFF88FF44;
                case "dark_red"              -> 0xFFAA0000;
                case "dark_green"            -> 0xFF007755;
                case "black"                 -> 0xFF111111;
                default                      -> def;
            };
        } catch (Exception e) { return def; }
    }

    // ── Tab visibility helpers ─────────────────────────────────────────────────

    /** Returns the set of currently-selected tab IDs across all tabs widgets. */
    private Set<String> buildActiveTabs() {
        Set<String> active = new HashSet<>();
        for (String id : widgetOrder) {
            JsonObject w = widgetMap.get(id);
            if (w != null && "tabs".equals(str(w, "type", ""))) {
                String sel = str(w, "selected", "");
                if (!sel.isEmpty()) active.add(sel);
            }
        }
        return active;
    }

    /** True if this widget should be rendered/interactive (no tab prop, or active tab). */
    private static boolean isTabVisible(JsonObject w, Set<String> activeTabs) {
        if (!w.has("tab")) return true;
        String tabId = str(w, "tab", "");
        return tabId.isEmpty() || activeTabs.contains(tabId);
    }

    /** Show/hide native MC EditBox widgets based on which tab is active. */
    private void updateNativeVisibility() {
        Set<String> active = buildActiveTabs();
        inputs.forEach((id, box) -> {
            JsonObject w = widgetMap.get(id);
            if (w != null && w.has("tab"))
                box.setVisible(isTabVisible(w, active));
        });
        // Native Button doesn't expose setVisible in this MC version.
        // flat_button is custom-rendered and already filtered by isTabVisible in the render loop.
    }
}
