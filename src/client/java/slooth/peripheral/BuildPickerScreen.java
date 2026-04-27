package slooth.peripheral;

import com.google.gson.JsonParser;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Modal overlay that lists saved Build sessions and lets the player open one,
 * delete one, or create a new one.  Opened when the player presses "Open" in
 * the Build tab.
 */
public class BuildPickerScreen extends Screen {

    // ── Layout ────────────────────────────────────────────────────────────────
    private static final int W   = 280;
    private static final int H   = 216;
    private static final int TH  = 14;   // title bar
    private static final int BH  = 30;   // bottom strip
    private static final int PAD = 5;
    private static final int ROW = 15;   // row height in list

    // Delete dialog
    private static final int DLG_W = 190;
    private static final int DLG_H = 62;

    // ── Colours (match PeripheralScreen palette) ──────────────────────────────
    private static final int C_BG          = 0xEE111111;
    private static final int C_PANEL       = 0xEE111111;
    private static final int C_TITLE       = 0xEE1A1A1A;
    private static final int C_BORDER      = 0xFF2A2A2A;
    private static final int C_ORANGE      = 0xFFFF8800;
    private static final int C_WHITE       = 0xFFDDDDDD;
    private static final int C_GREY        = 0xFF888888;
    private static final int C_DIM         = 0xFF555555;
    private static final int C_HOVER       = 0xFF252525;
    private static final int C_SCROLL      = 0xFF2A2A2A;
    private static final int C_SCROLL_THUMB= 0xFF555555;

    // Del button geometry (drawn inside each row)
    private static final int DEL_W = 22;
    private static final int DEL_H = 11;

    // ── State ─────────────────────────────────────────────────────────────────
    private final Screen           parent;
    private final Consumer<String> onOpen;   // called when resuming an existing session
    private final Consumer<String> onNew;    // called when creating a brand-new session

    private int    px, py, listY0, listY1, listH;
    private double scrollPx        = 0;
    private int    hoveredRow      = -1;
    private int    hoveredDelRow   = -1;  // row whose Del button is hovered

    private List<String> sessions = new ArrayList<>(); // script relative paths

    private String deleteConfirmName = null; // non-null = dialog open

    private TextFieldWidget nameField;

    // ── Constructor ───────────────────────────────────────────────────────────

