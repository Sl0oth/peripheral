package slooth.peripheral;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Peripheral control panel — press <b>G</b> in-game to open.
 *
 * <p>A thin shell that manages the tab bar and delegates all rendering,
 * input, and widget lifecycle to per-tab classes:
 * {@link LogTab}, {@link ScriptsTab}, {@link SettingsTab},
 * {@link StoreTab}, {@link BuildTab}.
 */
public class PeripheralScreen extends Screen {

    // ── Layout constants (package-private so tab classes can read them) ───────
    static final int W  = 404;
    static final int H  = 252;
    static final int TH = 14; // title bar height
    static final int TB = 13; // tab bar height
    static final int BH = 26; // bottom strip height

    // Scripts tab layout
    static final int BREAD_H  = 11;
    static final int SEARCH_H = 14;

    // Build tab layout
    static final int BUILD_DIV_X = 192;
    static final int BUILD_TOP_H = 16;
    static final int BUILD_HDR_H = 13;

    // Colours (package-private)
    static final int C_PANEL       = 0xEE111111;
    static final int C_TITLE       = 0xEE1A1A1A;
    static final int C_TABBAR      = 0xEE0F0F0F;
    static final int C_TABACT      = 0xDD252525;
    static final int C_CONTENT     = 0xCC0C0C0C;
    static final int C_BORDER      = 0xFF2A2A2A;
    static final int C_ORANGE      = 0xFFFF8800;
    static final int C_GREEN       = 0xFF00FFAA;
    static final int C_YELLOW      = 0xFFFFAA00;
    static final int C_CYAN        = 0xFF00CCFF;
    static final int C_WHITE       = 0xFFDDDDDD;
    static final int C_GREY        = 0xFF888888;
    static final int C_DIM         = 0xFF555555;
    static final int C_RED         = 0xFFFF4444;
    static final int C_SCROLL      = 0xFF2A2A2A;
    static final int C_SCROLL_THUMB= 0xFF555555;

    static final int TAB_LOG = 0, TAB_SCRIPTS = 1, TAB_SETTINGS = 2, TAB_STORE = 3, TAB_BUILD = 4;

    // File-access dialog dimensions
    private static final int FA_DLG_W = W - 20;
    private static final int FA_DLG_H = 100;

    // ── State ─────────────────────────────────────────────────────────────────
    private int currentTab = TAB_LOG;
    private int px, py, contentY, contentH;

    // Persists across screen open/close
    private static int s_currentTab = TAB_LOG;

    // File-access warning dialog (can surface on any tab)
    private String fileAccessConfirmPath = null;

    // ── Tab instances ─────────────────────────────────────────────────────────
    final LogTab      logTab;
    final ScriptsTab  scriptsTab;
    final SettingsTab settingsTab;
    final StoreTab    storeTab;
    final BuildTab    buildTab;

    // ── Widget tracking ───────────────────────────────────────────────────────
    private final List<ButtonWidget>    tabBtns    = new ArrayList<>();
    private final List<Object>          contentWgt = new ArrayList<>();

    public PeripheralScreen() {
        super(Text.literal("Peripheral"));
        logTab      = new LogTab(this);
        scriptsTab  = new ScriptsTab(this);
        settingsTab = new SettingsTab(this);
        storeTab    = new StoreTab(this);
        buildTab    = new BuildTab(this);
    }

    // ── Screen lifecycle ──────────────────────────────────────────────────────

    @Override
    protected void init() {
        px       = (width  - W) / 2;
        py       = (height - H) / 2;
        contentY = py + TH + TB;
        contentH = H  - TH - TB - BH;

        currentTab = s_currentTab;
        buildTabs();
        buildContent();
        scriptsTab.refreshScripts();
    }

    private void buildTabs() {
        tabBtns.forEach(this::remove);
        tabBtns.clear();
        String[] labels = {"Log", "Scripts", "Settings", "Store", "Build"};
        int[]    widths = {40, 60, 65, 48, 44};
        int x = px + 2, y = py + TH;
        for (int i = 0; i < 5; i++) {
            final int tab = i;
            ButtonWidget b = ButtonWidget.builder(Text.literal(labels[i]), btn -> switchTab(tab))
                .dimensions(x, y, widths[i], TB).build();
            tabBtns.add(b);
            addDrawableChild(b);
            x += widths[i] + 2;
        }
    }

