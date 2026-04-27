package slooth.peripheral;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

class StoreTab {

    private final PeripheralScreen host;
    private int px, contentY, contentH;

    // ── Store list/detail state ───────────────────────────────────────────────
    List<StoreClient.ScriptEntry> storeScripts = null;
    private StoreClient.ScriptEntry storeDetailEntry    = null;
    private String  storeStatus               = "";
    private int     storeScroll               = 0;
    private String  storeSearch               = "";
    private int     storeDetailCodeScroll     = 0;
    private int     storeDetailCodeHScroll    = 0;
    private int     storeScrollBeforeDetail   = 0;
    private boolean storeLoadFailed           = false;

    // H-scrollbar geometry cached each render frame (for drag hit-testing)
    private int     hBarY       = -1;
    private int     hBarX1      = 0;
    private int     hBarX2      = 0;
    private int     hBarMax     = 0;
    private boolean hBarDrag    = false;
    private int     hBarDragX   = 0;
    private int     hBarDragVal = 0;

    private TextFieldWidget searchField = null;

    // ── Submit overlay state ──────────────────────────────────────────────────
    private static final int SUB_W   = 340;
    private static final int SUB_H   = 174;
    private static final int SUB_ROW = 13;
    private static final int SUB_VIS = 4;

    private boolean         submitOpen       = false;
    private List<String>    submitScripts    = List.of();
    private String          submitPicked     = null;
    private TextFieldWidget submitDescField  = null;
    private ClickableWidget submitAnonBtn    = null;
    private ClickableWidget submitOkBtn      = null;
    private ClickableWidget submitCancelBtn  = null;
    private boolean         submitAnon       = false;
    private int             submitListScroll = 0;
    private String          submitStatus     = "";
    private String          submitRealName   = "";
    private String          submitMdContent  = null;
    private boolean         submitKeyWarnAck = false;

    StoreTab(PeripheralScreen host) { this.host = host; }

    // ── Build ─────────────────────────────────────────────────────────────────

