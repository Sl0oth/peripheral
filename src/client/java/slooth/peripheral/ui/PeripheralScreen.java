package slooth.peripheral.ui;

import slooth.peripheral.PeripheralClient;
import slooth.peripheral.api.PeripheralMcp;
import slooth.peripheral.hud.PeripheralHud;
import dev.pane.ui.*;
import slooth.peripheral.script.ScriptRunner;
import slooth.peripheral.util.PeripheralConfig;
import slooth.peripheral.util.PeripheralStateTracker;
import slooth.peripheral.record.Recorder;
import slooth.peripheral.record.Recording;

import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

import java.awt.Font;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static slooth.peripheral.ui.PeripheralUi.*;

/**
 * Peripheral control panel — press <b>G</b> in-game to open.
 *
 * <p>Seven tabs, painted on one retained Pane surface:
 * <ul>
 *   <li><b>Scripts</b>  – the home tab ({@link ScriptsPanel}): a library of folders and scripts
 *       beside the selected script's Run/Stop, Edit, auto-run, hotkey and live output.</li>
 *   <li><b>Macros</b>   – record what you do and play it back exactly ({@link MacrosPanel}).</li>
 *   <li><b>Log</b>      – every line from running scripts and the external agent, filtered by
 *       source, with the agent's status, task box and Stop underneath.</li>
 *   <li><b>Build</b>    – AI-assisted script building for one session file.</li>
 *   <li><b>HUD</b>      – which scripts are drawing on the HUD, with Move HUD (PaneLib's
 *       {@link UiHudEditor}), and per-script Clear and Reset positions.</li>
 *   <li><b>Settings</b> – sections for AI (API key, model), the external agent's URL, MCP and
 *       the HUD theme. Each section with fields has its own Save.</li>
 * </ul>
 *
 * <p>Auto-run modes (per script, chosen from the Scripts tab): off, singleplayer worlds,
 * multiplayer servers, or any world.
 *
 * <p>Data is polled on ticks and the surface repaints only when a visible value changes; folder
 * listings are read off the render thread.
 */
public class PeripheralScreen extends UiScreen {

    private static final int TAB_SCRIPTS = 0, TAB_MACROS = 1, TAB_LOG = 2, TAB_BUILD = 3, TAB_HUD = 4, TAB_SETTINGS = 5;
    private static final String[] TAB_LABELS = {"Scripts", "Macros", "Log", "Build", "HUD", "Settings"};
    private static final Set<String> TEXT_FIELDS = Set.of("agent-task", "search", "api-key", "agent-url", "model", "build-chat");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    // ── State ─────────────────────────────────────────────────────────────────
    private int currentTab = TAB_SCRIPTS;
    private boolean initialized, closed;
    private int tickCount;
    private String statusSig = "", logSig = "", hudSig = "";

    // Log
    private static final String[] LOG_FILTERS = {"All", "Scripts", "Agent"};
    private static int s_logFilter = 0;
    private boolean agentBusy;
    private String agentMessage="";
    private int copiedTick = -100;

    // Scripts
    private final ScriptsPanel scriptsPanel = new ScriptsPanel(this);
    private final MacrosPanel macrosPanel = new MacrosPanel(this);
    private boolean renamingMacro;
    private Recording trimming;

    // Dialogs
    private String renamingScript = null;         // null = rename sheet closed

    // Settings
    private static final int SEC_AI = 0, SEC_AGENT = 1, SEC_MCP = 2, SEC_LOOK = 3;
    private static final String[] SECTIONS = {"AI", "Agent", "MCP", "Appearance"};
    private static final UiIcon[] SECTION_ICONS = {UiIcon.STAR, UiIcon.SEND, UiIcon.TERMINAL, UiIcon.TUNE};
    private static int s_section = SEC_AI;
    private String saveMsg = "";
    private int saveTick = 0, savedSection = -1;
    private boolean apiKeyRevealed;

    // ── Build tab persistent state (static — survives screen open/close) ─────
    private static BuildSession s_build        = null;
    private static String       s_buildName    = "";
    private static boolean      s_codeReset    = false; // scroll code back to the top on next tick
    private static int          s_lastMsgCount = 0;
    private static int          s_currentTab   = TAB_SCRIPTS;
    private static boolean      s_showCode     = false; // narrow layout: which Build pane is shown

    private String buildCode = "";
    private String buildSig = "";
    private boolean lastBusy;
    private String buildNote = "";

    public PeripheralScreen() {
        this(new Ref<>());
    }

    private PeripheralScreen(Ref<PeripheralScreen> ref) {
        super(PeripheralUi.document("Peripheral", ui -> ref.v.paint(ui)), NativeOverlayRenderer.NONE,
            (UiEventHandler) e -> ref.v.onEvent(e),
            UiScreenOptions.builder().backdrop(UiBackdrop.NONE)
                .onKey((s, e) -> ref.v.onKey(e)).build());
        ref.v = this;
    }

    // ── Init / tab switching ──────────────────────────────────────────────────

    @Override
    protected void init() {
        super.init();
        closed = false;
        if (!initialized) {
            initialized = true;
            currentTab = Math.max(0, Math.min(TAB_LABELS.length - 1, s_currentTab));
            seedSettings();
            if (s_build != null) buildCode = s_build.getCode();
        }
        scriptsPanel.opened();
    }

    private void switchTab(int tab) {
        currentTab   = tab;
        s_currentTab = tab;
        cancelRename();
        if (tab == TAB_LOG) scrollToEnd("log");
        if (tab == TAB_SCRIPTS) scriptsPanel.refresh();
        if (tab == TAB_MACROS) macrosPanel.refresh();
        if (tab == TAB_SETTINGS) seedSettings();
        if (tab == TAB_BUILD) { scrollToEnd("chat"); if (s_build != null) buildCode = s_build.getCode(); }
        invalidate();
    }

    private void seedSettings() {
        apiKeyRevealed = false;
        setValue("api-key", PeripheralConfig.apiKey);
        setValue("agent-url", PeripheralConfig.agentUrl);
        setValue("model", PeripheralConfig.model);
        setValue("mcp-enabled", PeripheralConfig.mcpEnabled);
        setValue("theme", PeripheralConfig.theme);
    }

    /** Opens Settings on one section, such as Agent from the Log tab's Change button. */
    void openSettings(int section) {
        s_section = section;
        switchTab(TAB_SETTINGS);
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        super.tick();
        tickCount++;
        if (saveTick > 0 && --saveTick == 0) invalidate();
        // Pick up file access requests from running scripts; one waits while another prompt is open.
        String far = ScriptRunner.fileAccessRequestScript;
        if (far != null && openDialogId().isEmpty() && renamingScript == null) {
            ScriptRunner.fileAccessRequestScript = null;
            showDialog("file-access", new UiDialog("File access requested",
                basename(far) + " asked to use Peripheral's file helpers. Allow lets it read and write any file on your computer through them. "
                + "Scripts are ordinary Python running with your account's access; this does not sandbox them. Only allow scripts you fully trust.",
                "Allow", "fa-allow:" + far, "Deny", "fa-deny", DANGER_DIALOG));
        }
        String st = PeripheralStateTracker.status + "#" + PeripheralStateTracker.iterations;
        if (!st.equals(statusSig)) { statusSig = st; invalidate(); }
        if (currentTab == TAB_LOG) {
            List<String> log = PeripheralStateTracker.getLog(LOG_LINES);
            String sig = log.size() + ":" + log.hashCode() + ":" + (tickCount - copiedTick < 40);
            if (!sig.equals(logSig)) { logSig = sig; invalidate(); }
        }
        if (currentTab == TAB_SCRIPTS) scriptsPanel.tick();
        if (currentTab == TAB_MACROS) macrosPanel.tick();
        if (currentTab == TAB_HUD) {
            StringBuilder sig = new StringBuilder();
            PeripheralHud.owners().forEach((owner, n) -> sig.append(owner).append('=').append(n)
                .append(PeripheralHud.hasLayout(owner)).append(ScriptRunner.isRunning(owner)).append(';'));
            if (!sig.toString().equals(hudSig)) { hudSig = sig.toString(); invalidate(); }
        }
        if (currentTab == TAB_BUILD) buildTick();
    }

    private void buildTick() {
        if (s_codeReset) { s_codeReset = false; scrollTo("code", 0); }
        if (s_build == null) return;
        int msgCount = s_build.getMessages().size();
        boolean busy = s_build.busy;
        boolean reload = msgCount != s_lastMsgCount || busy != lastBusy || tickCount % 40 == 0;
        if (msgCount != s_lastMsgCount) { s_lastMsgCount = msgCount; scrollToEnd("chat"); s_codeReset = true; }
        lastBusy = busy;
        if (reload) buildCode = s_build.getCode(); // small local file; at most every 2 s while idle
        String sig = msgCount + "|" + busy + "|" + s_build.status + "|" + ScriptRunner.isRunning(s_build.getScriptRelPath())
            + "|" + buildCode.hashCode() + "|" + buildCode.length();
        if (!sig.equals(buildSig)) { buildSig = sig; invalidate(); }
    }

