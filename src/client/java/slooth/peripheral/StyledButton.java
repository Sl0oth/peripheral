package slooth.peripheral;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.concurrent.ConcurrentHashMap;

/**
 * A styled replacement for ButtonWidget with smooth hover animation.
 *
 * <p>Hover progress (0–1) is stored in a static map keyed by a stable hash of
 * the button's label and position, so animation state survives screen rebuilds
 * caused by window resize or tab switches.
 */
public class StyledButton extends ButtonWidget {

    private static final ConcurrentHashMap<Integer, Float> HOVER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Long>  TIMES = new ConcurrentHashMap<>();

    /** Speed: fraction of the range covered per millisecond. 0.012 ≈ 83 ms to full. */
    private static final float SPEED = 0.012f;

    private final int key;

    protected StyledButton(int x, int y, int w, int h, Text label, PressAction action) {
        super(x, y, w, h, label, action, b -> Text.literal(b.getMessage().getString()));
        this.key = label.getString().hashCode() * 31 + x * 17 + y;
    }

    public static StyledButton of(String label, int x, int y, int w, int h, PressAction action) {
        return new StyledButton(x, y, w, h, Text.literal(label), action);
    }

    @Override
    protected void renderWidget(DrawContext ctx, int mx, int my, float delta) {
        // ── Advance hover animation ────────────────────────────────────────────
        long now    = System.currentTimeMillis();
        long last   = TIMES.getOrDefault(key, now);
        float dt    = Math.min((now - last) / 1000f, 0.1f); // seconds, capped
        TIMES.put(key, now);

        float prev   = HOVER.getOrDefault(key, 0f);
        float target = (isHovered() && active) ? 1f : 0f;
        float next   = prev + (target - prev > 0 ? 1 : -1) * Math.min(Math.abs(target - prev), SPEED * dt * 1000);
        if (Math.abs(target - next) < 0.01f) next = target;
        HOVER.put(key, next);

        // ── Draw ───────────────────────────────────────────────────────────────
        int bg     = isMousePressed()
                   ? UiStyle.BTN_BG_PRESS
                   : UiDraw.lerpColor(UiStyle.BTN_BG, UiStyle.BTN_BG_HOVER, next);
        int border = UiDraw.lerpColor(UiStyle.BTN_BORDER, UiStyle.BTN_BORDER_HV, next);

        int x = getX(), y = getY(), w = getWidth(), h = getHeight();

        ctx.fill(x, y, x + w, y + h, bg);
        // top highlight
        ctx.fill(x, y, x + w, y + 1, border);
        // bottom edge
        ctx.fill(x, y + h - 1, x + w, y + h, UiStyle.BTN_BORDER);
        // left/right hairlines
        ctx.fill(x, y, x + 1, y + h, UiStyle.BTN_BORDER);
        ctx.fill(x + w - 1, y, x + w, y + h, UiStyle.BTN_BORDER);

        // accent pixel line at top when hovered
        if (next > 0.05f) {
            int accentAlpha = (int)(0xFF * next);
            int accent = (accentAlpha << 24) | (UiStyle.ACCENT & 0x00FFFFFF);
            ctx.fill(x + 1, y, x + w - 1, y + 1, accent);
        }

        // label
        var tr = MinecraftClient.getInstance().textRenderer;
        int textColor = active ? UiStyle.TEXT : UiStyle.TEXT_DIM;
        ctx.drawCenteredTextWithShadow(tr, getMessage(), x + w / 2, y + (h - 8) / 2, textColor);
    }

    private boolean isMousePressed() {
        var mc = MinecraftClient.getInstance();
        if (mc.mouse == null) return false;
        return isHovered() && mc.mouse.wasLeftButtonClicked();
    }
}
