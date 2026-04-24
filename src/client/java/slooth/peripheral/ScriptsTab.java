package slooth.peripheral;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

class ScriptsTab {

    private final PeripheralScreen host;
    private int px, py, contentY, contentH;

    // ── Script list state ─────────────────────────────────────────────────────
    List<ScriptRunner.ScriptInfo> scripts = new ArrayList<>();
    private int    scriptRefreshTick = 0;
    Path           currentFolder     = Path.of("");

    int     scroll      = 0;
    double  scrollPx    = 0.0;
    boolean scriptBtnsDirty = false;

    private TextFieldWidget searchField  = null;
    private String          searchQuery  = "";

    /** Per-row buttons — removed by reference on rebuild, not by label matching. */
    private final List<ButtonWidget> scriptRowBtns = new ArrayList<>();

    // ── Dialog state ──────────────────────────────────────────────────────────
    private static final int DLG_W = 190, DLG_H = 62, DLG_H_FOLDER = 74;

    String          deleteConfirmPath = null;

    // ── Rename overlay state ──────────────────────────────────────────────────
    private String          renamingScript  = null;
    private TextFieldWidget renameField     = null;
    private ButtonWidget    renameOkBtn     = null;
    private ButtonWidget    renameCancelBtn = null;

    ScriptsTab(PeripheralScreen host) { this.host = host; }

    // ── Build ─────────────────────────────────────────────────────────────────

    void build(int px, int py, int contentY, int contentH) {
        this.px = px; this.py = py; this.contentY = contentY; this.contentH = contentH;

        cancelRename(); // clear any leftover rename widgets before rebuilding

        // ── Search bar ────────────────────────────────────────────────────────
        int sx = px + 4, sy = contentY + PeripheralScreen.BREAD_H + 2;
        searchField = new TextFieldWidget(host.tr(), sx, sy, PeripheralScreen.W - 8, PeripheralScreen.SEARCH_H, Text.empty());
        searchField.setMaxLength(64);
        searchField.setPlaceholder(Text.literal("Search scripts..."));
        searchField.setFocusUnlocked(true);
        host.widgetAdd(searchField);

        // ── Bottom action bar ─────────────────────────────────────────────────
        int by = py + PeripheralScreen.H - PeripheralScreen.BH + 6;
        boolean inExamples = currentFolder.toString().replace(java.io.File.separatorChar, '/').startsWith("examples");

        ButtonWidget newBtn    = PeripheralScreen.btn("+ New",    px + 4,  by, 48, 14, b -> createNewScript());
        ButtonWidget folderBtn = PeripheralScreen.btn("+ Folder", px + 56, by, 56, 14, b -> createNewFolder());
        newBtn.active    = !inExamples;
        folderBtn.active = !inExamples;
        host.widgetAdd(newBtn);
        host.widgetAdd(folderBtn);
        host.widgetAdd(PeripheralScreen.btn("Clear HUD", px + PeripheralScreen.W - 126, by, 60, 14,
            b -> PeripheralHud.clearElements()));
        host.widgetAdd(PeripheralScreen.btn("Reload", px + PeripheralScreen.W - 62, by, 58, 14,
            b -> refreshScripts()));

        rebuildScriptBtns();
    }

