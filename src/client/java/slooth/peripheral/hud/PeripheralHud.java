package slooth.peripheral.hud;

import slooth.peripheral.PeripheralClient;
import slooth.peripheral.ui.PeripheralTheme;
import slooth.peripheral.util.PeripheralConfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.pane.ui.NativeOverlayContext;
import dev.pane.ui.UiContext;
import dev.pane.ui.UiEasing;
import dev.pane.ui.UiHud;
import dev.pane.ui.UiHudPlacement;
import dev.pane.ui.UiMinecraftTextStyle;
import dev.pane.ui.UiTheme;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Python-scriptable HUD, drawn and placed by PaneLib.
 *
 * <p>Scripts call hud_set([...]) / hud_update(id, ...) / hud_clear() via the HTTP API. Each element
 * becomes one PaneLib {@link UiHud} that scales with the GUI like vanilla's HUD, so script
 * coordinates stay in GUI pixels. Players move elements in PaneLib's {@code UiHudEditor}.
 *
 * <p>Element JSON fields (all optional except type):
 * <pre>
 *   type     – "label" | "bar" | "rect" | "divider" | "item" | "graph" | "compass"
 *   id       – string identifier (for hud_update, and for remembering where a player moved it)
 *   x, y     – position (pixels from anchor corner)
 *   anchor   – "top_left" (default) | "top_right" | "bottom_left" | "bottom_right" | "center"
 *
 *   label:   text, color, shadow (bool), align ("left" keeps x as the left edge on right/center anchors)
 *   bar:     w, h, value (0.0–1.0), color, bg_color
 *   rect:    w, h, color, border (color or omit for no border)
 *   divider: w, color
 *   item:    item_id (e.g. "minecraft:diamond_chestplate") – renders a 16×16 item sprite
 *   graph:   w, h, values (array), color, min, max (autoscale when min/max absent)
 *            – push a new sample with hud_update(id, push=<float>)
 *   compass: w, h, color – horizontal yaw strip with N/E/S/W ticks
 * </pre>
 *
 * Colors: named ("red","green","yellow","blue","white","grey","orange","purple","pink")
 *         or hex "#RRGGBB" / "#AARRGGBB".
 *
 * <p>Where players move elements is saved to {@code config/peripheral/hud_layouts.json}, keyed by
 * {@code script → id}, and wins over the script's own position until reset.
 */
public class PeripheralHud {

    // CopyOnWriteArrayList: safe to read from the client thread while HTTP thread writes.
    // Each element stores an "_owner" property (script name) so multiple scripts can
    // coexist — stopping one only removes its own elements.
    private static final CopyOnWriteArrayList<JsonObject> elements = new CopyOnWriteArrayList<>();

    private static final int GRAPH_MAX_POINTS = 240;

    // ── Saved positions ─────────────────────────────────────────────────────
    // owner (script) → id → {"placement": {...}} from the PaneLib editor, or the older {x, y, anchor}
    private static final Map<String, Map<String, JsonObject>> overrides = new ConcurrentHashMap<>();
    private static final Path LAYOUT_PATH = Paths.get("config", "peripheral", "hud_layouts.json");

    // ── Public API (called from HTTP handlers) ────────────────────────────────

    /** Replace all elements owned by scriptName with the new list. Other scripts' elements are untouched. */
    public static void setElements(List<JsonObject> elems, String scriptName) {
        String owner = scriptName != null ? scriptName : "";
        elements.removeIf(e -> owner.equals(getOwner(e)));
        for (JsonObject el : elems) {
            JsonObject copy = el.deepCopy();
            if (!owner.isEmpty()) copy.addProperty("_owner", owner);
            applyLegacyOverride(copy, owner);
            elements.add(copy);
        }
    }

    /** Remove only the elements owned by scriptName. Called when a script stops. */
    public static void clearIfOwner(String scriptName) {
        if (scriptName != null && !scriptName.isEmpty()) {
            elements.removeIf(e -> scriptName.equals(getOwner(e)));
        }
    }