    private void buildContent() {
        scriptsTab.cancelRename();
        contentWgt.forEach(w -> {
            if (w instanceof ButtonWidget b)    remove(b);
            if (w instanceof TextFieldWidget t) remove(t);
        });
        contentWgt.clear();

        switch (currentTab) {
            case TAB_LOG      -> logTab.build(px, py, contentY, contentH);
            case TAB_SCRIPTS  -> scriptsTab.build(px, py, contentY, contentH);
            case TAB_SETTINGS -> settingsTab.build(px, py, contentY, contentH);
            case TAB_STORE    -> storeTab.build(px, py, contentY, contentH);
            case TAB_BUILD    -> buildTab.build(px, py, contentY, contentH);
        }
    }

    private void switchTab(int tab) {
        currentTab = tab;
        s_currentTab = tab;
        logTab.reset();
        scriptsTab.resetScroll();
        scriptsTab.resetDialogs();
        fileAccessConfirmPath = null;
        buildContent();
        if (tab == TAB_SCRIPTS) scriptsTab.refreshScripts();
        if (tab == TAB_STORE && storeTab.storeScripts == null) storeTab.fetchStoreScripts();
        if (tab == TAB_BUILD)  buildTab.resetScrolls();
    }

    // ── Widget management (used by tab classes) ───────────────────────────────

    void widgetAdd(ButtonWidget b)    { addDrawableChild(b); contentWgt.add(b); }
    void widgetAdd(TextFieldWidget f) { addDrawableChild(f); contentWgt.add(f); }

    void widgetRemove(ButtonWidget b) {
        remove(b);
        contentWgt.remove(b);
    }
    void widgetRemove(TextFieldWidget f) {
        remove(f);
        contentWgt.remove(f);
    }

    void focusOn(TextFieldWidget f) { setFocused(f); }

    /** Rebuild the current tab's content (used after dialog close, detail-view switch, etc.). */
    void rebuild() { buildContent(); }

    /** Factory shortcut — no instance state needed, so it can be static. */
    static ButtonWidget btn(String label, int x, int y, int w, int h, ButtonWidget.PressAction action) {
        return ButtonWidget.builder(Text.literal(label), action).dimensions(x, y, w, h).build();
    }

    /** Expose textRenderer to tab classes. */
    net.minecraft.client.font.TextRenderer tr() { return textRenderer; }

    /** Expose MinecraftClient to tab classes. */
    net.minecraft.client.MinecraftClient mc() { return client; }

    // ── Tick ─────────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        super.tick();

        // Pick up file-access requests from running scripts (any tab)
        String far = ScriptRunner.fileAccessRequestScript;
        if (far != null && fileAccessConfirmPath == null) {
            ScriptRunner.fileAccessRequestScript = null;
            fileAccessConfirmPath = far;
        }

