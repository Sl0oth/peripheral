package slooth.peripheral;

import net.minecraft.client.gui.DrawContext;

/** Static rendering helpers for the Peripheral UI chrome. */
public final class UiDraw {

    private UiDraw() {}

    // ── Shadow ────────────────────────────────────────────────────────────────

    /**
     * Soft drop-shadow: two translucent offset rectangles rendered behind a panel.
     * Call this BEFORE filling the panel so the shadow is underneath.
     */
    public static void shadow(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x + 3, y + 4, x + w + 3, y + h + 4, UiStyle.SHADOW_DARK);
        ctx.fill(x + 5, y + 6, x + w + 5, y + h + 6, UiStyle.SHADOW_SOFT);
    }

    // ── Rounded rect (3 px radius, approximated with fill slices) ────────────

    /**
     * Solid filled rounded rectangle, 3 px corner radius.
     * Works for any size >= 8x8.
     */
    public static void roundedRect(DrawContext ctx, int x, int y, int w, int h, int color) {
        // top cap
        ctx.fill(x + 3, y,     x + w - 3, y + 1,     color);
        ctx.fill(x + 2, y + 1, x + w - 2, y + 2,     color);
        ctx.fill(x + 1, y + 2, x + w - 1, y + 3,     color);
        // middle
        ctx.fill(x,     y + 3, x + w,     y + h - 3, color);
        // bottom cap
        ctx.fill(x + 1, y + h - 3, x + w - 1, y + h - 2, color);
        ctx.fill(x + 2, y + h - 2, x + w - 2, y + h - 1, color);
        ctx.fill(x + 3, y + h - 1, x + w - 3, y + h,     color);
    }

    /**
     * 1-px border that follows the rounded rect outline.
     */
    public static void roundedBorder(DrawContext ctx, int x, int y, int w, int h, int color) {
        // top / bottom horizontals
        ctx.fill(x + 3, y,         x + w - 3, y + 1,         color);
        ctx.fill(x + 3, y + h - 1, x + w - 3, y + h,         color);
        // left / right verticals
        ctx.fill(x,         y + 3, x + 1,         y + h - 3, color);
        ctx.fill(x + w - 1, y + 3, x + w,         y + h - 3, color);
        // top-left corner pixels
        ctx.fill(x + 1, y + 1, x + 3, y + 2, color);
        ctx.fill(x + 1, y + 2, x + 2, y + 3, color);
        // top-right
        ctx.fill(x + w - 3, y + 1, x + w - 1, y + 2, color);
        ctx.fill(x + w - 2, y + 2, x + w - 1, y + 3, color);
        // bottom-left
        ctx.fill(x + 1, y + h - 2, x + 3, y + h - 1, color);
        ctx.fill(x + 1, y + h - 3, x + 2, y + h - 2, color);
        // bottom-right
        ctx.fill(x + w - 3, y + h - 2, x + w - 1, y + h - 1, color);
        ctx.fill(x + w - 2, y + h - 3, x + w - 1, y + h - 2, color);
    }

    // ── Composite helpers ─────────────────────────────────────────────────────

    /**
     * Full panel: shadow + rounded fill + border.
     */
    public static void panel(DrawContext ctx, int x, int y, int w, int h) {
        shadow(ctx, x, y, w, h);
        roundedRect(ctx, x, y, w, h, UiStyle.PANEL);
        roundedBorder(ctx, x, y, w, h, UiStyle.BORDER);
    }

    /**
     * Flat dialog panel: no shadow, flat fill, 1-px border on all four sides,
     * 2-px accent stripe along the top.
     */
    public static void dialog(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + h, UiStyle.PANEL);
        // border
        ctx.fill(x,         y,         x + w, y + 1,     UiStyle.BORDER);
        ctx.fill(x,         y + h - 1, x + w, y + h,     UiStyle.BORDER);
        ctx.fill(x,         y,         x + 1, y + h,     UiStyle.BORDER);
        ctx.fill(x + w - 1, y,         x + w, y + h,     UiStyle.BORDER);
        // accent stripe
        ctx.fill(x + 1, y + 1, x + w - 1, y + 3, UiStyle.ACCENT);
    }

    // ── Colour math ───────────────────────────────────────────────────────────

    /** Linear interpolation between two ARGB colours. t must be in [0, 1]. */
    public static int lerpColor(int a, int b, float t) {
        int aa = (a >>> 24) & 0xFF, ra = (a >> 16) & 0xFF, ga = (a >> 8) & 0xFF, ba2 = a & 0xFF;
        int ab = (b >>> 24) & 0xFF, rb = (b >> 16) & 0xFF, gb = (b >> 8) & 0xFF, bb2 = b & 0xFF;
        return ((int)(aa + (ab - aa) * t) << 24)
             | ((int)(ra + (rb - ra) * t) << 16)
             | ((int)(ga + (gb - ga) * t) <<  8)
             |  (int)(ba2 + (bb2 - ba2) * t);
    }
}
