package slooth.peripheral;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Terms of Use acceptance screen — shown before the player can open
 * the Peripheral GUI if they have not yet accepted.
 *
 * <p>The Accept button is locked until either the player scrolls to the
 * bottom OR 15 seconds elapse.  "Not now" just closes the screen.
 * Cannot be dismissed with Esc.
 */
public class TermsScreen extends Screen {

    // ── Layout ────────────────────────────────────────────────────────────────
    private static final int W        = 360;
    private static final int H        = 220;
    private static final int TH       = 14;   // title bar height
    private static final int BH       = 28;   // bottom button strip height
    private static final int PAD      = 6;
    private static final int LINE_H   = 11;
    private static final int SB_W     = 3;    // scroll bar width

    // ── Colours (match PeripheralScreen palette) ──────────────────────────────
    private static final int C_BG     = 0xEE111111;
    private static final int C_TITLE  = 0xEE1A1A1A;
    private static final int C_BORDER = 0xFF2A2A2A;
    private static final int C_ORANGE = 0xFFFF8800;
    private static final int C_WHITE  = 0xFFDDDDDD;
    private static final int C_GREY   = 0xFF888888;
    private static final int C_GREEN  = 0xFF00FFAA;
    private static final int C_SCROLL = 0xFF444444;
    private static final int C_SCROLL_THUMB = 0xFF888888;

    // ── Terms text ────────────────────────────────────────────────────────────
    private static final String[] LINES = {
        "By using Peripheral you agree to the following.",
        "",
        "§fProvided As-Is",
        "No warranty of any kind. The author makes no",
        "guarantee the mod will work or be bug-free.",
        "",
        "§fNo Liability",
        "The author (Sl0oth) is NOT liable for:",
        "  §7- Loss of items, progress, worlds, or data",
        "  §7- Corruption of save files or installations",
        "  §7- Bans or penalties from server operators",
        "  §7- Damage from scripts you write or run",
        "  §7- Damage from third-party scripts",
        "  §7- Harm from connecting to external services",
        "  §7- Any indirect or consequential damages",
        "",
        "§fScripts & User Content",
        "You are solely responsible for any scripts you",
        "write, download, or run — including AI-generated",
        "scripts and examples included with the mod.",
        "",
        "§fServer Rules",
        "Ensure your use complies with the rules of any",
        "server you play on. The author is not responsible",
        "for penalties imposed by server operators.",
        "",
        "§fExternal Services",
        "You are responsible for any costs or ToS issues",
        "arising from use of external APIs or services.",
        "",
        "§fChanges",
        "These terms may be updated at any time. Continued",
        "use of the mod means you accept the current terms.",
        "",
        "§aFull terms: github.com/Sl0oth/peripheral",
    };

    // ── State ─────────────────────────────────────────────────────────────────
    private final Screen nextScreen;

    private int px, py;            // top-left of box, set in init
    private int scrollY0, scrollY1, scrollRegionH;

    private double scrollPx       = 0;
    private int    totalContentH;
    private int    ticksRemaining = 300; // 15 s
    private boolean unlocked      = false;

    private ButtonWidget acceptBtn;

    public TermsScreen(Screen nextScreen) {
        super(Text.literal("Terms of Use"));
        this.nextScreen = nextScreen;
    }

