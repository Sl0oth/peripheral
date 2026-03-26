package slooth.peripheral;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Python-scriptable HUD overlay.
 *
 * <p>Scripts call hud_set([...]) / hud_update(id, ...) / hud_clear() via the HTTP API.
 * Elements are rendered every frame directly on the game screen.
 *
 * <p>Element JSON fields (all optional except type):
 * <pre>
 *   type     – "label" | "bar" | "rect" | "divider" | "item"
 *   id       – string identifier (for hud_update)
 *   x, y     – position (pixels from anchor corner)
 *   anchor   – "top_left" (default) | "top_right" | "bottom_left" | "bottom_right" | "center"
 *
 *   label:   text, color, shadow (bool)
 *   bar:     w, h, value (0.0–1.0), color, bg_color
 *   rect:    w, h, color, border (color or omit for no border)
 *   divider: w, color
 *   item:    item_id (e.g. "minecraft:diamond_chestplate") – renders a 16×16 item sprite
 * </pre>
 *
 * Colors: named ("red","green","yellow","blue","white","grey","orange","purple","pink")
 *         or hex "#RRGGBB" / "#AARRGGBB".
 */
public class PeripheralHud {

    // CopyOnWriteArrayList: safe to read from the render thread while HTTP thread writes
    private static final CopyOnWriteArrayList<JsonObject> elements = new CopyOnWriteArrayList<>();

    // ── Public API (called from HTTP handlers) ────────────────────────────────

    public static void setElements(List<JsonObject> elems) {
        elements.clear();
        elements.addAll(elems);
    }

    public static void updateElement(String id, JsonObject updates) {
        for (int i = 0; i < elements.size(); i++) {
            JsonObject el = elements.get(i);
            if (el.has("id") && el.get("id").getAsString().equals(id)) {
                JsonObject merged = el.deepCopy();
                updates.entrySet().forEach(e -> merged.add(e.getKey(), e.getValue()));
                elements.set(i, merged);
                return;
            }
        }
        // id not found — add as new element
        JsonObject copy = updates.deepCopy();
        copy.addProperty("id", id);
        elements.add(copy);
    }

    public static void clearElements() {
        elements.clear();
    }

    public static JsonArray getElementsJson() {
        JsonArray arr = new JsonArray();
        elements.forEach(arr::add);
        return arr;
    }

    // ── Registration ──────────────────────────────────────────────────────────

    public static void register() {
        HudRenderCallback.EVENT.register((ctx, tickDelta) -> {
            if (elements.isEmpty()) return;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;
            render(ctx, client);
        });
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private static void render(DrawContext ctx, MinecraftClient client) {
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        for (JsonObject el : elements) {
            try { renderElement(ctx, el, sw, sh, client); }
            catch (Exception ignored) {}
        }
    }

    private static void renderElement(DrawContext ctx, JsonObject el, int sw, int sh, MinecraftClient client) {
        String type   = str(el, "type",   "label");
        String anchor = str(el, "anchor", "top_left");
        int rawX = num(el, "x", 0);
        int rawY = num(el, "y", 0);

        // Compute element dimensions (needed for right/bottom anchors)
        // Labels with align='left': rawX is distance from anchor edge to LEFT side of text.
        String align = str(el, "align", "");
        boolean labelLeftAlign = type.equals("label") && "left".equals(align);

        int elW, elH;
        switch (type) {
            case "bar", "rect" -> { elW = num(el, "w", 100); elH = num(el, "h", type.equals("bar") ? 5 : 20); }
            case "divider"     -> { elW = num(el, "w", 100); elH = 1; }
            case "item"        -> { elW = 16; elH = 16; }
            default            -> { elW = labelLeftAlign ? 0 : client.textRenderer.getWidth(str(el, "text", "")); elH = 9; }
        }

        // Resolve anchor → actual screen coordinates
        int x = switch (anchor) {
            case "top_right", "bottom_right" -> labelLeftAlign ? sw - rawX : sw - rawX - elW;
            case "center"                    -> labelLeftAlign ? sw / 2 + rawX : sw / 2 + rawX - elW / 2;
            default                          -> rawX;
        };
        int y = switch (anchor) {
            case "bottom_left", "bottom_right" -> sh - rawY - elH;
            case "center"                      -> sh / 2 + rawY;
            default                            -> rawY;
        };

        switch (type) {
            case "label" -> {
                String  text   = str(el,  "text",   "");
                int     color  = color(el, "color",  0xFFDDDDDD);
                boolean shadow = bool(el, "shadow",  false);
                ctx.drawText(client.textRenderer, Text.literal(text), x, y, color, shadow);
            }
            case "rect" -> {
                int rw  = num(el,   "w",       50);
                int rh  = num(el,   "h",       20);
                int col = color(el, "color",   0xAA000000);
                ctx.fill(x, y, x + rw, y + rh, col);
                if (el.has("border")) {
                    int b = color(el, "border", 0xFF444444);
                    ctx.fill(x,          y,          x + rw, y + 1,      b);
                    ctx.fill(x,          y + rh - 1, x + rw, y + rh,     b);
                    ctx.fill(x,          y,          x + 1,  y + rh,     b);
                    ctx.fill(x + rw - 1, y,          x + rw, y + rh,     b);
                }
            }
            case "bar" -> {
                int   bw  = num(el,   "w",        100);
                int   bh  = num(el,   "h",        5);
                float val = flt(el,   "value",    0f);
                int   col = color(el, "color",    0xFF00FFAA);
                int   bg  = color(el, "bg_color", 0xFF222222);
                ctx.fill(x, y, x + bw, y + bh, bg);
                int filled = (int)(Math.max(0f, Math.min(1f, val)) * bw);
                if (filled > 0) ctx.fill(x, y, x + filled, y + bh, col);
            }
            case "divider" -> {
                int dw  = num(el,   "w",     100);
                int col = color(el, "color", 0xFF444444);
                ctx.fill(x, y, x + dw, y + 1, col);
            }
            case "item" -> {
                String itemId = str(el, "item_id", "");
                if (!itemId.isEmpty()) {
                    Identifier ident = Identifier.tryParse(itemId);
                    if (ident != null && Registries.ITEM.containsId(ident)) {
                        ctx.drawItem(new ItemStack(Registries.ITEM.get(ident)), x, y);
                    }
                }
            }
        }
    }

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
