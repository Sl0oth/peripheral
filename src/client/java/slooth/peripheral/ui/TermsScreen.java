package slooth.peripheral.ui;

import dev.pane.ui.*;
import slooth.peripheral.util.PeripheralConfig;

import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;

import static slooth.peripheral.ui.PeripheralUi.*;

/**
 * Terms of Use acceptance screen — shown before the player can open
 * the Peripheral GUI if they have not yet accepted.
 *
 * <p>The Accept button is locked until either the player scrolls to the
 * bottom OR 15 seconds elapse.  "Not now" just closes the screen.
 * Cannot be dismissed with Esc.
 */
public class TermsScreen extends UiScreen {

    private static final float LINE_H = 17;

    // ── Terms text ────────────────────────────────────────────────────────────
    // Exact wording. "§f" marks a heading, "§7" a list item, "§a" the link line.
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

    private int     ticksRemaining = 300; // 15 s
    private boolean unlocked       = false;
    private int     shownSeconds   = -1;

    public TermsScreen(Screen nextScreen) {
        this(new Ref<>(), nextScreen);
    }

    private TermsScreen(Ref<TermsScreen> ref, Screen nextScreen) {
        super(PeripheralUi.document("Terms of Use", ui -> ref.v.paint(ui)), NativeOverlayRenderer.NONE,
            (UiEventHandler) e -> ref.v.onEvent(e), UiScreenOptions.builder().backdrop(UiBackdrop.NONE).build());
        ref.v = this;
        this.nextScreen = nextScreen;
    }

    // ── Tick ──────────────────────────────────────────────────────────────────
    @Override
    public void tick() {
        super.tick();
        if (unlocked) return;
        if (scrollSnapshot("terms").map(v -> v.offset() >= v.maximumOffset() - LINE_H).orElse(false)) unlocked = true;
        if (ticksRemaining > 0) ticksRemaining--;
        if (ticksRemaining <= 0) unlocked = true;
        int secs = (ticksRemaining + 19) / 20;
        if (unlocked || secs != shownSeconds) { shownSeconds = secs; invalidate(); }
    }

    private void onEvent(UiEvent e) {
        if (e.type() != UiEventType.ACTION) return;
        switch (e.key()) {
            case "decline" -> minecraft.gui.setScreen(null);
            case "accept" -> {
                if (!unlocked) return;
                PeripheralConfig.termsAccepted = true;
                PeripheralConfig.save();
                minecraft.gui.setScreen(nextScreen);
            }
            default -> {}
        }
    }

    // ── Paint ─────────────────────────────────────────────────────────────────
    private void paint(UiContext ui) {
        var c = ui.canvas();
        float sw = ui.width(), sh = ui.height();
        c.fill(0x88000000, 0, 0, sw, sh);
        float w = Math.min(560, sw - 24), h = Math.min(560, sh - 24);
        float x = Math.round((sw - w) / 2), y = Math.round((sh - h) / 2);
        frame(c, x, y, w, h);
        float pad = w < 420 ? 14 : 20, ix = x + pad, iw = w - pad * 2, cy = y + pad;
        boolean shortWin = h < 360;

        c.text("Terms of Use", ix, cy, 20, TEXT, Font.BOLD, SANS);
        cy += 28;
        if (!shortWin) {
            c.text(c.ellipsize("Please read these before using Peripheral.", iw, 11, Font.PLAIN, SANS), ix, cy, 11, MUTED, Font.PLAIN, SANS);
            cy += 22;
        }

        float footH = 34, footY = y + h - pad - footH;
        UiRect view = new UiRect(ix, cy, iw, Math.max(40, footY - 12 - cy));
        well(c, view.x(), view.y(), view.width(), view.height());

        // Lines keep their exact wording; long ones wrap only when the dialog is narrower than the text.
        List<Object[]> rows = new ArrayList<>();
        float textW = view.width() - 32;
        for (String raw : LINES) {
            int kind = raw.contains("§f") ? 1 : raw.contains("§7") ? 2 : raw.contains("§a") ? 3 : 0;
            String plain = strip(raw);
            int style = kind == 1 ? Font.BOLD : Font.PLAIN;
            float size = kind == 1 ? 12.5F : 11.5F;
            if (plain.isEmpty()) { rows.add(new Object[]{"", kind}); continue; }
            String lead = plain.startsWith("  ") ? "    " : "";
            for (String part : c.wrapText(plain.strip(), textW - (lead.isEmpty() ? 0 : 16), size, style, kind == 3 ? MONO : SANS))
                rows.add(new Object[]{lead + part, kind});
        }
        float contentH = rows.size() * LINE_H + 24;
        ui.scroll("terms", view, contentH, s -> {
            var sc = s.canvas();
            for (int i = 0; i < rows.size(); i++) {
                String line = (String) rows.get(i)[0];
                int kind = (int) rows.get(i)[1];
                if (line.isEmpty()) continue;
                float ly = view.y() + 12 + i * LINE_H;
                float lx = view.x() + 16 + (line.startsWith("    ") ? 16 : 0);
                String shown = line.strip();
                if (kind == 2) { sc.fill(FAINT, lx - 9, ly + 7, 3, 3); shown = shown.startsWith("- ") ? shown.substring(2) : shown; }
                switch (kind) {
                    case 1 -> sc.text(shown, lx, ly - 1, 12.5F, TEXT, Font.BOLD, SANS);
                    case 2 -> sc.text(shown, lx, ly, 11.5F, MUTED, Font.PLAIN, SANS);
                    case 3 -> sc.text(shown, lx, ly, 11, MUTED, Font.PLAIN, MONO);
                    default -> sc.text(shown, lx, ly, 11.5F, CODE, Font.PLAIN, SANS);
                }
            }
        });

        // Footer: lock hint at left, Not now / Accept at right.
        String acceptLabel = unlocked ? "Accept" : "Accept (" + Math.max(1, (ticksRemaining + 19) / 20) + "s)";
        float aw = btnW(c, acceptLabel, G_CHECK, 12), nw = btnW(c, "Not now", G_NONE, 11), by = footY + 2;
        action(ui, "accept", acceptLabel, "accept", G_CHECK, x + w - pad - aw, by, aw, 30, PRIMARY, LIME_INK, 12, unlocked);
        action(ui, "decline", "Not now", "decline", G_NONE, x + w - pad - aw - 8 - nw, by, nw, 30, SECONDARY, TEXT, 11, true);
        float room = w - pad * 2 - aw - nw - 24;
        String hint = unlocked ? "You can accept now." : "Scroll to the end, or wait, to enable Accept.";
        if (room > 80) mid(c, c.ellipsize(hint, room, 10.5F, Font.PLAIN, SANS), ix, by + 15, 10.5F, FAINT, Font.PLAIN, SANS);
    }

    // ── Screen flags ──────────────────────────────────────────────────────────
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public boolean isPauseScreen()      { return false; }
}