        switch (currentTab) {
            case TAB_LOG      -> logTab.tick();
            case TAB_SCRIPTS  -> scriptsTab.tick();
            case TAB_SETTINGS -> settingsTab.tick();
            case TAB_BUILD    -> buildTab.tick();
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Suppress the blur pass — we draw our own veil in render().
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        // ── Chrome ────────────────────────────────────────────────────────────
        ctx.fill(0, 0, width, height, 0x88000000);
        ctx.fill(px, py, px + W, py + H, C_PANEL);
        ctx.fill(px, py, px + W, py + TH, C_TITLE);
        ctx.drawText(textRenderer, Text.literal("■"), px + 4, py + 3, C_ORANGE, false);
        ctx.drawText(textRenderer, Text.literal("PERIPHERAL"), px + 4 + textRenderer.getWidth("■ "), py + 3, C_WHITE, false);
        String st = PeripheralStateTracker.status.toUpperCase();
        if (PeripheralStateTracker.iterations > 0) st += " #" + PeripheralStateTracker.iterations;
        ctx.drawText(textRenderer, Text.literal(st), px + 4 + textRenderer.getWidth("■ PERIPHERAL  "), py + 3, C_YELLOW, false);

        ctx.fill(px, py + TH, px + W, py + TH + TB, C_TABBAR);
        int[] tabX = {px + 2, px + 44, px + 106, px + 173, px + 223};
        int[] tabW = {40, 60, 65, 48, 44};
        ctx.fill(tabX[currentTab], py + TH, tabX[currentTab] + tabW[currentTab], py + TH + TB, C_TABACT);
        ctx.fill(tabX[currentTab], py + TH + TB - 2, tabX[currentTab] + tabW[currentTab], py + TH + TB, C_ORANGE);

        ctx.fill(px + 1, contentY, px + W - 1, contentY + contentH, C_CONTENT);
        ctx.fill(px, contentY + contentH, px + W, contentY + contentH + 1, C_BORDER);

        // Flush pending script-button rebuild once per frame
        if (currentTab == TAB_SCRIPTS) scriptsTab.flushDirty();

        // ── Tab content ───────────────────────────────────────────────────────
        switch (currentTab) {
            case TAB_LOG      -> logTab.render(ctx, mx, my);
            case TAB_SCRIPTS  -> scriptsTab.render(ctx);
            case TAB_SETTINGS -> settingsTab.render(ctx);
            case TAB_STORE    -> storeTab.render(ctx, mx, my);
            case TAB_BUILD    -> buildTab.render(ctx, mx, my);
        }

        // ── Bottom status strip ───────────────────────────────────────────────
        if (currentTab == TAB_SCRIPTS) {
            long total   = scriptsTab.scripts.stream().filter(i -> !i.isFolder()).count();
            long running = scriptsTab.scripts.stream().filter(i -> !i.isFolder() && i.running()).count();
            String strip = total + (total == 1 ? " script" : " scripts");
            if (running > 0) strip += "  ·  " + running + " running";
            ctx.drawText(textRenderer, Text.literal(strip), px + 116, py + H - BH + 9, C_GREY, false);
        }

        super.render(ctx, mx, my, delta);

        // ── Overlays (always drawn last, on top) ──────────────────────────────
        if (fileAccessConfirmPath != null) renderFileAccessConfirm(ctx, mx, my);
        if (currentTab == TAB_SCRIPTS)    scriptsTab.renderOverlays(ctx, mx, my, delta);
    }

    // ── File-access dialog ────────────────────────────────────────────────────

