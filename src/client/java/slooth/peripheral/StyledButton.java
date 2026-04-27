package slooth.peripheral;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

import java.util.concurrent.ConcurrentHashMap;

/**
 * A styled replacement for ButtonWidget with smooth hover animation.
 *
 * <p>Extends ClickableWidget directly for full rendering control (PressableWidget
 * makes renderWidget final in MC 1.21.11). Hover progress (0–1) is stored in a
 * static map keyed by a stable hash of the button's label and position, so
 * animation state survives screen rebuilds caused by window resize or tab switches.
 */
public class StyledButton extends ClickableWidget {

    private static final ConcurrentHashMap<Integer, Float> HOVER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Long>  TIMES = new ConcurrentHashMap<>();

    /** Speed: fraction of the range covered per millisecond. 0.012 ≈ 83 ms to full. */
    private static final float SPEED = 0.012f;

    private final int key;
    private final ButtonWidget.PressAction onPress;

    public StyledButton(int x, int y, int w, int h, Text label, ButtonWidget.PressAction onPress) {
        super(x, y, w, h, label);
        this.onPress = onPress;
        this.key = label.getString().hashCode() * 31 + x * 17 + y;
    }

    public static StyledButton of(String label, int x, int y, int w, int h, ButtonWidget.PressAction action) {
        return new StyledButton(x, y, w, h, Text.literal(label), action);
    }

    @Override
    public void onClick(Click click, boolean focused) {
        onPress.onPress(null);
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        appendDefaultNarrations(builder);
    }

    @Override
    protected void renderWidget(DrawContext ctx, int mx, int my, float delta) {
        // ── Advance hover animation ────────────────────────────────────────────
        long now  = System.currentTimeMillis();
        long last = TIMES.getOrDefault(key, now);
        float dt  = Math.min((now - last) / 1000f, 0.1f);
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
        ctx.fill(x, y, x + w, y + 1, border);
        ctx.fill(x, y + h - 1, x + w, y + h, UiStyle.BTN_BORDER);
        ctx.fill(x, y, x + 1, y + h, UiStyle.BTN_BORDER);
        ctx.fill(x + w - 1, y, x + w, y + h, UiStyle.BTN_BORDER);

        if (next > 0.05f) {
            int accentAlpha = (int)(0xFF * next);
            int accent = (accentAlpha << 24) | (UiStyle.ACCENT & 0x00FFFFFF);
            ctx.fill(x + 1, y, x + w - 1, y + 1, accent);
        }

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