    // ── Events ────────────────────────────────────────────────────────────────

    private void onEvent(UiEvent e) {
        if(e.type()==UiEventType.VALUE&&scriptsPanel.event(e))return;
        if (e.type() == UiEventType.VALUE) {
            switch (e.key()) {
                case "mcp-enabled" -> { PeripheralConfig.mcpEnabled = booleanValue("mcp-enabled", false); PeripheralConfig.save(); PeripheralMcp.configure(); }
                case "theme" -> { PeripheralConfig.theme = e.value(); PeripheralConfig.save(); }
                default -> {}
            }
            invalidate();
            return;
        }
        switch (e.type()) {
            case SUBMIT -> {
                switch (e.key()) {
                    case "rename-value" -> finishRename();
                    case "agent-task" -> sendTask();
                    case "build-chat" -> sendBuildMessage();
                    case "api-key", "model" -> saveSettings(SEC_AI);
                    case "agent-url" -> saveSettings(SEC_AGENT);
                    default -> {}
                }
            }
            case ACTION -> onAction(e.key());
            default -> {}
        }
    }

    /** A sheet or dialog is up; PaneLib routes input only to it. */
    private boolean modalOpen() { return renamingScript != null || trimming != null || openDialogId().isPresent(); }

    private void onAction(String a) {
        if (a.equals("noop")) return;
        if (currentTab == TAB_SCRIPTS && scriptsPanel.handle(a)) { invalidate(); return; }
        if (currentTab == TAB_MACROS && macrosPanel.handle(a)) { invalidate(); return; }
        int colon = a.indexOf(':');
        String verb = colon < 0 ? a : a.substring(0, colon), arg = colon < 0 ? "" : a.substring(colon + 1);
        switch (verb) {
            case "close" -> onClose();
            case "tab" -> switchTab(Integer.parseInt(arg));
            // Log
            case "log-filter" -> { s_logFilter = Integer.parseInt(arg); scrollToEnd("log"); }
            case "log-copy" -> copyLog();
            case "log-clear" -> { PeripheralStateTracker.clearLog(); scrollToEnd("log"); }
            case "agent-send" -> sendTask();
            case "agent-stop" -> sendStop();
            case "agent-settings" -> openSettings(SEC_AGENT);
            case "agent-pill" -> { s_logFilter = 2; switchTab(TAB_LOG); }
            // Scripts
            case "edit" -> { if (!arg.isEmpty()) minecraft.gui.setScreen(new ScriptEditorScreen(this, arg)); }
            case "rename" -> startRename(arg);
            case "rename-ok" -> finishRename();
            case "rename-cancel" -> cancelRename();
            case "del" -> askDelete(arg);
            case "build" -> { openBuildSession(arg); switchTab(TAB_BUILD); }
            case "del-confirm" -> deleteScript(arg);
            case "macro-rename" -> startRenameMacro(arg);
            case "macro-trim" -> startTrim(arg);
            case "trim-ok" -> finishTrim();
            case "trim-cancel" -> cancelTrim();
            case "macro-del" -> showDialog("delete", new UiDialog("Delete macro?", arg + " will be deleted. This cannot be undone.",
                "Delete", "macro-del-confirm:" + arg, "Cancel", "del-cancel", DANGER_DIALOG));
            case "macro-del-confirm" -> {
                try { Recording.delete(arg); macrosPanel.say("Deleted " + arg + "."); }
                catch (Exception e) { macrosPanel.say("Couldn't delete " + arg + ": " + e.getMessage()); }
                macrosPanel.refresh();
            }
            case "fa-allow" -> PeripheralConfig.setFileAccess(arg, true);
            case "new-script" -> createNewScript();
            case "new-folder" -> createNewFolder();
            case "hud-edit" -> minecraft.gui.setScreen(new UiHudEditor(this, THEME));
            case "hud-clear" -> PeripheralHud.clearElements();
            case "hud-clear-owner" -> PeripheralHud.clearIfOwner(arg);
            case "hud-reset" -> PeripheralHud.resetLayout(arg);
            case "hud-theme" -> openSettings(SEC_LOOK);
            // Settings
            case "key-toggle" -> apiKeyRevealed = !apiKeyRevealed;
            case "settings-section" -> s_section = Integer.parseInt(arg);
            case "settings-save" -> saveSettings(SEC_AI);
            case "agent-save" -> saveSettings(SEC_AGENT);
            case "mcp-copy" -> { Minecraft.getInstance().keyboardHandler.setClipboard(PeripheralMcp.connectionDetails()); saveMsg = "Copied"; saveTick = 40; savedSection = SEC_MCP; }
            // Build
            case "build-open" -> openBuildPicker();
            case "build-clear" -> { if (s_build != null) { s_build.clearChat(); scrollToEnd("chat"); } }
            case "build-run" -> toggleBuildScript();
            case "build-edit" -> { if (s_build != null) minecraft.gui.setScreen(new ScriptEditorScreen(this, s_build.getScriptRelPath())); }
            case "build-copy" -> copyReply(Integer.parseInt(arg));
            case "build-fix" -> sendLogToAI();
            case "build-send" -> sendBuildMessage();
            case "build-settings" -> openSettings(SEC_AI);
            default -> {}
        }
        invalidate();
    }