    void build(int px, int py, int contentY, int contentH) {
        this.px = px; this.contentY = contentY; this.contentH = contentH;

        // Reset submit overlay widgets (closeSubmit handles widget removal)
        closeSubmit();

        int by = py + PeripheralScreen.H - PeripheralScreen.BH + 6;

        if (storeDetailEntry != null) {
            host.widgetAdd(PeripheralScreen.btn("← Back", px + 4, by, 46, 14, b -> {
                storeDetailEntry = null;
                storeDetailCodeScroll = 0;
                storeScroll = storeScrollBeforeDetail;
                host.rebuild();
            }));
            host.widgetAdd(PeripheralScreen.btn("⧉ Copy Code", px + (PeripheralScreen.W / 2) - 34, by, 68, 14, b -> {
                String code = storeDetailEntry != null ? storeDetailEntry.code() : null;
                if (code != null && !code.isBlank()) {
                    host.mc().keyboard.setClipboard(code);
                    storeStatus = "Copied!";
                } else {
                    storeStatus = "Code not loaded yet.";
                }
            }));
            host.widgetAdd(PeripheralScreen.btn("▼ Install", px + PeripheralScreen.W - 64, by, 60, 14,
                b -> installScript(storeDetailEntry)));
        } else {
            host.widgetAdd(PeripheralScreen.btn("↺ Refresh", px + 4, by, 60, 14, b -> {
                StoreClient.invalidateCache();
                storeScripts = null; storeDetailEntry = null; storeStatus = "";
                fetchStoreScripts();
            }));
            host.widgetAdd(PeripheralScreen.btn("+ Submit", px + PeripheralScreen.W - 64, by, 60, 14,
                b -> openSubmit()));

            searchField = new TextFieldWidget(host.tr(), px + 68, by, PeripheralScreen.W - 136, 14, Text.empty());
            searchField.setMaxLength(60);
            searchField.setPlaceholder(Text.literal("Search..."));
            searchField.setText(storeSearch);
            searchField.setChangedListener(t -> { storeSearch = t; storeScroll = 0; });
            host.widgetAdd(searchField);
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    void render(DrawContext ctx, int mx, int my) {
        int lx    = px + 4;
        int ly    = contentY + 3;
        int lineH = 18;

        if (storeDetailEntry != null) {
            renderDetail(ctx, mx, my, lx, ly);
            if (submitOpen) renderSubmitOverlay(ctx);
            return;
        }

        // ── List view ─────────────────────────────────────────────────────────
        if (storeScripts == null) {
            ctx.drawText(host.tr(), Text.literal(storeStatus.isEmpty() ? "Loading..." : storeStatus),
                lx, ly + 4, PeripheralScreen.C_GREY, false);
            if (submitOpen) renderSubmitOverlay(ctx);
            return;
        }

        String q = storeSearch.toLowerCase();
        List<StoreClient.ScriptEntry> visible = storeScripts.stream()
            .filter(s -> q.isEmpty()
                || s.name().toLowerCase().contains(q)
                || s.description().toLowerCase().contains(q)
                || s.author().toLowerCase().contains(q))
            .toList();

        if (visible.isEmpty()) {
            if (storeScripts.isEmpty()) {
                if (storeLoadFailed) {
                    ctx.drawText(host.tr(), Text.literal("§cFailed to load store."), lx, ly + 4, PeripheralScreen.C_GREY, false);
                    ctx.drawText(host.tr(), Text.literal("§eTry hitting Refresh."),  lx, ly + 14, PeripheralScreen.C_DIM,  false);
                    ctx.drawText(host.tr(), Text.literal("§7If that doesn't work, check your internet connection."), lx, ly + 24, PeripheralScreen.C_DIM, false);
                } else {
                    ctx.drawText(host.tr(), Text.literal("No scripts found."), lx, ly + 4, PeripheralScreen.C_GREY, false);
                    ctx.drawText(host.tr(), Text.literal("§7Try hitting Refresh — the store may not have loaded."), lx, ly + 14, PeripheralScreen.C_DIM, false);
                }
            } else {
                ctx.drawText(host.tr(), Text.literal("No results."), lx, ly + 4, PeripheralScreen.C_GREY, false);
            }
            if (submitOpen) renderSubmitOverlay(ctx);
            return;
        }

        int maxRows = (contentH - 6) / lineH;
        int total   = visible.size();
        storeScroll = Math.max(0, Math.min(storeScroll, Math.max(0, total - maxRows)));

        int y = ly;
        for (int i = storeScroll; i < Math.min(total, storeScroll + maxRows); i++) {
            StoreClient.ScriptEntry s = visible.get(i);
            ctx.fill(px + 1, y, px + PeripheralScreen.W - 1, y + lineH - 1, i % 2 == 0 ? 0xFF111111 : 0xFF161616);
            ctx.fill(px + 1, y + lineH - 1, px + PeripheralScreen.W - 1, y + lineH, PeripheralScreen.C_BORDER);

            ctx.drawText(host.tr(), Text.literal(s.name()), lx + 2, y + 2, PeripheralScreen.C_ORANGE, false);
            String byLine = "by " + s.author();
            ctx.drawText(host.tr(), Text.literal(byLine),
                px + PeripheralScreen.W - 4 - host.tr().getWidth(byLine), y + 2, PeripheralScreen.C_DIM, false);

            String desc = s.description();
            if (desc.length() > 62) desc = desc.substring(0, 59) + "...";
            ctx.drawText(host.tr(), Text.literal(desc), lx + 2, y + 10, PeripheralScreen.C_GREY, false);

            y += lineH;
            if (y >= contentY + contentH - 2) break;
        }

        if (total > maxRows) {
            String hint = (storeScroll + 1) + "-" + Math.min(total, storeScroll + maxRows) + " / " + total;
            ctx.drawText(host.tr(), Text.literal(hint),
                px + PeripheralScreen.W - 4 - host.tr().getWidth(hint),
                px + PeripheralScreen.H - PeripheralScreen.BH + 9, PeripheralScreen.C_DIM, false);
        }
        if (!storeStatus.isEmpty())
            ctx.drawText(host.tr(), Text.literal(storeStatus),
                lx, px + PeripheralScreen.H - PeripheralScreen.BH + 9, PeripheralScreen.C_GREY, false);

        if (submitOpen) renderSubmitOverlay(ctx);
    }

    private void renderDetail(DrawContext ctx, int mx, int my, int lx, int ly) {
        StoreClient.ScriptEntry s = storeDetailEntry;
        int y = ly + 2;

        ctx.drawText(host.tr(), Text.literal(s.name()), lx + 2, y, PeripheralScreen.C_ORANGE, false);
        String byLine = "by " + s.author();
        ctx.drawText(host.tr(), Text.literal(byLine),
            px + PeripheralScreen.W - 4 - host.tr().getWidth(byLine), y, PeripheralScreen.C_DIM, false);
        y += 11;

        String fullDesc = s.description().isEmpty() ? "No description." : s.description();
        int descLines = 0;
        for (String line : wrapText(fullDesc, 58)) {
            if (descLines >= 2) break;
            ctx.drawText(host.tr(), Text.literal(line), lx + 2, y, PeripheralScreen.C_GREY, false);
            y += 9; descLines++;
        }
        y += 4;

        ctx.fill(lx + 2, y, px + PeripheralScreen.W - 4, y + 1, PeripheralScreen.C_BORDER);
        y += 4;

        ctx.drawText(host.tr(), Text.literal("SOURCE CODE"), lx + 2, y, PeripheralScreen.C_ORANGE, false);
        ctx.drawText(host.tr(), Text.literal("↕ scroll  ↔ shift+scroll"),
            px + PeripheralScreen.W - 4 - host.tr().getWidth("↕ scroll  ↔ shift+scroll"), y, PeripheralScreen.C_DIM, false);
        y += 10;

        final int CODE_LINE_H = 9;
        final int SCROLLBAR_H = 7;
        final int WARN_H      = 40;
        final int CODE_BOX_H  = contentY + contentH - y - SCROLLBAR_H - WARN_H - 18;
        final int CODE_VIS    = Math.max(1, CODE_BOX_H / CODE_LINE_H);
        final int codeBoxX1   = lx + 2;
        final int codeBoxX2   = px + PeripheralScreen.W - 4;
        final int codeBoxW    = codeBoxX2 - codeBoxX1 - 6;

        ctx.fill(codeBoxX1, y, codeBoxX2, y + CODE_BOX_H, 0xFF0A0A0A);
        ctx.fill(codeBoxX1, y,              codeBoxX2, y + 1,            PeripheralScreen.C_BORDER);
        ctx.fill(codeBoxX1, y + CODE_BOX_H, codeBoxX2, y + CODE_BOX_H + 1, PeripheralScreen.C_BORDER);
        ctx.fill(codeBoxX1, y,              codeBoxX1 + 1, y + CODE_BOX_H, PeripheralScreen.C_BORDER);
        ctx.fill(codeBoxX2 - 1, y,          codeBoxX2, y + CODE_BOX_H,    PeripheralScreen.C_BORDER);

        ctx.enableScissor(codeBoxX1 + 1, y + 1, codeBoxX2 - 1, y + CODE_BOX_H);

        String code = s.code();
        int maxLineLen = 0, maxHScroll = 0;
        if (code == null || code.isBlank()) {
            ctx.drawText(host.tr(), Text.literal("Loading..."), codeBoxX1 + 4, y + 4, PeripheralScreen.C_DIM, false);
        } else {
            String[] codeLines = code.split("\n", -1);
            int maxVScroll = Math.max(0, codeLines.length - CODE_VIS);
            storeDetailCodeScroll = Math.max(0, Math.min(storeDetailCodeScroll, maxVScroll));
            for (String cl : codeLines) maxLineLen = Math.max(maxLineLen, host.tr().getWidth(cl));
            maxHScroll = Math.max(0, maxLineLen - codeBoxW);
            storeDetailCodeHScroll = Math.max(0, Math.min(storeDetailCodeHScroll, maxHScroll));
            int cy = y + 3;
            for (int cl = storeDetailCodeScroll;
                 cl < Math.min(codeLines.length, storeDetailCodeScroll + CODE_VIS); cl++) {
                ctx.drawText(host.tr(), Text.literal(codeLines[cl]),
                    codeBoxX1 + 4 - storeDetailCodeHScroll, cy, 0xFF88CC88, false);
                cy += CODE_LINE_H;
            }
        }
        ctx.disableScissor();

        // Horizontal scrollbar
        final int sbY = y + CODE_BOX_H + 1;
        hBarY = sbY; hBarX1 = codeBoxX1 + 1; hBarX2 = codeBoxX2 - 1; hBarMax = maxHScroll;
        ctx.fill(hBarX1, sbY, hBarX2, sbY + SCROLLBAR_H, 0xFF141414);
        if (maxHScroll > 0) {
            int trackW = hBarX2 - hBarX1;
            int thumbW = Math.max(16, Math.min(trackW, trackW * codeBoxW / Math.max(maxLineLen, 1)));
            int thumbX = hBarX1 + (int)((long)(trackW - thumbW) * storeDetailCodeHScroll / maxHScroll);
            ctx.fill(thumbX, sbY + 1, thumbX + thumbW, sbY + SCROLLBAR_H - 1,
                hBarDrag ? 0xFF888888 : 0xFF555555);
        } else { hBarY = -1; }

        if (code != null && !code.isBlank()) {
            String[] codeLines = code.split("\n", -1);
            String ind = (storeDetailCodeScroll + 1) + "-"
                + Math.min(codeLines.length, storeDetailCodeScroll + CODE_VIS) + "/" + codeLines.length;
            ctx.drawText(host.tr(), Text.literal(ind),
                codeBoxX2 - host.tr().getWidth(ind) - 2, sbY + 1, PeripheralScreen.C_DIM, false);
        }
        y += CODE_BOX_H + SCROLLBAR_H + 4;

        // Warning banner
        final int warnX1 = lx + 2, warnX2 = px + PeripheralScreen.W - 4;
        ctx.fill(warnX1, y, warnX2, y + WARN_H, 0xFF180E00);
        ctx.fill(warnX1, y, warnX1 + 2, y + WARN_H, 0xFFFF8800);
        ctx.drawText(host.tr(), Text.literal("§6⚠  USE AT YOUR OWN RISK — Community Scripts"),
            warnX1 + 6, y + 4, 0xFFFFAA00, false);
        int wy = y + 14;
        for (String wl : wrapTextPx(
                "The author of Peripheral is not liable for any damage caused by scripts from this store.",
                warnX2 - warnX1 - 10)) {
            ctx.drawText(host.tr(), Text.literal("§7" + wl), warnX1 + 6, wy, PeripheralScreen.C_DIM, false);
            wy += 9;
        }

        if (!storeStatus.isEmpty())
            ctx.drawText(host.tr(), Text.literal(storeStatus),
                lx, px + PeripheralScreen.H - PeripheralScreen.BH + 9, PeripheralScreen.C_GREEN, false);
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        double cx = mouseX, cy = mouseY;

        // Submit overlay script-list clicks
        if (submitOpen) {
            int bx    = px + (PeripheralScreen.W - SUB_W) / 2;
            int by    = contentY + (contentH - SUB_H) / 2;
            int listY = by + 26;
            if (cx >= bx + 4 && cx < bx + SUB_W - 4 && cy >= listY && cy < listY + SUB_VIS * SUB_ROW) {
                int row = (int)((cy - listY) / SUB_ROW) + submitListScroll;
                if (row >= 0 && row < submitScripts.size()) {
                    submitPicked    = submitScripts.get(row);
                    submitMdContent = checkForMd(submitPicked);
                }
                return true;
            }
            if (cx >= bx && cx < bx + SUB_W && cy >= by && cy < by + SUB_H) {
                // Let button/field dispatch happen (Screen.super handles it)
                return false; // allow super.mouseClicked to run
            }
        }

        // List row clicks → open detail view
        if (!submitOpen && storeDetailEntry == null && storeScripts != null) {
            String q = storeSearch.toLowerCase();
            List<StoreClient.ScriptEntry> visible = storeScripts.stream()
                .filter(s -> q.isEmpty()
                    || s.name().toLowerCase().contains(q)
                    || s.description().toLowerCase().contains(q)
                    || s.author().toLowerCase().contains(q))
                .toList();
            int lineH = 18, y = contentY + 3;
            int maxRows = (contentH - 6) / lineH;
            for (int i = storeScroll; i < Math.min(visible.size(), storeScroll + maxRows); i++) {
                StoreClient.ScriptEntry s = visible.get(i);
                if (cy >= y && cy < y + lineH) {
                    storeScrollBeforeDetail = storeScroll;
                    storeDetailEntry = s;
                    storeStatus = "";
                    StoreClient.fetchScript(s.id(), full -> { if (full != null) storeDetailEntry = full; });
                    host.rebuild();
                    return true;
                }
                y += lineH;
                if (y >= contentY + contentH - 2) break;
            }
        }

        // Horizontal scrollbar drag start
        if (storeDetailEntry != null && hBarY >= 0) {
            int icx = (int) cx, icy = (int) cy;
            if (icy >= hBarY && icy < hBarY + 7 && icx >= hBarX1 && icx < hBarX2) {
                hBarDrag    = true;
                hBarDragX   = icx;
                hBarDragVal = storeDetailCodeHScroll;
                return true;
            }
        }
        return false;
    }

    boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        if (submitOpen) {
            int maxScr = Math.max(0, submitScripts.size() - SUB_VIS);
            submitListScroll = (int) Math.max(0, Math.min(submitListScroll - vAmt, maxScr));
            return true;
        }
        if (storeDetailEntry != null) {
            String code = storeDetailEntry.code();
            if (code != null) {
                if (hAmt != 0) {
                    storeDetailCodeHScroll = (int) Math.max(0, storeDetailCodeHScroll + hAmt * 6);
                } else {
                    int maxScr = Math.max(0, code.split("\n", -1).length - 6);
                    storeDetailCodeScroll = (int) Math.max(0, Math.min(storeDetailCodeScroll - vAmt, maxScr));
                }
            }
            return true;
        }
        if (storeScripts != null) {
            int rowH    = 18;
            int maxRows = (contentH - 6) / rowH;
            int maxScr  = Math.max(0, storeScripts.size() - maxRows);
            storeScroll = (int) Math.max(0, Math.min(storeScroll - vAmt, maxScr));
            return true;
        }
        return false;
    }

    boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (button == 0 && hBarDrag && hBarMax > 0) {
            int trackW = hBarX2 - hBarX1;
            if (trackW > 0) {
                int delta = (int) mouseX - hBarDragX;
                storeDetailCodeHScroll = Math.max(0,
                    Math.min(hBarDragVal + delta * hBarMax / trackW, hBarMax));
            }
            return true;
        }
        return false;
    }

    boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && hBarDrag) { hBarDrag = false; return true; }
        return false;
    }

    // ── Store fetching / installing ───────────────────────────────────────────

    void fetchStoreScripts() {
        storeStatus = "Loading...";
        storeScripts = null;
        storeLoadFailed = false;
        StoreClient.fetchList(list -> {
            storeLoadFailed  = (list == null);
            storeScripts     = list == null ? List.of() : list;
            storeStatus      = list == null ? "Failed to load store." : "";
            storeDetailEntry = null;
        });
    }

    private void installScript(StoreClient.ScriptEntry entry) {
        if (entry == null || entry.code() == null) { storeStatus = "No code to install."; return; }
        storeStatus = "Installing...";
        java.nio.file.Path scriptsDir = java.nio.file.Paths.get("config", "peripheral", "scripts");
        StoreClient.install(entry, scriptsDir, result -> {
            if (result != null) {
                storeStatus = "Installed: " + result;
                host.scriptsTab.scriptBtnsDirty = true;
                host.scriptsTab.refreshScripts();
            } else {
                storeStatus = "Install failed.";
            }
        });
    }

    // ── Submit overlay ────────────────────────────────────────────────────────

    private void openSubmit() {
        submitKeyWarnAck = false;
        closeSubmit();
        submitScripts = ScriptRunner.listAllScripts().stream()
            .filter(s -> !s.startsWith("examples/") && !s.equals("examples"))
            .collect(Collectors.toList());
        if (!submitScripts.isEmpty()) {
            submitPicked    = submitScripts.get(0);
            submitMdContent = checkForMd(submitPicked);
        }

        net.minecraft.client.MinecraftClient mc = host.mc();
        submitRealName = (mc.player != null) ? mc.player.getName().getString() : "Unknown";
        submitOpen = true;

        int bx = px + (PeripheralScreen.W - SUB_W) / 2;
        int by = contentY + (contentH - SUB_H) / 2;

        submitDescField = new TextFieldWidget(host.tr(), bx + 4, by + 97, SUB_W - 8, 14, Text.empty());
        submitDescField.setMaxLength(200);
        submitDescField.setFocusUnlocked(false);
        host.widgetAdd(submitDescField);
        host.focusOn(submitDescField);

        submitAnonBtn = PeripheralScreen.btn("Anon: OFF", bx + 4, by + 119, 60, 12, b -> {
            submitAnon = !submitAnon;
            submitAnonBtn.setMessage(Text.literal("Anon: " + (submitAnon ? "ON " : "OFF")));
        });
        host.widgetAdd(submitAnonBtn);

        int btnY = by + SUB_H - 18;
        submitOkBtn     = PeripheralScreen.btn("Submit", bx + SUB_W - 4 - 58,           btnY, 58, 14, b -> doSubmit());
        submitCancelBtn = PeripheralScreen.btn("Cancel", bx + SUB_W - 4 - 58 - 4 - 46, btnY, 46, 14, b -> closeSubmit());
        host.widgetAdd(submitOkBtn);
        host.widgetAdd(submitCancelBtn);
    }

    private void closeSubmit() {
        if (submitDescField  != null) { host.widgetRemove(submitDescField);  submitDescField  = null; }
        if (submitAnonBtn    != null) { host.widgetRemove(submitAnonBtn);    submitAnonBtn    = null; }
        if (submitOkBtn      != null) { host.widgetRemove(submitOkBtn);      submitOkBtn      = null; }
        if (submitCancelBtn  != null) { host.widgetRemove(submitCancelBtn);  submitCancelBtn  = null; }
        submitOpen = false; submitPicked = null; submitRealName = "";
        submitStatus = ""; submitAnon = false; submitListScroll = 0; submitMdContent = null;
    }

    private void renderSubmitOverlay(DrawContext ctx) {
        final int BG       = 0xFF111111;
        final int TITLE_BG = 0xFF1C1C1C;
        final int ORANGE   = 0xFFFF8800;
        final int BORDER   = 0xFF333333;
        final int LIST_BG  = 0xFF0D0D0D;
        final int ROW_ALT  = 0xFF161616;
        final int ROW_SEL  = 0xFF1A2E1A;
        final int TXT      = 0xFFCCCCCC;
        final int TXT_DIM  = 0xFF666666;
        final int TXT_SEL  = 0xFFAAFF88;
        final int TXT_PATH = 0xFF555555;

        int bx = px + (PeripheralScreen.W - SUB_W) / 2;
        int by = contentY + (contentH - SUB_H) / 2;

        ctx.fill(px + 1, contentY, px + PeripheralScreen.W - 1, contentY + contentH, 0xCC000000);

        ctx.fill(bx, by, bx + SUB_W, by + SUB_H, BG);
        ctx.fill(bx,             by,             bx + SUB_W,     by + 1,        BORDER);
        ctx.fill(bx,             by + SUB_H - 1, bx + SUB_W,     by + SUB_H,    BORDER);
        ctx.fill(bx,             by,             bx + 1,         by + SUB_H,    BORDER);
        ctx.fill(bx + SUB_W - 1, by,             bx + SUB_W,     by + SUB_H,    BORDER);

        ctx.fill(bx + 1, by + 1, bx + SUB_W - 1, by + 15, TITLE_BG);
        ctx.fill(bx + 1, by + 14, bx + SUB_W - 1, by + 15, BORDER);
        ctx.drawCenteredTextWithShadow(host.tr(),
            Text.literal("Submit Script to Store"), bx + SUB_W / 2, by + 4, ORANGE);

        ctx.drawText(host.tr(), Text.literal("SCRIPT"), bx + 4, by + 19, ORANGE, false);
        ctx.fill(bx + 40, by + 22, bx + SUB_W - 4, by + 23, BORDER);

        int listTop = by + 26;
        int total   = submitScripts.size();
        submitListScroll = Math.max(0, Math.min(submitListScroll, Math.max(0, total - SUB_VIS)));

        ctx.fill(bx + 4, listTop, bx + SUB_W - 4, listTop + SUB_VIS * SUB_ROW, LIST_BG);
        ctx.fill(bx + 4, listTop,                         bx + SUB_W - 4, listTop + 1,              BORDER);
        ctx.fill(bx + 4, listTop + SUB_VIS * SUB_ROW - 1, bx + SUB_W - 4, listTop + SUB_VIS * SUB_ROW, BORDER);
        ctx.fill(bx + 4, listTop, bx + 5, listTop + SUB_VIS * SUB_ROW, BORDER);
        ctx.fill(bx + SUB_W - 5, listTop, bx + SUB_W - 4, listTop + SUB_VIS * SUB_ROW, BORDER);

        if (total == 0) {
            ctx.drawText(host.tr(), Text.literal("No local scripts found."), bx + 9, listTop + 4, TXT_DIM, false);
        } else {
            for (int i = submitListScroll; i < Math.min(total, submitListScroll + SUB_VIS); i++) {
                String  s   = submitScripts.get(i);
                int     ry  = listTop + (i - submitListScroll) * SUB_ROW;
                boolean sel = s.equals(submitPicked);
                ctx.fill(bx + 5, ry, bx + SUB_W - 5, ry + SUB_ROW,
                    sel ? ROW_SEL : (i % 2 == 0 ? LIST_BG : ROW_ALT));
                String fname  = s.contains("/") ? s.substring(s.lastIndexOf('/') + 1) : s;
                if (fname.endsWith(".py")) fname = fname.substring(0, fname.length() - 3);
                String folder = s.contains("/") ? "  " + s.substring(0, s.lastIndexOf('/')) : "";
                ctx.drawText(host.tr(), Text.literal(fname), bx + 9, ry + 3, sel ? TXT_SEL : TXT, false);
                if (!folder.isEmpty())
                    ctx.drawText(host.tr(), Text.literal(folder),
                        bx + 9 + host.tr().getWidth(fname), ry + 3, TXT_PATH, false);
            }
            if (total > SUB_VIS) {
                String hint = (submitListScroll + 1) + "-" + Math.min(total, submitListScroll + SUB_VIS) + "/" + total;
                ctx.drawText(host.tr(), Text.literal(hint),
                    bx + SUB_W - 4 - host.tr().getWidth(hint) - 1,
                    listTop + SUB_VIS * SUB_ROW - 10, TXT_DIM, false);
            }
        }

        ctx.drawText(host.tr(), Text.literal("DESCRIPTION"), bx + 4, by + 83, ORANGE, false);
        ctx.fill(bx + 68, by + 86, bx + SUB_W - 4, by + 87, BORDER);
        if (submitMdContent != null && submitPicked != null) {
            String mdName = submitPicked.contains("/")
                ? submitPicked.substring(submitPicked.lastIndexOf('/') + 1) : submitPicked;
            mdName = mdName.replaceFirst("\\.py$", "") + ".md";
            ctx.drawText(host.tr(), Text.literal("Using " + mdName), bx + 4, by + 100, 0xFF44FF44, false);
            ctx.drawText(host.tr(), Text.literal("(text field ignored)"),
                bx + 4 + host.tr().getWidth("Using " + mdName) + 4, by + 100, TXT_DIM, false);
        }

        String name = submitAnon ? "Anonymous" : submitRealName;
        ctx.drawText(host.tr(), Text.literal("Post as:"), bx + 68, by + 121, TXT_DIM, false);
        ctx.drawText(host.tr(), Text.literal(name),
            bx + 68 + host.tr().getWidth("Post as: "), by + 121, submitAnon ? TXT_DIM : TXT, false);
        if (submitAnon)
            ctx.drawText(host.tr(), Text.literal("  (admins see real name)"),
                bx + 68 + host.tr().getWidth("Post as: " + name), by + 121, TXT_DIM, false);

        ctx.fill(bx + 4, by + 134, bx + SUB_W - 4, by + 135, BORDER);
        ctx.drawText(host.tr(),
            Text.literal("Scripts become public once approved. The mod author is not liable."),
            bx + 4, by + 139, TXT_DIM, false);

        if (!submitStatus.isEmpty()) {
            boolean ok = submitStatus.startsWith("Submitted");
            ctx.drawText(host.tr(), Text.literal(submitStatus),
                bx + 4, by + SUB_H - 16, ok ? 0xFF44FF44 : 0xFFFF4444, false);
        }
    }

    private void doSubmit() {
        if (submitPicked == null) { submitStatus = "Pick a script first."; return; }
        String desc = (submitMdContent != null && !submitMdContent.isBlank())
            ? submitMdContent
            : (submitDescField != null ? submitDescField.getText().trim() : "");
        if (desc.isEmpty()) { submitStatus = "Add a description or a .md file."; return; }

        java.nio.file.Path path = ScriptRunner.SCRIPTS_DIR.resolve(submitPicked);
        String content;
        try {
            content = java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) { submitStatus = "Could not read script."; return; }

        java.util.regex.Matcher keyMatcher = java.util.regex.Pattern.compile(
            "sk-[a-zA-Z0-9\\-_]{20,}|api[_\\-]?key\\s*[=:]\\s*['\"][^'\"]{10,}['\"]",
            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(content);
        if (keyMatcher.find() && !submitKeyWarnAck) {
            submitStatus = "§c\u26a0 Script may contain an API key! Remove it before submitting. Click Submit again to override.";
            submitKeyWarnAck = true;
            return;
        }

        String scriptName = submitPicked;
        if (scriptName.contains("/")) scriptName = scriptName.substring(scriptName.lastIndexOf('/') + 1);
        if (scriptName.endsWith(".py")) scriptName = scriptName.substring(0, scriptName.length() - 3);

        net.minecraft.client.MinecraftClient mc = host.mc();
        String realName    = (mc.player != null) ? mc.player.getName().getString() : "Unknown";
        String displayName = submitAnon ? "Anonymous" : realName;

        submitStatus = "Submitting...";
        StoreClient.submit(scriptName, desc, displayName, realName, content, ok -> {
            submitStatus = ok ? "Submitted! Pending review." : "Failed. Try again.";
        });
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String checkForMd(String relPath) {
        if (relPath == null) return null;
        String mdRel = relPath.endsWith(".py")
            ? relPath.substring(0, relPath.length() - 3) + ".md" : relPath + ".md";
        java.nio.file.Path p = ScriptRunner.SCRIPTS_DIR.resolve(mdRel);
        try {
            if (java.nio.file.Files.exists(p))
                return java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        return null;
    }

    private List<String> wrapTextPx(String text, int maxPx) {
        List<String> out = new ArrayList<>();
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String w : words) {
            String test = line.length() > 0 ? line + " " + w : w;
            if (host.tr().getWidth(test) > maxPx && line.length() > 0) {
                out.add(line.toString()); line = new StringBuilder(w);
            } else {
                if (line.length() > 0) line.append(' '); line.append(w);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    private List<String> wrapText(String text, int maxChars) {
        List<String> out = new ArrayList<>();
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String w : words) {
            if (line.length() + w.length() + 1 > maxChars && line.length() > 0) {
                out.add(line.toString()); line = new StringBuilder();
            }
            if (line.length() > 0) line.append(' ');
            line.append(w);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }
}
