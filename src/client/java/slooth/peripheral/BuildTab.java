package slooth.peripheral;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

class BuildTab {

    private final PeripheralScreen host;
    private int px, py, contentY, contentH;

    // ── Static — survives screen open/close ──────────────────────────────────
    static BuildSession s_build        = null;
    static String       s_buildName    = "";
    static int          s_chatScroll   = 0;
    static int          s_codeScroll   = 0;
    static int          s_lastMsgCount = 0;

    // ── Per-instance (widgets, render cache) ──────────────────────────────────
    private int             chatTotalLines = 0;
    private int             codeTotalLines = 0;
    private TextFieldWidget scriptField    = null;
    private TextFieldWidget chatInput      = null;
    private ClickableWidget sendBtn        = null;
    private ClickableWidget runBtn         = null;

    // Render cache for mouseClicked hit-testing
    private int[] chatFlatMsgIdx = new int[0];
    private int   chatStartLine  = 0;
    private int   chatPanelY0    = 0;
    private int   chatLineH      = 9;
    private int   chatX0         = 0;
    private int   chatX1         = 0;
    private int   hoveredMsg     = -1;
    private int   copiedTick     = 0;

    BuildTab(PeripheralScreen host) { this.host = host; }

    // ── Build ─────────────────────────────────────────────────────────────────

    void build(int px, int py, int contentY, int contentH) {
        this.px = px; this.py = py; this.contentY = contentY; this.contentH = contentH;
        int divX = px + PeripheralScreen.BUILD_DIV_X;

        // ── Top bar: Script field + Open + Clear ──────────────────────────────
        scriptField = new TextFieldWidget(host.tr(),
            px + 50, contentY + 1, PeripheralScreen.W - 146, 12, Text.empty());
        scriptField.setMaxLength(64);
        scriptField.setPlaceholder(Text.literal("my_script.py"));
        scriptField.setText(s_buildName);
        scriptField.setFocusUnlocked(true);
        host.widgetAdd(scriptField);

        host.widgetAdd(PeripheralScreen.btn("Open",  px + PeripheralScreen.W - 90, contentY + 1, 40, 12,
            b -> openPicker()));
        host.widgetAdd(PeripheralScreen.btn("Clear", px + PeripheralScreen.W - 46, contentY + 1, 42, 12,
            b -> { if (s_build != null) { s_build.clearChat(); s_chatScroll = 0; } }));

        // ── Right panel header: Run (toggle) + Fix with AI ────────────────────
        int hdrY = contentY + PeripheralScreen.BUILD_TOP_H + 2;
        runBtn = PeripheralScreen.btn("Run", divX + 4, hdrY, 40, 10, b -> toggleScript());
        host.widgetAdd(runBtn);
        ClickableWidget fixBtn = PeripheralScreen.btn("Fix", px + PeripheralScreen.W - 34, hdrY, 30, 10,
            b -> sendLogToAI());
        fixBtn.setTooltip(Tooltip.of(Text.literal(
            "Reads the script's log file and sends the last 60 lines to the AI to identify and fix errors.")));
        host.widgetAdd(fixBtn);

        // ── Bottom strip: chat input + Send ───────────────────────────────────
        int by = py + PeripheralScreen.H - PeripheralScreen.BH + 5;
        chatInput = new TextFieldWidget(host.tr(), px + 4, by, PeripheralScreen.W - 52, 14, Text.empty());
        chatInput.setMaxLength(2000);
        chatInput.setPlaceholder(Text.literal("Describe what you want or ask for changes..."));
        chatInput.setFocusUnlocked(true);
        host.widgetAdd(chatInput);

        sendBtn = PeripheralScreen.btn("Send", px + PeripheralScreen.W - 46, by - 1, 42, 16,
            b -> sendMessage());
        sendBtn.active = s_build == null || !s_build.busy;
        host.widgetAdd(sendBtn);
    }

