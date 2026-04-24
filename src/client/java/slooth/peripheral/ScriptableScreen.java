package slooth.peripheral;

import com.google.gson.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A generic, script-driven GUI screen rendered with Minecraft's own UI system.
 *
 * <p>Python scripts define the layout once via {@code open_gui(layout)} and then
 * update individual widgets at runtime via {@code gui_update(id, **props)}.
 * The screen uses Minecraft's native {@code DrawContext}, {@code textRenderer},
 * {@code ButtonWidget}, and {@code TextFieldWidget} — so it looks identical to
 * any built-in Minecraft screen.  Press <b>H</b> to open or close it while a
 * script is running.
 *
 * <h2>Widget types</h2>
 * <pre>
 *   label    – static or dynamic text line  (props: text, color)
 *   bar      – horizontal progress bar      (props: value 0.0–1.0, color)
 *   button   – clickable button             (props: label)
 *              fires an event returned by gui_poll() / GET /gui
 *   input    – text field                   (props: placeholder)
 *              value readable via gui_input(id) / GET /gui → inputs
 *   divider  – 1 px horizontal rule
 *   rect     – filled rectangle             (props: w, h, color)
 * </pre>
 *
 * <h2>Coordinate system</h2>
 * <p>All widget {@code x}/{@code y} values are relative to the top-left of the
 * panel content area (i.e. below the title bar, inside the 4 px padding).
 *
 * <h2>Thread safety</h2>
 * <p>Widget state ({@code widgetMap}, {@code widgetOrder}) is held in a
 * {@link ConcurrentHashMap} / {@link java.util.concurrent.CopyOnWriteArrayList}
 * so the HTTP thread can update props while the render thread is drawing.
 * Button-click events are queued in a {@link ConcurrentLinkedQueue} and popped
 * one per {@code GET /gui} poll call.
 *
 * <h2>Removing this screen</h2>
 * <p>Delete this file and remove the {@code open_gui}, {@code update_gui},
 * {@code close_gui} cases from {@link PeripheralHttpServer}, the
 * {@code GET /gui} handler, and the H-key binding in {@link PeripheralClient}.
 */
public class ScriptableScreen extends Screen {

    // ── Shared state (written by HTTP thread, read by render thread) ──────────
    private static volatile String   sTitle = "Script GUI";
    private static volatile int      sW     = 300;
    private static volatile int      sH     = 200;

    // Ordered list of widget IDs (preserves script-defined order)
    private static final List<String>            widgetOrder = Collections.synchronizedList(new ArrayList<>());
    // Live widget property store — updated by update_gui calls
    private static final Map<String, JsonObject> widgetMap   = new ConcurrentHashMap<>();
    // Button-click events — produced by render thread, consumed by HTTP thread
    private static final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
    // Whether a ScriptableScreen is currently shown
    private static final AtomicBoolean screenOpen = new AtomicBoolean(false);
    // Reference to the current instance so HTTP thread can read input values
    private static volatile ScriptableScreen instance = null;

    // ── Layout constants ──────────────────────────────────────────────────────
    private static final int TITLE_H = 14;  // height of title bar
    private static final int PAD     = 8;   // inner padding

    // ── Colours — dark theme matching PeripheralScreen ────────────────────────
    private static final int C_BG     = 0xEE0D0D0D;
    private static final int C_TITLE  = 0xDD0E2B1C;
    private static final int C_BORDER = 0xFF153020;
    private static final int C_GREEN  = 0xFF00FFAA;
    private static final int C_WHITE  = 0xFFDDDDDD;
    private static final int C_BAR_BG = 0xFF333333;

    // ── Panel origin (set in init, used in render) ────────────────────────────
    private int panelX, panelY;

    // ── Interactive widget instances (created in init) ─────────────────────────
    private final List<ButtonWidget>           buttons = new ArrayList<>();
    private final Map<String, TextFieldWidget> inputs  = new HashMap<>();

    // ══════════════════════════════════════════════════════════════════════════
    // Static API — called from PeripheralHttpServer on the HTTP thread
    // ══════════════════════════════════════════════════════════════════════════