    void rebuildScriptBtns() {
        // Remove previously-added per-row buttons by reference
        scriptRowBtns.forEach(b -> host.widgetRemove(b));
        scriptRowBtns.clear();

        List<ScriptRunner.ScriptInfo> visible = filteredScripts();

        boolean hasParent = !currentFolder.toString().isEmpty() && searchQuery.isEmpty();
        int rowH    = 18;
        int firstY  = contentY + PeripheralScreen.BREAD_H + PeripheralScreen.SEARCH_H + 4;
        int maxRows = (contentH - PeripheralScreen.BREAD_H - PeripheralScreen.SEARCH_H - 6) / rowH;

        int xAuto   = px + PeripheralScreen.W - 4  - 38;
        int xRunStop= xAuto   - 4 - 40;
        int xEdit   = xRunStop - 4 - 36;
        int xDel    = xEdit    - 4 - 28;
        int xRename = xDel     - 4 - 44;

        int totalItems = (hasParent ? 1 : 0) + visible.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, totalItems - maxRows)));

        int visIdx = 0;

        if (hasParent && 0 >= scroll && visIdx < maxRows) {
            int ry = firstY + visIdx * rowH;
            addRowBtn(PeripheralScreen.btn("Back", xAuto, ry, 38, 14, x -> navigateUp()));
            visIdx++;
        }

        for (int i = 0; i < visible.size(); i++) {
            int virtualIdx = (hasParent ? 1 : 0) + i;
            if (virtualIdx < scroll) continue;
            if (visIdx >= maxRows) break;

            ScriptRunner.ScriptInfo info = visible.get(i);
            int ry = firstY + visIdx * rowH;

            if (info.isFolder()) {
                String folderRel = currentFolder.toString().isEmpty()
                    ? info.name()
                    : currentFolder.toString().replace(java.io.File.separatorChar, '/') + "/" + info.name();
                addRowBtn(PeripheralScreen.btn("Open",   xAuto,   ry, 38, 14, x -> navigateInto(info.name())));
                addRowBtn(PeripheralScreen.btn("Del",    xDel,    ry, 28, 14, x -> { deleteConfirmPath = folderRel; host.rebuild(); }));
                addRowBtn(PeripheralScreen.btn("Rename", xRename, ry, 44, 14, x -> startRename(folderRel)));
            } else {
                boolean running = info.running();
                String arMode   = PeripheralConfig.getAutoRun(info.name());
                String arLabel  = switch (arMode) {
                    case "world" -> " SP";
                    case "multi" -> " MP";
                    case "both"  -> "ALL";
                    default      -> "OFF";
                };
                addRowBtn(PeripheralScreen.btn(arLabel, xAuto, ry, 38, 14,
                    x -> { PeripheralConfig.cycleAutoRun(info.name()); rebuildScriptBtns(); }));
                addRowBtn(running
                    ? PeripheralScreen.btn("Stop", xRunStop, ry, 40, 14,
                        x -> { ScriptRunner.stopScript(info.name()); refreshScripts(); })
                    : PeripheralScreen.btn("Run", xRunStop, ry, 40, 14,
                        x -> { ScriptRunner.runScript(info.name());  refreshScripts(); }));
                addRowBtn(PeripheralScreen.btn("Edit", xEdit, ry, 36, 14,
                    x -> host.mc().setScreen(new ScriptEditorScreen(host, info.name()))));
                addRowBtn(PeripheralScreen.btn("Del", xDel, ry, 28, 14,
                    x -> { deleteConfirmPath = info.name(); host.rebuild(); }));
                addRowBtn(PeripheralScreen.btn("Rename", xRename, ry, 44, 14,
                    x -> startRename(info.name())));
            }
            visIdx++;
        }
    }

    private void addRowBtn(ButtonWidget b) {
        host.widgetAdd(b);
        scriptRowBtns.add(b);
    }

    // ── Render ────────────────────────────────────────────────────────────────

    void render(DrawContext ctx) {
        // Breadcrumb
        String crumb = "scripts";
        if (!currentFolder.toString().isEmpty())
            crumb += " / " + currentFolder.toString()
                .replace(java.io.File.separatorChar, '/').replace("/", " / ");
        ctx.drawText(host.tr(), Text.literal("▶ " + crumb), px + 4, contentY + 2, PeripheralScreen.C_GREY, false);

        List<ScriptRunner.ScriptInfo> visible = filteredScripts();
        boolean hasParent = !currentFolder.toString().isEmpty() && searchQuery.isEmpty();
        int rowH    = 18;
        int firstY  = contentY + PeripheralScreen.BREAD_H + PeripheralScreen.SEARCH_H + 4;
        int maxRows = (contentH - PeripheralScreen.BREAD_H - PeripheralScreen.SEARCH_H - 6) / rowH;

        int xRename = px + PeripheralScreen.W - 4 - 38 - 4 - 40 - 4 - 36 - 4 - 28 - 4 - 44;

        int totalItems = (hasParent ? 1 : 0) + visible.size();
        if (totalItems == 0) {
            String hint = searchQuery.isEmpty()
                ? "No scripts or folders here.  Press '+ New' to create one."
                : "No scripts match \"" + searchQuery + "\".";
            ctx.drawText(host.tr(), Text.literal(hint), px + 8, firstY + 4, PeripheralScreen.C_GREY, false);
            return;
        }

        int visIdx = 0;

        if (hasParent) {
            if (0 >= scroll && visIdx < maxRows) {
                int ry = firstY + visIdx * rowH;
                ctx.fill(px + 2, ry - 1, xRename - 2, ry + rowH - 3, 0xCC111111);
                ctx.drawText(host.tr(), Text.literal("← .."), px + 8, ry + 2, PeripheralScreen.C_GREY, false);
                visIdx++;
            }
        }

        for (int i = 0; i < visible.size(); i++) {
            int virtualIdx = (hasParent ? 1 : 0) + i;
            if (virtualIdx < scroll) continue;
            if (visIdx >= maxRows) break;

            ScriptRunner.ScriptInfo info = visible.get(i);
            int ry = firstY + visIdx * rowH;

            if (info.isFolder()) {
                ctx.fill(px + 2, ry - 1, xRename - 2, ry + rowH - 3, 0xCC141408);
                ctx.drawText(host.tr(), Text.literal("/" + info.name()), px + 8, ry + 2, PeripheralScreen.C_YELLOW, false);
            } else {
                boolean run   = info.running();
                String arMode = PeripheralConfig.getAutoRun(info.name());
                boolean arOn  = !arMode.equals("off");

                int rowBg = run ? 0xCC0D1E0D : (arOn ? 0xCC0D0D1C : 0xCC111111);
                ctx.fill(px + 2, ry - 1, xRename - 2, ry + rowH - 3, rowBg);
                ctx.fill(px + 5, ry + 3, px + 10, ry + 9,
                    run ? 0xFF00FF88 : (arOn ? 0xFF3399FF : PeripheralScreen.C_DIM));

                String fn = info.name();
                if (fn.contains("/")) fn = fn.substring(fn.lastIndexOf('/') + 1);
                String slotPrefix = "";
                for (int si = 1; si <= 5; si++) {
                    if (PeripheralConfig.getScriptSlot(si).equals(info.name())) {
                        slotPrefix = "§6[" + si + "] ";
                        break;
                    }
                }
                String label = fn.length() > 22 ? fn.substring(0, 19) + "..." : fn;
                ctx.drawText(host.tr(), Text.literal(slotPrefix + label),
                    px + 14, ry + 2, run ? 0xFFCCFFDD : PeripheralScreen.C_WHITE, false);

                if (run && info.startedAt() > 0) {
                    long s = Instant.now().getEpochSecond() - info.startedAt();
                    ctx.drawText(host.tr(),
                        Text.literal(s < 60 ? s + "s" : (s / 60) + "m" + (s % 60) + "s"),
                        px + 145, ry + 2, PeripheralScreen.C_GREY, false);
                } else {
                    PeripheralConfig.getScriptSlots().forEach((slot, fname) -> {
                        if (fname.equals(info.name()))
                            ctx.drawText(host.tr(), Text.literal("[" + slot + "]"),
                                px + 145, ry + 2, PeripheralScreen.C_DIM, false);
                    });
                }
            }
            visIdx++;
        }
    }

    /** Overlays drawn after super.render() — rename dialog and delete confirm dialog. */
    void renderOverlays(DrawContext ctx, int mx, int my, float delta) {
        if (deleteConfirmPath != null) renderDeleteConfirm(ctx, mx, my);
        if (renamingScript    != null) renderRenameOverlay(ctx, mx, my, delta);
    }

    private void renderRenameOverlay(DrawContext ctx, int mx, int my, float delta) {
        ctx.fill(px + 1, contentY, px + PeripheralScreen.W - 1, contentY + contentH, 0xBB000000);
        int bx  = px + PeripheralScreen.W / 2 - 90;
        int bby = contentY + contentH / 2 - 25;
        int bw  = 180, bh = 50;
        ctx.fill(bx, bby, bx + bw, bby + bh, 0xFF1A1A1A);
        ctx.fill(bx,          bby,          bx + bw, bby + 1,    PeripheralScreen.C_BORDER);
        ctx.fill(bx,          bby + bh - 1, bx + bw, bby + bh,   PeripheralScreen.C_BORDER);
        ctx.fill(bx,          bby,          bx + 1,  bby + bh,    PeripheralScreen.C_BORDER);
        ctx.fill(bx + bw - 1, bby,          bx + bw, bby + bh,   PeripheralScreen.C_BORDER);
        ctx.fill(bx + 1, bby + 1, bx + bw - 1, bby + 14, 0xFF1C1C1C);
        ctx.fill(bx + 1, bby + 13, bx + bw - 1, bby + 14, PeripheralScreen.C_BORDER);
        ctx.drawCenteredTextWithShadow(host.tr(), Text.literal("Rename Script"),
            bx + bw / 2, bby + 3, PeripheralScreen.C_ORANGE);
        if (renameField     != null) renameField.render(ctx, mx, my, delta);
        if (renameOkBtn     != null) renameOkBtn.render(ctx, mx, my, delta);
        if (renameCancelBtn != null) renameCancelBtn.render(ctx, mx, my, delta);
    }

    private void renderDeleteConfirm(DrawContext ctx, int mx, int my) {
        boolean isFolder = Files.isDirectory(ScriptRunner.SCRIPTS_DIR.resolve(deleteConfirmPath));
        int dlgH = isFolder ? DLG_H_FOLDER : DLG_H;
        int dlgX = px + (PeripheralScreen.W - DLG_W) / 2;
        int dlgY = py + (PeripheralScreen.H - dlgH) / 2;

        ctx.fill(px, py, px + PeripheralScreen.W, py + PeripheralScreen.H, 0x88000000);

        ctx.fill(dlgX, dlgY, dlgX + DLG_W, dlgY + dlgH, PeripheralScreen.C_PANEL);
        ctx.fill(dlgX,              dlgY,            dlgX + DLG_W, dlgY + 1,        PeripheralScreen.C_BORDER);
        ctx.fill(dlgX,              dlgY + dlgH - 1, dlgX + DLG_W, dlgY + dlgH,    PeripheralScreen.C_BORDER);
        ctx.fill(dlgX,              dlgY,            dlgX + 1,      dlgY + dlgH,    PeripheralScreen.C_BORDER);
        ctx.fill(dlgX + DLG_W - 1, dlgY,            dlgX + DLG_W,  dlgY + dlgH,   PeripheralScreen.C_BORDER);
        ctx.fill(dlgX + 1, dlgY + 1, dlgX + DLG_W - 1, dlgY + 3, 0xFFCC2222);

        ctx.drawText(host.tr(), Text.literal(isFolder ? "§cDelete folder?" : "§cDelete script?"),
            dlgX + 8, dlgY + 7, PeripheralScreen.C_WHITE, false);

        String display = deleteConfirmPath;
        if (host.tr().getWidth(display) > DLG_W - 16)
            display = host.tr().trimToWidth(display, DLG_W - 24) + "…";
        ctx.drawText(host.tr(), Text.literal("§7" + display), dlgX + 8, dlgY + 18, PeripheralScreen.C_DIM, false);
        if (isFolder)
            ctx.drawText(host.tr(), Text.literal("§cAll scripts inside will be deleted."),
                dlgX + 8, dlgY + 28, PeripheralScreen.C_DIM, false);
        ctx.drawText(host.tr(), Text.literal("§7This cannot be undone."),
            dlgX + 8, isFolder ? dlgY + 38 : dlgY + 28, PeripheralScreen.C_DIM, false);

        int cfmX = dlgX + 8, cfmY = dlgY + dlgH - 18, cfmW = 78, cfmH = 14;
        boolean cfmHov = mx >= cfmX && mx < cfmX + cfmW && my >= cfmY && my < cfmY + cfmH;
        ctx.fill(cfmX, cfmY, cfmX + cfmW, cfmY + cfmH, cfmHov ? 0xFFAA1111 : 0xFF881111);
        ctx.fill(cfmX, cfmY, cfmX + cfmW, cfmY + 1, 0xFFCC3333);
        ctx.drawText(host.tr(), Text.literal("Confirm"),
            cfmX + (cfmW - host.tr().getWidth("Confirm")) / 2, cfmY + 3, PeripheralScreen.C_WHITE, false);

        int cclX = dlgX + DLG_W - 8 - 72, cclY = cfmY, cclW = 72, cclH = 14;
        boolean cclHov = mx >= cclX && mx < cclX + cclW && my >= cclY && my < cclY + cclH;
        ctx.fill(cclX, cclY, cclX + cclW, cclY + cclH, cclHov ? 0xFF444444 : 0xFF2A2A2A);
        ctx.fill(cclX, cclY, cclX + cclW, cclY + 1, 0xFF666666);
        ctx.drawText(host.tr(), Text.literal("Cancel"),
            cclX + (cclW - host.tr().getWidth("Cancel")) / 2, cclY + 3, PeripheralScreen.C_DIM, false);
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    void tick() {
        if (searchField != null) {
            String q = searchField.getText().toLowerCase();
            if (!q.equals(searchQuery)) {
                searchQuery     = q;
                scroll          = 0;
                scrollPx        = 0;
                scriptBtnsDirty = true;
            }
        }
        if (++scriptRefreshTick >= 10) {
            scriptRefreshTick = 0;
            refreshScripts();
        }
    }

    /** Called once per frame from PeripheralScreen.render() when on Scripts tab. */
    void flushDirty() {
        if (scriptBtnsDirty) {
            scriptBtnsDirty = false;
            rebuildScriptBtns();
        }
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;

        // Delete confirm dialog
        if (deleteConfirmPath != null) {
            boolean isFolder = Files.isDirectory(ScriptRunner.SCRIPTS_DIR.resolve(deleteConfirmPath));
            int dlgH = isFolder ? DLG_H_FOLDER : DLG_H;
            int dlgX = px + (PeripheralScreen.W - DLG_W) / 2;
            int dlgY = py + (PeripheralScreen.H - dlgH) / 2;
            double cx = mouseX, cy = mouseY;

            int cfmX = dlgX + 8, cfmY = dlgY + dlgH - 18, cfmW = 78, cfmH = 14;
            if (cx >= cfmX && cx < cfmX + cfmW && cy >= cfmY && cy < cfmY + cfmH) {
                deleteScript(deleteConfirmPath);
                deleteConfirmPath = null;
                host.rebuild();
                return true;
            }
            int cclX = dlgX + DLG_W - 8 - 72, cclY = cfmY, cclW = 72, cclH = 14;
            if (cx >= cclX && cx < cclX + cclW && cy >= cclY && cy < cclY + cclH) {
                deleteConfirmPath = null;
                host.rebuild();
                return true;
            }
            if (cx < dlgX || cx >= dlgX + DLG_W || cy < dlgY || cy >= dlgY + dlgH) {
                deleteConfirmPath = null;
                host.rebuild();
            }
            return true;
        }
        return false;
    }

    boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        int rowH = 18;
        boolean hasParent = !currentFolder.toString().isEmpty();
        int totalItems = (hasParent ? 1 : 0) + scripts.size();
        int maxRows    = (contentH - PeripheralScreen.BREAD_H - 4) / rowH;
        int maxScr     = Math.max(0, totalItems - maxRows);
        scrollPx        = Math.max(0, Math.min(scrollPx - vAmt * (rowH * 0.5), maxScr * rowH));
        scroll          = (int)(scrollPx / rowH);
        scriptBtnsDirty = true;
        return true;
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    void navigateInto(String folderName) {
        currentFolder = currentFolder.resolve(folderName);
        scroll = 0; scrollPx = 0;
        refreshScripts();
    }

    void navigateUp() {
        Path parent   = currentFolder.getParent();
        currentFolder = (parent == null) ? Path.of("") : parent;
        scroll = 0; scrollPx = 0;
        refreshScripts();
    }

    void refreshScripts() {
        scripts = ScriptRunner.listEntries(currentFolder);
        rebuildScriptBtns();
    }

    void resetScroll() { scroll = 0; scrollPx = 0; searchQuery = ""; }
    void resetDialogs() { deleteConfirmPath = null; }

    // ── Script file helpers ───────────────────────────────────────────────────

    private void createNewScript() {
        String name = "script.py";
        int n = 2;
        Path dir = ScriptRunner.SCRIPTS_DIR.resolve(currentFolder);
        while (Files.exists(dir.resolve(name))) name = "script_" + n++ + ".py";
        try {
            String template = "from mc import *\n\n# Your script here\nmsg(\"Hello!\")\n";
            Files.writeString(dir.resolve(name), template);
            String relPath = ScriptRunner.SCRIPTS_DIR.relativize(dir.resolve(name))
                             .toString().replace(java.io.File.separatorChar, '/');
            refreshScripts();
            host.mc().setScreen(new ScriptEditorScreen(host, relPath));
        } catch (Exception ignored) {}
    }

    private void createNewFolder() {
        String name = "folder";
        int n = 2;
        Path dir = ScriptRunner.SCRIPTS_DIR.resolve(currentFolder);
        while (Files.exists(dir.resolve(name))) name = "folder_" + n++;
        try { Files.createDirectories(dir.resolve(name)); refreshScripts(); }
        catch (Exception ignored) {}
    }

    void deleteScript(String relPath) {
        Path target = ScriptRunner.SCRIPTS_DIR.resolve(relPath);
        if (Files.isDirectory(target)) {
            try {
                Files.walk(target)
                    .filter(p -> !Files.isDirectory(p) && p.toString().endsWith(".py"))
                    .forEach(p -> {
                        String rel = ScriptRunner.SCRIPTS_DIR.relativize(p).toString()
                            .replace(java.io.File.separatorChar, '/');
                        ScriptRunner.stopScript(rel);
                        PeripheralHud.clearIfOwner(rel);
                        BuildSession.deleteChatFor(rel);
                        host.buildTab.clearIfDeleted(rel);
                    });
                Files.walk(target)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
            } catch (Exception ignored) {}
        } else {
            ScriptRunner.stopScript(relPath);
            PeripheralHud.clearIfOwner(relPath);
            BuildSession.deleteChatFor(relPath);
            host.buildTab.clearIfDeleted(relPath);
            try { Files.deleteIfExists(target); } catch (Exception ignored) {}
            try { Files.deleteIfExists(ScriptRunner.SCRIPTS_DIR.resolve(relPath + ".log")); }
            catch (Exception ignored) {}
        }
        refreshScripts();
    }

    // ── Rename overlay ────────────────────────────────────────────────────────

    void startRename(String relPath) {
        cancelRename();
        renamingScript = relPath;
        int bx  = px + PeripheralScreen.W / 2 - 90;
        int bby = contentY + contentH / 2 - 25;

        String fn = relPath.contains("/") ? relPath.substring(relPath.lastIndexOf('/') + 1) : relPath;
        renameField = new TextFieldWidget(host.tr(), bx + 4, bby + 14, 172, 14, Text.empty());
        renameField.setMaxLength(64);
        renameField.setFocusUnlocked(false);
        renameField.setText(fn);
        renameField.setCursorToStart(false);
        renameField.setSelectionEnd(fn.length());
        host.widgetAdd(renameField);
        host.focusOn(renameField);

        renameOkBtn     = PeripheralScreen.btn("OK",     bx + 34, bby + 33, 44, 13, x -> finishRename());
        renameCancelBtn = PeripheralScreen.btn("Cancel", bx + 84, bby + 33, 50, 13, x -> cancelRename());
        host.widgetAdd(renameOkBtn);
        host.widgetAdd(renameCancelBtn);
    }

    void cancelRename() {
        if (renameField     != null) { host.widgetRemove(renameField);     renameField     = null; }
        if (renameOkBtn     != null) { host.widgetRemove(renameOkBtn);     renameOkBtn     = null; }
        if (renameCancelBtn != null) { host.widgetRemove(renameCancelBtn); renameCancelBtn = null; }
        renamingScript = null;
    }

    private void finishRename() {
        if (renamingScript == null || renameField == null) return;
        String newName = renameField.getText().trim();
        String old = renamingScript;
        cancelRename();
        if (!newName.isEmpty()) renameScript(old, newName);
    }

    private void renameScript(String oldRelPath, String newName) {
        newName = newName.replace("/", "").replace("\\", "").replace("..", "").trim();
        if (newName.isEmpty() || newName.equals(".py")) return;
        boolean isDir = Files.isDirectory(ScriptRunner.SCRIPTS_DIR.resolve(oldRelPath));
        if (!isDir && !newName.endsWith(".py")) newName += ".py";
        String folder = oldRelPath.contains("/")
            ? oldRelPath.substring(0, oldRelPath.lastIndexOf('/') + 1) : "";
        String newRelPath = folder + newName;
        if (newRelPath.equals(oldRelPath)) return;
        Path newFile = ScriptRunner.SCRIPTS_DIR.resolve(newRelPath);
        if (Files.exists(newFile)) {
            PeripheralClient.LOGGER.warn("[Peripheral] Rename failed: {} already exists.", newName);
            return;
        }
        try {
            ScriptRunner.stopScript(oldRelPath);
            Path oldFile = ScriptRunner.SCRIPTS_DIR.resolve(oldRelPath);
            Files.move(oldFile, newFile);
            Path oldLog = ScriptRunner.SCRIPTS_DIR.resolve(oldRelPath + ".log");
            if (Files.exists(oldLog))
                Files.move(oldLog, ScriptRunner.SCRIPTS_DIR.resolve(newRelPath + ".log"));
            refreshScripts();
        } catch (Exception ignored) {}
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<ScriptRunner.ScriptInfo> filteredScripts() {
        if (searchQuery.isEmpty()) return scripts;
        return scripts.stream()
            .filter(s -> {
                String fn = s.name();
                if (fn.contains("/")) fn = fn.substring(fn.lastIndexOf('/') + 1);
                return fn.toLowerCase().contains(searchQuery);
            })
            .collect(Collectors.toList());
    }
}
