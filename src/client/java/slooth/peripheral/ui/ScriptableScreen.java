package slooth.peripheral.ui;

import slooth.peripheral.api.PeripheralEvents;
import slooth.peripheral.api.PeripheralHttpServer;
import dev.pane.ui.*;

import com.google.gson.*;
import java.awt.Font;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Script-driven GUI screen. Open via {@code open_gui(layout)}, update via
 * {@code gui_update(id, **props)}, press H to open/close while script runs.
 *
 * <h2>Widget types</h2>
 * <pre>
 *   label        – text line.  props: text, color, shadow, align (left/center/right), w (wraps at width)
 *   section      – section header with thin underline.  props: text, color, w
 *   bar          – horizontal progress bar.  props: value (0–1), w, h, color, bg_color
 *   divider      – 1px horizontal rule.  props: w, color
 *   rect         – filled rectangle.  props: w, h, color, border (color, omit = no border)
 *   button       – button.  fires event on click.  props: label, w, h
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
 *   tabs, dropdown, item – see the examples.
 * </pre>
 *
 * <h2>Panel options</h2>
 * <pre>
 *   title, w, h, bg_color, accent_color, title_color, theme
 * </pre>
 *
 * <h2>Colors</h2>
 * Named: red green yellow blue white grey dark_grey orange purple pink cyan lime dark_red dark_green
 * Hex:   '#RRGGBB' or '#AARRGGBB'
 *
 * <h2>Coordinates</h2>
 * Script coordinates are Minecraft GUI pixels, as before. The retained Pane surface is painted
 * inside a canvas scale of {@link #layoutScale()}, so every widget lands on the same GUI pixel it
 * always did while text is rasterized as crisp sans at physical resolution. Pointer handling
 * keeps using the raw GUI coordinates Minecraft delivers, the same space the paint uses. Item
 * icons are the one native layer: they are drawn by Minecraft's item renderer at their GUI
 * position after the surface, and an open dropdown re-composites above them.
 *
 * <p>The default ("terminal") theme now paints Peripheral's charcoal design; vanilla and custom
 * themes and every script-specified colour keep their meaning.
 */
public class ScriptableScreen extends UiScreen {

    // ── Layout constants ──────────────────────────────────────────────────────
    private static final int TITLE_H = 15;
    private static final int PAD     = 6;
    private static final float TS    = 8.5F; // sans size matching the old 9px font line

    // ── Default colours (terminal role values; used to seed sBg etc.) ─────────
    private static final int C_BG_DEFAULT     = 0xEE0D0D0D;
    private static final int C_ACCENT_DEFAULT = 0xFF153020;
    private static final int C_TITLE_DEFAULT  = 0xDD0E1F11;
    // Peripheral design defaults for the default theme.
    private static final int M_BG = 0xF51D1D1D, M_ACCENT = PeripheralUi.LINE, M_TITLE = 0xFF262626;

    // ── Shared state (HTTP thread writes, render thread reads) ─────────────────
    private static volatile String ownerScript = "";
    private static volatile String sTitle     = "Script GUI";
    private static volatile int    sW         = 300;
    private static volatile int    sH         = 200;
    private static volatile int    sBg        = C_BG_DEFAULT;
    private static volatile int    sAccent    = C_ACCENT_DEFAULT;
    private static volatile int    sTitleBg   = C_TITLE_DEFAULT;
    private static volatile String sThemeName = "terminal";
    private static volatile boolean explicitTheme;
    // Whether the layout supplied its own panel colours (those always win over the design defaults).
    private static volatile boolean customBg, customAccent, customTitle;

    private static final List<String>            widgetOrder = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, JsonObject> widgetMap   = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean screenOpen = new AtomicBoolean(false);
    private static volatile ScriptableScreen instance = null;
    /** Bumped by every layout/prop change; the open screen repaints once per tick when it moves. */
    private static final AtomicLong revision = new AtomicLong();

    // ── Theme-derived palette (refreshed each paint by syncTheme) ──────────────
    private PeripheralTheme theme = PeripheralTheme.byName(sThemeName);
    private boolean modern;
    private int C_GREEN, C_WHITE, C_GREY, C_DIM, C_ORANGE,
                C_BAR_BG, C_ROW_SEL, C_ROW_HOV, C_SCROLL, C_SCROLL_THUMB, C_INPUT, C_BORDER;
    private int bg, accent, titleBg;

    /** Refresh palette from the active theme. The default theme maps to Peripheral's design. */
    private void syncTheme() {
        theme = PeripheralTheme.byName(sThemeName);
        modern = theme.isTerminal()&&!explicitTheme;
        if (modern) {
            C_GREEN = PeripheralUi.LIME; C_WHITE = PeripheralUi.TEXT; C_GREY = PeripheralUi.MUTED; C_DIM = PeripheralUi.LINE;
            C_ORANGE = PeripheralUi.TEXT; C_BAR_BG = 0xFF2A2A2A; C_ROW_SEL = PeripheralUi.LIME_TINT; C_ROW_HOV = 0x16FFFFFF;
            C_SCROLL = PeripheralUi.LINE_SOFT; C_SCROLL_THUMB = PeripheralUi.FAINT; C_INPUT = PeripheralUi.FIELD; C_BORDER = PeripheralUi.LINE;
        } else {
            C_GREEN = theme.accent; C_WHITE = theme.text; C_GREY = theme.textDim; C_DIM = theme.divider;
            C_ORANGE = theme.warning; C_BAR_BG = theme.barBg; C_ROW_SEL = theme.selection; C_ROW_HOV = theme.rowHover;
            C_SCROLL = theme.scrollTrack; C_SCROLL_THUMB = theme.scrollThumb; C_INPUT = theme.inputBg; C_BORDER = theme.inputBorder;
        }
        bg      = modern && !customBg     ? M_BG     : sBg;
        accent  = modern && !customAccent ? M_ACCENT : sAccent;
        titleBg = modern && !customTitle  ? M_TITLE  : sTitleBg;
    }

    // ── Instance fields ───────────────────────────────────────────────────────
    private int panelX, panelY;
    private final Map<String, LineEdit> inputs = new ConcurrentHashMap<>();
    private String focusedInput   = null;
    private String keyboardFocus = null;
    private String sliderDragId   = null;
    private String openDropdownId = null;
    private String pressId        = null;
    private String hoverKey       = null;
    private int    mouseGX = -1, mouseGY = -1;
    private long   seenRevision   = -1;

    // ══════════════════════════════════════════════════════════════════════════
    // Static API — called from PeripheralHttpServer on the HTTP thread
    // ══════════════════════════════════════════════════════════════════════════

    /** Open with the given layout JSON. Safe to call from any thread. */
    public static void openWithLayout(JsonObject layout, String script) {
        ownerScript = script == null ? "" : script;
        PeripheralTheme th = layout.has("theme")
            ? PeripheralTheme.byName(str(layout, "theme", "terminal"))
            : PeripheralTheme.current();
        sThemeName = th.name;
        explicitTheme = layout.has("theme")&&!layout.get("theme").isJsonNull();
        sTitle   = str(layout, "title",        "Script GUI");
        sW       = num(layout, "w",            300);
        sH       = num(layout, "h",            200);
        sBg      = color(layout, "bg_color",   th.panel);
        sAccent  = color(layout, "accent_color", th.panelBorder);
        sTitleBg = color(layout, "title_color", th.titleBar);
        customBg     = layout.has("bg_color");
        customAccent = layout.has("accent_color");
        customTitle  = layout.has("title_color");
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
        revision.incrementAndGet();
        Minecraft.getInstance().execute(() ->
            Minecraft.getInstance().gui.setScreen(new ScriptableScreen()));
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
        revision.incrementAndGet();
        closeScreen();
    }

    /** Update one widget's properties while the screen is open. Any thread. */
    public static void update(String id, JsonObject props) {
        JsonObject target;
        String inputValue;
        synchronized (widgetOrder) {
            JsonObject w = widgetMap.get(id);
            if (w == null) return;
            for (Map.Entry<String, JsonElement> e : props.entrySet())
                w.add(e.getKey(), e.getValue());
            target=w;
            inputValue="input".equals(str(w,"type",""))&&props.has("value")
                ?(props.get("value").isJsonNull()?"":str(w,"value","")):null;
        }
        revision.incrementAndGet();
        ScriptableScreen active=instance;
        if(inputValue!=null&&active!=null)PeripheralUi.onMain(()->{
            if(instance!=active)return;
            synchronized(widgetOrder){
                if(widgetMap.get(id)!=target||!"input".equals(str(target,"type","")))return;
                String latest=target.has("value")&&target.get("value").isJsonNull()?"":str(target,"value","");
                if(!latest.equals(inputValue))return;
                active.inputs.computeIfAbsent(id,k->new LineEdit("",256)).set(inputValue);
            }
            active.invalidate();
        });
    }

    /** Pop the next event (button click, list row click, etc.), or null. */
    public static String pollEvent() { return events.poll(); }

    /**
     * Raise a widget event: queued for HTTP {@code /gui} polling and also pushed
     * to socket subscribers via {@link PeripheralEvents} as a {@code gui} event.
     */
    private static void fireEvent(String ev) {
        if (ev == null) return;
        events.offer(ev);
        JsonObject d = new JsonObject();
        d.addProperty("event", ev);
        int colon = ev.indexOf(':');
        if (colon >= 0) {
            d.addProperty("id",    ev.substring(0, colon));
            d.addProperty("value", ev.substring(colon + 1));
        } else {
            d.addProperty("id", ev);
        }
        d.addProperty("_owner", ownerScript);
        PeripheralEvents.emit("gui", d);
    }

    /** True if a ScriptableScreen is currently displayed. */
    public static boolean ownedBy(String owner) { return owner.equals(ownerScript); }
    public static boolean isOpen()    { return screenOpen.get(); }

    /** True if a layout has been loaded (re-opening makes sense). */
    public static boolean hasLayout() { return !widgetOrder.isEmpty(); }

    /** Close the screen if open. Safe from any thread. */
    public static void closeScreen() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> { if (mc.gui.screen() instanceof ScriptableScreen) mc.gui.setScreen(null); });
    }

    /**
     * Return all readable widget values as id → string.
     * Covers: input (text), checkbox (true/false), list (selected index), slider (float).
     */
    public static Map<String, String> getInputValues() {
        ScriptableScreen scr = instance;
        Map<String, String> map = new LinkedHashMap<>();
        synchronized (widgetOrder) {
            for (String id : widgetOrder) {
                JsonObject w = widgetMap.get(id);
                if (w == null) continue;
                switch (str(w, "type", "")) {
                    case "input" -> {if(scr!=null){LineEdit input=scr.inputs.get(id);if(input!=null)map.put(id,input.value());}}
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
        this(new PeripheralUi.Ref<>());
    }

    private ScriptableScreen(PeripheralUi.Ref<ScriptableScreen> ref) {
        super(PeripheralUi.document(sTitle, ui -> ref.v.paint(ui)), ctx -> ref.v.renderItems(ctx),
            (UiEventHandler) e -> {}, UiScreenOptions.builder().backdrop(UiBackdrop.NONE).build());
        ref.v = this;
        screenOpen.set(true);
        instance = this;
    }

    @Override
    protected void init() {
        super.init();
        panelX = (width  - sW) / 2;
        panelY = (height - sH) / 2;
        // Inputs keep what the player typed across a resize; first open seeds from "value".
        synchronized (widgetOrder) {
            for (String id : widgetOrder) {
                JsonObject w = widgetMap.get(id);
                if (w != null && "input".equals(str(w, "type", "")) && !inputs.containsKey(id))
                    inputs.put(id, new LineEdit(w.has("value") ? str(w, "value", "") : "", 256));
            }
        }
        seenRevision = revision.get();
    }

    @Override
    public void tick() {
        super.tick();
        long r = revision.get();
        if (r != seenRevision) {
            seenRevision = r;
            synchronized(widgetOrder){
                inputs.keySet().removeIf(id->{JsonObject w=widgetMap.get(id);return w==null||!"input".equals(str(w,"type",""));});
                for(String id:widgetOrder){JsonObject w=widgetMap.get(id);if(w!=null&&"input".equals(str(w,"type","")))inputs.computeIfAbsent(id,k->new LineEdit(str(w,"value",""),256));}
            }
            invalidate();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Paint (GUI coordinates inside a canvas scale)
    // ══════════════════════════════════════════════════════════════════════════

    private void paint(UiContext ui) {
        syncTheme();
        UiCanvas c = ui.canvas();
        float L = layoutScale();
        // Dim world behind panel
        c.fill(modern ? 0x88000000 : 0xC0101010, 0, 0, ui.width(), ui.height());
        c.withScale(L, L, 0, 0, () -> paintGui(c));
        if (openDropdownId != null) {
            int[] r = popupRect(widgetMap.get(openDropdownId));
            if (r != null) ui.nativeOverlayLayer("dropdown", r[0] * L, r[1] * L, r[2] * L, r[3] * L);
        }
    }

    private void paintGui(UiCanvas c) {
        int mx = mouseGX, my = mouseGY;
        if (theme.bevel()) {
            // ── Vanilla bevelled panel ────────────────────────────────────────
            bevelBox(c, panelX, panelY, panelX + sW, panelY + sH, bg, theme.bevelLight, theme.bevelDark);
            if (!sTitle.isEmpty()) {
                f(c, panelX + 1, panelY + 1, panelX + sW - 1, panelY + TITLE_H, titleBg);
                f(c, panelX + 1, panelY + TITLE_H - 1, panelX + sW - 1, panelY + TITLE_H, theme.bevelDark);
                text(c, sTitle, panelX + 5, panelY + 4, theme.text, theme.textShadow);
            }
        } else if (modern) {
            c.roundRect(bg, panelX, panelY, sW, sH, 3);
            c.strokeRoundRect(accent, 1, panelX, panelY, sW, sH, 3);
            if (!sTitle.isEmpty()) {
                c.roundRect(titleBg, panelX + 1, panelY + 1, sW - 2, TITLE_H - 1, 3, 3, 0, 0);
                f(c, panelX + 1, panelY + TITLE_H - 1, panelX + sW - 1, panelY + TITLE_H, accent);
                c.text(c.ellipsize(sTitle, sW - 12, 9, Font.BOLD, PeripheralUi.SANS), panelX + 6, panelY + 2.5F, 9, PeripheralUi.TEXT, Font.BOLD, PeripheralUi.SANS);
            }
        } else {
            // ── Panel background ──────────────────────────────────────────────
            f(c, panelX, panelY, panelX + sW, panelY + sH, bg);
            f(c, panelX, panelY, panelX + sW, panelY + 1, accent);
            f(c, panelX, panelY + sH - 1, panelX + sW, panelY + sH, accent);
            f(c, panelX, panelY, panelX + 1, panelY + sH, accent);
            f(c, panelX + sW - 1, panelY, panelX + sW, panelY + sH, accent);
            // Corner accent notches
            f(c, panelX + 1, panelY + 1, panelX + 3, panelY + 2, accent);
            f(c, panelX + 1, panelY + 1, panelX + 2, panelY + 3, accent);
            f(c, panelX + sW - 3, panelY + 1, panelX + sW - 1, panelY + 2, accent);
            f(c, panelX + sW - 2, panelY + 1, panelX + sW - 1, panelY + 3, accent);
            if (!sTitle.isEmpty()) {
                f(c, panelX + 1, panelY + 1, panelX + sW - 1, panelY + TITLE_H, titleBg);
                f(c, panelX + 1, panelY + TITLE_H - 1, panelX + sW - 1, panelY + TITLE_H, accent);
                text(c, "§a◈ §f" + sTitle, panelX + 5, panelY + 4, C_GREEN, false);
            }
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
                renderWidget(c, mx, my, id, type, w, wx, wy);
                if(id.equals(keyboardFocus)&&("button".equals(type)||"flat_button".equals(type)))
                    outline(c,wx-1,wy-1,num(w,"w",60)+2,num(w,"h",14)+2,C_GREEN);
            }
            // ── Open dropdown popup (painted last so it floats above everything) ─
            if (openDropdownId != null) {
                JsonObject w = widgetMap.get(openDropdownId);
                if (w != null) renderDropdownPopup(c, mx, my, w);
            }
        }
    }

    private void renderWidget(UiCanvas c, int mx, int my, String id, String type, JsonObject w, int wx, int wy) {
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
                    for (String line : wrapText(c, text, lw)) {
                        text(c, line, alignX(wx, lw, tw(c, line), align), ty, col, shad);
                        ty += 9;
                    }
                } else {
                    float tx = (lw > 0) ? alignX(wx, lw, tw(c, text), align) : wx;
                    text(c, text, tx, wy, col, shad);
                }
            }

            // ── section header ────────────────────────────────────────────────
            case "section" -> {
                String text = str(w, "text", "");
                int    col  = color(w, "color", C_ORANGE);
                int    sw   = num(w, "w", sW - PAD * 2);
                if (modern && !w.has("color")) c.text(text, wx, wy - 1, TS, col, Font.BOLD, PeripheralUi.SANS);
                else text(c, text, wx, wy, col, false);
                f(c, wx, wy + 11, wx + sw, wy + 12, col & 0x44FFFFFF | 0x44000000);
            }

            // ── bar ───────────────────────────────────────────────────────────
            case "bar" -> {
                int   bw  = num(w, "w", 100);
                int   bh  = num(w, "h", 5);
                float val = Math.max(0, Math.min(1, flt(w, "value", 0f)));
                int   col = color(w, "color", C_GREEN);
                int   bgc = color(w, "bg_color", C_BAR_BG);
                f(c, wx, wy, wx + bw, wy + bh, bgc);
                f(c, wx, wy, wx + (int) (val * bw), wy + bh, col);
                f(c, wx, wy, wx + bw, wy + 1, 0x33FFFFFF);
                f(c, wx, wy + bh - 1, wx + bw, wy + bh, 0x22000000);
            }

            // ── divider ───────────────────────────────────────────────────────
            case "divider" -> {
                int dw  = num(w, "w", sW - PAD * 2);
                int col = color(w, "color", C_DIM);
                f(c, wx, wy, wx + dw, wy + 1, col);
            }

            // ── rect ──────────────────────────────────────────────────────────
            case "rect" -> {
                int rw  = num(w, "w", 50);
                int rh  = num(w, "h", 20);
                int col = color(w, "color", modern ? 0xFF222222 : 0xFF1A1A1A);
                f(c, wx, wy, wx + rw, wy + rh, col);
                if (w.has("border")) outline(c, wx, wy, rw, rh, color(w, "border", accent));
            }

            // ── checkbox ──────────────────────────────────────────────────────
            case "checkbox" -> {
                boolean checked = bool(w, "checked", false);
                String  label   = str(w, "label", "");
                int     col     = color(w, "color", C_WHITE);
                boolean hov     = mx >= wx && mx < wx + 11 && my >= wy && my < wy + 11;
                f(c, wx, wy, wx + 11, wy + 11, C_INPUT);
                if (hov) f(c, wx, wy, wx + 11, wy + 11, 0x22FFFFFF);
                outline(c, wx, wy, 11, 11, modern ? (checked ? C_GREEN : C_BORDER) : accent);
                if (checked) {
                    if (modern) {
                        f(c, wx + 2, wy + 2, wx + 9, wy + 9, C_GREEN);
                        c.line(PeripheralUi.LIME_INK, 1.2F, wx + 3.5F, wy + 5.5F, wx + 5, wy + 7.2F);
                        c.line(PeripheralUi.LIME_INK, 1.2F, wx + 5, wy + 7.2F, wx + 7.8F, wy + 3.8F);
                    } else {
                        f(c, wx + 2, wy + 2, wx + 9, wy + 9, C_GREEN);
                        f(c, wx + 3, wy + 3, wx + 8, wy + 8, 0xFF007744);
                    }
                }
                if (!label.isEmpty()) text(c, label, wx + 15, wy + 2, col, false);
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
                int   fill = (int) (t * slw);
                f(c, wx, mid - 1, wx + slw, mid + 2, modern ? 0xFF2A2A2A : 0xFF242424);
                if (!modern) f(c, wx, mid - 1, wx + slw, mid, 0xFF111111);
                if (fill > 0) f(c, wx, mid - 1, wx + fill, mid + 2, modern ? col : (col & 0xAAFFFFFF | 0x55000000));
                int hx = wx + fill;
                if (modern) {
                    c.roundRect(0xFF3A3A3A, hx - 3, wy, 6, slh, 2);
                    c.roundRect(C_WHITE, hx - 2, wy + 1, 4, slh - 2, 1.5F);
                } else {
                    f(c, hx - 3, wy, hx + 3, wy + slh, 0xFF333333);
                    f(c, hx - 2, wy + 1, hx + 2, wy + slh - 1, 0xFFAAAAAA);
                    f(c, hx - 1, wy + 2, hx + 1, wy + slh - 2, C_WHITE);
                }
                if (bool(w, "show_value", false))
                    text(c, formatFloat(val), wx + slw + 5, wy + (slh - 8) / 2f, C_GREY, false);
            }

            case "list"    -> renderList(c, mx, my, w, wx, wy);
            case "textbox" -> renderTextbox(c, w, wx, wy);

            // ── button (was a native Minecraft button) ────────────────────────
            case "button" -> {
                int bw = num(w, "w", 60), bh = num(w, "h", 14);
                String lbl = str(w, "label", id);
                boolean hov = mx >= wx && mx < wx + bw && my >= wy && my < wy + bh;
                boolean down = id.equals(pressId) && hov;
                int tc;
                if (theme.bevel()) {
                    bevelBox(c, wx, wy, wx + bw, wy + bh, theme.buttonFace,
                        down ? theme.buttonBevelDark : theme.buttonBevelLight, down ? theme.buttonBevelLight : theme.buttonBevelDark);
                    if (hov) f(c, wx + 1, wy + 1, wx + bw - 1, wy + bh - 1, theme.buttonHover);
                    tc = theme.buttonText;
                } else if (modern) {
                    c.roundRect(down ? 0xFF202020 : hov ? 0xFF2F2F2F : 0xFF262626, wx, wy, bw, bh, 2);
                    c.strokeRoundRect(0xFF3A3A3A, .75F, wx, wy, bw, bh, 2);
                    tc = PeripheralUi.TEXT;
                } else {
                    f(c, wx, wy, wx + bw, wy + bh, theme.buttonFace);
                    if (hov) f(c, wx, wy, wx + bw, wy + bh, theme.buttonHover);
                    outline(c, wx, wy, bw, bh, theme.panelBorder);
                    tc = theme.buttonText;
                }
                text(c, lbl, wx + (bw - tw(c, lbl)) / 2, wy + (bh - 8) / 2f, tc, theme.bevel() && theme.textShadow);
            }

            // ── input (was a native EditBox) ──────────────────────────────────
            case "input" -> {
                int iw = num(w, "w", 150), ih = num(w, "h", 14);
                LineEdit edit = inputs.get(id);
                boolean focused = id.equals(focusedInput);
                f(c, wx, wy, wx + iw, wy + ih, modern ? C_INPUT : 0xFF000000);
                outline(c, wx, wy, iw, ih, focused ? (modern ? C_GREEN : 0xFFFFFFFF) : (modern ? C_BORDER : 0xFFA0A0A0));
                if (edit != null) edit.paint(c, wx, wy, iw, ih, TS, PeripheralUi.SANS, modern ? PeripheralUi.TEXT : 0xFFE0E0E0,
                    modern ? PeripheralUi.FAINT : 0xFF808080, modern ? PeripheralUi.LIME : 0xFFD0D0D0, str(w, "placeholder", ""), focused);
            }

            // ── flat_button ───────────────────────────────────────────────────
            case "flat_button" -> {
                int    bw  = num(w, "w", 60);
                int    bh  = num(w, "h", 14);
                String lbl = str(w, "label", "");
                int    col = color(w, "color", theme.bevel() ? theme.buttonText : C_GREEN);
                int    bgc = color(w, "bg_color", theme.bevel() ? theme.buttonFace : modern ? 0xFF262626 : 0xFF181818);
                boolean hov = mx >= wx && mx < wx + bw && my >= wy && my < wy + bh;
                boolean pressed = id.equals(pressId) && hov;
                int ox = pressed ? 1 : 0, oy = pressed ? 1 : 0;
                if (theme.bevel()) {
                    bevelBox(c, wx, wy, wx + bw, wy + bh, bgc,
                        pressed ? theme.buttonBevelDark : theme.buttonBevelLight,
                        pressed ? theme.buttonBevelLight : theme.buttonBevelDark);
                    if (hov) f(c, wx + 1, wy + 1, wx + bw - 1, wy + bh - 1, theme.buttonHover);
                } else {
                    f(c, wx, wy, wx + bw, wy + bh, bgc);
                    if (pressed) f(c, wx, wy, wx + bw, wy + bh, theme.press);
                    if (hov) f(c, wx, wy, wx + bw, wy + bh, 0x22FFFFFF);
                    f(c, wx, wy, wx + bw, wy + 1, col & 0x77FFFFFF | 0x77000000);
                    f(c, wx, wy + bh - 1, wx + bw, wy + bh, col & 0x33FFFFFF | 0x33000000);
                    f(c, wx, wy, wx + 1, wy + bh, col & 0x33FFFFFF | 0x33000000);
                    f(c, wx + bw - 1, wy, wx + bw, wy + bh, col & 0x33FFFFFF | 0x33000000);
                }
                text(c, lbl, wx + ox + (bw - tw(c, lbl)) / 2, wy + oy + (bh - 8) / 2f, col, theme.bevel() && theme.textShadow);
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
                    f(c, tx, wy, tx + tabW, wy + th,
                        sel ? (modern ? PeripheralUi.LIME_TINT : theme.selection)
                            : (modern ? 0x00000000 : theme.inputBg));
                    if (hov) f(c, tx, wy, tx + tabW, wy + th, C_ROW_HOV);
                    if (sel) f(c, tx, wy + (modern ? th - 2 : 0), tx + tabW, wy + (modern ? th : 2), modern ? C_GREEN : accent);
                    if (i > 0) f(c, tx, wy + 2, tx + 1, wy + th - 1, C_DIM);
                    text(c, label, tx + (tabW - tw(c, label)) / 2, wy + 3, sel ? C_GREEN : C_GREY, false);
                    if (!sel) f(c, tx, wy + th - 1, tx + tabW, wy + th, accent);
                }
                int usedW = tabW * n;
                if (usedW < tw) f(c, wx + usedW, wy + th - 1, wx + tw, wy + th, accent);
            }

            // ── dropdown (closed state) ────────────────────────────────────────
            case "dropdown" -> {
                int    dw  = num(w, "w", 100);
                int    dh  = num(w, "h", 12);
                JsonArray opts = w.has("options") ? w.getAsJsonArray("options") : new JsonArray();
                int    sel = num(w, "selected", 0);
                String selText = (sel >= 0 && sel < opts.size()) ? opts.get(sel).getAsString() : "";
                boolean open = id.equals(openDropdownId);
                boolean hov  = mx >= wx && mx < wx + dw && my >= wy && my < wy + dh;
                f(c, wx, wy, wx + dw, wy + dh, C_INPUT);
                if (hov || open) f(c, wx, wy, wx + dw, wy + dh, 0x22FFFFFF);
                outline(c, wx, wy, dw, dh, modern ? (open ? C_GREEN : C_BORDER) : accent);
                text(c, selText, wx + 4, wy + (dh - 8) / 2f, C_WHITE, false);
                float ax = wx + dw - 9, ay = wy + dh / 2f;
                if (open) c.polygon(C_GREY, ax, ay + 2, ax + 5, ay + 2, ax + 2.5F, ay - 1.5F);
                else c.polygon(C_GREY, ax, ay - 1.5F, ax + 5, ay - 1.5F, ax + 2.5F, ay + 2);
            }

            // ── item icon (icon itself is drawn natively in renderItems) ──────
            case "item" -> {
                String lbl = str(w, "label", "");
                if (!lbl.isEmpty()) text(c, lbl, wx + 20, wy + 4, C_GREY, false);
            }
        }
    }

    // ── Dropdown popup ────────────────────────────────────────────────────────

    /** {x, y, w, h} of a dropdown's popup in GUI coordinates, or null. */
    private int[] popupRect(JsonObject w) {
        if (w == null) return null;
        int wx = panelX + PAD + num(w, "x", 0), wy = panelY + TITLE_H + num(w, "y", 0);
        int dw = num(w, "w", 100), dh = num(w, "h", 12);
        int n = w.has("options") ? w.getAsJsonArray("options").size() : 0;
        if (n == 0) return null;
        int popH = n * dh;
        int py = wy + dh;
        if (py + popH > panelY + sH - PAD) py = wy - popH;
        return new int[]{wx, py, dw, popH};
    }

    private void renderDropdownPopup(UiCanvas c, int mx, int my, JsonObject w) {
        int[] r = popupRect(w);
        if (r == null) return;
        int wx = r[0], py = r[1], dw = r[2], popH = r[3], rowH = num(w, "h", 12);
        JsonArray opts = w.getAsJsonArray("options");
        int sel = num(w, "selected", 0);
        f(c, wx, py, wx + dw, py + popH, modern ? 0xFF1A1A1A : 0xFF111111);
        outline(c, wx, py, dw, popH, modern ? C_BORDER : accent);
        c.withClip(wx + 1, py + 1, dw - 2, popH - 2, () -> {
            for (int i = 0; i < opts.size(); i++) {
                String opt = opts.get(i).getAsString();
                int ry = py + i * rowH;
                boolean hov = mx >= wx && mx < wx + dw && my >= ry && my < ry + rowH;
                if (i == sel) f(c, wx + 1, ry, wx + dw - 1, ry + rowH, C_ROW_SEL);
                else if (hov) f(c, wx + 1, ry, wx + dw - 1, ry + rowH, C_ROW_HOV);
                text(c, opt, wx + 4, ry + (rowH - 8) / 2f, hov || i == sel ? C_WHITE : C_GREY, false);
            }
        });
    }

    // ── List widget ───────────────────────────────────────────────────────────

    private void renderList(UiCanvas c, int mx, int my, JsonObject w, int wx, int wy) {
        int lw      = num(w, "w", 150);
        int lh      = num(w, "h", 80);
        int rowH    = Math.max(1, num(w, "row_h", 16));
        int selected= num(w, "selected", -1);
        JsonArray items = w.has("items") ? w.getAsJsonArray("items") : new JsonArray();
        int total   = items.size();
        int vis     = Math.max(1, lh / rowH);
        int scroll  = Math.max(0, Math.min(num(w, "scroll", 0), Math.max(0, total - vis)));
        int sbW     = (total > vis) ? 4 : 0;
        int innerW  = lw - 2 - sbW;

        f(c, wx, wy, wx + lw, wy + lh, color(w, "bg_color", modern ? 0xFF151515 : 0xCC0C0C0C));
        outline(c, wx, wy, lw, lh, accent);

        c.withClip(wx + 1, wy + 1, lw - 2 - sbW, lh - 2, () -> {
            for (int i = scroll; i < total; i++) {
                int ry = wy + (i - scroll) * rowH;
                if (ry >= wy + lh) break;
                boolean isSel = (i == selected);
                boolean isHov = mx >= wx + 1 && mx < wx + lw - sbW - 1 && my >= ry && my < ry + rowH;
                JsonElement el = items.get(i);
                int rowBg = 0;
                if (el.isJsonObject() && el.getAsJsonObject().has("row_color"))
                    rowBg = color(el.getAsJsonObject(), "row_color", 0);
                if (isSel)           f(c, wx + 1, ry, wx + 1 + innerW, ry + rowH, C_ROW_SEL);
                else if (rowBg != 0) f(c, wx + 1, ry, wx + 1 + innerW, ry + rowH, rowBg);
                else if (isHov)      f(c, wx + 1, ry, wx + 1 + innerW, ry + rowH, C_ROW_HOV);

                String text = "", dot = null, detail = null;
                int textColor = C_WHITE;
                if (el.isJsonObject()) {
                    JsonObject item = el.getAsJsonObject();
                    text      = str(item, "text", "");
                    textColor = color(item, "color", C_WHITE);
                    if (item.has("dot"))    dot    = item.get("dot").getAsString();
                    if (item.has("detail")) detail = item.get("detail").getAsString();
                } else {
                    text = el.getAsString();
                }
                int textX = wx + 6;
                if (dot != null) {
                    int dc = parseColor(dot, C_GREY);
                    int dy = ry + rowH / 2 - 2;
                    f(c, wx + 4, dy, wx + 8, dy + 4, dc);
                    textX = wx + 13;
                }
                if (isSel) f(c, wx + 1, ry, wx + 2, ry + rowH, C_GREEN);
                text(c, text, textX, ry + (rowH - 8) / 2f, textColor, false);
                if (detail != null)
                    text(c, detail, wx + 1 + innerW - tw(c, detail) - 3, ry + (rowH - 8) / 2f, C_GREY, false);
            }
        });

        if (total > vis) {
            int sbH  = lh - 2;
            int barH = Math.max(5, sbH * vis / total);
            float frac = (float) scroll / Math.max(1, total - vis);
            int barY = wy + 1 + (int) ((sbH - barH) * frac);
            f(c, wx + lw - sbW, wy + 1, wx + lw - 1, wy + lh - 1, C_SCROLL);
            f(c, wx + lw - sbW, barY, wx + lw - 1, barY + barH, C_SCROLL_THUMB);
        }
    }

    // ── Textbox widget ────────────────────────────────────────────────────────

    private void renderTextbox(UiCanvas c, JsonObject w, int wx, int wy) {
        int tbw    = num(w, "w", 150);
        int tbh    = num(w, "h", 60);
        int lineH  = Math.max(1, num(w, "line_h", 9));
        int col    = color(w, "color", C_WHITE);

        List<String> lines = new ArrayList<>();
        if (w.has("lines")) {
            for (JsonElement el : w.getAsJsonArray("lines")) lines.add(el.getAsString());
        } else {
            lines.addAll(Arrays.asList(str(w, "text", "").split("\n", -1)));
        }
        int total  = lines.size();
        int vis    = Math.max(1, tbh / lineH);
        int scroll = Math.max(0, Math.min(num(w, "scroll", 0), Math.max(0, total - vis)));
        int sbW    = (total > vis) ? 4 : 0;

        f(c, wx, wy, wx + tbw, wy + tbh, color(w, "bg_color", modern ? 0xFF111111 : 0xCC0C0C0C));
        outline(c, wx, wy, tbw, tbh, accent);
        c.withClip(wx + 1, wy + 1, tbw - 2 - sbW, tbh - 2, () -> {
            for (int i = scroll; i < total; i++) {
                int ly = wy + 2 + (i - scroll) * lineH;
                if (ly >= wy + tbh) break;
                text(c, lines.get(i), wx + 3, ly, col, false);
            }
        });
        if (total > vis) {
            int sbH  = tbh - 2;
            int barH = Math.max(5, sbH * vis / total);
            float frac = (float) scroll / Math.max(1, total - vis);
            int barY = wy + 1 + (int) ((sbH - barH) * frac);
            f(c, wx + tbw - sbW, wy + 1, wx + tbw - 1, wy + tbh - 1, C_SCROLL);
            f(c, wx + tbw - sbW, barY, wx + tbw - 1, barY + barH, C_SCROLL_THUMB);
        }
    }

    // ── Native item layer (Minecraft's item renderer, GUI coordinates) ────────

    private void renderItems(NativeOverlayContext ctx) {
        synchronized (widgetOrder) {
            Set<String> activeTabs = buildActiveTabs();
            for (String id : widgetOrder) {
                JsonObject w = widgetMap.get(id);
                if (w == null || !"item".equals(str(w, "type", "")) || !isTabVisible(w, activeTabs)) continue;
                int wx = panelX + PAD + num(w, "x", 0);
                int wy = panelY + TITLE_H + num(w, "y", 0);
                String itemId = str(w, "item", str(w, "item_id", ""));
                if (!itemId.isEmpty()) {
                    if (!itemId.contains(":")) itemId = "minecraft:" + itemId;
                    Identifier ident = Identifier.tryParse(itemId);
                    if (ident != null && BuiltInRegistries.ITEM.containsKey(ident))
                        ctx.graphics().item(new ItemStack(BuiltInRegistries.ITEM.getValue(ident)), wx, wy);
                }
                int count = num(w, "count", 0);
                if (count > 1) {
                    String cs = String.valueOf(count);
                    ctx.graphics().text(font, cs, wx + 17 - font.width(cs), wy + 9, C_WHITE, true);
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Input (raw GUI coordinates — the space the paint uses)
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void mouseMoved(double mx, double my) {
        super.mouseMoved(mx, my);
        mouseGX = (int) mx; mouseGY = (int) my;
        String key = hoverKeyAt(mx, my);
        if (!Objects.equals(key, hoverKey)) { hoverKey = key; invalidate(); }
    }

    /** Identity of whatever hover-sensitive target is under the pointer, so idle motion causes no repaint. */
    private String hoverKeyAt(double mx, double my) {
        synchronized (widgetOrder) {
            if (openDropdownId != null) {
                JsonObject w = widgetMap.get(openDropdownId);
                int[] r = popupRect(w);
                if (r != null && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3])
                    return openDropdownId + "#opt" + (int) ((my - r[1]) / Math.max(1, num(w, "h", 12)));
            }
            Set<String> active = buildActiveTabs();
            for (int i = widgetOrder.size() - 1; i >= 0; i--) {
                String id = widgetOrder.get(i);
                JsonObject w = widgetMap.get(id);
                if (w == null || !isTabVisible(w, active)) continue;
                String type = str(w, "type", "");
                int wx = panelX + PAD + num(w, "x", 0), wy = panelY + TITLE_H + num(w, "y", 0);
                switch (type) {
                    case "checkbox" -> { if (mx >= wx && mx < wx + 11 && my >= wy && my < wy + 11) return id; }
                    case "button", "flat_button" -> {
                        int bw = num(w, "w", 60), bh = num(w, "h", 14);
                        if (mx >= wx && mx < wx + bw && my >= wy && my < wy + bh) return id;
                    }
                    case "dropdown" -> {
                        if (mx >= wx && mx < wx + num(w, "w", 100) && my >= wy && my < wy + num(w, "h", 12)) return id;
                    }
                    case "tabs" -> {
                        int n = w.has("items") ? w.getAsJsonArray("items").size() : 0, tw = num(w, "w", sW - PAD * 2);
                        if (n > 0 && mx >= wx && mx < wx + tw && my >= wy && my < wy + 14) return id + "#" + (int) ((mx - wx) / Math.max(1, tw / n));
                    }
                    case "list" -> {
                        int lw = num(w, "w", 150), lh = num(w, "h", 80), rowH = Math.max(1, num(w, "row_h", 16));
                        if (mx >= wx && mx < wx + lw && my >= wy && my < wy + lh) return id + "#" + (int) ((my - wy) / rowH);
                    }
                    default -> {}
                }
            }
        }
        return null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean focused) {
        if (click.button() != 0) return false;
        double mx = click.x(), my = click.y();
        invalidate();
        String previousFocus = focusedInput;
        focusedInput = null;
        keyboardFocus = null;

        // ── Handle open dropdown first ────────────────────────────────────────
        if (openDropdownId != null) {
            synchronized (widgetOrder) {
                JsonObject dw = widgetMap.get(openDropdownId);
                int[] r = popupRect(dw);
                if (r != null && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                    int row = (int) ((my - r[1]) / Math.max(1, num(dw, "h", 12)));
                    int n = dw.getAsJsonArray("options").size();
                    if (row >= 0 && row < n) {
                        dw.addProperty("selected", row);
                        fireEvent(openDropdownId + ":" + row);
                    }
                    openDropdownId = null;
                    return true;
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
                            int idx = (int) ((mx - wx) / Math.max(1, tabW));
                            if (idx >= 0 && idx < n) {
                                JsonObject tab = tabItems.get(idx).getAsJsonObject();
                                String tabId  = str(tab, "id", String.valueOf(idx));
                                w.addProperty("selected", tabId);
                                fireEvent(id + ":" + tabId);
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
                            fireEvent(id);
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
                        int rowH = Math.max(1, num(w, "row_h", 16));
                        int scr  = num(w, "scroll", 0);
                        if (mx >= wx && mx < wx + lw && my >= wy && my < wy + lh) {
                            int row = scr + (int) ((my - wy) / rowH);
                            int total = w.has("items") ? w.getAsJsonArray("items").size() : 0;
                            if (row >= 0 && row < total) {
                                w.addProperty("selected", row);
                                fireEvent(id + ":" + row);
                            }
                            return true;
                        }
                    }
                    case "button", "flat_button" -> {
                        int bw = num(w, "w", 60);
                        int bh = num(w, "h", 14);
                        if (mx >= wx && mx < wx + bw && my >= wy && my < wy + bh) {
                            pressId = id;
                            keyboardFocus = id;
                            fireEvent(id);
                            return true;
                        }
                    }
                    case "input" -> {
                        int iw = num(w, "w", 150), ih = num(w, "h", 14);
                        LineEdit edit = inputs.get(id);
                        if (edit != null && mx >= wx && mx < wx + iw && my >= wy && my < wy + ih) {
                            focusedInput = id;
                            keyboardFocus = id;
                            edit.click((float) mx, false);
                            return true;
                        }
                    }
                    case "textbox" -> {
                        int tbw = num(w, "w", 150);
                        int tbh = num(w, "h", 60);
                        if (mx >= wx && mx < wx + tbw && my >= wy && my < wy + tbh)
                            return true; // absorb clicks inside textbox
                    }
                    default -> {}
                }
            }
        }
        return previousFocus != null; // a click that only removed focus still counts as handled
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dx, double dy) {
        if (click.button() == 0 && sliderDragId != null) {
            JsonObject w = widgetMap.get(sliderDragId);
            if (w != null) { applySlider(w, panelX + PAD + num(w, "x", 0), num(w, "w", 100), click.x()); invalidate(); }
            return true;
        }
        if (click.button() == 0 && focusedInput != null) {
            LineEdit edit = inputs.get(focusedInput);
            if (edit != null) { edit.click((float) click.x(), true); invalidate(); return true; }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (click.button() == 0 && pressId != null) { pressId = null; invalidate(); }
        if (click.button() == 0 && sliderDragId != null) {
            JsonObject w = widgetMap.get(sliderDragId);
            if (w != null) fireEvent(sliderDragId + ":" + formatFloat(flt(w, "value", 0f)));
            sliderDragId = null;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        synchronized (widgetOrder) {
            Set<String> active = buildActiveTabs();
            for (String id : widgetOrder) {
                JsonObject w = widgetMap.get(id);
                if (w == null || !isTabVisible(w, active)) continue;
                String type = str(w, "type", "");
                if (!type.equals("list") && !type.equals("textbox")) continue;
                int wx = panelX + PAD + num(w, "x", 0);
                int wy = panelY + TITLE_H + num(w, "y", 0);
                int ww = num(w, "w", 150);
                int wh = num(w, "h", type.equals("list") ? 80 : 60);
                if (mx >= wx && mx < wx + ww && my >= wy && my < wy + wh) {
                    int rowH  = Math.max(1, type.equals("list") ? num(w, "row_h", 16) : num(w, "line_h", 9));
                    int total = type.equals("list")
                        ? (w.has("items") ? w.getAsJsonArray("items").size() : 0)
                        : countTextLines(w);
                    int vis   = Math.max(1, wh / rowH);
                    int scr   = Math.max(0, Math.min(
                        num(w, "scroll", 0) - (int) Math.signum(vAmt) * Math.max(1, (int) Math.abs(vAmt)),
                        Math.max(0, total - vis)));
                    w.addProperty("scroll", scr);
                    invalidate();
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if(input.key()==org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE&&openDropdownId!=null){openDropdownId=null;invalidate();return true;}
        if(input.key()==org.lwjgl.glfw.GLFW.GLFW_KEY_TAB){
            synchronized(widgetOrder){
                Set<String> active=buildActiveTabs();List<String> choices=new ArrayList<>();
                for(String id:widgetOrder){JsonObject w=widgetMap.get(id);if(w==null||!isTabVisible(w,active))continue;String type=str(w,"type","");if(type.equals("button")||type.equals("flat_button")||type.equals("input"))choices.add(id);}
                if(!choices.isEmpty()){int at=choices.indexOf(keyboardFocus),direction=input.hasShiftDown()?-1:1;if(at<0)at=input.hasShiftDown()?0:-1;keyboardFocus=choices.get(Math.floorMod(at+direction,choices.size()));focusedInput="input".equals(str(widgetMap.get(keyboardFocus),"type",""))?keyboardFocus:null;invalidate();return true;}
            }
        }
        if(focusedInput==null&&keyboardFocus!=null&&(input.key()==org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER||input.key()==org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE)){
            synchronized(widgetOrder){JsonObject w=widgetMap.get(keyboardFocus);if(w!=null&&isTabVisible(w,buildActiveTabs())&&(str(w,"type","").equals("button")||str(w,"type","").equals("flat_button"))){fireEvent(keyboardFocus);return true;}}
        }
        if (focusedInput != null && input.key() != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            LineEdit edit = inputs.get(focusedInput);
            if (edit != null && edit.key(input)) { invalidate(); return true; }
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        if (focusedInput == null) return false;
        LineEdit edit = inputs.get(focusedInput);
        if (edit != null && edit.type(input)) { invalidate(); return true; }
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void applySlider(JsonObject w, int wx, int slw, double mx) {
        float t    = (float) (mx - wx) / Math.max(1, slw);
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

    private List<String> wrapText(UiCanvas c, String text, int maxW) {
        List<String> result = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String test = line.length() > 0 ? line + " " + word : word;
            if (tw(c, test) > maxW && line.length() > 0) {
                result.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(test);
            }
        }
        if (line.length() > 0) result.add(line.toString());
        return result.isEmpty() ? List.of("") : result;
    }

    private float alignX(int wx, int w, float textW, String align) {
        return switch (align) {
            case "center" -> wx + (w - textW) / 2;
            case "right"  -> wx + w - textW;
            default       -> wx;
        };
    }

    // ── Canvas helpers (GUI coordinates) ──────────────────────────────────────

    private static void f(UiCanvas c, float x1, float y1, float x2, float y2, int col) {
        if (x2 > x1 && y2 > y1 && (col >>> 24) != 0) c.fill(col, x1, y1, x2 - x1, y2 - y1);
    }
    private static void outline(UiCanvas c, float x, float y, float w, float h, int col) {
        f(c, x, y, x + w, y + 1, col); f(c, x, y + h - 1, x + w, y + h, col);
        f(c, x, y, x + 1, y + h, col); f(c, x + w - 1, y, x + w, y + h, col);
    }
    private void bevelBox(UiCanvas c, int x1, int y1, int x2, int y2, int face, int light, int dark) {
        f(c, x1, y1, x2, y2, face);
        outline(c, x1, y1, x2 - x1, y2 - y1, theme.panelBorder);
        f(c, x1 + 1, y1 + 1, x2 - 1, y1 + 2, light);
        f(c, x1 + 1, y1 + 1, x1 + 2, y2 - 1, light);
        f(c, x1 + 1, y2 - 2, x2 - 1, y2 - 1, dark);
        f(c, x2 - 2, y1 + 1, x2 - 1, y2 - 1, dark);
    }
    /** Sans text at the old font's cell position; legacy § codes keep working. */
    private static void text(UiCanvas c, String s, float x, float y, int col, boolean shadow) {
        if (s == null || s.isEmpty()) return;
        boolean legacy = s.indexOf('§') >= 0;
        if (shadow) {
            int sh = (col & 0xFF000000) | ((col & 0xFCFCFC) >> 2);
            c.text(PeripheralUi.strip(s), x + 1, y, TS, sh, Font.PLAIN, PeripheralUi.SANS);
        }
        if (legacy) c.formattedText(UiRichText.legacy(s), x, y - 1, TS, col, PeripheralUi.SANS);
        else c.text(s, x, y - 1, TS, col, Font.PLAIN, PeripheralUi.SANS);
    }
    private static float tw(UiCanvas c, String s) {
        if (s == null || s.isEmpty()) return 0;
        return s.indexOf('§') >= 0 ? c.formattedTextWidth(UiRichText.legacy(s), TS, PeripheralUi.SANS)
            : c.textWidth(s, TS, Font.PLAIN, PeripheralUi.SANS);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Screen overrides
    // ══════════════════════════════════════════════════════════════════════════

    @Override public void removed() {
        // Only the screen that is still registered clears the shared flags; a replacement
        // opened by openWithLayout must not be reported closed by its predecessor.
        if (instance == this) { screenOpen.set(false); instance = null; }
        super.removed();
    }
    @Override public boolean isPauseScreen()   { return false; }

    // ══════════════════════════════════════════════════════════════════════════
    // Static helpers
    // ══════════════════════════════════════════════════════════════════════════

    private static String formatFloat(float v) {
        return v == (int) v ? String.valueOf((int) v) : String.format("%.2f", v);
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key)&&!o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
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
}
