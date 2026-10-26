package slooth.peripheral.ui;

import dev.pane.ui.*;
import slooth.peripheral.script.ScriptRunner;

import com.google.gson.JsonParser;
import java.awt.Font;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

import static slooth.peripheral.ui.PeripheralUi.*;

/**
 * Modal sheet that lists saved Build sessions and lets the player open one,
 * delete one, or create a new one.  Opened when the player presses "Open" in
 * the Build tab.
 */
public class BuildPickerScreen extends UiScreen {

    private static final float ROW = 34;

    // ── State ─────────────────────────────────────────────────────────────────
    private final Screen           parent;
    private final Consumer<String> onOpen;   // called when resuming an existing session
    private final Consumer<String> onNew;    // called when creating a brand-new session

    private final List<String> sessions       = new ArrayList<>(); // script relative paths
    private final Set<String>  sessionHasChat = new HashSet<>();   // scripts that have a saved chat
    private boolean loaded;

    private String deleteConfirmName = null; // non-null = dialog open

    // ── Constructor ───────────────────────────────────────────────────────────

    public BuildPickerScreen(Screen parent, Consumer<String> onOpen, Consumer<String> onNew) {
        this(new Ref<>(), parent, onOpen, onNew);
    }

    private BuildPickerScreen(Ref<BuildPickerScreen> ref, Screen parent, Consumer<String> onOpen, Consumer<String> onNew) {
        super(PeripheralUi.document("Open Script", ui -> ref.v.paint(ui)), NativeOverlayRenderer.NONE,
            (UiEventHandler) e -> ref.v.onEvent(e),
            UiScreenOptions.builder().backdrop(UiBackdrop.NONE).onKey((s, e) -> ref.v.onKey(e)).build());
        ref.v = this;
        this.parent = parent;
        this.onOpen = onOpen;
        this.onNew  = onNew;
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        super.init();
        // Scan once per opening; a resize keeps the list and the typed name.
        if (!loaded) { loadSessions(); loaded = true; }
    }