    /** Open the screen with the given layout JSON. Safe to call from any thread. */
    public static void openWithLayout(JsonObject layout) {
        sTitle = str(layout, "title", "Script GUI");
        sW     = num(layout, "w",     300);
        sH     = num(layout, "h",     200);
        // Hold widgetOrder's lock for the entire clear+repopulate so the render
        // thread (which also synchronizes on widgetOrder in render() and init())
        // never sees a partially rebuilt widget list.
        synchronized (widgetOrder) {
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
        }
        MinecraftClient.getInstance().execute(() ->
            MinecraftClient.getInstance().setScreen(new ScriptableScreen()));
    }

    /** Update one widget's properties while the screen is open. Any thread. */
    public static void update(String id, JsonObject props) {
        JsonObject existing = widgetMap.get(id);
        if (existing == null) return;
        // Build a new object from a snapshot of the existing fields, then apply
        // updates, then replace the map entry atomically via put(). The render
        // thread will see either the old complete object or the new complete
        // object — never a partially mutated one.
        JsonObject updated = existing.deepCopy();
        for (Map.Entry<String, JsonElement> e : props.entrySet())
            updated.add(e.getKey(), e.getValue());
        widgetMap.put(id, updated);
    }

    /** Pop the next button-click event ID, or null if none pending. */
    public static String pollEvent() { return events.poll(); }

    /** True if a ScriptableScreen is currently displayed. */
    public static boolean isOpen() { return screenOpen.get(); }

    /** True if a layout has been loaded by a script (so reopening makes sense). */
    public static boolean hasLayout() { return !widgetOrder.isEmpty(); }

