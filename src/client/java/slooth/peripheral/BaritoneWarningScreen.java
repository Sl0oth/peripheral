package slooth.peripheral;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shown once when the GUI is opened and Baritone is not installed.
 * Dismissed forever by clicking "Never show again".
 */
public class BaritoneWarningScreen extends Screen {

    private final Screen parent;

    // Layout constants
    private static final int BOX_W = 320;
    private static final int BOX_H = 180;

    public BaritoneWarningScreen(Screen parent) {
        super(Component.literal("Baritone Not Detected"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width  / 2;
        int by = this.height / 2 + BOX_H / 2;  // bottom of box

        // OK — dismiss just this once
        this.addRenderableWidget(Button.builder(
                Component.literal("OK"),
                btn -> minecraft.setScreen(parent))
            .bounds(cx - 110, by - 32, 100, 20)
            .build());

        // Never show again — persist dismissal
        this.addRenderableWidget(Button.builder(
                Component.literal("Never show again"),
                btn -> {
                    PeripheralConfig.baritoneWarnDismissed = true;
                    PeripheralConfig.save();
                    minecraft.setScreen(parent);
                })
            .bounds(cx + 10, by - 32, 100, 20)
            .build());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        // Skip the blur pass — applyBlur() can only fire once per frame
        // and the game world has already consumed it. Just draw a dark veil.
        ctx.fill(0, 0, this.width, this.height, 0xA0000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        this.extractBackground(ctx, mouseX, mouseY, delta);

        int cx = this.width  / 2;
        int cy = this.height / 2;
        int x0 = cx - BOX_W / 2;
        int y0 = cy - BOX_H / 2;
        int x1 = cx + BOX_W / 2;
        int y1 = cy + BOX_H / 2;

        // Box background
        ctx.fill(x0, y0, x1, y1, 0xE0101010);
        // Border: top, bottom, left, right (1px)
        ctx.fill(x0,     y0,     x1,     y0 + 1, 0xFFFF8800);
        ctx.fill(x0,     y1 - 1, x1,     y1,     0xFFFF8800);
        ctx.fill(x0,     y0,     x0 + 1, y1,     0xFFFF8800);
        ctx.fill(x1 - 1, y0,     x1,     y1,     0xFFFF8800);

        // Title
        ctx.centeredText(font,
            Component.literal("⚠  Baritone Not Detected"),
            cx, y0 + 12, 0xFFFF8800);

        // Body lines
        int lh = 10;
        int ty = y0 + 32;
        ctx.centeredText(font,
            Component.literal("Baritone is not installed. The following script"),
            cx, ty, 0xFFCCCCCC);
        ctx.centeredText(font,
            Component.literal("functions will not work:"),
            cx, ty + lh, 0xFFCCCCCC);

        ctx.centeredText(font,
            Component.literal("mine_auto()  •  baritone_goto()  •  baritone()  •  baritone_stop()"),
            cx, ty + lh * 3, 0xFFFFFF55);

        ctx.centeredText(font,
            Component.literal("Download Baritone:"),
            cx, ty + lh * 5, 0xFFCCCCCC);

        ctx.centeredText(font,
            Component.literal("modrinth.com/mod/baritone"),
            cx, ty + lh * 6 + 2, 0xFF55AAFF);

        ctx.centeredText(font,
            Component.literal("github.com/cabaletta/baritone/releases"),
            cx, ty + lh * 7 + 4, 0xFF55AAFF);

        super.extractRenderState(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