    // ── Init ──────────────────────────────────────────────────────────────────
    @Override
    protected void init() {
        px = (this.width  - W) / 2;
        py = (this.height - H) / 2;

        totalContentH = LINES.length * LINE_H + PAD;

        scrollY0 = py + TH + PAD;
        scrollY1 = py + H - BH - PAD;
        scrollRegionH = scrollY1 - scrollY0;

        int btnY = py + H - BH + 4;
        int cx   = px + W / 2;

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Not now"),
                btn -> client.setScreen(null))
            .dimensions(cx - 106, btnY, 100, 20)
            .build());

        acceptBtn = ButtonWidget.builder(
                Text.literal("Accept (15s)"),
                btn -> {
                    PeripheralConfig.termsAccepted = true;
                    PeripheralConfig.save();
                    client.setScreen(nextScreen);
                })
            .dimensions(cx + 6, btnY, 100, 20)
            .build();
        acceptBtn.active = false;
        this.addDrawableChild(acceptBtn);
    }

    // ── Tick ──────────────────────────────────────────────────────────────────
    @Override
    public void tick() {
        if (unlocked) return;

        int maxScroll = Math.max(0, totalContentH - scrollRegionH);
        if ((int) scrollPx >= maxScroll - LINE_H) unlocked = true;
        if (ticksRemaining > 0) ticksRemaining--;
        if (ticksRemaining <= 0) unlocked = true;

        if (unlocked) {
            acceptBtn.active = true;
            acceptBtn.setMessage(Text.literal("Accept"));
        } else {
            int secs = (ticksRemaining + 19) / 20;
            acceptBtn.setMessage(Text.literal("Accept (" + secs + "s)"));
        }
    }

    // ── Input ─────────────────────────────────────────────────────────────────
    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        int maxScroll = Math.max(0, totalContentH - scrollRegionH);
        scrollPx = Math.max(0, Math.min(maxScroll, scrollPx - vy * 14));
        return true;
    }

    // ── Render ────────────────────────────────────────────────────────────────
    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) {
        // Darken game world behind the panel
        ctx.fill(0, 0, this.width, this.height, 0x88000000);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx, mx, my, delta);

        int x0 = px, y0 = py, x1 = px + W, y1 = py + H;

        // ── Panel background ──────────────────────────────────────────────────
        ctx.fill(x0, y0, x1, y1, C_BG);

        // ── Title bar ─────────────────────────────────────────────────────────
        ctx.fill(x0, y0, x1, y0 + TH, C_TITLE);
        ctx.fill(x0, y0 + TH, x1, y0 + TH + 1, C_BORDER);
        ctx.drawCenteredTextWithShadow(textRenderer,
            Text.literal("Peripheral — Terms of Use"),
            x0 + W / 2, y0 + 3, C_ORANGE);

        // ── Border ────────────────────────────────────────────────────────────
        ctx.fill(x0,     y0,     x1,     y0 + 1, C_BORDER);
        ctx.fill(x0,     y1 - 1, x1,     y1,     C_BORDER);
        ctx.fill(x0,     y0,     x0 + 1, y1,     C_BORDER);
        ctx.fill(x1 - 1, y0,     x1,     y1,     C_BORDER);

        // ── Bottom strip separator ─────────────────────────────────────────────
        ctx.fill(x0, y1 - BH, x1, y1 - BH + 1, C_BORDER);

        // ── Scroll area ───────────────────────────────────────────────────────
        int textX  = x0 + PAD;
        int textX1 = x1 - PAD - SB_W - 2;

        ctx.enableScissor(textX, scrollY0, textX1, scrollY1);
        int ty = scrollY0 + PAD - (int) scrollPx;
        for (String line : LINES) {
            if (ty + LINE_H > scrollY0 && ty < scrollY1) {
                ctx.drawTextWithShadow(textRenderer, Text.literal(line),
                    textX, ty, C_WHITE);
            }
            ty += LINE_H;
        }
        ctx.disableScissor();

        // ── Scroll bar ────────────────────────────────────────────────────────
        int sbX = x1 - PAD - SB_W;
        ctx.fill(sbX, scrollY0, sbX + SB_W, scrollY1, C_SCROLL);
        int maxScroll = Math.max(1, totalContentH - scrollRegionH);
        float frac  = (float) Math.min(1.0, scrollPx / maxScroll);
        int   barH  = Math.max(14, scrollRegionH * scrollRegionH
                                   / Math.max(scrollRegionH, totalContentH));
        int   barY  = scrollY0 + (int) ((scrollRegionH - barH) * frac);
        ctx.fill(sbX, barY, sbX + SB_W, barY + barH, C_SCROLL_THUMB);

        super.render(ctx, mx, my, delta);
    }

    // ── Screen flags ──────────────────────────────────────────────────────────
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public boolean shouldPause()      { return false; }
}