    /** Close the screen if it is open. Safe to call from any thread. */
    public static void closeScreen() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> { if (mc.currentScreen instanceof ScriptableScreen) mc.setScreen(null); });
    }

    /** Return all current input field values as id → text. */
    public static Map<String, String> getInputValues() {
        ScriptableScreen scr = instance;
        if (scr == null) return Map.of();
        Map<String, String> map = new LinkedHashMap<>();
        scr.inputs.forEach((id, tf) -> map.put(id, tf.getText()));
        return map;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Screen lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    public ScriptableScreen() {
        super(Text.literal(sTitle));
        screenOpen.set(true);
        instance = this;
    }

    @Override
    protected void init() {
        panelX = (width  - sW) / 2;
        panelY = (height - sH) / 2;

        // Remove any previously added widgets
        buttons.forEach(this::remove);
        buttons.clear();
        inputs.values().forEach(this::remove);
        inputs.clear();

        // Create Minecraft widget objects for interactive types (button, input).
        // Non-interactive types (label, bar, divider, rect) are drawn in render().
        synchronized (widgetOrder) {
            for (String id : widgetOrder) {
                JsonObject w = widgetMap.get(id);
                if (w == null) continue;
                String type = str(w, "type", "");
                // Widget x/y are relative to content area (inside title bar + padding)
                int wx = panelX + PAD + num(w, "x", 0);
                int wy = panelY + TITLE_H + num(w, "y", 0);

                switch (type) {
                    case "button" -> {
                        int bw = num(w, "w", 60);
                        int bh = num(w, "h", 14);
                        String label = str(w, "label", id);
                        String fid   = id;
                        ButtonWidget btn = ButtonWidget.builder(Text.literal(label),
                                b -> events.offer(fid))
                            .dimensions(wx, wy, bw, bh)
                            .build();
                        buttons.add(btn);
                        addDrawableChild(btn);
                    }
                    case "input" -> {
                        int iw = num(w, "w", 150);
                        int ih = num(w, "h", 14);
                        TextFieldWidget tf = new TextFieldWidget(
                            textRenderer, wx, wy, iw, ih, Text.literal(""));
                        if (w.has("placeholder")) tf.setSuggestion(str(w, "placeholder", ""));
                        if (w.has("value"))       tf.setText(str(w, "value", ""));
                        inputs.put(id, tf);
                        addDrawableChild(tf);
                    }
                }
            }
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // ── Panel chrome ─────────────────────────────────────────────────────
        // Background
        ctx.fill(panelX,          panelY,          panelX + sW,     panelY + sH,      C_BG);
        // Border (1px on each edge)
        ctx.fill(panelX,          panelY,          panelX + sW,     panelY + 1,       C_BORDER);
        ctx.fill(panelX,          panelY + sH - 1, panelX + sW,     panelY + sH,      C_BORDER);
        ctx.fill(panelX,          panelY,          panelX + 1,      panelY + sH,      C_BORDER);
        ctx.fill(panelX + sW - 1, panelY,          panelX + sW,     panelY + sH,      C_BORDER);
        // Title bar
        ctx.fill(panelX, panelY, panelX + sW, panelY + TITLE_H, C_TITLE);
        ctx.drawText(textRenderer,
            Text.literal("§a◈ §f" + sTitle),
            panelX + 4, panelY + 3, C_GREEN, false);

        // ── Non-interactive widgets ───────────────────────────────────────────
        synchronized (widgetOrder) {
            for (String id : widgetOrder) {
                JsonObject w = widgetMap.get(id);
                if (w == null) continue;
                String type = str(w, "type", "");
                int wx = panelX + PAD + num(w, "x", 0);
                int wy = panelY + TITLE_H + num(w, "y", 0);

                switch (type) {
                    case "label" -> {
                        String  text   = str(w, "text", "");
                        int     color  = color(w, "color", C_WHITE);
                        boolean shadow = w.has("shadow") && w.get("shadow").getAsBoolean();
                        ctx.drawText(textRenderer, Text.literal(text), wx, wy, color, shadow);
                    }
                    case "bar" -> {
                        int   bw  = num(w, "w", 100);
                        int   bh  = num(w, "h", 5);
                        float val = flt(w, "value", 0f);
                        int   col = color(w, "color",    0xFF00FFAA);
                        int   bg  = color(w, "bg_color", C_BAR_BG);
                        ctx.fill(wx, wy, wx + bw, wy + bh, bg);
                        int filled = (int)(Math.max(0f, Math.min(1f, val)) * bw);
                        if (filled > 0) ctx.fill(wx, wy, wx + filled, wy + bh, col);
                    }
                    case "divider" -> {
                        int dw  = num(w, "w", sW - PAD * 2);
                        int col = color(w, "color", C_BORDER);
                        ctx.fill(wx, wy, wx + dw, wy + 1, col);
                    }
                    case "rect" -> {
                        int rw  = num(w, "w", 50);
                        int rh  = num(w, "h", 20);
                        int col = color(w, "color", 0xFF222222);
                        ctx.fill(wx, wy, wx + rw, wy + rh, col);
                    }
                    // "button" and "input" are drawn by super.render() via addDrawableChild
                }
            }
        }

        // Draws buttons, inputs, and any other registered drawables
        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public void removed() {
        screenOpen.set(false);
        instance = null;
    }

    /** Don't pause the game when this screen is open. */
    @Override
    public boolean shouldPause() { return false; }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) ? o.get(key).getAsString() : def;
    }
    private static int num(JsonObject o, String key, int def) {
        return o.has(key) ? o.get(key).getAsInt() : def;
    }
    private static float flt(JsonObject o, String key, float def) {
        return o.has(key) ? o.get(key).getAsFloat() : def;
    }
    private static int color(JsonObject o, String key, int def) {
        if (!o.has(key)) return def;
        String s = o.get(key).getAsString().trim();
        try {
            if (s.startsWith("#")) {
                long v = Long.parseLong(s.substring(1), 16);
                return s.length() > 7 ? (int) v : (int)(0xFF000000L | v);
            }
            return switch (s.toLowerCase()) {
                case "red"              -> 0xFFFF4444;
                case "green"            -> 0xFF00FFAA;
                case "yellow"           -> 0xFFFFAA00;
                case "blue"             -> 0xFF00CCFF;
                case "white"            -> 0xFFDDDDDD;
                case "grey", "gray"     -> 0xFF888888;
                case "dark_grey", "dark_gray" -> 0xFF444444;
                case "orange"           -> 0xFFFF8800;
                case "purple"           -> 0xFFAA66FF;
                case "pink"             -> 0xFFFF88BB;
                case "dark_red"         -> 0xFFAA0000;
                case "dark_green"       -> 0xFF007755;
                default                 -> def;
            };
        } catch (Exception e) { return def; }
    }
}