    // ── Render ────────────────────────────────────────────────────────────────

    void render(DrawContext ctx, int mx, int my) {
        int divX  = px + PeripheralScreen.BUILD_DIV_X;
        int lineH = 9;

        int topSep  = contentY + PeripheralScreen.BUILD_TOP_H;
        int hdrY    = topSep + 1;
        int hdrSep  = hdrY + PeripheralScreen.BUILD_HDR_H;
        int panelY0 = hdrSep + 1;
        int panelY1 = contentY + contentH;

        int chatX0 = px + 1,           chatX1 = divX;
        int codeX0 = divX + 1,         codeX1 = px + PeripheralScreen.W - 1;
        int chatW  = chatX1 - chatX0;

        // ── Structural lines ──────────────────────────────────────────────────
        ctx.drawText(host.tr(), Text.literal("Script:"), px + 4, contentY + 3, PeripheralScreen.C_GREY, false);
        ctx.fill(px + 1, topSep,  px + PeripheralScreen.W - 1, topSep + 1, PeripheralScreen.C_BORDER);
        ctx.fill(divX,   hdrY,    divX + 1, panelY1,            PeripheralScreen.C_BORDER);
        ctx.fill(px + 1, hdrSep,  px + PeripheralScreen.W - 1, hdrSep + 1, PeripheralScreen.C_BORDER);

        ctx.drawText(host.tr(), Text.literal("CHAT"), chatX0 + 3, hdrY + 2, PeripheralScreen.C_ORANGE, false);
        ctx.drawText(host.tr(), Text.literal("CODE"), codeX0 + 3, hdrY + 2, PeripheralScreen.C_ORANGE, false);

        if (s_build != null) {
            boolean running = ScriptRunner.isRunning(s_build.getScriptRelPath());
            ctx.drawText(host.tr(), Text.literal(running ? "§a●" : "§7○"),
                codeX0 + 3 + host.tr().getWidth("CODE") + 5, hdrY + 2, PeripheralScreen.C_WHITE, false);
        }

        int visLines = Math.max(1, (panelY1 - panelY0) / lineH);

        // ── No session: placeholder hints ─────────────────────────────────────
        if (s_build == null) {
            ctx.enableScissor(chatX0, panelY0, chatX1, panelY1);
            int ty = panelY0 + 3;
            for (String hint : new String[]{
                    "Type a script name and press Open.",
                    "Chat history reloads each session.",
                    "AI writes code directly to the file.",
                    "Use Fix after running to send errors."}) {
                for (String wl : wrapTextPx(hint, chatW - 8)) {
                    ctx.drawText(host.tr(), Text.literal("§7" + wl), chatX0 + 4, ty, PeripheralScreen.C_DIM, false);
                    ty += lineH;
                }
                ty += 2;
            }
            ctx.disableScissor();
            return;
        }

        // ── Chat: build flat line list ────────────────────────────────────────
        int chatWrapW = chatW - 12;
        List<String>  flatLines   = new ArrayList<>();
        List<Integer> flatColors  = new ArrayList<>();
        List<Integer> flatMsgIdxL = new ArrayList<>();

        List<BuildSession.ChatMessage> msgs = s_build.getMessages();
        for (int mi = 0; mi < msgs.size(); mi++) {
            BuildSession.ChatMessage m = msgs.get(mi);
            boolean isUser = m.role().equals("user");
            flatLines.add(isUser ? "§eYOU" : "§bAI");
            flatColors.add(isUser ? PeripheralScreen.C_YELLOW : PeripheralScreen.C_CYAN);
            flatMsgIdxL.add(mi);
            boolean inCode = false;
            int codeCount  = 0;
            for (String raw : m.content().split("\n", -1)) {
                if (raw.startsWith("```")) {
                    if (!inCode) { inCode = true; codeCount = 0; }
                    else {
                        inCode = false;
                        if (codeCount > 0) {
                            flatLines.add("  §7[" + codeCount + "L written to script]");
                            flatColors.add(PeripheralScreen.C_DIM);
                            flatMsgIdxL.add(mi);
                        }
                    }
                    continue;
                }
                if (inCode) { codeCount++; continue; }
                if (raw.isBlank()) {
                    flatLines.add(""); flatColors.add(0); flatMsgIdxL.add(mi); continue;
                }
                for (String wl : wrapTextPx(raw, chatWrapW)) {
                    flatLines.add("  " + wl);
                    flatColors.add(PeripheralScreen.C_WHITE);
                    flatMsgIdxL.add(mi);
                }
            }
            flatLines.add(""); flatColors.add(0); flatMsgIdxL.add(-1);
        }
        if (s_build.busy) {
            flatLines.add("  §7Thinking…"); flatColors.add(PeripheralScreen.C_GREY); flatMsgIdxL.add(-1);
        }
        if (!s_build.status.isEmpty() && !s_build.busy) {
            for (String wl : wrapTextPx(s_build.status, chatWrapW)) {
                flatLines.add("  §c" + wl); flatColors.add(PeripheralScreen.C_RED); flatMsgIdxL.add(-1);
            }
        }

        // Cache render state for mouseClicked
        chatFlatMsgIdx  = flatMsgIdxL.stream().mapToInt(Integer::intValue).toArray();
        chatLineH       = lineH;
        chatPanelY0     = panelY0;
        this.chatX0     = chatX0;
        this.chatX1     = chatX1;
        chatTotalLines  = flatLines.size();

        int chatMaxScr  = Math.max(0, chatTotalLines - visLines);
        s_chatScroll    = Math.max(0, Math.min(s_chatScroll, chatMaxScr));
        int startLine   = Math.max(0, chatTotalLines - visLines - s_chatScroll);
        chatStartLine   = startLine;

        // Determine hovered message
        int relLine = ((int) my - panelY0) / lineH;
        int absLine = startLine + relLine;
        hoveredMsg = ((int) mx >= chatX0 && (int) mx < chatX1 - 3
                && relLine >= 0 && relLine < visLines && absLine < chatFlatMsgIdx.length)
            ? chatFlatMsgIdx[absLine] : -1;

        ctx.enableScissor(chatX0, panelY0, chatX1, panelY1);
        for (int i = startLine; i < Math.min(flatLines.size(), startLine + visLines); i++) {
            int ry = panelY0 + 2 + (i - startLine) * lineH;
            if (hoveredMsg >= 0 && i < chatFlatMsgIdx.length && chatFlatMsgIdx[i] == hoveredMsg)
                ctx.fill(chatX0, ry - 1, chatX1 - 3, ry + lineH - 1, 0x22FFFFFF);
            String line = flatLines.get(i);
            if (!line.isEmpty())
                ctx.drawText(host.tr(), Text.literal(line), chatX0 + 4, ry, flatColors.get(i), false);
        }
        if (copiedTick > 0)
            ctx.drawCenteredTextWithShadow(host.tr(), Text.literal("§aCopied!"),
                (chatX0 + chatX1) / 2, panelY1 - lineH - 2, PeripheralScreen.C_GREEN);
        ctx.disableScissor();

        if (chatMaxScr > 0) {
            int sbH  = panelY1 - panelY0;
            int barH = Math.max(8, sbH * visLines / Math.max(chatTotalLines, 1));
            int barY = panelY0 + (int) ((sbH - barH) * (1f - (float) s_chatScroll / chatMaxScr));
            ctx.fill(chatX1 - 3, panelY0, chatX1, panelY1, PeripheralScreen.C_SCROLL);
            ctx.fill(chatX1 - 3, barY,    chatX1, barY + barH, PeripheralScreen.C_SCROLL_THUMB);
        }

        // ── Code panel ────────────────────────────────────────────────────────
        String   code    = s_build.getCode();
        String[] codeArr = code.isEmpty()
            ? new String[]{"§7(send a message to generate code)"}
            : code.split("\n", -1);

        codeTotalLines = codeArr.length;
        int codeMaxScr = Math.max(0, codeArr.length - visLines);
        s_codeScroll   = Math.max(0, Math.min(s_codeScroll, codeMaxScr));

        ctx.enableScissor(codeX0, panelY0, codeX1, panelY1);
        for (int i = s_codeScroll; i < Math.min(codeArr.length, s_codeScroll + visLines); i++)
            ctx.drawText(host.tr(), Text.literal(codeArr[i]),
                codeX0 + 4, panelY0 + 2 + (i - s_codeScroll) * lineH, 0xFF88CC88, false);
        ctx.disableScissor();

        if (codeMaxScr > 0) {
            int sbH  = panelY1 - panelY0;
            int barH = Math.max(8, sbH * visLines / Math.max(codeArr.length, 1));
            int barY = panelY0 + (int) ((sbH - barH) * ((float) s_codeScroll / codeMaxScr));
            ctx.fill(codeX1 - 3, panelY0, codeX1, panelY1, PeripheralScreen.C_SCROLL);
            ctx.fill(codeX1 - 3, barY,    codeX1, barY + barH, PeripheralScreen.C_SCROLL_THUMB);
        }

        if (s_build.busy)
            ctx.drawText(host.tr(), Text.literal("§7Thinking…"),
                px + 4, py + PeripheralScreen.H - PeripheralScreen.BH + 5, PeripheralScreen.C_GREY, false);
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    void tick() {
        if (sendBtn != null) sendBtn.active = (s_build == null || !s_build.busy);
        if (copiedTick > 0) copiedTick--;
        if (s_build != null) {
            if (runBtn != null) {
                boolean running = ScriptRunner.isRunning(s_build.getScriptRelPath());
                runBtn.setMessage(Text.literal(running ? "Running" : "Run"));
            }
            int msgCount = s_build.getMessages().size();
            if (msgCount != s_lastMsgCount) {
                s_lastMsgCount = msgCount;
                s_chatScroll   = 0;
                s_codeScroll   = 0;
            }
        }
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || s_build == null) return false;
        double mx = mouseX, my = mouseY;
        if (mx >= chatX0 && mx < chatX1 - 3) {
            int relLine = ((int) my - chatPanelY0) / chatLineH;
            int absLine = chatStartLine + relLine;
            if (relLine >= 0 && absLine < chatFlatMsgIdx.length) {
                int msgIdx = chatFlatMsgIdx[absLine];
                if (msgIdx >= 0 && msgIdx < s_build.getMessages().size()) {
                    host.mc().keyboard.setClipboard(s_build.getMessages().get(msgIdx).content());
                    copiedTick = 40;
                    return true;
                }
            }
        }
        return false;
    }

    boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        int lineH  = 9;
        int panelH = contentY + contentH - (contentY + PeripheralScreen.BUILD_TOP_H + PeripheralScreen.BUILD_HDR_H + 2);
        int vis    = Math.max(1, panelH / lineH);
        if (mx < px + PeripheralScreen.BUILD_DIV_X) {
            int maxScr = Math.max(0, chatTotalLines - vis);
            s_chatScroll = (int) Math.max(0, Math.min(s_chatScroll - vAmt, maxScr));
        } else {
            int maxScr = Math.max(0, codeTotalLines - vis);
            s_codeScroll = (int) Math.max(0, Math.min(s_codeScroll - vAmt, maxScr));
        }
        return true;
    }

    void resetScrolls() { s_chatScroll = 0; s_codeScroll = 0; }

    // ── Session management ────────────────────────────────────────────────────

    void openSession(String name) {
        if (name == null || name.isBlank()) name = "my_script.py";
        if (!name.endsWith(".py")) name += ".py";
        s_buildName = name;
        if (scriptField != null) scriptField.setText(name);
        if (s_build == null || !s_build.getScriptRelPath().equals(name)) {
            s_build        = new BuildSession(name);
            s_chatScroll   = 0;
            s_codeScroll   = 0;
            s_lastMsgCount = s_build.getMessages().size();
        }
        if (chatInput != null) chatInput.setFocusUnlocked(true);
        if (sendBtn   != null) sendBtn.active = !s_build.busy;
    }

    /** Called by ScriptsTab.deleteScript() when the open session's script is deleted. */
    void clearIfDeleted(String relPath) {
        if (s_build != null && s_build.getScriptRelPath().equals(relPath)) {
            s_build        = null;
            s_buildName    = "";
            s_lastMsgCount = 0;
        }
    }

    void sendText(String text) {
        if (s_build == null || s_build.busy || text.isEmpty()) return;
        if (PeripheralConfig.apiKey.isEmpty()) {
            s_build.status = "No API key set. Add one in the Settings tab.";
            return;
        }
        if (sendBtn != null) sendBtn.active = false;
        s_build.send(text, () -> { s_chatScroll = 0; s_codeScroll = 0; });
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void openPicker() {
        host.mc().setScreen(new BuildPickerScreen(host,
            name -> { openSession(name); host.mc().setScreen(host); },
            name -> {
                String unique = uniqueScriptName(name);
                openSession(unique);
                if (s_build != null) { s_build.clearChat(); s_lastMsgCount = 0; }
                host.mc().setScreen(host);
            }
        ));
    }

    private String uniqueScriptName(String name) {
        if (name == null || name.isBlank()) name = "my_script.py";
        if (!name.endsWith(".py")) name += ".py";
        String base = name.substring(0, name.length() - 3);
        String candidate = name;
        int n = 1;
        while (Files.exists(ScriptRunner.SCRIPTS_DIR.resolve(candidate)))
            candidate = base + "_" + n++ + ".py";
        return candidate;
    }

    private void sendMessage() {
        if (chatInput == null) return;
        String text = chatInput.getText().trim();
        if (text.isEmpty()) return;
        if (s_build == null) {
            String name = scriptField != null ? scriptField.getText().trim() : s_buildName;
            openSession(name);
            if (s_build == null) return;
        }
        chatInput.setText("");
        sendText(text);
    }

    private void toggleScript() {
        if (s_build == null) return;
        String rel = s_build.getScriptRelPath();
        if (ScriptRunner.isRunning(rel)) {
            ScriptRunner.stopScript(rel);
        } else {
            if (!Files.exists(ScriptRunner.SCRIPTS_DIR.resolve(rel))) return;
            ScriptRunner.runScript(rel);
        }
    }

    private void sendLogToAI() {
        if (s_build == null) return;
        java.nio.file.Path logPath = ScriptRunner.SCRIPTS_DIR.resolve(
            s_build.getScriptRelPath() + ".log");
        String logText = "";
        try {
            if (Files.exists(logPath)) logText = Files.readString(logPath).strip();
        } catch (Exception ignored) {}
        if (logText.isEmpty()) {
            if (chatInput != null)
                chatInput.setText("Run the script first to generate a log.");
            return;
        }
        String[] lines = logText.split("\n", -1);
        int from = Math.max(0, lines.length - 60);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) sb.append(lines[i]).append('\n');
        sendText("I ran the script and got this output:\n```\n"
            + sb.toString().strip() + "\n```\nPlease identify any errors and fix them.");
    }

    private List<String> wrapTextPx(String text, int maxPx) {
        List<String> out = new ArrayList<>();
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String w : words) {
            String test = line.length() > 0 ? line + " " + w : w;
            if (host.tr().getWidth(test) > maxPx && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder(w);
            } else {
                if (line.length() > 0) line.append(' ');
                line.append(w);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }
}