    /** Update a single element by id (searches across all scripts' elements). */
    public static void updateElement(String id, JsonObject updates) { updateElement(id, updates, ""); }
    public static void updateElement(String id, JsonObject updates, String owner) {
        for (int i = 0; i < elements.size(); i++) {
            JsonObject el = elements.get(i);
            if (el.has("id") && el.get("id").getAsString().equals(id) && owner.equals(getOwner(el))) {
                JsonObject merged = el.deepCopy();
                applyUpdates(merged, updates);
                elements.set(i, merged);
                return;
            }
        }
        // id not found — add as new element (owner unknown, will never be auto-cleared)
        JsonObject copy = new JsonObject();
        copy.addProperty("id", id);
        copy.addProperty("_owner", owner);
        applyUpdates(copy, updates);
        elements.add(copy);
    }

    /** Merge props into el, handling the graph "push" pseudo-prop specially. */
    private static void applyUpdates(JsonObject el, JsonObject updates) {
        updates.entrySet().forEach(e -> {
            String k = e.getKey();
            if (k.equals("_owner")) return;
            if (k.equals("push")) {
                JsonArray vals = el.has("values") && el.get("values").isJsonArray()
                    ? el.getAsJsonArray("values") : new JsonArray();
                try { vals.add(e.getValue().getAsFloat()); } catch (Exception ignored) {}
                while (vals.size() > GRAPH_MAX_POINTS) vals.remove(0);
                el.add("values", vals);
                return;
            }
            el.add(k, e.getValue());
        });
    }

    /** Clear all elements from all scripts (used by the UI "Clear" button). */
    public static void clearElements() {
        elements.clear();
    }

    public static JsonArray getElementsJson() {
        JsonArray arr = new JsonArray();
        elements.forEach(arr::add);
        return arr;
    }

    /** Live element list. */
    public static CopyOnWriteArrayList<JsonObject> elements() { return elements; }