    private void renderFileAccessConfirm(DrawContext ctx, int mx, int my) {
        int dlgX   = px + (W - FA_DLG_W) / 2;
        int dlgY   = py + (H - FA_DLG_H) / 2;
        int innerW = FA_DLG_W - 16;

        ctx.fill(px, py, px + W, py + H, 0x88000000);

        ctx.fill(dlgX, dlgY, dlgX + FA_DLG_W, dlgY + FA_DLG_H, C_PANEL);
        ctx.fill(dlgX,                 dlgY,                 dlgX + FA_DLG_W, dlgY + 1,            C_BORDER);
        ctx.fill(dlgX,                 dlgY + FA_DLG_H - 1,  dlgX + FA_DLG_W, dlgY + FA_DLG_H,    C_BORDER);
        ctx.fill(dlgX,                 dlgY,                 dlgX + 1,         dlgY + FA_DLG_H,    C_BORDER);
        ctx.fill(dlgX + FA_DLG_W - 1,  dlgY,                 dlgX + FA_DLG_W,  dlgY + FA_DLG_H,   C_BORDER);
        ctx.fill(dlgX + 1, dlgY + 1, dlgX + FA_DLG_W - 1, dlgY + 3, C_ORANGE);

        String scriptLabel = fileAccessConfirmPath;
        if (scriptLabel != null && scriptLabel.contains("/"))
            scriptLabel = scriptLabel.substring(scriptLabel.lastIndexOf('/') + 1);

        ctx.drawText(textRenderer, Text.literal("§6\u26a0 File Access Requested"), dlgX + 8, dlgY + 7, C_ORANGE, false);

        int wy = dlgY + 18;
        ctx.drawText(textRenderer, Text.literal("§c" + scriptLabel + " §7tried to write a file."),
            dlgX + 8, wy, C_RED, false);
        wy += 10;

        for (String line : wrapTextPx(
                "If you allow this, the script can read and write ANY file on your computer. Only allow scripts you fully trust.",
                innerW)) {
            ctx.drawText(textRenderer, Text.literal("§7" + line), dlgX + 8, wy, C_DIM, false);
            wy += 9;
        }

        int enX = dlgX + 8, enY = dlgY + FA_DLG_H - 20, enW = 84, enH = 14;
        boolean enHov = mx >= enX && mx < enX + enW && my >= enY && my < enY + enH;
        ctx.fill(enX, enY, enX + enW, enY + enH, enHov ? 0xFFCC5500 : 0xFFAA4400);
        ctx.fill(enX, enY, enX + enW, enY + 1, 0xFFFF7700);
        ctx.drawText(textRenderer, Text.literal("Allow"),
            enX + (enW - textRenderer.getWidth("Allow")) / 2, enY + 3, C_WHITE, false);

        int ccX = dlgX + FA_DLG_W - 8 - 72, ccY = enY, ccW = 72, ccH = 14;
        boolean ccHov = mx >= ccX && mx < ccX + ccW && my >= ccY && my < ccY + ccH;
        ctx.fill(ccX, ccY, ccX + ccW, ccY + ccH, ccHov ? 0xFF444444 : 0xFF2A2A2A);
        ctx.fill(ccX, ccY, ccX + ccW, ccY + 1, 0xFF666666);
        ctx.drawText(textRenderer, Text.literal("Deny"),
            ccX + (ccW - textRenderer.getWidth("Deny")) / 2, ccY + 3, C_DIM, false);
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // File-access dialog intercepts all clicks
        if (button == 0 && fileAccessConfirmPath != null) {
            int dlgX = px + (W - FA_DLG_W) / 2;
            int dlgY = py + (H - FA_DLG_H) / 2;

            int enX = dlgX + 8, enY = dlgY + FA_DLG_H - 20, enW = 84, enH = 14;
            if (mouseX >= enX && mouseX < enX + enW && mouseY >= enY && mouseY < enY + enH) {
                PeripheralConfig.setFileAccess(fileAccessConfirmPath, true);
                fileAccessConfirmPath = null;
                buildContent();
                return true;
            }
            int ccX = dlgX + FA_DLG_W - 8 - 72, ccY = enY, ccW = 72, ccH = 14;
            if (mouseX >= ccX && mouseX < ccX + ccW && mouseY >= ccY && mouseY < ccY + ccH) {
                fileAccessConfirmPath = null;
                buildContent();
                return true;
            }
            if (mouseX < dlgX || mouseX >= dlgX + FA_DLG_W || mouseY < dlgY || mouseY >= dlgY + FA_DLG_H) {
                fileAccessConfirmPath = null;
                buildContent();
            }
            return true;
        }

        // Delegate to active tab
        boolean handled = switch (currentTab) {
            case TAB_LOG     -> logTab.mouseClicked(mouseX, mouseY, button);
            case TAB_SCRIPTS -> scriptsTab.mouseClicked(mouseX, mouseY, button);
            case TAB_STORE   -> storeTab.mouseClicked(mouseX, mouseY, button);
            case TAB_BUILD   -> buildTab.mouseClicked(mouseX, mouseY, button);
            default          -> false;
        };
        return handled || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        boolean handled = switch (currentTab) {
            case TAB_LOG     -> logTab.mouseScrolled(mx, my, hAmt, vAmt);
            case TAB_SCRIPTS -> scriptsTab.mouseScrolled(mx, my, hAmt, vAmt);
            case TAB_STORE   -> storeTab.mouseScrolled(mx, my, hAmt, vAmt);
            case TAB_BUILD   -> buildTab.mouseScrolled(mx, my, hAmt, vAmt);
            default          -> false;
        };
        return handled || super.mouseScrolled(mx, my, hAmt, vAmt);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (currentTab == TAB_STORE && storeTab.mouseDragged(mouseX, mouseY, button, dx, dy)) return true;
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (currentTab == TAB_STORE && storeTab.mouseReleased(mouseX, mouseY, button)) return true;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override public boolean shouldPause() { return false; }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private List<String> wrapTextPx(String text, int maxPx) {
        List<String> out = new ArrayList<>();
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String w : words) {
            String test = line.length() > 0 ? line + " " + w : w;
            if (textRenderer.getWidth(test) > maxPx && line.length() > 0) {
                out.add(line.toString()); line = new StringBuilder(w);
            } else {
                if (line.length() > 0) line.append(' '); line.append(w);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }
}