    private void loadSessions() {
        sessions.clear();
        sessionHasChat.clear();

        // Find which scripts have a saved chat file
        try {
            if (Files.exists(BuildSession.CHATS_DIR)) {
                try (var files = Files.list(BuildSession.CHATS_DIR)) {
                    files.filter(p -> p.toString().endsWith(".json"))
                        .forEach(p -> {
                            try {
                                var obj = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                                if (obj.has("script")) sessionHasChat.add(obj.get("script").getAsString());
                            } catch (Exception ignored) {}
                        });
                }
            }
        } catch (Exception ignored) {}

        // List every .py in the scripts directory (excluding mc.py library)
        try {
            if (Files.exists(ScriptRunner.SCRIPTS_DIR)) {
                try (var walk = Files.walk(ScriptRunner.SCRIPTS_DIR)) {
                    walk.filter(p -> p.toString().endsWith(".py"))
                        .filter(p -> !p.getFileName().toString().equals("mc.py"))
                        .map(p -> ScriptRunner.SCRIPTS_DIR.relativize(p).toString().replace('\\', '/'))
                        .sorted()
                        .forEach(sessions::add);
                }
            }
        } catch (Exception ignored) {}
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void pickNew() {
        String name = stringValue("new-name", "").trim();
        if (name.isEmpty()) name = "my_script.py";
        if (!name.endsWith(".py")) name += ".py";
        onNew.accept(name);
    }

    private void pickSession(int row) {
        if (row < 0 || row >= sessions.size()) return;
        onOpen.accept(sessions.get(row));
    }

    private void confirmDelete() {
        if (deleteConfirmName == null) return;
        BuildSession.deleteChatFor(deleteConfirmName);
        sessionHasChat.remove(deleteConfirmName);
        deleteConfirmName = null;
    }

    private void onEvent(UiEvent e) {
        if (e.type() == UiEventType.SUBMIT && e.key().equals("new-name")) { if (deleteConfirmName == null) pickNew(); return; }
        if (e.type() != UiEventType.ACTION) return;
        String a = e.key();
        if (deleteConfirmName != null) {
            // Only the dialog's own buttons act while it is open; anything else cancels.
            if (a.equals("delete-confirm")) confirmDelete();
            else if (!a.equals("noop")) deleteConfirmName = null;
            invalidate();
            return;
        }
        if (a.startsWith("open:")) { pickSession(Integer.parseInt(a.substring(5))); return; }
        if (a.startsWith("del:")) {
            int row = Integer.parseInt(a.substring(4));
            if (row >= 0 && row < sessions.size()) { deleteConfirmName = sessions.get(row); invalidate(); }
            return;
        }
        switch (a) {
            case "new" -> pickNew();
            case "cancel" -> minecraft.gui.setScreen(parent);
            default -> {}
        }
    }

    private boolean onKey(KeyEvent e) {
        if (deleteConfirmName != null && e.key() == GLFW.GLFW_KEY_ESCAPE) { deleteConfirmName = null; invalidate(); return true; }
        return false;
    }

    // ── Paint ─────────────────────────────────────────────────────────────────

    private void paint(UiContext ui) {
        var c = ui.canvas();
        float sw = ui.width(), sh = ui.height();
        c.fill(0x88000000, 0, 0, sw, sh);
        float w = Math.min(560, sw - 24), h = Math.min(520, sh - 24);
        float x = Math.round((sw - w) / 2), y = Math.round((sh - h) / 2);
        frame(c, x, y, w, h);
        float pad = w < 420 ? 14 : 20, ix = x + pad, iw = w - pad * 2, cy = y + pad;
        boolean shortWin = h < 340;

        float cancelW = btnW(c, "Cancel", G_CLOSE, 11);
        action(ui, "cancel", "Cancel", "cancel", G_CLOSE, x + w - pad - cancelW, cy, cancelW, 28, SECONDARY, TEXT, 11, true);
        c.text(c.ellipsize("Open a Build session", iw - cancelW - 12, 18, Font.BOLD, SANS), ix, cy + 2, 18, TEXT, Font.BOLD, SANS);
        cy += 32;
        if (!shortWin) {
            c.text(c.ellipsize("Pick a script to continue its chat, or name a new one.", iw, 11, Font.PLAIN, SANS), ix, cy, 11, MUTED, Font.PLAIN, SANS);
            cy += 22;
        }

        // Bottom strip: name field + New.
        float footY = y + h - pad - 30;
        float newW = btnW(c, "New", G_PLUS, 12);
        ui.textField("new-name", "new-name", "new_script.py", ix, footY, iw - newW - 8, 30, "", 64);
        action(ui, "new", "New", "new", G_PLUS, x + w - pad - newW, footY, newW, 30, PRIMARY, LIME_INK, 12, true);

        UiRect view = new UiRect(ix, cy, iw, Math.max(ROW, footY - 12 - cy));
        well(c, view.x(), view.y(), view.width(), view.height());
        if (sessions.isEmpty()) {
            centered(c, "No scripts found", "No scripts found in scripts folder. Name one below and press New.", view);
        } else {
            float contentH = sessions.size() * ROW + 8;
            ui.scroll("sessions", view, contentH, s -> {
                var sc = s.canvas();
                for (int i = 0; i < sessions.size(); i++) {
                    float ry = view.y() + 4 + i * ROW, rx = view.x() + 4, rw = view.width() - 12;
                    String name = sessions.get(i);
                    boolean hasChat = sessionHasChat.contains(name);
                    float delW = hasChat ? btnW(sc, "Delete chat", G_NONE, 11) : 0;
                    String id = "row:" + i;
                    if (s.hovered(id)) sc.roundRect(HOVER, rx, ry, rw, ROW - 2, 3);
                    if (s.focused(id)) sc.strokeRoundRect(LIME, 1, rx, ry, rw, ROW - 2, 3);
                    s.hit(id, "open:" + i, rx, ry, rw - (hasChat ? delW + 10 : 0), ROW - 2);
                    glyph(sc, G_FILE, hasChat ? LIME : FAINT, rx + 10, ry + (ROW - 2) / 2 - 6, 12);
                    float nameMax = rw - 30 - (hasChat ? delW + 90 : 10);
                    mid(sc, fitTail(sc, name, Math.max(30, nameMax), 11.5F, Font.PLAIN), rx + 30, ry + (ROW - 2) / 2, 11.5F, hasChat ? TEXT : MUTED, Font.PLAIN, MONO);
                    if (hasChat) {
                        float bx = rx + rw - delW - 4;
                        if (rw > 300) mid(sc, "chat saved", bx - 80, ry + (ROW - 2) / 2, 10.5F, FAINT, Font.PLAIN, SANS);
                        action(s, "del:" + i, "Delete chat", "del:" + i, G_NONE, bx, ry + 4, delW, ROW - 10, SECONDARY, TEXT, 11, true);
                    }
                }
            });
        }

        // ── Delete confirmation dialog (drawn on top of everything) ───────────
        if (deleteConfirmName != null) {
            UiRect m = modal(ui, 360, 176, "delete-cancel");
            float mx = m.x() + 16, my = m.y() + 16, mw = m.width() - 32;
            glyph(c, G_WARN, RED, mx, my + 3, 14);
            c.text("Delete chat history?", mx + 22, my, 15, TEXT, Font.BOLD, SANS);
            my += 28;
            c.text(fitTail(c, deleteConfirmName, mw, 11, Font.PLAIN), mx, my, 11, MUTED, Font.PLAIN, MONO);
            my += 20;
            c.text(c.ellipsize("The script file is kept. This cannot be undone.", mw, 11, Font.PLAIN, SANS), mx, my, 11, MUTED, Font.PLAIN, SANS);
            float by = m.bottom() - 16 - 30;
            float dw = btnW(c, "Delete", G_NONE, 12), kw = btnW(c, "Cancel", G_NONE, 11);
            action(ui, "delete-confirm", "Delete", "delete-confirm", G_NONE, m.right() - 16 - dw, by, dw, 30, DANGER, STOP_TEXT, 12, true);
            action(ui, "delete-cancel", "Cancel", "delete-cancel", G_NONE, m.right() - 16 - dw - 8 - kw, by, kw, 30, SECONDARY, TEXT, 11, true);
        }
    }

    @Override public boolean shouldCloseOnEsc() { return true; }
    @Override public boolean isPauseScreen()      { return false; }
}