    /** Scripts drawing on the HUD, in the order their first element arrived, with element counts. */
    public static Map<String, Integer> owners() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (JsonObject el : elements) out.merge(getOwner(el), 1, Integer::sum);
        return out;
    }

    /** Whether a player moved any of this script's elements. */
    public static boolean hasLayout(String owner) {
        Map<String, JsonObject> m = overrides.get(owner == null ? "" : owner);
        return m != null && !m.isEmpty();
    }

    /** Forgets where a player moved this script's elements, so they go back to the script's own positions. */
    public static void resetLayout(String owner) {
        if (overrides.remove(owner == null ? "" : owner) != null) saveLayouts();
    }

    public static String ownerOf(JsonObject el) { return getOwner(el); }

    private static String getOwner(JsonObject el) {
        return el.has("_owner") ? el.get("_owner").getAsString() : "";
    }

    // ── Persistence ────────────────────────────────────────────────────────────

    public static void loadLayouts() {
        overrides.clear();
        if (!Files.exists(LAYOUT_PATH)) return;
        try (var r = Files.newBufferedReader(LAYOUT_PATH, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            for (var scriptE : root.entrySet()) {
                if (!scriptE.getValue().isJsonObject()) continue;
                Map<String, JsonObject> m = new ConcurrentHashMap<>();
                for (var idE : scriptE.getValue().getAsJsonObject().entrySet()) {
                    if (idE.getValue().isJsonObject()) m.put(idE.getKey(), idE.getValue().getAsJsonObject());
                }
                overrides.put(scriptE.getKey(), m);
            }
        } catch (Exception e) {
            PeripheralClient.LOGGER.warn("[Peripheral] HUD layout load failed: {}", e.getMessage());
        }
    }

    public static void saveLayouts() {
        try {
            Files.createDirectories(LAYOUT_PATH.getParent());
            JsonObject root = new JsonObject();
            overrides.forEach((owner, m) -> {
                JsonObject byId = new JsonObject();
                m.forEach(byId::add);
                if (byId.size() > 0) root.add(owner, byId);
            });
            try (var w = Files.newBufferedWriter(LAYOUT_PATH, StandardCharsets.UTF_8)) {
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root, w);
            }
        } catch (Exception e) {
            PeripheralClient.LOGGER.warn("[Peripheral] HUD layout save failed: {}", e.getMessage());
        }
    }

    /** Remembers where a player put one element; the script's own placement clears it. */
    public static void recordPlacement(String owner, String id, UiHudPlacement placement, UiHudPlacement scripts) {
        if (id == null || id.isEmpty()) return;
        var byId = overrides.computeIfAbsent(owner != null ? owner : "", k -> new ConcurrentHashMap<>());
        if (placement.equals(scripts)) byId.remove(id);
        else {
            JsonObject p = new JsonObject();
            p.addProperty("anchor", placement.anchor().name());
            p.addProperty("marginX", placement.marginX());
            p.addProperty("marginY", placement.marginY());
            p.addProperty("offsetX", placement.offsetX());
            p.addProperty("offsetY", placement.offsetY());
            JsonObject o = new JsonObject();
            o.add("placement", p);
            byId.put(id, o);
        }
        saveLayouts();
    }

    /** A placement a player saved for this element, or null. */
    public static UiHudPlacement savedPlacement(String owner, String id) {
        if (id == null) return null;
        Map<String, JsonObject> m = overrides.get(owner);
        JsonObject o = m == null ? null : m.get(id);
        if (o == null || !o.has("placement")) return null;
        try {
            JsonObject p = o.getAsJsonObject("placement");
            return new UiHudPlacement(UiHudPlacement.Anchor.valueOf(p.get("anchor").getAsString()),
                p.get("marginX").getAsFloat(), p.get("marginY").getAsFloat(), 1,
                p.get("offsetX").getAsFloat(), p.get("offsetY").getAsFloat());
        } catch (Exception e) { return null; }
    }

    /** Layouts saved by the old Peripheral editor stored the element's own x, y and anchor. */
    private static void applyLegacyOverride(JsonObject el, String owner) {
        if (!el.has("id")) return;
        Map<String, JsonObject> m = overrides.get(owner != null ? owner : "");
        if (m == null) return;
        JsonObject o = m.get(el.get("id").getAsString());
        if (o == null || o.has("placement")) return;
        if (o.has("x"))      el.addProperty("x", o.get("x").getAsInt());
        if (o.has("y"))      el.addProperty("y", o.get("y").getAsInt());
        if (o.has("anchor")) el.addProperty("anchor", o.get("anchor").getAsString());
    }

    // ── PaneLib widgets ─────────────────────────────────────────────────────

    /** One element's widget and what it was last given. */
    private static final class Widget {
        final UiHud hud;
        String owner, id;
        volatile JsonObject el;
        Widget(UiHud hud) { this.hud = hud; }
    }

    private static final Map<String, Widget> widgets = new HashMap<>();
    private static int nextId;

    public static void register() {
        loadLayouts();
        ClientTickEvents.END_CLIENT_TICK.register(PeripheralHud::sync);
    }

    /** Keeps one PaneLib HUD per element: created, resized, placed and repainted from the element list. */
    private static void sync(Minecraft client) {
        Map<String, JsonObject> current = new LinkedHashMap<>();
        Map<String, Integer> unnamed = new HashMap<>();
        for (JsonObject el : elements) {
            String owner = getOwner(el);
            String key = owner + "\u0000" + (el.has("id") ? "id:" + el.get("id").getAsString()
                : "#" + unnamed.merge(owner, 1, Integer::sum));
            current.putIfAbsent(key, el);
        }
        widgets.entrySet().removeIf(entry -> {
            if (current.containsKey(entry.getKey())) return false;
            entry.getValue().hud.close();
            return true;
        });
        if (client.font == null) return;
        // While the player drags HUDs around, the editor owns their placement.
        boolean editing = client.gui.screen() instanceof dev.pane.ui.UiHudEditor;
        float yaw = client.player != null ? client.player.getYRot() : 0;
        for (var entry : current.entrySet()) {
            JsonObject el = entry.getValue();
            Widget w = widgets.computeIfAbsent(entry.getKey(), k -> create());
            w.owner = getOwner(el);
            w.id = el.has("id") ? el.get("id").getAsString() : null;
            w.el = el;
            Shape shape = shape(el, client);
            w.hud.setSize(shape.width(), shape.height());
            UiHudPlacement scripts = placement(el, shape);
            UiHudPlacement saved = savedPlacement(w.owner, w.id);
            if (!editing) {
                // While the editor is open it owns placement and the move callbacks; content stays live.
                w.hud.setPlacement(saved != null ? saved : scripts);
                if (w.id != null) {
                    String owner = w.owner, id = w.id;
                    w.hud.setEditable((owner.isEmpty() ? "" : basename(owner) + " · ") + id, scripts,
                        moved -> recordPlacement(owner, id, moved, scripts));
                } else w.hud.setEditable(null, null, null);
            }
            w.hud.setValue("el", el);
            w.hud.setValue("theme", PeripheralConfig.theme);
            if ("compass".equals(str(el, "type", ""))) w.hud.setValue("yaw", (double) yaw);
        }
    }

    private static Widget create() {
        Widget[] self = new Widget[1];
        UiHud hud = new UiHud(1, 1, UiTheme.dark(), ui -> paint(ui), overlay -> overlay(overlay, self[0]));
        self[0] = new Widget(hud);
        hud.setScalesWithGui(true);
        hud.setMotionScale(0);      // script HUDs move and appear instantly, like vanilla's
        hud.setShowOnScreens(true); // and stay visible behind screens, as before
        hud.attach(Identifier.fromNamespaceAndPath("peripheral", "hud/" + nextId++));
        return self[0];
    }

    /** An element's widget size and how far its drawing reaches past the box the script placed. */
    private record Shape(int boxWidth, int boxHeight, int above, int below, boolean leftAligned) {
        int width() { return Math.max(1, Math.min(1024, boxWidth)); }
        int height() { return Math.max(1, Math.min(1024, boxHeight + above + below)); }
    }

    private static Shape shape(JsonObject el, Minecraft client) {
        String type = str(el, "type", "label");
        boolean left = type.equals("label") && "left".equals(str(el, "align", ""));
        return switch (type) {
            case "bar", "rect" -> new Shape(num(el, "w", 100), num(el, "h", type.equals("bar") ? 5 : 20), 0, 0, false);
            case "graph"       -> new Shape(num(el, "w", 120), num(el, "h", 40), 0, 0, false);
            // The facing marker reaches 2 above and below the strip; letters sit under it.
            case "compass"     -> new Shape(num(el, "w", 120), num(el, "h", 16), 2, 2 + client.font.lineHeight, false);
            case "divider"     -> new Shape(num(el, "w", 100), 1, 0, 0, false);
            case "item"        -> new Shape(16, 16, 0, 0, false);
            default            -> new Shape(client.font.width(str(el, "text", "")), client.font.lineHeight, 0, 0, left);
        };
    }

    /**
     * The PaneLib placement that puts the element's box exactly where Peripheral always has: x, y in
     * GUI pixels from the anchor's corner, or from the screen centre for "center".
     */
    private static UiHudPlacement placement(JsonObject el, Shape shape) {
        String anchor = str(el, "anchor", "top_left");
        float x = num(el, "x", 0), y = num(el, "y", 0), w = shape.boxWidth(), h = shape.boxHeight();
        boolean right = anchor.equals("top_right") || anchor.equals("bottom_right");
        boolean bottom = anchor.equals("bottom_left") || anchor.equals("bottom_right");
        boolean center = anchor.equals("center");
        // A left-aligned label measured from the right or the centre keeps x as its left edge.
        float leftEdgeShift = shape.leftAligned() ? w : 0;
        if (center) {
            float ox = x + (shape.leftAligned() ? w / 2 : 0);
            float oy = y + h / 2 + (shape.below() - shape.above()) / 2F;
            return new UiHudPlacement(UiHudPlacement.Anchor.CENTER, 0, 0, 1, ox, oy);
        }
        UiHudPlacement.Anchor a = right ? (bottom ? UiHudPlacement.Anchor.BOTTOM_RIGHT : UiHudPlacement.Anchor.TOP_RIGHT)
            : bottom ? UiHudPlacement.Anchor.BOTTOM_LEFT : UiHudPlacement.Anchor.TOP_LEFT;
        float mx = right ? x - leftEdgeShift : x;
        float my = bottom ? y - shape.below() : y - shape.above();
        // Margins can't go negative, so a box placed past the edge keeps the overflow as an offset.
        float ox = mx < 0 ? (right ? -mx : mx) : 0, oy = my < 0 ? (bottom ? -my : my) : 0;
        return new UiHudPlacement(a, Math.max(0, mx), Math.max(0, my), 1, ox, oy);
    }

    // ── Painting ─────────────────────────────────────────────────────────────

    private static void paint(UiContext ui) {
        Object value = ui.value("el");
        if (!(value instanceof JsonObject el)) return;
        var c = ui.canvas();
        PeripheralTheme th = PeripheralTheme.current();
        boolean term = th.isTerminal();
        String type = str(el, "type", "label");
        float w = ui.width(), h = ui.height();
        switch (type) {
            case "label" -> ui.nativeSlot("text", 0, 0, w, h);
            case "rect" -> {
                c.fill(color(el, "color", 0xAA000000), 0, 0, w, h);
                if (el.has("border")) {
                    int b = color(el, "border", term ? 0xFF444444 : th.divider);
                    c.fill(b, 0, 0, w, 1); c.fill(b, 0, h - 1, w, 1); c.fill(b, 0, 0, 1, h); c.fill(b, w - 1, 0, 1, h);
                }
            }
            case "bar" -> {
                float val = Math.max(0f, Math.min(1f, flt(el, "value", 0f)));
                c.fill(color(el, "bg_color", term ? 0xFF222222 : th.barBg), 0, 0, w, h);
                int filled = (int) (val * w);
                if (filled > 0) c.fill(color(el, "color", term ? 0xFF00FFAA : th.accent), 0, 0, filled, h);
            }
            case "divider" -> c.fill(color(el, "color", term ? 0xFF444444 : th.divider), 0, 0, w, 1);
            case "item" -> ui.nativeSlot("item", 0, 0, 16, 16);
            case "graph" -> paintGraph(ui, el, (int) w, (int) h, term, th);
            case "compass" -> paintCompass(ui, el, term, th);
            default -> {}
        }
    }

    private static void overlay(NativeOverlayContext overlay, Widget widget) {
        JsonObject el = widget == null ? null : widget.el;
        if (el == null) return;
        PeripheralTheme th = PeripheralTheme.current();
        boolean term = th.isTerminal();
        switch (str(el, "type", "label")) {
            case "label" -> overlay.text("text", str(el, "text", ""), UiMinecraftTextStyle.hud()
                .color(color(el, "color", term ? 0xFFDDDDDD : th.text)).shadow(bool(el, "shadow", false)));
            case "item" -> {
                Identifier ident = Identifier.tryParse(str(el, "item_id", ""));
                if (ident != null && BuiltInRegistries.ITEM.containsKey(ident))
                    overlay.item("item", new ItemStack(BuiltInRegistries.ITEM.getValue(ident)));
            }
            case "compass" -> {
                int col = color(el, "color", term ? 0xFF00FFAA : th.accent);
                for (String label : COMPASS_MAJOR) overlay.text("compass:" + label, label,
                    UiMinecraftTextStyle.hud().color(col).shadow(false).alignment(UiMinecraftTextStyle.Alignment.CENTER));
            }
            default -> {}
        }
    }

    private static void paintGraph(UiContext ui, JsonObject el, int w, int h, boolean term, PeripheralTheme th) {
        var c = ui.canvas();
        int col = color(el, "color", term ? 0xFF00FFAA : th.accent);
        int area = (col & 0x00FFFFFF) | 0x55000000;
        c.fill(color(el, "bg_color", 0x66000000), 0, 0, w, h);
        if (!el.has("values") || !el.get("values").isJsonArray()) return;
        JsonArray arr = el.getAsJsonArray("values");
        int n = arr.size();
        if (n == 0) return;
        // Use only the most recent w samples (one column each).
        int start = Math.max(0, n - w), count = n - start;
        boolean hasMin = el.has("min"), hasMax = el.has("max");
        float min = hasMin ? flt(el, "min", 0f) : Float.MAX_VALUE;
        float max = hasMax ? flt(el, "max", 1f) : -Float.MAX_VALUE;
        if (!hasMin || !hasMax) {
            for (int i = start; i < n; i++) {
                float v = valAt(arr, i);
                if (!hasMin && v < min) min = v;
                if (!hasMax && v > max) max = v;
            }
        }
        if (max <= min) max = min + 1f;
        int prevY = -1;
        for (int i = 0; i < count; i++) {
            float t = Math.max(0f, Math.min(1f, (valAt(arr, start + i) - min) / (max - min)));
            int topY = h - 1 - Math.round(t * (h - 1));
            c.fill(area, i, topY, 1, h - topY);
            int a = prevY >= 0 ? Math.min(prevY, topY) : topY, b = prevY >= 0 ? Math.max(prevY, topY) : topY;
            c.fill(col, i, a, 1, b - a + 1);
            prevY = topY;
        }
    }

    private static final String[] COMPASS_LABELS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
    private static final String[] COMPASS_MAJOR = {"S", "W", "N", "E"};
    private static final float[] COMPASS_YAWS = {0, 45, 90, 135, 180, 225, 270, 315};

    private static void paintCompass(UiContext ui, JsonObject el, boolean term, PeripheralTheme th) {
        var c = ui.canvas();
        int w = num(el, "w", 120), h = num(el, "h", 16), top = 2;
        int col = color(el, "color", term ? 0xFF00FFAA : th.accent);
        int tick = term ? 0xFF888888 : th.textDim;
        c.fill(color(el, "bg_color", 0x88000000), 0, top, w, h);
        float yaw = ui.animateAngle("yaw", (float) ui.numberValue("yaw", 0), 90, UiEasing.EASE_OUT);
        float cx = w / 2F, visible = 120f, perDeg = w / visible;
        for (int i = 0; i < COMPASS_LABELS.length; i++) {
            float delta = wrap180(COMPASS_YAWS[i] - yaw);
            boolean major = COMPASS_LABELS[i].length() == 1;
            if (Math.abs(delta) > visible / 2f) continue;
            float tx = Math.round(cx + delta * perDeg);
            c.fill(tick, tx, top, 1, major ? h : h / 2F);
            if (major) ui.nativeSlot("compass:" + COMPASS_LABELS[i], tx - 8, top + h + 1, 16, 9);
        }
        // Centre marker (current facing).
        c.fill(col, cx, 0, 1, h + 4);
    }

    private static float valAt(JsonArray arr, int i) {
        try { return arr.get(i).getAsFloat(); } catch (Exception e) { return 0f; }
    }

    private static float wrap180(float deg) {
        deg %= 360f;
        if (deg > 180f)  deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }

    private static String basename(String path) { int i = path.lastIndexOf('/'); return path.substring(i + 1); }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static String str(JsonObject o, String k, String def) {
        return o.has(k) ? o.get(k).getAsString() : def;
    }

    static int num(JsonObject o, String k, int def) {
        try { return o.has(k) ? o.get(k).getAsInt() : def; } catch (Exception e) { return def; }
    }

    static float flt(JsonObject o, String k, float def) {
        try { return o.has(k) ? o.get(k).getAsFloat() : def; } catch (Exception e) { return def; }
    }

    static boolean bool(JsonObject o, String k, boolean def) {
        return o.has(k) ? o.get(k).getAsBoolean() : def;
    }

    static int color(JsonObject o, String key, int def) {
        if (!o.has(key)) return def;
        String s = o.get(key).getAsString().trim();
        try {
            if (s.startsWith("#")) {
                long v = Long.parseLong(s.substring(1), 16);
                return s.length() > 7 ? (int) v : (int)(0xFF000000L | v);
            }
            return switch (s.toLowerCase()) {
                case "red"                    -> 0xFFFF4444;
                case "green"                  -> 0xFF00FFAA;
                case "yellow"                 -> 0xFFFFAA00;
                case "blue"                   -> 0xFF00CCFF;
                case "white"                  -> 0xFFDDDDDD;
                case "grey", "gray"           -> 0xFF888888;
                case "dark_grey", "dark_gray" -> 0xFF444444;
                case "orange"                 -> 0xFFFF8800;
                case "purple"                 -> 0xFFAA66FF;
                case "pink"                   -> 0xFFFF88BB;
                case "dark_red"               -> 0xFFAA0000;
                case "dark_green"             -> 0xFF007755;
                default                       -> def;
            };
        } catch (Exception e) { return def; }
    }
}