    public BuildPickerScreen(Screen parent, Consumer<String> onOpen, Consumer<String> onNew) {
        super(Text.literal("Build Sessions"));
        this.parent = parent;
        this.onOpen = onOpen;
        this.onNew  = onNew;
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        px = (width  - W) / 2;
        py = (height - H) / 2;

        listY0 = py + TH + PAD;
        listY1 = py + H - BH - PAD;
        listH  = listY1 - listY0;

        loadSessions();

        int by = py + H - BH + 7;

        // Name field — takes most of the strip
        nameField = new TextFieldWidget(textRenderer,
            px + PAD, by, W - PAD * 2 - 82, 14, Text.empty());
        nameField.setMaxLength(64);
        nameField.setPlaceholder(Text.literal("new_script.py"));
        nameField.setFocusUnlocked(true);
        addDrawableChild(nameField);

        // New button
        addDrawableChild(ButtonWidget.builder(Text.literal("New"), btn -> pickNew())
            .dimensions(px + W - PAD - 76, by - 1, 36, 16).build());

        // Cancel button
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"),
                btn -> client.setScreen(parent))
            .dimensions(px + W - PAD - 37, by - 1, 37, 16).build());
    }

    private void loadSessions() {
        sessions.clear();
        try {
            if (!Files.exists(BuildSession.CHATS_DIR)) return;
            sessions = Files.list(BuildSession.CHATS_DIR)
                .filter(p -> p.toString().endsWith(".json"))
                .map(p -> {
                    try {
                        var obj = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                        if (obj.has("script")) return obj.get("script").getAsString();
                    } catch (Exception ignored) {}
                    String n = p.getFileName().toString();
                    return n.endsWith(".json") ? n.substring(0, n.length() - 5) : n;
                })
                .sorted()
                .collect(Collectors.toList());
        } catch (Exception ignored) {}
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void pickNew() {
        String name = nameField.getText().trim();
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
        sessions.remove(deleteConfirmName);
        deleteConfirmName = null;
        // Clamp scroll
        int maxScroll = Math.max(0, sessions.size() * ROW - listH);
        if (scrollPx > maxScroll) scrollPx = maxScroll;
    }

    // ── Del button hit-testing ────────────────────────────────────────────────

    /** Returns the screen-x of a row's Del button. */
    private int delBtnX() {
        return px + W - PAD - 3 - DEL_W;
    }

    /** Returns true if (mx, my) is inside the Del button for the given row. */
    private boolean inDelBtn(int row, double mx, double my) {
        int ry  = listY0 + row * ROW - (int) scrollPx;
        int bx  = delBtnX();
        int by  = ry + (ROW - DEL_H) / 2;
        return mx >= bx && mx < bx + DEL_W && my >= by && my < by + DEL_H
            && ry >= listY0 && ry + ROW <= listY1 + ROW; // don't hit buttons outside list
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        if (deleteConfirmName != null) return true;
        int maxScroll = Math.max(0, sessions.size() * ROW - listH);
        scrollPx = Math.max(0, Math.min(maxScroll, scrollPx - vy * ROW));
        return true;
    }

    @Override
    public boolean mouseClicked(Click click, boolean focused) {
        double mx = click.x(), my = click.y();
        int button = click.button();

        // ── Delete confirmation dialog ────────────────────────────────────────
        if (deleteConfirmName != null) {
            int dlgX = px + (W - DLG_W) / 2;
            int dlgY = py + (H - DLG_H) / 2;
            int cfmX = dlgX + 8,          cfmY = dlgY + DLG_H - 18, cfmW = 78, cfmH = 14;
            int cclX = dlgX + DLG_W - 80, cclY = cfmY,              cclW = 72, cclH = 14;

            if (button == 0) {
                if (mx >= cfmX && mx < cfmX + cfmW && my >= cfmY && my < cfmY + cfmH) {
                    confirmDelete();
                } else {
                    // Cancel / click outside
                    deleteConfirmName = null;
                }
            }
            return true;
        }

        // ── Del button on a row ───────────────────────────────────────────────
        if (button == 0) {
            for (int i = 0; i < sessions.size(); i++) {
                if (inDelBtn(i, mx, my)) {
                    deleteConfirmName = sessions.get(i);
                    return true;
                }
            }
            // Row click (not on Del button)
            int row = rowAt((int) my);
            if (row >= 0) { pickSession(row); return true; }
        }

        return super.mouseClicked(click, focused);
    }

    private int rowAt(int my) {
        if (my < listY0 || my >= listY1) return -1;
        int idx = ((int) (my - listY0 + scrollPx)) / ROW;
        return (idx >= 0 && idx < sessions.size()) ? idx : -1;
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) {
        ctx.fill(0, 0, width, height, 0x88000000);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx, mx, my, delta);

        // Update hover state (suppress when dialog open)
        hoveredRow    = (deleteConfirmName == null) ? rowAt(my) : -1;
        hoveredDelRow = -1;
        if (deleteConfirmName == null) {
            for (int i = 0; i < sessions.size(); i++) {
                if (inDelBtn(i, mx, my)) { hoveredDelRow = i; break; }
            }
        }

        int x0 = px, y0 = py, x1 = px + W, y1 = py + H;

        // Panel background
        ctx.fill(x0, y0, x1, y1, C_BG);

        // Title bar
        ctx.fill(x0, y0, x1, y0 + TH, C_TITLE);
        ctx.fill(x0, y0 + TH, x1, y0 + TH + 1, C_BORDER);
        ctx.drawCenteredTextWithShadow(textRenderer,
            Text.literal("Build Sessions"), x0 + W / 2, y0 + 3, C_ORANGE);

        // Outer border
        ctx.fill(x0,     y0,     x1,     y0 + 1, C_BORDER);
        ctx.fill(x0,     y1 - 1, x1,     y1,     C_BORDER);
        ctx.fill(x0,     y0,     x0 + 1, y1,     C_BORDER);
        ctx.fill(x1 - 1, y0,     x1,     y1,     C_BORDER);

        // Bottom strip separator
        ctx.fill(x0, y1 - BH, x1, y1 - BH + 1, C_BORDER);

        // ── Session list ──────────────────────────────────────────────────────
        int sbW     = 3;
        int innerX0 = x0 + PAD;
        int innerX1 = x1 - PAD - sbW - 2;

        ctx.enableScissor(innerX0, listY0, innerX1, listY1);

        if (sessions.isEmpty()) {
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("No saved sessions — create one below."),
                innerX0, listY0 + 5, C_GREY);
        } else {
            int dbx = delBtnX();
            for (int i = 0; i < sessions.size(); i++) {
                int ry = listY0 + i * ROW - (int) scrollPx;
                if (ry + ROW <= listY0 || ry >= listY1) continue;

                // Row hover highlight (only if Del not hovered)
                if (i == hoveredRow && i != hoveredDelRow)
                    ctx.fill(innerX0, ry, innerX1, ry + ROW - 1, C_HOVER);

                // Script name (leave room for Del button)
                int nameMaxX = dbx - 4;
                String name = sessions.get(i);
                if (textRenderer.getWidth(name) > nameMaxX - innerX0 - 3)
                    name = textRenderer.trimToWidth(name, nameMaxX - innerX0 - 6) + "…";
                ctx.drawTextWithShadow(textRenderer, Text.literal(name),
                    innerX0 + 3, ry + 4,
                    i == hoveredRow && i != hoveredDelRow ? C_WHITE : C_GREY);

                // Del button
                int dby    = ry + (ROW - DEL_H) / 2;
                boolean dh = (i == hoveredDelRow);
                ctx.fill(dbx, dby, dbx + DEL_W, dby + DEL_H, dh ? 0xFFAA1111 : 0xFF661111);
                ctx.fill(dbx, dby, dbx + DEL_W, dby + 1, dh ? 0xFFCC3333 : 0xFF882222);
                ctx.drawText(textRenderer, Text.literal("Del"),
                    dbx + (DEL_W - textRenderer.getWidth("Del")) / 2, dby + 2,
                    C_WHITE, false);
            }
        }
        ctx.disableScissor();

        // Scroll bar
        int totalH = sessions.size() * ROW;
        if (totalH > listH) {
            int sbX = x1 - PAD - sbW;
            ctx.fill(sbX, listY0, sbX + sbW, listY1, C_SCROLL);
            int   maxScroll = Math.max(1, totalH - listH);
            float frac      = (float) Math.min(1.0, scrollPx / maxScroll);
            int   barH      = Math.max(12, listH * listH / Math.max(listH, totalH));
            int   barY      = listY0 + (int) ((listH - barH) * frac);
            ctx.fill(sbX, barY, sbX + sbW, barY + barH, C_SCROLL_THUMB);
        }

        super.render(ctx, mx, my, delta);

        // ── Delete confirmation dialog (drawn on top of everything) ───────────
        if (deleteConfirmName != null) renderDeleteConfirm(ctx, mx, my);
    }

    private void renderDeleteConfirm(DrawContext ctx, int mx, int my) {
        int dlgX = px + (W - DLG_W) / 2;
        int dlgY = py + (H - DLG_H) / 2;

        // Dim the panel behind the dialog
        ctx.fill(px, py, px + W, py + H, 0x88000000);

        // Dialog box
        ctx.fill(dlgX, dlgY, dlgX + DLG_W, dlgY + DLG_H, C_PANEL);
        ctx.fill(dlgX,              dlgY,            dlgX + DLG_W, dlgY + 1,        C_BORDER);
        ctx.fill(dlgX,              dlgY + DLG_H - 1, dlgX + DLG_W, dlgY + DLG_H,  C_BORDER);
        ctx.fill(dlgX,              dlgY,            dlgX + 1,      dlgY + DLG_H,   C_BORDER);
        ctx.fill(dlgX + DLG_W - 1, dlgY,            dlgX + DLG_W,  dlgY + DLG_H,   C_BORDER);
        // Red accent bar
        ctx.fill(dlgX + 1, dlgY + 1, dlgX + DLG_W - 1, dlgY + 3, 0xFFCC2222);

        ctx.drawText(textRenderer, Text.literal("§cDelete chat session?"),
            dlgX + 8, dlgY + 7, C_WHITE, false);

        String display = deleteConfirmName;
        if (textRenderer.getWidth(display) > DLG_W - 16)
            display = textRenderer.trimToWidth(display, DLG_W - 24) + "…";
        ctx.drawText(textRenderer, Text.literal("§7" + display), dlgX + 8, dlgY + 18, C_DIM, false);
        ctx.drawText(textRenderer, Text.literal("§7This cannot be undone."), dlgX + 8, dlgY + 28, C_DIM, false);

        // Confirm button (red)
        int cfmX = dlgX + 8, cfmY = dlgY + DLG_H - 18, cfmW = 78, cfmH = 14;
        boolean cfmHover = mx >= cfmX && mx < cfmX + cfmW && my >= cfmY && my < cfmY + cfmH;
        ctx.fill(cfmX, cfmY, cfmX + cfmW, cfmY + cfmH, cfmHover ? 0xFFAA1111 : 0xFF881111);
        ctx.fill(cfmX, cfmY, cfmX + cfmW, cfmY + 1, 0xFFCC3333);
        ctx.drawText(textRenderer, Text.literal("Confirm"),
            cfmX + (cfmW - textRenderer.getWidth("Confirm")) / 2, cfmY + 3, C_WHITE, false);

        // Cancel button (grey)
        int cclX = dlgX + DLG_W - 80, cclY = cfmY, cclW = 72, cclH = 14;
        boolean cclHover = mx >= cclX && mx < cclX + cclW && my >= cclY && my < cclY + cclH;
        ctx.fill(cclX, cclY, cclX + cclW, cclY + cclH, cclHover ? 0xFF444444 : 0xFF2A2A2A);
        ctx.fill(cclX, cclY, cclX + cclW, cclY + 1, 0xFF666666);
        ctx.drawText(textRenderer, Text.literal("Cancel"),
            cclX + (cclW - textRenderer.getWidth("Cancel")) / 2, cclY + 3, C_DIM, false);
    }

    @Override public boolean shouldCloseOnEsc() { return true; }
    @Override public boolean shouldPause()      { return false; }
}
