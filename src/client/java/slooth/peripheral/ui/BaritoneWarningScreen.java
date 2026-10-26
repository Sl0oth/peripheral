package slooth.peripheral.ui;

import dev.pane.ui.*;
import slooth.peripheral.util.PeripheralConfig;

import java.awt.Font;
import net.minecraft.client.gui.screens.Screen;

import static slooth.peripheral.ui.PeripheralUi.*;

/**
 * Shown once when the GUI is opened and Baritone is not installed.
 * Dismissed forever by clicking "Never show again".
 */
public class BaritoneWarningScreen extends UiScreen {

    private final Screen parent;

    public BaritoneWarningScreen(Screen parent) {
        this(new Ref<>(), parent);
    }

    private BaritoneWarningScreen(Ref<BaritoneWarningScreen> ref, Screen parent) {
        super(PeripheralUi.document("Baritone Not Detected", ui -> ref.v.paint(ui)), NativeOverlayRenderer.NONE,
            (UiEventHandler) e -> ref.v.onEvent(e), UiScreenOptions.builder().backdrop(UiBackdrop.NONE).build());
        ref.v = this;
        this.parent = parent;
    }

    private void onEvent(UiEvent e) {
        if (e.type() != UiEventType.ACTION) return;
        switch (e.key()) {
            // OK — dismiss just this once
            case "ok" -> minecraft.gui.setScreen(parent);
            // Never show again — persist dismissal
            case "never" -> {
                PeripheralConfig.baritoneWarnDismissed = true;
                PeripheralConfig.save();
                minecraft.gui.setScreen(parent);
            }
            default -> {}
        }
    }

    private void paint(UiContext ui) {
        var c = ui.canvas();
        float sw = ui.width(), sh = ui.height();
        c.fill(0xA0000000, 0, 0, sw, sh);
        float w = Math.min(500, sw - 24), pad = w < 400 ? 14 : 20, iw = w - pad * 2;
        String fns = "mine_auto()  •  baritone_goto()  •  baritone()  •  baritone_stop()";
        var body = c.wrapText("Baritone is not installed. The following script functions will not work:", iw - 22, 12, Font.PLAIN, SANS);
        var fnLines = c.wrapText(fns, iw - 24, 11, Font.PLAIN, MONO);
        float needed = pad + 28 + body.size() * 17 + 10 + fnLines.size() * 16 + 20 + 18 + 2 * 18 + 22 + 30 + pad;
        float h = Math.min(needed, sh - 24);
        float x = Math.round((sw - w) / 2), y = Math.round((sh - h) / 2);
        frame(c, x, y, w, h);
        float ix = x + pad, cy = y + pad;

        glyph(c, G_WARN, AMBER, ix, cy + 4, 15);
        c.text(c.ellipsize("Baritone Not Detected", iw - 24, 18, Font.BOLD, SANS), ix + 24, cy, 18, TEXT, Font.BOLD, SANS);
        cy += 32;
        float footY = y + h - pad - 30;
        float bodyBottom = footY - 10;
        c.withClip(x, cy, w, Math.max(0, bodyBottom - cy), () -> {
            float ty = y + pad + 32;
            for (String line : body) { c.text(line, ix, ty, 12, CODE, Font.PLAIN, SANS); ty += 17; }
            ty += 8;
            float boxH = fnLines.size() * 16 + 12;
            well(c, ix, ty, iw, boxH);
            for (int i = 0; i < fnLines.size(); i++) c.text(fnLines.get(i), ix + 12, ty + 6 + i * 16, 11, AMBER, Font.PLAIN, MONO);
            ty += boxH + 14;
            c.text("Download Baritone:", ix, ty, 11.5F, MUTED, Font.PLAIN, SANS);
            ty += 20;
            c.text(fitTail(c, "modrinth.com/mod/baritone", iw, 11, Font.PLAIN), ix, ty, 11, TEXT, Font.PLAIN, MONO);
            ty += 18;
            c.text(fitTail(c, "github.com/cabaletta/baritone/releases", iw, 11, Font.PLAIN), ix, ty, 11, TEXT, Font.PLAIN, MONO);
        });

        float okW = btnW(c, "OK", G_NONE, 12), nW = btnW(c, "Never show again", G_NONE, 11);
        action(ui, "ok", "OK", "ok", G_NONE, x + w - pad - okW, footY, okW, 30, PRIMARY, LIME_INK, 12, true);
        action(ui, "never", "Never show again", "never", G_NONE, x + w - pad - okW - 8 - nW, footY, nW, 30, SECONDARY, TEXT, 11, true);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