    private boolean onKey(KeyEvent e) {
        if (e.key() == GLFW.GLFW_KEY_G && !modalOpen() && !TEXT_FIELDS.contains(focusedId().orElse(""))) {
            onClose();
            return true;
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Paint
    // ══════════════════════════════════════════════════════════════════════════

    private void paint(UiContext ui) {
        var c = ui.canvas();
        float sw = ui.width(), sh = ui.height();
        boolean compact = sw < 620 || sh < 420, roomy = sw >= 1100 && sh >= 640;
        float edge = compact ? 6 : 12, pad = roomy ? 22 : compact ? 12 : 16;
        float fx = edge, fy = edge, fw = sw - edge * 2, fh = sh - edge * 2;
        frame(c, fx, fy, fw, fh);
        float cx = fx + pad, cw = fw - pad * 2, top = fy + pad;

        // Header: title, tabs, Close. One row when it fits, otherwise tabs drop below the title.
        float closeW = btnW(c, "Close", G_CLOSE, 11);
        action(ui, "close", "Close", "close", G_CLOSE, cx + cw - closeW, top, closeW, 28, SECONDARY, TEXT, 11, true);
        String agent = agentPill();
        float pillW = agent == null ? 0 : ui.buttonWidth(agent, null, CHIP_ON) + 14;
        float titleW = c.textWidth("Peripheral", 20, Font.BOLD, SANS);
        float[] tabW = new float[TAB_LABELS.length];
        float tabsW = 0;
        for (int i = 0; i < tabW.length; i++) { tabW[i] = btnW(c, TAB_LABELS[i], G_NONE, 11); tabsW += tabW[i] + (i > 0 ? 4 : 0); }
        boolean oneRow = titleW + 24 + tabsW + 16 + closeW + (pillW > 0 ? pillW + 8 : 0) <= cw;
        c.text("Peripheral", cx, top + 2, 20, TEXT, Font.BOLD, SANS);
        float tx = oneRow ? cx + titleW + 24 : cx, ty = oneRow ? top : top + 36;
        for (int i = 0; i < tabW.length; i++) {
            boolean on = i == currentTab;
            action(ui, "tab:" + i, TAB_LABELS[i], "tab:" + i, G_NONE, tx, ty, tabW[i], 28, on ? CHIP_ON : CHIP, on ? LIME : TEXT, 11, true);
            tx += tabW[i] + 4;
        }
        if (agent != null) {
            // Only while the agent is doing something: where it is, and a way to its log.
            float px = cx + cw - closeW - 8 - pillW;
            if (px > (oneRow ? tx : cx + titleW + 16)) {
                ui.button("agent-pill", agent, null, "agent-pill", px, top, pillW, 28, CHIP_ON, true);
                c.circle(statusColor(PeripheralStateTracker.status), px + 11, top + 14, 3.5F);
            }
        }
        float y = ty + 36;
        float bottom = fy + fh - pad;
        switch (currentTab) {
            case TAB_LOG -> paintLog(ui, cx, y, cw, bottom - y);
            case TAB_SCRIPTS -> paintScripts(ui, cx, y, cw, bottom - y);
            case TAB_MACROS -> macrosPanel.paint(ui, cx, y, cw, bottom - y);
            case TAB_SETTINGS -> paintSettings(ui, cx, y, cw, bottom - y);
            case TAB_BUILD -> paintBuild(ui, cx, y, cw, bottom - y);
            case TAB_HUD -> paintHud(ui, cx, y, cw, bottom - y);
            default -> {}
        }
        paintDialogs(ui);
    }

    // ── Log ──────────────────────────────────────────────────────────────────

    private static final int LOG_LINES = 200;

    /**
     * Log: every line from scripts and the external agent in one following PaneLib text view, so
     * it selects, copies and opens links natively. Chips filter it to one source; the agent's
     * status, task box and Stop sit underneath.
     */
    private void paintLog(UiContext ui, float x, float y, float w, float h) {
        var c = ui.canvas();
        // Toolbar: source filter on the left, Copy and Clear on the right.
        float tx = x;
        for (int i = 0; i < LOG_FILTERS.length; i++) {
            boolean on = i == s_logFilter;
            float fw = ui.buttonWidth(LOG_FILTERS[i], null, CHIP);
            ui.button("log-filter:" + i, LOG_FILTERS[i], null, "log-filter:" + i, tx, y, fw, 28, on ? CHIP_ON : CHIP, true);
            tx += fw + 4;
        }
        List<PeripheralStateTracker.Entry> entries = filteredLog();
        boolean copied = tickCount - copiedTick < 40, anyLog = !PeripheralStateTracker.getEntries(1).isEmpty();
        String copyLabel = copied ? "Copied" : "Copy";
        UiIcon copyIcon = copied ? UiIcon.CHECK : UiIcon.COPY;
        float clearW = ui.buttonWidth("Clear", UiIcon.TRASH, SECONDARY), copyW = ui.buttonWidth("Copied", copyIcon, SECONDARY);
        if (tx + 12 + copyW + 6 + clearW <= x + w) {
            ui.button("log-clear", "Clear", UiIcon.TRASH, "log-clear", x + w - clearW, y, clearW, 28, SECONDARY, anyLog);
            ui.button("log-copy", copyLabel, copyIcon, "log-copy", x + w - clearW - 6 - copyW, y, copyW, 28, SECONDARY, !entries.isEmpty());
        } else {
            ui.iconButton("log-clear", "Clear the log", UiIcon.TRASH, "log-clear", x + w - 28, y, 28, SECONDARY, anyLog);
            ui.iconButton("log-copy", copied ? "Copied" : "Copy what's shown", copyIcon, "log-copy", x + w - 62, y, 28, SECONDARY, !entries.isEmpty());
        }

        float cardH = 106, cardY = y + h - cardH;
        paintAgentCard(ui, x, cardY, w, cardH);

        UiRect view = new UiRect(x, y + 36, w, Math.max(48, cardY - 10 - (y + 36)));
        List<UiRichText.Span> spans = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            String line = strip(entries.get(i).text());
            spans.add(new UiRichText.Span(i + 1 < entries.size() ? line + "\n" : line, logColor(line), false, false, false, false, false));
        }
        ui.textView("log", UiRichText.of(spans), view.x(), view.y(), view.width(), view.height(), LOG_VIEW, false, UiScrollFollow.WHEN_AT_END);
        c.strokeRoundRect(LINE_SOFT, 1, view.x(), view.y(), view.width(), view.height(), 0);
        if (entries.isEmpty()) {
            String url = PeripheralConfig.agentUrl;
            switch (s_logFilter) {
                case 1 -> centered(c, "No script output yet", "Lines your scripts print show up here, and on each script in the Scripts tab.", view);
                case 2 -> centered(c, "No agent activity yet", "An external agent reports its steps here. Send it a task below once its server is running at " + url + ".", view);
                default -> centered(c, "Nothing logged yet", "Script output and agent steps show up here as they happen.", view);
            }
        }
    }

    /** The external agent: what it's doing, a task box, and Stop. */
    private void paintAgentCard(UiContext ui, float x, float y, float w, float h) {
        var c = ui.canvas();
        panel(c, x, y, w, h);
        String status = PeripheralStateTracker.status;
        boolean active = agentActive();
        float ix = x + 12, iw = w - 24, row = y + 18;
        mid(c, "Agent", ix, row, 12, TEXT, Font.BOLD, SANS);
        float sx = ix + c.textWidth("Agent", 12, Font.BOLD, SANS) + 14;
        c.circle(active ? statusColor(status) : FAINT, sx, row, 3.5F);
        String state = (status == null || status.isBlank() ? "idle" : status)
            + (PeripheralStateTracker.iterations > 0 && active ? " · step " + PeripheralStateTracker.iterations : "")
            + (active && !PeripheralStateTracker.currentTask.isBlank() ? " · " + PeripheralStateTracker.currentTask : "");
        float stopW = ui.buttonWidth("Stop", UiIcon.STOP, DANGER);
        mid(c, c.ellipsize(state, Math.max(30, x + w - 12 - stopW - 12 - (sx + 10)), 11, Font.PLAIN, SANS), sx + 10, row, 11, active ? TEXT : MUTED, Font.PLAIN, SANS);
        ui.button("agent-stop", "Stop", UiIcon.STOP, "agent-stop", x + w - 12 - stopW, y + 6, stopW, 26, active ? DANGER : SECONDARY, true);

        float fy = y + 38, sendW = ui.buttonWidth("Send", UiIcon.SEND, PRIMARY);
        ui.textField("agent-task", "agent-task", agentBusy ? "Sending…" : "Give the agent a task…", ix, fy, iw - sendW - 8, 30, "", 256);
        ui.button("agent-send", "Send", UiIcon.SEND, "agent-send", ix + iw - sendW, fy, sendW, 30, PRIMARY, !agentBusy);

        float ny = fy + 30 + 14;
        float changeW = ui.buttonWidth("Change", null, CHIP);
        String note = agentMessage.isEmpty() ? "Tasks go to the agent server at " + PeripheralConfig.agentUrl : agentMessage;
        mid(c, c.ellipsize(note, iw - changeW - 8, 10.5F, Font.PLAIN, SANS), ix, ny, 10.5F, agentMessage.isEmpty() ? FAINT : MUTED, Font.PLAIN, SANS);
        ui.button("agent-settings", "Change", null, "agent-settings", ix + iw - changeW, ny - 11, changeW, 22, CHIP, true);
    }

    private List<PeripheralStateTracker.Entry> filteredLog() {
        List<PeripheralStateTracker.Entry> all = PeripheralStateTracker.getEntries(LOG_LINES);
        if (s_logFilter == 0) return all;
        boolean agent = s_logFilter == 2;
        return all.stream().filter(e -> e.agent() == agent).toList();
    }

    private void copyLog() {
        List<PeripheralStateTracker.Entry> entries = filteredLog();
        if (entries.isEmpty()) return;
        Minecraft.getInstance().keyboardHandler.setClipboard(String.join("\n", entries.stream().map(e -> strip(e.text())).toList()));
        copiedTick = tickCount;
    }

    /** Whether the external agent says it's doing something; it reports "idle" when it isn't. */
    private static boolean agentActive() {
        String s = PeripheralStateTracker.status;
        return s != null && !s.isBlank() && !s.equalsIgnoreCase("idle");
    }

    /** Header pill text while the agent is active, else null. */
    private static String agentPill() {
        if (!agentActive()) return null;
        return "Agent · " + PeripheralStateTracker.status + (PeripheralStateTracker.iterations > 0 ? " · step " + PeripheralStateTracker.iterations : "");
    }

    private static int statusColor(String s) {
        String l = s == null ? "" : s.toLowerCase(java.util.Locale.ROOT);
        if (l.contains("error") || l.contains("fail")) return RED;
        if (l.contains("wait") || l.contains("pause")) return AMBER;
        return LIME;
    }

    private static int logColor(String l) {
        if (l.contains("ERROR") || l.contains("FAIL"))         return RED;
        if (l.contains("WAIT") || l.contains("CRAFT"))         return AMBER;
        if (l.contains("DONE"))                                return LIME;
        if (l.contains("SCRIPT") || l.contains("script"))      return MUTED;
        return CODE;
    }

    // ── Scripts ──────────────────────────────────────────────────────────────

    private void paintScripts(UiContext ui, float x, float y, float w, float h) {
        scriptsPanel.paint(ui, x, y, w, h);
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    /**
     * Settings: sections down the left (a chip row in narrow windows), one section on the right.
     * Fields save with their section's Save or Enter; switches and pickers save as soon as they change.
     */
    private void paintSettings(UiContext ui, float x, float y, float w, float h) {
        var c = ui.canvas();
        boolean side = w >= 560;
        float px = x, py = y, pw = w, ph = h;
        if (side) {
            float navW = 168;
            panel(c, x, y, navW, h);
            for (int i = 0; i < SECTIONS.length; i++) {
                boolean on = i == s_section;
                ui.button("settings-section:" + i, SECTIONS[i], SECTION_ICONS[i], "settings-section:" + i,
                    x + 8, y + 8 + i * 38, navW - 16, 32, on ? CHIP_ON : CHIP, true);
            }
            px = x + navW + 12; pw = w - navW - 12;
        } else {
            float tx = x;
            for (int i = 0; i < SECTIONS.length; i++) {
                boolean on = i == s_section;
                float bw = ui.buttonWidth(SECTIONS[i], null, CHIP);
                ui.button("settings-section:" + i, SECTIONS[i], null, "settings-section:" + i, tx, y, bw, 28, on ? CHIP_ON : CHIP, true);
                tx += bw + 4;
            }
            py = y + 36; ph = h - 36;
        }
        panel(c, px, py, pw, ph);
        UiRect view = new UiRect(px + 1, py + 1, pw - 2, ph - 2);
        float fw = Math.min(520, pw - 40), fx = px + 20;
        float contentH = switch (s_section) { case SEC_AI -> 330; case SEC_AGENT -> 230; case SEC_MCP -> 300; default -> 190; };
        final float top = py + 20;
        ui.scroll("settings:" + s_section, view, Math.max(contentH, view.height()), s -> {
            switch (s_section) {
                case SEC_AI -> aiSection(s, fx, top, fw);
                case SEC_AGENT -> agentSection(s, fx, top, fw);
                case SEC_MCP -> mcpSection(s, fx, top, fw);
                default -> lookSection(s, fx, top, fw);
            }
        });
    }

    // ── HUD ──────────────────────────────────────────────────────────────────

    /**
     * HUD: every script drawing on the HUD, with how many elements it has, whether a player moved
     * them, and Clear / Reset positions. Move HUD opens PaneLib's editor over the game.
     */
    private void paintHud(UiContext ui, float x, float y, float w, float h) {
        var c = ui.canvas();
        Map<String, Integer> owners = PeripheralHud.owners();
        int total = owners.values().stream().mapToInt(Integer::intValue).sum();
        float moveW = ui.buttonWidth("Move HUD", UiIcon.PEN, PRIMARY), clearW = ui.buttonWidth("Clear all", UiIcon.TRASH, SECONDARY);
        ui.button("hud-edit", "Move HUD", UiIcon.PEN, "hud-edit", x + w - moveW, y, moveW, 30, PRIMARY, true);
        ui.tooltip("hud-edit", "Drag HUD elements to new places on screen");
        ui.button("hud-clear", "Clear all", UiIcon.TRASH, "hud-clear", x + w - moveW - 6 - clearW, y, clearW, 30, SECONDARY, total > 0);
        ui.tooltip("hud-clear", "Remove everything scripts have drawn on the HUD");
        mid(c, "On your HUD", x, y + 15, 15, TEXT, Font.BOLD, SANS);
        String summary = total == 0 ? "Nothing yet" : total + (total == 1 ? " element" : " elements") + " from "
            + owners.size() + (owners.size() == 1 ? " script" : " scripts");
        float sx = x + c.textWidth("On your HUD", 15, Font.BOLD, SANS) + 12;
        if (sx + c.textWidth(summary, 11, Font.PLAIN, SANS) < x + w - moveW - clearW - 18) mid(c, summary, sx, y + 16, 11, MUTED, Font.PLAIN, SANS);

        float footH = 22, footY = y + h - footH;
        String theme = "HUD theme: " + PeripheralConfig.theme;
        mid(c, theme, x, footY + footH / 2, 10.5F, FAINT, Font.PLAIN, SANS);
        float changeW = ui.buttonWidth("Change", null, CHIP);
        ui.button("hud-theme", "Change", null, "hud-theme", x + c.textWidth(theme, 10.5F, Font.PLAIN, SANS) + 8, footY, changeW, footH, CHIP, true);

        UiRect view = new UiRect(x, y + 42, w, Math.max(40, footY - 10 - (y + 42)));
        panel(c, view.x(), view.y(), view.width(), view.height());
        if (owners.isEmpty()) {
            centered(c, "Nothing on your HUD", "Scripts that draw on the HUD show up here, such as hud/trail.py or hud/dashboard.py from the examples.", view);
            return;
        }
        float rowH = 64, gap = 8, pad = 10;
        List<String> names = new ArrayList<>(owners.keySet());
        float contentH = pad * 2 + names.size() * (rowH + gap) - gap;
        ui.scroll("hud-list", new UiRect(view.x() + 1, view.y() + 1, view.width() - 2, view.height() - 2), contentH, s -> {
            var sc = s.canvas();
            float ry = view.y() + pad;
            for (String owner : names) {
                float rx = view.x() + pad, rw = view.width() - pad * 2;
                sc.roundRect(0xFF242424, rx, ry, rw, rowH, 4);
                boolean running = !owner.isEmpty() && ScriptRunner.isRunning(owner), moved = PeripheralHud.hasLayout(owner);
                UiIcon.FILE.draw(sc, running ? LIME : MUTED, rx + 12, ry + 12, 14, 1.4F);
                String name = owner.isEmpty() ? "Other" : basename(owner);
                float right = rx + rw - 10;
                float clearOW = s.buttonWidth("Clear", null, SECONDARY), resetW = s.buttonWidth("Reset positions", UiIcon.REFRESH, SECONDARY);
                s.button("hud-clear-owner:" + owner, "Clear", null, "hud-clear-owner:" + owner, right - clearOW, ry + 17, clearOW, 30, SECONDARY, true);
                s.tooltip("hud-clear-owner:" + owner, "Remove what this script drew, until it draws again");
                right -= clearOW + 6;
                if (moved) {
                    s.button("hud-reset:" + owner, "Reset positions", UiIcon.REFRESH, "hud-reset:" + owner, right - resetW, ry + 17, resetW, 30, SECONDARY, true);
                    s.tooltip("hud-reset:" + owner, "Put its elements back where the script places them");
                    right -= resetW + 6;
                }
                float tx = rx + 34, room = Math.max(40, right - 10 - tx);
                mid(sc, sc.ellipsize(name, room, 13, Font.BOLD, SANS), tx, ry + 20, 13, TEXT, Font.BOLD, SANS);
                int n = owners.get(owner);
                String meta = (owner.isEmpty() ? "" : running ? "running · " : "stopped · ") + n + (n == 1 ? " element" : " elements") + (moved ? " · moved" : "");
                mid(sc, sc.ellipsize(meta, room, 11, Font.PLAIN, SANS), tx, ry + 40, 11, running ? LIME : MUTED, Font.PLAIN, SANS);
                String ids = String.join(", ", PeripheralHud.elements().stream().filter(e -> PeripheralHud.ownerOf(e).equals(owner))
                    .map(e -> e.has("id") ? e.get("id").getAsString() : e.has("type") ? e.get("type").getAsString() : "element").toList());
                mid(sc, sc.ellipsize(ids, room, 10.5F, Font.PLAIN, MONO), tx, ry + 55, 10.5F, FAINT, Font.PLAIN, MONO);
                ry += rowH + gap;
            }
        });
    }

    /** Section title and one-line explanation; returns where the fields start. */
    private static float sectionHead(UiCanvas c, String title, String about, float x, float y, float w) {
        c.text(title, x, y, 15, TEXT, Font.BOLD, SANS);
        return y + 28 + para(c, about, x, y + 26, w, 11, 16, MUTED) + 12;
    }

    private static float label(UiCanvas c, String text, float x, float y) {
        c.text(text, x, y, 11, MUTED, Font.PLAIN, SANS);
        return y + 18;
    }

    /** A section's Save, enabled only with unsaved changes, and what happened last. */
    private void saveRow(UiContext s, String id, int section, boolean dirty, float x, float y, float w) {
        var c = s.canvas();
        float saveW = s.buttonWidth("Save", UiIcon.CHECK, PRIMARY);
        s.button(id, "Save", UiIcon.CHECK, id, x, y, saveW, 30, PRIMARY, dirty);
        String note = dirty ? "Unsaved changes" : saveTick > 0 && savedSection == section ? saveMsg : "";
        int color = dirty ? AMBER : saveMsg.startsWith("Error") ? RED : LIME;
        if (!note.isEmpty()) mid(c, c.ellipsize(note, w - saveW - 16, 11, Font.PLAIN, SANS), x + saveW + 12, y + 15, 11, color, Font.PLAIN, SANS);
    }

    private void aiSection(UiContext s, float fx, float y, float fw) {
        var c = s.canvas();
        float cy = sectionHead(c, "AI", "Build uses this to write and fix your scripts. Running and editing scripts works without it.", fx, y, fw);
        cy = label(c, "API key", fx, cy);
        String showLabel = apiKeyRevealed ? "Hide" : "Show";
        float showW = s.buttonWidth("Hide", null, SECONDARY);
        if (apiKeyRevealed) s.textField("api-key", "api-key", "Paste your API key", fx, cy, fw - showW - 8, 30, PeripheralConfig.apiKey, 512);
        else s.passwordField("api-key", "api-key", "Paste your API key", fx, cy, fw - showW - 8, 30, PeripheralConfig.apiKey, 512);
        s.button("key-toggle", showLabel, null, "key-toggle", fx + fw - showW, cy, showW, 30, SECONDARY, true);
        cy += 36;
        cy += para(c, "Kept in config/peripheral.json on this computer, and hidden unless you press Show.", fx, cy, fw, 10.5F, 15, FAINT) + 16;
        cy = label(c, "Model", fx, cy);
        s.textField("model", "model", "Model name", fx, cy, fw, 30, PeripheralConfig.model, 512);
        cy += 36;
        cy += para(c, "Names starting with \"claude\" use Anthropic. Anything else uses OpenAI.", fx, cy, fw, 10.5F, 15, FAINT) + 18;
        boolean dirty = !stringValue("api-key", PeripheralConfig.apiKey).trim().equals(PeripheralConfig.apiKey)
            || !stringValue("model", PeripheralConfig.model).trim().equals(PeripheralConfig.model);
        saveRow(s, "settings-save", SEC_AI, dirty, fx, cy, fw);
    }

    private void agentSection(UiContext s, float fx, float y, float fw) {
        var c = s.canvas();
        float cy = sectionHead(c, "External agent", "A separate program that takes tasks from the Log tab and reports its steps there. It isn't MCP, and nothing here is needed to run scripts.", fx, y, fw);
        cy = label(c, "Agent server URL", fx, cy);
        s.textField("agent-url", "agent-url", "http://127.0.0.1:25586", fx, cy, fw, 30, PeripheralConfig.agentUrl, 512);
        cy += 48;
        boolean dirty = !stringValue("agent-url", PeripheralConfig.agentUrl).trim().equals(PeripheralConfig.agentUrl);
        saveRow(s, "agent-save", SEC_AGENT, dirty, fx, cy, fw);
    }

    private void mcpSection(UiContext s, float fx, float y, float fw) {
        var c = s.canvas();
        float cy = sectionHead(c, "MCP connection", "Lets an AI app on this computer read and control Minecraft. It's off by default, and your choice is kept for future launches.", fx, y, fw);
        s.toggle("mcp-toggle", "mcp-enabled", fx, cy + 6, PeripheralConfig.mcpEnabled);
        s.tooltip("mcp-toggle", "Allow MCP connections");
        mid(c, "Allow MCP connections", fx + 42, cy + 15, 12, TEXT, Font.PLAIN, SANS);
        cy += 34;
        boolean active = PeripheralMcp.active();
        c.circle(active ? LIME : PeripheralConfig.mcpEnabled ? RED : FAINT, fx + 4, cy + 7, 3.5F);
        mid(c, c.ellipsize(PeripheralMcp.status(), fw - 16, 11, Font.PLAIN, SANS), fx + 14, cy + 7, 11, MUTED, Font.PLAIN, SANS);
        cy += 30;
        cy = label(c, "Address", fx, cy);
        c.text(PeripheralMcp.URL, fx, cy, 11.5F, TEXT, Font.PLAIN, MONO);
        cy += 26;
        float copyW = s.buttonWidth("Copy connection", UiIcon.COPY, SECONDARY);
        s.button("mcp-copy", "Copy connection", UiIcon.COPY, "mcp-copy", fx, cy, copyW, 30, SECONDARY, active);
        if (saveTick > 0 && savedSection == SEC_MCP) mid(c, saveMsg, fx + copyW + 12, cy + 15, 11, LIME, Font.PLAIN, SANS);
        cy += 40;
        para(c, "The connection details include a private key. Paste them into a local MCP app and keep them private. Minecraft has to be running.", fx, cy, fw, 10.5F, 15, FAINT);
    }

    private void lookSection(UiContext s, float fx, float y, float fw) {
        var c = s.canvas();
        float cy = sectionHead(c, "Appearance", "The theme for HUD overlays, and for script windows that don't pick their own.", fx, y, fw);
        cy = label(c, "Theme", fx, cy);
        List<UiSelectOption> themes = PeripheralTheme.names().stream().map(n -> new UiSelectOption(n, n)).toList();
        s.dropdown("theme", "theme", fx, cy, Math.min(260, fw), 30, PeripheralConfig.theme, themes, SECONDARY, UiMenuStyle.defaults());
        cy += 42;
        para(c, "Custom themes load from config/peripheral/themes.", fx, cy, fw, 10.5F, 15, FAINT);
    }

    // ── Build ────────────────────────────────────────────────────────────────

    private static final UiInputStyle CODE_VIEW = UiInputStyle.defaults().font(MONO, 11.5F, Font.PLAIN).lineHeight(16).padding(12, 8)
        .appearance(UiInputAppearance.defaults().background(LOG).border(LOG).focus(LINE).text(CODE).placeholder(FAINT).selection(SELECTION).radius(0));
    private static final UiInputStyle LOG_VIEW = CODE_VIEW.lineHeight(17);
    private static final float MSG_SIZE = 11.5F, MSG_LINE = 16;

    /**
     * Build: the AI chat for one script beside that script's code. Header names the script, with
     * a Script menu (open another, clear chat) and Run. Narrow windows switch Chat / Code with tabs.
     */
    private void paintBuild(UiContext ui, float x, float y, float w, float h) {
        var c = ui.canvas();
        boolean has = s_build != null, busy = has && s_build.busy;
        boolean running = has && ScriptRunner.isRunning(s_build.getScriptRelPath());

        // Header: which script this is, then its menu and Run/Stop.
        float hh = 32, right = x + w;
        String runLabel = running ? "Stop" : "Run";
        float runW = ui.buttonWidth(runLabel, running ? UiIcon.STOP : UiIcon.PLAY, PRIMARY);
        if (has) {
            ui.button("build-run", runLabel, running ? UiIcon.STOP : UiIcon.PLAY, "build-run", right - runW, y + 1, runW, 30, running ? DANGER : PRIMARY, true);
            right -= runW + 6;
        }
        float menuW = ui.menuButtonWidth("Script", null, SECONDARY);
        ui.menuButton("build-menu", "Script", null, right - menuW, y + 1, menuW, 30, SECONDARY, true, List.of(
            new UiMenuItem("open", has ? "Open another script…" : "Open a script…", "build-open"),
            new UiMenuItem("clear", "Clear this chat", has && !busy && !s_build.getMessages().isEmpty() ? "build-clear" : "noop")));
        right -= menuW + 12;
        if (has) {
            UiIcon.FILE.draw(c, running ? LIME : MUTED, x, y + 8, 16, 1.4F);
            float nx = x + 24;
            String name = c.ellipsize(basename(s_build.getScriptRelPath()), Math.max(40, right - nx), 15, Font.BOLD, SANS);
            mid(c, name, nx, y + hh / 2, 15, TEXT, Font.BOLD, SANS);
            float px = nx + c.textWidth(name, 15, Font.BOLD, SANS) + 10;
            String meta = running ? "running" : "scripts/" + s_build.getScriptRelPath();
            if (c.textWidth(meta, 10.5F, Font.PLAIN, MONO) <= right - px) mid(c, meta, px, y + hh / 2 + 1, 10.5F, running ? LIME : FAINT, Font.PLAIN, MONO);
        } else {
            mid(c, "No script open", x, y + hh / 2, 15, TEXT, Font.BOLD, SANS);
        }
        float cy = y + hh + 10;

        if (PeripheralConfig.apiKey.isEmpty()) {
            float sW = ui.buttonWidth("Add a key", UiIcon.CHEVRON_RIGHT, SECONDARY);
            c.roundRect(AMBER_TINT, x, cy, w, 34, 3);
            UiIcon.WARNING.draw(c, AMBER, x + 10, cy + 10, 14, 1.4F);
            mid(c, c.ellipsize("Build needs an AI API key to write code. Running and editing scripts doesn't.", w - sW - 46, 11, Font.PLAIN, SANS), x + 32, cy + 17, 11, AMBER, Font.PLAIN, SANS);
            ui.button("build-settings", "Add a key", UiIcon.CHEVRON_RIGHT, "build-settings", x + w - sW - 4, cy + 4, sW, 26, SECONDARY, true);
            cy += 42;
        }

        float bottom = y + h;
        if (w >= 640) {
            float chatW = Math.round(Math.max(280, w * .44F));
            chatPaneUi(ui, x, cy, chatW, bottom - cy);
            codePaneUi(ui, x + chatW + 12, cy, w - chatW - 12, bottom - cy);
        } else {
            String pane = ui.tabs("build-pane", "build-pane", List.of(new UiSelectOption("chat", "Chat"), new UiSelectOption("code", "Code")),
                x, cy, Math.min(w, 220), 28, s_showCode ? "code" : "chat");
            s_showCode = pane.equals("code");
            cy += 36;
            if (s_showCode) codePaneUi(ui, x, cy, w, bottom - cy); else chatPaneUi(ui, x, cy, w, bottom - cy);
        }
    }

    /** The conversation as message cards, following the newest one, with the message box below. */
    private void chatPaneUi(UiContext ui, float x, float y, float w, float h) {
        var c = ui.canvas();
        boolean has = s_build != null, busy = has && s_build.busy;
        panel(c, x, y, w, h);
        mid(c, "Chat", x + 12, y + 16, 12, TEXT, Font.BOLD, SANS);
        if (busy) mid(c, "Thinking…", x + 12 + c.textWidth("Chat", 12, Font.BOLD, SANS) + 10, y + 16, 11, LIME, Font.PLAIN, SANS);

        // Message box at the bottom of the chat, where you read the reply.
        float boxH = 30, boxY = y + h - 10 - boxH, sendW = ui.buttonWidth("Send", UiIcon.SEND, PRIMARY);
        ui.textField("build-chat", "build-chat", busy ? "Waiting for the reply…" : has ? "Ask for a change, or describe a new script…" : "Describe the script you want…",
            x + 10, boxY, w - 20 - sendW - 8, boxH, "", 2000);
        ui.button("build-send", "Send", UiIcon.SEND, "build-send", x + w - 10 - sendW, boxY, sendW, boxH, PRIMARY, !busy);

        UiRect view = new UiRect(x + 1, y + 32, w - 2, Math.max(32, boxY - 10 - (y + 32)));
        c.fill(LOG, view.x(), view.y(), view.width(), view.height());
        hline(c, LINE_SOFT, x + 1, view.y(), w - 2);
        if (!has || s_build.getMessages().isEmpty() && !busy && s_build.status.isEmpty()) {
            centered(c, has ? "Describe what you want" : "Pick a script to build",
                has ? "Say what the script should do, or ask for a change. The current file goes along with your first message, and the AI writes its code straight into the file."
                    : "Open one from the Script menu, or pick Build with AI on a script in the Scripts tab. You can also just describe a new script below.", view);
            return;
        }
        // Lay out every card first so the scroll knows its height.
        float pad = 12, textW = view.width() - pad * 2 - 20;
        record Card(boolean user, String label, List<String> lines, int codeLines, int index) {}
        List<Card> cards = new ArrayList<>();
        var messages = s_build.getMessages();
        for (int i = 0; i < messages.size(); i++) {
            var m = messages.get(i);
            boolean user = m.role().equals("user");
            List<String> lines = new ArrayList<>();
            int code = 0; boolean inCode = false;
            for (String raw : m.content().split("\n", -1)) {
                if (raw.startsWith("```")) { inCode = !inCode; continue; }
                if (inCode) { code++; continue; }
                if (raw.isBlank()) { if (!lines.isEmpty() && !lines.getLast().isEmpty()) lines.add(""); continue; }
                lines.addAll(c.wrapText(raw, textW, MSG_SIZE, Font.PLAIN, SANS));
            }
            while (!lines.isEmpty() && lines.getLast().isEmpty()) lines.removeLast();
            cards.add(new Card(user, user ? "You" : "Assistant", lines, code, i));
        }
        float contentH = pad;
        for (Card card : cards) contentH += cardHeight(card.lines().size(), card.codeLines() > 0) + 10;
        List<String> problem = busy || s_build.status.isEmpty() ? List.of() : c.wrapText(s_build.status, textW, MSG_SIZE, Font.PLAIN, SANS);
        contentH += (busy ? 24 : 0) + problem.size() * MSG_LINE + pad;
        final float total = contentH;
        ui.scroll("chat", view, total, UiScrollFollow.WHEN_AT_END, s -> {
            var sc = s.canvas();
            float cy = view.y() + pad;
            for (Card card : cards) {
                float ch = cardHeight(card.lines().size(), card.codeLines() > 0), cx = view.x() + pad, cw = view.width() - pad * 2;
                sc.roundRect(card.user() ? LIME_TINT : PANEL, cx, cy, cw, ch, 4);
                mid(sc, card.label(), cx + 10, cy + 13, 10.5F, card.user() ? LIME : MUTED, Font.BOLD, SANS);
                if (!card.user()) s.iconButton("copy:" + card.index(), "Copy this reply", UiIcon.COPY, "build-copy:" + card.index(), cx + cw - 26, cy + 3, 22, CHIP, true);
                float ly = cy + 26;
                for (String line : card.lines()) { if (!line.isEmpty()) sc.text(line, cx + 10, ly, MSG_SIZE, CODE, Font.PLAIN, SANS); ly += MSG_LINE; }
                if (card.codeLines() > 0) {
                    String chip = "Wrote " + card.codeLines() + (card.codeLines() == 1 ? " line" : " lines") + " to the script";
                    UiIcon.FILE.draw(sc, FAINT, cx + 10, ly + 3, 11, 1.3F);
                    sc.text(chip, cx + 26, ly + 1, 10.5F, FAINT, Font.PLAIN, SANS);
                }
                cy += ch + 10;
            }
            if (busy) { mid(sc, "Thinking…", view.x() + pad + 4, cy + 10, MSG_SIZE, MUTED, Font.PLAIN, SANS); cy += 24; }
            for (String line : problem) { sc.text(line, view.x() + pad + 4, cy, MSG_SIZE, RED, Font.PLAIN, SANS); cy += MSG_LINE; }
        });
    }

    private static float cardHeight(int lines, boolean code) { return 26 + lines * MSG_LINE + (code ? 20 : 0) + 8; }

    /** The script's code, read-only with line numbers; Edit opens the editor, Fix sends the last run's errors. */
    private void codePaneUi(UiContext ui, float x, float y, float w, float h) {
        var c = ui.canvas();
        panel(c, x, y, w, h);
        boolean has = s_build != null;
        float bx = x + w - 6;
        float editW = ui.buttonWidth("Edit", UiIcon.PEN, SECONDARY), fixW = ui.buttonWidth("Fix errors", UiIcon.WARNING, SECONDARY);
        bx -= editW;
        ui.button("build-edit", "Edit", UiIcon.PEN, "build-edit", bx, y + 4, editW, 24, SECONDARY, has);
        ui.tooltip("build-edit", "Open the script in the editor");
        bx -= fixW + 6;
        ui.button("build-fix", "Fix errors", UiIcon.WARNING, "build-fix", bx, y + 4, fixW, 24, SECONDARY, has && !s_build.busy);
        ui.tooltip("build-fix", "Send the last 60 lines of the script's output to the AI and ask it to fix the errors");
        mid(c, "Code", x + 12, y + 16, 12, TEXT, Font.BOLD, SANS);
        float noteX = x + 12 + c.textWidth("Code", 12, Font.BOLD, SANS) + 10;
        String note = !buildNote.isEmpty() ? buildNote : has && !buildCode.isEmpty() ? buildCode.split("\n", -1).length + " lines" : "";
        if (bx - 10 - noteX > 40 && !note.isEmpty())
            mid(c, c.ellipsize(note, bx - 10 - noteX, 10.5F, Font.PLAIN, SANS), noteX, y + 16, 10.5F, buildNote.isEmpty() ? FAINT : AMBER, Font.PLAIN, SANS);

        UiRect view = new UiRect(x + 1, y + 32, w - 2, Math.max(32, h - 33));
        hline(c, LINE_SOFT, x + 1, view.y(), w - 2);
        if (!has || buildCode.isEmpty()) {
            c.fill(LOG, view.x(), view.y(), view.width(), view.height());
            centered(c, has ? "No code yet" : "No script open", has ? "Send a message and the AI writes the script here." : "Open a script from the Script menu.", view);
            return;
        }
        ui.textView("code", buildCode.replace("\t", "    "), view.x(), view.y(), view.width(), view.height(), CODE_VIEW, true);
    }

    // ── Dialogs ──────────────────────────────────────────────────────────────

    /** The rename sheet; delete and file-access prompts are PaneLib dialogs. */
    private void paintDialogs(UiContext ui) {
        if (trimming != null) { paintTrim(ui); return; }
        if (renamingScript == null) return;
        var c = ui.canvas();
        UiRect m = modal(ui, 400, 176, "noop");
        float mx = m.x() + 16, my = m.y() + 16, mw = m.width() - 32;
        c.text(renamingMacro ? "Rename macro" : renamingScript.endsWith(".py") ? "Rename script" : "Rename folder", mx, my, 15, TEXT, Font.BOLD, SANS);
        my += 30;
        ui.textField("rename-field", "rename-value", "New name", mx, my, mw, 32, renamingMacro ? renamingScript : basename(renamingScript), 64);
        my += 40;
        c.text(c.ellipsize("Enter renames · Esc cancels", mw, 10.5F, Font.PLAIN, SANS), mx, my, 10.5F, FAINT, Font.PLAIN, SANS);
        float by = m.bottom() - 16 - 30;
        float oW = ui.buttonWidth("Rename", null, PRIMARY), kW = ui.buttonWidth("Cancel", null, SECONDARY);
        ui.button("rename-cancel", "Cancel", null, "rename-cancel", m.right() - 16 - oW - 8 - kW, by, kW, 30, SECONDARY, true);
        ui.button("rename-ok", "Rename", null, "rename-ok", m.right() - 16 - oW, by, oW, 30, PRIMARY, true);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Actions (behaviour preserved from the previous screen)
    // ══════════════════════════════════════════════════════════════════════════

    // ── Agent communication ───────────────────────────────────────────────────

    private void sendTask() {
        String t = stringValue("agent-task", "").trim();
        if (t.isEmpty()||agentBusy) return;
        agentBusy=true;agentMessage="Sending task…";invalidate();
        agentPost("/task", "{\"task\":\"" + esc(t) + "\"}",(ok,message)->{
            agentBusy=false;agentMessage=ok?"Task sent.":message+" Draft kept.";
            if(ok&&stringValue("agent-task","").trim().equals(t))setValue("agent-task","");
            invalidate();
        });
    }

    private void sendStop() { agentPost("/stop", "{\"type\":\"stop\"}",(ok,message)->{agentMessage=ok?"Stop request sent.":message;invalidate();}); }

    private void agentPost(String path, String json,java.util.function.BiConsumer<Boolean,String> done) {
        try {
            URI uri=URI.create(PeripheralConfig.agentUrl.replaceAll("/+$","")+path);
            if(uri.getHost()==null||!("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme())))throw new IllegalArgumentException("Agent URL");
            HttpRequest req = HttpRequest.newBuilder()
                .uri(uri).timeout(java.time.Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((response,error)->onMain(()->{
                boolean ok=error==null&&response.statusCode()>=200&&response.statusCode()<300;
                done.accept(ok,error!=null?"Could not reach the agent; check its URL and server.":ok?"Sent.":"Agent returned HTTP "+response.statusCode()+".");
            }));
        } catch (Exception e) { done.accept(false,"Agent URL must be a valid http or https address."); }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // ── Settings save ─────────────────────────────────────────────────────────

    /** Saves one section's fields: AI saves the key and model, Agent saves the URL. */
    private void saveSettings(int section) {
        try {
            if (section == SEC_AI) {
                PeripheralConfig.apiKey = stringValue("api-key", PeripheralConfig.apiKey).trim();
                PeripheralConfig.model  = stringValue("model", PeripheralConfig.model).trim();
            } else {
                PeripheralConfig.agentUrl = stringValue("agent-url", PeripheralConfig.agentUrl).trim();
            }
            PeripheralConfig.save();
            saveMsg  = "Saved";
            saveTick = 60;
        } catch (Exception e) {
            saveMsg  = "Error: " + e.getMessage();
            saveTick = 80;
        }
        savedSection = section;
        invalidate();
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    private void openBuildPicker() {
        minecraft.gui.setScreen(new BuildPickerScreen(this,
            // onOpen: resume existing session with history
            name -> { openBuildSession(name); minecraft.gui.setScreen(this); },
            // onNew: find a unique script name, clear chat, start fresh
            name -> {
                String unique = uniqueScriptName(name);
                openBuildSession(unique);
                if (s_build != null) { s_build.clearChat(); s_lastMsgCount = 0; }
                minecraft.gui.setScreen(this);
            }
        ));
    }

    /** If the script file already exists, append _1, _2, … until a free name is found. */
    private String uniqueScriptName(String name) {
        if (name == null || name.isBlank()) name = "my_script.py";
        if (!name.endsWith(".py")) name += ".py";
        String base = name.substring(0, name.length() - 3); // strip .py
        String candidate = name;
        int n = 1;
        while (Files.exists(slooth.peripheral.script.ScriptPaths.resolve(candidate))) {
            candidate = base + "_" + n + ".py";
            n++;
        }
        return candidate;
    }

    /** Opens or resumes a session. Only loads local code and history; nothing is sent. */
    private void openBuildSession(String name) {
        if (name == null || name.isBlank()) name = "my_script.py";
        if (!name.endsWith(".py")) name += ".py";
        s_buildName = name;
        if (s_build == null || !s_build.getScriptRelPath().equals(name)) {
            s_build        = new BuildSession(name);
            scrollToEnd("chat");
            s_codeReset    = true;
            s_lastMsgCount = s_build.getMessages().size();
        }
        buildCode = s_build.getCode();
        buildNote = "";
    }

    private void sendBuildMessage() {
        String text = stringValue("build-chat", "").trim();
        if (text.isEmpty()) return;
        // No script open yet: the first message starts a new one, never an existing file.
        if (s_build == null) {
            openBuildSession(uniqueScriptName("my_script.py"));
            if (s_build == null) return;
        }
        // The draft is cleared only once a request has actually been dispatched.
        if (dispatchBuild(text)) setValue("build-chat", "");
    }

    /**
     * Explicit generation boundary: requires a key and an idle session, snapshots the current
     * script file first, and includes the existing code as context on the session's first message.
     * Returns false (with a visible reason) when nothing was sent.
     */
    private boolean dispatchBuild(String text) {
        if (s_build == null || text.isEmpty()) return false;
        if (s_build.busy) { buildNote = "Still waiting for the previous reply."; invalidate(); return false; }
        if (PeripheralConfig.apiKey.isEmpty()) {
            s_build.status = "No API key set. Add one in the Settings tab.";
            invalidate();
            return false;
        }
        String rel = s_build.getScriptRelPath();
        try {
            BuildDraftSafety.snapshot(rel);
        } catch (Exception e) {
            s_build.status = "Not sent: couldn't back up " + basename(rel) + " first (" + e.getMessage() + ").";
            invalidate();
            return false;
        }
        String code = s_build.getCode();
        String message = text;
        if (s_build.getMessages().isEmpty() && !code.isBlank()) {
            message = "Here is my existing script `" + rel + "`:\n```python\n" + code
                + "\n```\nPlease read it and be ready to help me modify or debug it.\n\n" + text;
        }
        buildNote = "";
        s_build.send(message, () -> { scrollToEnd("chat"); s_codeReset = true; });
        scrollToEnd("chat");
        invalidate();
        return true;
    }

    private void toggleBuildScript() {
        if (s_build == null) return;
        String rel = s_build.getScriptRelPath();
        if (ScriptRunner.isRunning(rel)) {
            ScriptRunner.stopScript(rel);
        } else {
            if (!Files.exists(slooth.peripheral.script.ScriptPaths.resolve(rel))) { buildNote = "Nothing to run yet: the script file doesn't exist."; return; }
            buildNote = "";
            ScriptRunner.runScript(rel);
        }
    }

    /** Copies one assistant reply, code included, to the clipboard. */
    private void copyReply(int index) {
        if (s_build == null || index < 0 || index >= s_build.getMessages().size()) return;
        Minecraft.getInstance().keyboardHandler.setClipboard(s_build.getMessages().get(index).content());
    }

    private void sendLogToAI() {
        if (s_build == null) return;
        java.nio.file.Path logPath = slooth.peripheral.script.ScriptPaths.resolve(
            s_build.getScriptRelPath() + ".log");
        String logText = "";
        try {
            if (Files.exists(logPath)) logText = Files.readString(logPath).strip();
        } catch (Exception ignored) {}
        if (logText.isEmpty()) {
            // No log yet — say so instead of touching the player's draft
            buildNote = "Run the script first to generate a log.";
            return;
        }
        // Keep last 60 lines
        String[] lines = logText.split("\n", -1);
        int from = Math.max(0, lines.length - 60);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) sb.append(lines[i]).append('\n');
        String msg = "I ran the script and got this output:\n```\n"
            + sb.toString().strip() + "\n```\nPlease identify any errors and fix them.";
        dispatchBuild(msg);
    }

    // ── New files ─────────────────────────────────────────────────────────────

    private void createNewFolder() {
        String name = "folder";
        int n = 2;
        Path dir = slooth.peripheral.script.ScriptPaths.resolve(scriptsPanel.folderPath().toString().replace(java.io.File.separatorChar, '/'));
        while (Files.exists(dir.resolve(name))) {
            name = "folder_" + n++;
        }
        try {
            Files.createDirectories(dir.resolve(name));
            scriptsPanel.refresh();
            // Straight into naming it: a folder called "folder" is rarely what anyone wants.
            startRename(ScriptRunner.SCRIPTS_DIR.relativize(dir.resolve(name)).toString().replace(java.io.File.separatorChar, '/'));
        } catch (Exception ignored) {}
    }

    // ── Rename sheet ──────────────────────────────────────────────────────────

    private static final String RENAME_SCOPE = "rename-sheet";

    /** Opens the rename sheet with the name (without .py) selected, so typing replaces it. */
    private void startRenameMacro(String name) {
        cancelRename();
        renamingScript = name;
        renamingMacro = true;
        setValue("rename-value", name);
        pushFocusScope(RENAME_SCOPE, List.of("rename-field", "rename-cancel", "rename-ok"), "rename-field", this::cancelRename);
        requestFocus("rename-field", 0, name.length());
    }

    private void startRename(String relPath) {
        cancelRename();
        renamingScript = relPath;
        renamingMacro = false;
        String fn = basename(relPath);
        setValue("rename-value", fn);
        pushFocusScope(RENAME_SCOPE, List.of("rename-field", "rename-cancel", "rename-ok"), "rename-field", this::cancelRename);
        requestFocus("rename-field", 0, fn.endsWith(".py") ? fn.length() - 3 : fn.length());
    }

    private void finishRename() {
        if (renamingScript == null) return;
        String newName = stringValue("rename-value", "").trim();
        String old = renamingScript;
        boolean macro = renamingMacro;
        cancelRename();
        if (newName.isEmpty()) return;
        if (macro) renameMacro(old, newName); else renameScript(old, newName);
    }

    private void cancelRename() {
        if (renamingScript == null) return;
        renamingScript = null;
        if (focusScopeId().filter(RENAME_SCOPE::equals).isPresent()) popFocusScope(RENAME_SCOPE);
        invalidate();
    }

    private void renameMacro(String old, String typed) {
        String name = Recording.cleanName(typed);
        if (name.equals(old)) return;
        if (Recording.exists(name)) { macrosPanel.say("There's already a macro called " + name + "."); return; }
        try {
            Recording.load(old).renamed(name).save();
            Recording.delete(old);
            macrosPanel.say("Renamed to " + name + ".");
        } catch (Exception e) { macrosPanel.say("Couldn't rename " + old + ": " + e.getMessage()); }
        macrosPanel.refresh();
    }

    // ── Trim sheet ───────────────────────────────────────────────────────────

    private static final String TRIM_SCOPE = "trim-sheet";

    private void startTrim(String name) {
        try { trimming = Recording.load(name); }
        catch (Exception e) { macrosPanel.say("Couldn't open " + name + "."); return; }
        setValue("trim-start", 0d);
        setValue("trim-end", (double) trimming.ticks());
        pushFocusScope(TRIM_SCOPE, List.of("trim-start", "trim-end", "trim-cancel", "trim-ok"), "trim-start", this::cancelTrim);
    }

    private void finishTrim() {
        if (trimming == null) return;
        int from = (int) Math.round(numberValue("trim-start", 0)), to = (int) Math.round(numberValue("trim-end", trimming.ticks()));
        Recording rec = trimming;
        cancelTrim();
        if (to - from < 1) { macrosPanel.say("Nothing would be left. Keep at least one tick."); return; }
        try {
            Recording kept = rec.trimmed(from, to);
            kept.save();
            macrosPanel.say("Trimmed " + rec.name + " to " + Recorder.seconds(kept.ticks()) + ".");
        } catch (Exception e) { macrosPanel.say("Couldn't trim " + rec.name + ": " + e.getMessage()); }
        macrosPanel.refresh();
    }

    private void cancelTrim() {
        if (trimming == null) return;
        trimming = null;
        if (focusScopeId().filter(TRIM_SCOPE::equals).isPresent()) popFocusScope(TRIM_SCOPE);
        invalidate();
    }

    private void paintTrim(UiContext ui) {
        var c = ui.canvas();
        UiRect m = modal(ui, 440, 200, "noop");
        float mx = m.x() + 16, my = m.y() + 16, mw = m.width() - 32;
        int ticks = trimming.ticks();
        c.text("Trim " + c.ellipsize(trimming.name, mw - 60, 15, Font.BOLD, SANS), mx, my, 15, TEXT, Font.BOLD, SANS);
        my += 32;
        double start = numberValue("trim-start", 0), end = numberValue("trim-end", ticks);
        mid(c, "Start at " + Recorder.seconds((int) Math.round(start)), mx, my + 8, 11, MUTED, Font.PLAIN, SANS);
        ui.slider("trim-start", "trim-start", mx + 110, my, mw - 110, 0, Math.max(1, ticks - 1), 0);
        my += 34;
        mid(c, "End at " + Recorder.seconds((int) Math.round(end)), mx, my + 8, 11, MUTED, Font.PLAIN, SANS);
        ui.slider("trim-end", "trim-end", mx + 110, my, mw - 110, 1, ticks, ticks);
        my += 34;
        int kept = (int) Math.max(0, Math.round(end) - Math.round(start));
        c.text(c.ellipsize("Keeps " + Recorder.seconds(kept) + " of " + Recorder.seconds(ticks) + ". Playback starts where the kept part starts.", mw, 10.5F, Font.PLAIN, SANS),
            mx, my, 10.5F, FAINT, Font.PLAIN, SANS);
        float by = m.bottom() - 16 - 30;
        float oW = ui.buttonWidth("Save", UiIcon.CHECK, PRIMARY), kW = ui.buttonWidth("Cancel", null, SECONDARY);
        ui.button("trim-cancel", "Cancel", null, "trim-cancel", m.right() - 16 - oW - 8 - kW, by, kW, 30, SECONDARY, true);
        ui.button("trim-ok", "Save", UiIcon.CHECK, "trim-ok", m.right() - 16 - oW, by, oW, 30, PRIMARY, kept >= 1);
    }

    /** Native confirmation; the path rides on the confirm action so nothing else holds it. */
    private void askDelete(String relPath) {
        boolean folder = Files.isDirectory(slooth.peripheral.script.ScriptPaths.resolve(relPath));
        showDialog("delete", new UiDialog(folder ? "Delete folder?" : "Delete script?",
            "scripts/" + relPath + (folder ? " and every script inside it will be deleted." : " will be deleted.") + " This cannot be undone.",
            "Delete", "del-confirm:" + relPath, "Cancel", "del-cancel", DANGER_DIALOG));
    }

    private void renameScript(String oldRelPath, String newName) {
        // Strip path traversal
        newName = newName.replace("/", "").replace("\\", "").replace("..", "").trim();
        if (newName.isEmpty() || newName.equals(".py")) return;
        boolean isDir = Files.isDirectory(slooth.peripheral.script.ScriptPaths.resolve(oldRelPath));
        if (!isDir && !newName.endsWith(".py")) newName += ".py";
        // Keep the same folder
        String folder = oldRelPath.contains("/")
            ? oldRelPath.substring(0, oldRelPath.lastIndexOf('/') + 1) : "";
        String newRelPath = folder + newName;
        if (newRelPath.equals(oldRelPath)) return;
        Path newFile = slooth.peripheral.script.ScriptPaths.resolve(newRelPath);
        if (Files.exists(newFile)) {
            PeripheralClient.LOGGER.warn("[Peripheral] Rename failed: {} already exists.", newName);
            return;
        }
        try {
            ScriptRunner.stopScript(oldRelPath);
            Path oldFile = slooth.peripheral.script.ScriptPaths.resolve(oldRelPath);
            Files.move(oldFile, newFile);
            Path oldLog = slooth.peripheral.script.ScriptPaths.resolve(oldRelPath + ".log");
            if (Files.exists(oldLog))
                Files.move(oldLog, slooth.peripheral.script.ScriptPaths.resolve(newRelPath + ".log"));
            PeripheralConfig.renameScript(oldRelPath, newRelPath);
            scriptsPanel.renamed(oldRelPath, newRelPath);
        } catch (Exception ignored) {}
    }

    // ── Script file helpers ───────────────────────────────────────────────────

    private void createNewScript() {
        String name = "script.py";
        int n = 2;
        Path dir = slooth.peripheral.script.ScriptPaths.resolve(scriptsPanel.folderPath().toString().replace(java.io.File.separatorChar, '/'));
        while (Files.exists(dir.resolve(name))) {
            name = "script_" + n++ + ".py";
        }
        try {
            String template = "from mc import *\n\n# Your script here\nmsg(\"Hello!\")\n";
            Files.writeString(dir.resolve(name), template);
            // Build relative path from SCRIPTS_DIR
            String relPath = ScriptRunner.SCRIPTS_DIR.relativize(dir.resolve(name))
                             .toString().replace(java.io.File.separatorChar, '/');
            scriptsPanel.select(relPath);
            scriptsPanel.refresh();
            minecraft.gui.setScreen(new ScriptEditorScreen(this, relPath));
        } catch (Exception ignored) {}
    }

    private void deleteScript(String relPath) {
        Path target = slooth.peripheral.script.ScriptPaths.resolve(relPath);
        if (Files.isDirectory(target)) {
            // Stop all scripts inside, delete their chats, then delete recursively
            try {
                try (var walk = Files.walk(target)) {
                    walk.filter(p -> !Files.isDirectory(p) && p.toString().endsWith(".py"))
                        .forEach(p -> {
                            String rel = slooth.peripheral.script.ScriptPaths.root().relativize(p).toString()
                                .replace(java.io.File.separatorChar, '/');
                            ScriptRunner.stopScript(rel);
                            PeripheralHud.clearIfOwner(rel);
                            BuildSession.deleteChatFor(rel);
                        });
                }
                try (var walk = Files.walk(target)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
                }
            } catch (Exception ignored) {}
        } else {
            ScriptRunner.stopScript(relPath);
            PeripheralHud.clearIfOwner(relPath);
            BuildSession.deleteChatFor(relPath);
            try { Files.deleteIfExists(target); } catch (Exception ignored) {}
            try { Files.deleteIfExists(slooth.peripheral.script.ScriptPaths.resolve(relPath + ".log")); }
            catch (Exception ignored) {}
        }
        // If the open Build session was just deleted, clear it
        if (s_build != null && s_build.getScriptRelPath().equals(relPath)) {
            s_build = null;
            s_buildName = "";
            s_lastMsgCount = 0;
            buildCode = "";
        }
        scriptsPanel.deleted(relPath);
    }

    // ── Screen overrides ──────────────────────────────────────────────────────

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void removed() {
        closed = true;
        scriptsPanel.closed();
        super.removed();
    }
}
