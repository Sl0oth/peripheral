package slooth.peripheral;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Peripheral control panel — press <b>G</b> in-game to open.
 *
 * <p>Three tabs:
 * <ul>
 *   <li><b>Log</b>      – live output from running scripts and the AI agent;
 *       HTTP URLs are rendered as clickable blue underlined links.</li>
 *   <li><b>Scripts</b>  – browse folders, run/stop scripts, open the in-game
 *       editor, configure auto-run behaviour, and search by name.</li>
 *   <li><b>Settings</b> – OpenAI API key, agent server URL, model name.</li>
 * </ul>
 *
 * <p>Auto-run modes (per script, cycled with the <em>OFF/SP/MP/ALL</em> button):
 * <ul>
 *   <li><b>OFF</b>  – never auto-start</li>
 *   <li><b>SP</b>   – auto-start when joining a singleplayer world</li>
 *   <li><b>MP</b>   – auto-start when joining a multiplayer server</li>
 *   <li><b>ALL</b>  – auto-start on any world join</li>
 * </ul>
 */
public class PeripheralScreen extends Screen {

    // ── Layout ────────────────────────────────────────────────────────────────
    private static final int W  = 404;
    private static final int H  = 252;
    private static final int TH = 14; // title bar height
    private static final int TB = 13; // tab bar height
    private static final int BH = 26; // bottom strip height

    // Colours — Litematica-inspired neutral dark with orange accents
    private static final int C_PANEL  = 0xEE111111;
    private static final int C_TITLE  = 0xEE1A1A1A;
    private static final int C_TABBAR = 0xEE0F0F0F;
    private static final int C_TABACT = 0xDD252525;
    private static final int C_CONTENT= 0xCC0C0C0C;
    private static final int C_BORDER = 0xFF2A2A2A;
    private static final int C_ORANGE = 0xFFFF8800; // section headers, accents
    private static final int C_GREEN  = 0xFF00FFAA; // running / success
    private static final int C_YELLOW = 0xFFFFAA00; // folders / warnings
    private static final int C_CYAN   = 0xFF00CCFF;
    private static final int C_WHITE  = 0xFFDDDDDD;
    private static final int C_GREY   = 0xFF888888;
    private static final int C_DIM    = 0xFF555555;
    private static final int C_RED    = 0xFFFF4444;
    private static final int C_SCROLL       = 0xFF2A2A2A;
    private static final int C_SCROLL_THUMB = 0xFF555555;

    private static final int TAB_LOG = 0, TAB_SCRIPTS = 1, TAB_SETTINGS = 2, TAB_STORE = 3, TAB_BUILD = 4;

    // Layout constants for the Scripts tab
    private static final int BREAD_H  = 11; // breadcrumb row height
    private static final int SEARCH_H = 14; // search bar row height

    // ── State ─────────────────────────────────────────────────────────────────
    private int currentTab = TAB_LOG;
    private int px, py, contentY, contentH;

    private int     logScroll       = 0;
    private int     scriptScroll    = 0;
    // Pixel-accurate scroll positions — the same pattern Minecraft's EntryListWidget uses.
    // Keeping these as doubles lets Mac trackpad fractional deltas accumulate naturally
    // without getting swallowed by integer truncation.
    private double  logScrollPx     = 0.0;
    private double  scriptScrollPx  = 0.0;
    private boolean scriptBtnsDirty = false; // rebuild once per render frame, not per event

    // ── Clickable URL support in the Log tab ──────────────────────────────────
    private static final Pattern URL_PAT = Pattern.compile("https?://[^\\s§]+");
    // Maps the screen-Y coordinate of a log row → the URL found on that row.
    // Rebuilt every frame inside renderLog so it always matches what is visible.
    private final Map<Integer, String> logUrlByY = new HashMap<>();
    private static final int C_URL       = 0xFF4499FF; // normal link colour
    private static final int C_URL_HOVER = 0xFF88CCFF; // hovered link colour

    private List<ScriptRunner.ScriptInfo> scripts = new ArrayList<>();
    private int scriptRefreshTick = 0;

    /** Current folder being browsed (relative to SCRIPTS_DIR). "" = root. */
    private Path currentFolder = Path.of("");

    // Widgets — rebuilt on tab switch
    private EditBox taskInput;
    private EditBox apiKeyField;
    private EditBox agentUrlField;
    private EditBox modelField;
    private EditBox searchField;   // script search bar
    private String          searchQuery = "";
    private String saveMsg  = "";
    private int    saveTick = 0;

    // ── Store tab state ───────────────────────────────────────────────────────
    private java.util.List<StoreClient.ScriptEntry> storeScripts = null;
    /** Script currently open in the detail view, or null = list view. */
    private StoreClient.ScriptEntry storeDetailEntry = null;
    private String storeStatus = "";
    private int    storeScroll = 0;
    private String storeSearch = "";
    private int    storeDetailCodeScroll  = 0;
    private int    storeDetailCodeHScroll = 0; // horizontal pixel offset
    // H-scrollbar geometry cached each render frame (for drag hit-testing)
    private int     storeHBarY       = -1; // -1 = not drawn
    private int     storeHBarX1      = 0;
    private int     storeHBarX2      = 0;
    private int     storeHBarMax     = 0;
    private boolean storeHBarDrag    = false;
    private int     storeHBarDragX   = 0;
    private int     storeHBarDragVal = 0;
    private net.minecraft.client.gui.components.EditBox storeSearchField = null;
    private int     storeScrollBeforeDetail = 0;
    private boolean storeLoadFailed         = false;

    // Delete confirmation dialog state
    private String deleteConfirmPath = null; // null = dialog closed

    // File access warning dialog state
    private String fileAccessConfirmPath = null; // null = dialog closed

    // Submit overlay state
    private boolean         storeSubmitOpen    = false;
    private java.util.List<String> submitScripts = java.util.List.of();
    private String          submitPickedScript = null;
    private EditBox submitDescField    = null;
    private Button    submitAnonBtn      = null;
    private Button    submitOkBtn        = null;
    private Button    submitCancelBtn    = null;
    private boolean         submitAnon         = false;
    private int             submitListScroll   = 0;
    private String          submitStatus       = "";
    private String          submitRealName     = "";
    private String          submitMdContent    = null; // non-null if a .md file was found next to the script
    private boolean         submitKeyWarnAcknowledged = false;
    private String[]        submitOwnedEntry   = null; // [storeId, uuid] if updating, null if new

    // Rename overlay state
    private String          renamingScript  = null;
    private EditBox renameField     = null;
    private Button    renameOkBtn     = null;
    private Button    renameCancelBtn = null;

    // ── Build tab layout ──────────────────────────────────────────────────────
    private static final int BUILD_DIV_X  = 192; // vertical divider offset from px
    private static final int BUILD_TOP_H  = 16;  // script picker bar height
    private static final int BUILD_HDR_H  = 13;  // panel header height (CHAT / CODE + buttons)

    // ── Build tab persistent state (static — survives screen open/close) ─────
    private static BuildSession s_build        = null;
    private static String       s_buildName    = "";
    private static int          s_chatScroll   = 0;  // 0 = latest at bottom
    private static int          s_codeScroll   = 0;  // 0 = top
    private static int          s_lastMsgCount = 0;
    private static int          s_currentTab   = TAB_LOG;

    // ── Text selection (read-only panels) ─────────────────────────────────────
    // Each panel caches its visible lines + layout each frame so mouse hit-testing
    // and Ctrl+C can reference the same data. selLine0 = anchor (fixed on click),
    // selLine1 = cursor (tracks drag). Both are indices into the cached list; -1 = none.
    private static final int C_SEL_BG = 0x554499FF;

    private final List<String> cachedLogDisp   = new ArrayList<>();
    private int logRenderStart = 0, logRenderLineH = 9, logRenderY0 = 0;
    private int logSelLine0 = -1, logSelLine1 = -1;
    private boolean logSelDrag = false;

    private final List<String> cachedChatFlat  = new ArrayList<>();
    private int chatRenderStart = 0, chatRenderLineH = 9, chatRenderY0 = 0;
    private int chatRenderX0 = 0, chatRenderX1 = 0, chatRenderY1 = 0;
    private int chatSelLine0 = -1, chatSelLine1 = -1;
    private boolean chatSelDrag = false;

    private final List<String> cachedCodeLines = new ArrayList<>();
    private int codeRenderStart = 0, codeRenderLineH = 9, codeRenderY0 = 0;
    private int codeRenderX0 = 0, codeRenderX1 = 0, codeRenderY1 = 0;
    private int codeSelLine0 = -1, codeSelLine1 = -1;
    private boolean codeSelDrag = false;

    // ── Build tab per-instance (widgets, render cache) ────────────────────────
    private int             buildChatTotalLines = 0;
    private int             buildCodeTotalLines = 0;
    private EditBox buildScriptField    = null;
    private EditBox buildChatInput      = null;
    private Button    buildSendBtn        = null;
    private Button    buildRunBtn         = null;


    private final List<Button> tabBtns    = new ArrayList<>();
    private final List<Object>       contentWgt = new ArrayList<>();

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public PeripheralScreen() {
        super(Component.literal("Peripheral"));
    }

    // ── Init / tab switching ──────────────────────────────────────────────────

    @Override
    protected void init() {
        px       = (width  - W) / 2;
        py       = (height - H) / 2;
        contentY = py + TH + TB;
        contentH = H  - TH - TB - BH;

        currentTab = s_currentTab;
        buildTabs();
        buildContent();
        refreshScripts();
    }

    private void buildTabs() {
        tabBtns.forEach(this::removeWidget);
        tabBtns.clear();
        String[] labels = {"Log", "Scripts", "Settings", "Store", "Build"};
        int[] widths    = {40, 60, 65, 48, 44};
        int x = px + 2, y = py + TH;
        for (int i = 0; i < 5; i++) {
            final int tab = i;
            Button b = Button.builder(Component.literal(labels[i]), btn -> switchTab(tab))
                .bounds(x, y, widths[i], TB).build();
            tabBtns.add(b);
            addRenderableWidget(b);
            x += widths[i] + 2;
        }
    }

    private void buildContent() {
        cancelRename();
        contentWgt.forEach(w -> {
            if (w instanceof Button b)    removeWidget(b);
            if (w instanceof EditBox t) removeWidget(t);
        });
        contentWgt.clear();
        taskInput = apiKeyField = agentUrlField = modelField = searchField = null;
        buildRunBtn = null;

        switch (currentTab) {
            case TAB_LOG      -> buildLog();
            case TAB_SCRIPTS  -> buildScripts();
            case TAB_SETTINGS -> buildSettings();
            case TAB_STORE    -> buildStore();
            case TAB_BUILD    -> buildBuild();
        }

    }

    private void switchTab(int tab) {
        currentTab              = tab;
        s_currentTab            = tab;
        logScroll               = 0;  logScrollPx    = 0;
        scriptScroll            = 0;  scriptScrollPx = 0;
        searchQuery             = "";
        deleteConfirmPath       = null;
        fileAccessConfirmPath   = null;
        buildContent();
        if (tab == TAB_SCRIPTS) refreshScripts();
        if (tab == TAB_STORE && storeScripts == null) fetchStoreScripts();
        if (tab == TAB_BUILD) { s_chatScroll = 0; s_codeScroll = 0; }
        logSelLine0  = -1; logSelLine1  = -1; logSelDrag  = false;
        chatSelLine0 = -1; chatSelLine1 = -1; chatSelDrag = false;
        codeSelLine0 = -1; codeSelLine1 = -1; codeSelDrag = false;
    }

    // ── Tab builders ──────────────────────────────────────────────────────────

    private void buildLog() {
        int by = py + H - BH + 5;
        taskInput = new EditBox(font, px + 48, by, W - 124, 14, Component.empty());
        taskInput.setMaxLength(256);
        taskInput.setHint(Component.literal("Send task to AI agent…"));
        taskInput.setCanLoseFocus(false);
        add(taskInput);

        Button go    = btn("Go",    px + W - 74, by - 1, 30, 16, b -> sendTask());
        Button stop  = btn("Stop",  px + W - 42, by - 1, 38, 16, b -> sendStop());
        Button clear = btn("Clear", px + 4,      by - 1, 40, 16, b -> { PeripheralStateTracker.clearLog(); logScroll = 0; logScrollPx = 0; });
        add(go); add(stop); add(clear);
    }

    private void buildScripts() {
        // ── Search bar ────────────────────────────────────────────────────────
        int sx = px + 4, sy = contentY + BREAD_H + 2;
        searchField = new EditBox(font, sx, sy, W - 8, SEARCH_H, Component.empty());
        searchField.setMaxLength(64);
        searchField.setHint(Component.literal("Search scripts..."));
        searchField.setCanLoseFocus(true);
        add(searchField);

        // ── Bottom action bar ─────────────────────────────────────────────────
        int by = py + H - BH + 6;
        boolean inExamples = currentFolder.toString().replace(java.io.File.separatorChar, '/').startsWith("examples");
        Button newBtn    = btn("+ New",    px + 4,  by, 48, 14, b -> createNewScript());
        Button folderBtn = btn("+ Folder", px + 56, by, 56, 14, b -> createNewFolder());
        newBtn.active    = !inExamples;
        folderBtn.active = !inExamples;
        add(newBtn);
        add(folderBtn);
        add(btn("Clear HUD", px + W - 126, by, 60, 14, b -> {
            PeripheralHud.clearElements();
        }));
        add(btn("Reload",   px + W - 62, by, 58, 14, b -> refreshScripts()));

        rebuildScriptBtns();
    }

    private void rebuildScriptBtns() {
        // Remove all previously-built per-row buttons
        contentWgt.removeIf(w -> {
            if (w instanceof Button b) {
                String lbl = b.getMessage().getString();
                if (lbl.equals("Run") || lbl.equals("Stop") || lbl.equals("Edit")
                        || lbl.equals("Del")    || lbl.equals("Rename") || lbl.equals("Open")
                        || lbl.equals("Back")
                        || lbl.equals("OFF")    || lbl.equals(" SP")
                        || lbl.equals(" MP")    || lbl.equals("ALL")) {
                    removeWidget(b); return true;
                }
            }
            return false;
        });

        // Apply search filter (case-insensitive substring match on file name)
        java.util.List<ScriptRunner.ScriptInfo> visible = searchQuery.isEmpty()
            ? scripts
            : scripts.stream()
                .filter(s -> {
                    String fn = s.name();
                    if (fn.contains("/")) fn = fn.substring(fn.lastIndexOf('/') + 1);
                    return fn.toLowerCase().contains(searchQuery);
                })
                .collect(Collectors.toList());

        boolean hasParent = !currentFolder.toString().isEmpty() && searchQuery.isEmpty();
        int rowH    = 18;
        // Rows start below breadcrumb + search bar
        int firstY  = contentY + BREAD_H + SEARCH_H + 4;
        int maxRows = (contentH - BREAD_H - SEARCH_H - 6) / rowH;

        // Button layout (right → left), 4 px gap between each:
        // [ Auto 38 ][ Run/Stop 40 ][ Edit 36 ][ Del 28 ][ Rename 44 ][ File 34 ]
        int xAuto   = px + W - 4  - 38;
        int xRunStop= px + W - 4  - 38 - 4 - 40;
        int xEdit   = px + W - 4  - 38 - 4 - 40 - 4 - 36;
        int xDel    = px + W - 4  - 38 - 4 - 40 - 4 - 36 - 4 - 28;
        int xRename = px + W - 4  - 38 - 4 - 40 - 4 - 36 - 4 - 28 - 4 - 44;

        int totalItems = (hasParent ? 1 : 0) + visible.size();
        scriptScroll = Math.max(0, Math.min(scriptScroll, Math.max(0, totalItems - maxRows)));

        int visIdx = 0;

        // ".." back row (only when not searching, so we don't lose folder context)
        if (hasParent) {
            if (0 >= scriptScroll && visIdx < maxRows) {
                int ry = firstY + visIdx * rowH;
                add(btn("Back", xAuto, ry, 38, 14, x -> navigateUp()));
                visIdx++;
            }
        }

        // Script / folder rows
        for (int i = 0; i < visible.size(); i++) {
            int virtualIdx = (hasParent ? 1 : 0) + i;
            if (virtualIdx < scriptScroll) continue;
            if (visIdx >= maxRows) break;

            ScriptRunner.ScriptInfo info = visible.get(i);
            int ry = firstY + visIdx * rowH;

            if (info.isFolder()) {
                // Build the full relative path for this folder (needed by delete/rename)
                String folderRel = currentFolder.toString().isEmpty()
                    ? info.name()
                    : currentFolder.toString().replace(java.io.File.separatorChar, '/') + "/" + info.name();
                add(btn("Open",   xAuto,   ry, 38, 14, x -> navigateInto(info.name())));
                add(btn("Del",    xDel,    ry, 28, 14, x -> { deleteConfirmPath = folderRel; buildContent(); }));
                add(btn("Rename", xRename, ry, 44, 14, x -> startRename(folderRel)));
            } else {
                boolean running = info.running();

                // Auto-run cycle: OFF → SP (singleplayer) → MP (multiplayer) → ALL
                String arMode  = PeripheralConfig.getAutoRun(info.name());
                String arLabel = switch (arMode) {
                    case "world" -> " SP";
                    case "multi" -> " MP";
                    case "both"  -> "ALL";
                    default      -> "OFF";
                };
                add(btn(arLabel,   xAuto,    ry, 38, 14,
                    x -> { PeripheralConfig.cycleAutoRun(info.name()); rebuildScriptBtns(); }));
                add(running
                    ? btn("Stop",  xRunStop, ry, 40, 14,
                        x -> { ScriptRunner.stopScript(info.name()); refreshScripts(); })
                    : btn("Run",   xRunStop, ry, 40, 14,
                        x -> { ScriptRunner.runScript(info.name());  refreshScripts(); }));
                add(btn("Edit",    xEdit,    ry, 36, 14,
                    x -> minecraft.setScreen(new ScriptEditorScreen(PeripheralScreen.this, info.name()))));
                add(btn("Del",     xDel,     ry, 28, 14,
                    x -> { deleteConfirmPath = info.name(); buildContent(); }));
                add(btn("Rename",  xRename,  ry, 44, 14,
                    x -> startRename(info.name())));
            }
            visIdx++;
        }
    }

    private void buildSettings() {
        int lx = px + 8, fy = contentY + 12, fw = W - 84;

        apiKeyField = field(lx + 62, fy, fw, PeripheralConfig.apiKey);
        add(apiKeyField);
        add(btn("Show", px + W - 46, fy - 1, 38, 16, b -> {
            if (apiKeyField != null) {
                boolean showing = b.getMessage().getString().equals("Hide");
                b.setMessage(Component.literal(showing ? "Show" : "Hide"));
                apiKeyField.setHint(Component.literal(showing ? "" : "hidden"));
            }
        }));

        agentUrlField = field(lx + 80, fy + 26, fw - 18, PeripheralConfig.agentUrl);
        add(agentUrlField);

        modelField = field(lx + 52, fy + 52, fw - 18, PeripheralConfig.model);
        add(modelField);

        add(btn("Save", px + W / 2 - 24, fy + 78, 48, 16, b -> saveSettings()));
    }

    // ── Store tab ─────────────────────────────────────────────────────────────

    private void buildStore() {
        storeSubmitOpen = false; submitPickedScript = null; submitOwnedEntry = null;
        submitDescField = null; submitAnonBtn = null; submitOkBtn = null; submitCancelBtn = null;
        submitStatus = ""; submitAnon = false; submitListScroll = 0;

        int by = py + H - BH + 6;

        if (storeDetailEntry != null) {
            // Detail view buttons
            add(btn("← Back", px + 4, by, 46, 14, b -> {
                storeDetailEntry = null;
                storeDetailCodeScroll = 0;
                storeScroll = storeScrollBeforeDetail;
                buildContent();
            }));
            add(btn("⧉ Copy Code", px + (W / 2) - 34, by, 68, 14, b -> {
                String code = storeDetailEntry != null ? storeDetailEntry.code() : null;
                if (code != null && !code.isBlank()) {
                    net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(code);
                    storeStatus = "Copied!";
                } else {
                    storeStatus = "Code not loaded yet.";
                }
            }));
            add(btn("▼ Install", px + W - 64, by, 60, 14, b -> {
                installStoreScript(storeDetailEntry);
            }));
        } else {
            // List view buttons + search field
            add(btn("↺ Refresh", px + 4, by, 60, 14, b -> {
                StoreClient.invalidateCache();
                storeScripts = null; storeDetailEntry = null; storeStatus = ""; fetchStoreScripts();
            }));
            add(btn("+ Submit", px + W - 64, by, 60, 14, b -> openStoreSubmit()));

            // Search field in the bottom bar
            storeSearchField = new net.minecraft.client.gui.components.EditBox(
                font, px + 68, by, W - 136, 14, Component.empty());
            storeSearchField.setMaxLength(60);
            storeSearchField.setHint(Component.literal("Search..."));
            storeSearchField.setValue(storeSearch);
            storeSearchField.setResponder(t -> { storeSearch = t; storeScroll = 0; });
            add(storeSearchField);
        }
    }

    private void buildBuild() {
        int divX = px + BUILD_DIV_X;

        // ── Top bar: script name field + Open + Clear ─────────────────────────
        buildScriptField = new EditBox(font,
            px + 4, contentY + 1, W - 98, 12, Component.empty());
        buildScriptField.setMaxLength(64);
        buildScriptField.setHint(Component.literal("my_script.py"));
        buildScriptField.setValue(s_buildName);
        buildScriptField.setCanLoseFocus(true);
        add(buildScriptField);

        add(btn("Open",  px + W - 90, contentY + 1, 40, 12, b -> openBuildPicker()));
        add(btn("Clear", px + W - 46, contentY + 1, 42, 12, b -> {
            if (s_build != null) { s_build.clearChat(); s_chatScroll = 0; }
        }));

        // ── Right panel header: Run (toggle) + Fix with AI ────────────────────
        int hdrY = contentY + BUILD_TOP_H + 2;
        buildRunBtn = btn("Run", divX + 4, hdrY, 40, 10, b -> toggleBuildScript());
        add(buildRunBtn);
        Button saveBtn = btn("Save", divX + 48, hdrY, 36, 10, b -> {
            if (s_build == null) return;
            String code = s_build.getCode();
            if (code.isBlank()) return;
            try {
                Path scriptPath = ScriptRunner.SCRIPTS_DIR.resolve(s_build.getScriptRelPath());
                Files.createDirectories(scriptPath.getParent());
                Files.writeString(scriptPath, code);
            } catch (Exception e) {
                s_build.status = "Save failed: " + e.getMessage();
            }
        });
        saveBtn.setTooltip(Tooltip.create(Component.literal("Save the current code to the script file")));
        add(saveBtn);
        Button fixBtn = btn("Fix", px + W - 34, hdrY, 30, 10, b -> sendLogToAI());
        fixBtn.setTooltip(Tooltip.create(Component.literal(
            "Reads the script's log file and sends the last 60 lines to the AI to identify and fix errors.")));
        add(fixBtn);

        // ── Bottom strip: chat input + Send ───────────────────────────────────
        int by = py + H - BH + 5;
        buildChatInput = new EditBox(font, px + 4, by, W - 52, 14, Component.empty());
        buildChatInput.setMaxLength(2000);
        buildChatInput.setHint(Component.literal("Describe what you want or ask for changes..."));
        buildChatInput.setCanLoseFocus(true);
        add(buildChatInput);

        buildSendBtn = btn("Send", px + W - 46, by - 1, 42, 16, b -> sendBuildMessage());
        buildSendBtn.active = s_build == null || !s_build.busy;
        add(buildSendBtn);
    }

    private void openBuildPicker() {
        minecraft.setScreen(new BuildPickerScreen(this,
            // onOpen: resume existing session with history
            name -> { openBuildSession(name); minecraft.setScreen(this); },
            // onNew: find a unique script name, clear chat, start fresh
            name -> {
                String unique = uniqueScriptName(name);
                openBuildSession(unique);
                if (s_build != null) { s_build.clearChat(); s_lastMsgCount = 0; }
                minecraft.setScreen(this);
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
        while (Files.exists(ScriptRunner.SCRIPTS_DIR.resolve(candidate))) {
            candidate = base + "_" + n + ".py";
            n++;
        }
        return candidate;
    }

    private void openBuildSession(String name) {
        if (name == null || name.isBlank()) name = "my_script.py";
        if (!name.endsWith(".py")) name += ".py";
        s_buildName = name;
        if (buildScriptField != null) buildScriptField.setValue(name);
        if (s_build == null || !s_build.getScriptRelPath().equals(name)) {
            s_build        = new BuildSession(name);
            s_chatScroll   = 0;
            s_codeScroll   = 0;
            s_lastMsgCount = s_build.getMessages().size();

            // New session with existing code — send the code as initial context
            String code = s_build.getCode();
            if (s_build.getMessages().isEmpty() && !code.isBlank()) {
                sendBuildText("Here is my existing script `" + name + "`:\n```python\n"
                    + code + "\n```\nPlease read it and be ready to help me modify or debug it.");
            }
        }
        if (buildChatInput != null) buildChatInput.setCanLoseFocus(true);
        if (buildSendBtn   != null) buildSendBtn.active = !s_build.busy;
    }

    private void sendBuildMessage() {
        if (buildChatInput == null) return;
        String text = buildChatInput.getValue().trim();
        if (text.isEmpty()) return;
        // Auto-create session from script field if not yet open
        if (s_build == null) {
            String name = buildScriptField != null ? buildScriptField.getValue().trim() : s_buildName;
            openBuildSession(name);
            if (s_build == null) return;
        }
        buildChatInput.setValue("");
        sendBuildText(text);
    }

    private void sendBuildText(String text) {
        if (s_build == null) return;
        if (s_build.busy || text.isEmpty()) return;
        if (PeripheralConfig.apiKey.isEmpty()) {
            s_build.status = "No API key set. Add one in the Settings tab.";
            return;
        }
        if (buildSendBtn != null) buildSendBtn.active = false;
        s_build.send(text, () -> { s_chatScroll = 0; s_codeScroll = 0; });
    }

    private void testBuildScript(boolean stop) {
        if (s_build == null) return;
        String rel = s_build.getScriptRelPath();
        if (stop) { ScriptRunner.stopScript(rel); return; }
        if (!Files.exists(ScriptRunner.SCRIPTS_DIR.resolve(rel))) return;
        ScriptRunner.runScript(rel);
    }

    private void toggleBuildScript() {
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
            // No log yet — just show a hint in the chat input
            if (buildChatInput != null)
                buildChatInput.setValue("Run the script first to generate a log.");
            return;
        }
        // Keep last 60 lines
        String[] lines = logText.split("\n", -1);
        int from = Math.max(0, lines.length - 60);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) sb.append(lines[i]).append('\n');
        String msg = "I ran the script and got this output:\n```\n"
            + sb.toString().strip() + "\n```\nPlease identify any errors and fix them.";
        sendBuildText(msg);
    }

    private void fetchStoreScripts() {
        storeStatus  = "Loading...";
        storeScripts = null;
        storeLoadFailed = false;
        StoreClient.fetchList(list -> {
            storeLoadFailed  = (list == null);
            storeScripts     = list == null ? java.util.List.of() : list;
            storeStatus      = list == null ? "Failed to load store." : "";
            storeDetailEntry = null;
        });
    }

    /** Render the Store tab — list view or detail view. */
    private void renderStore(GuiGraphicsExtractor ctx) {
        int lx    = px + 4;
        int ly    = contentY + 3;
        int lineH = 18;

        // ── Detail view ───────────────────────────────────────────────────────
        if (storeDetailEntry != null) {
            StoreClient.ScriptEntry s = storeDetailEntry;
            int y = ly + 2;

            // Title + author on one line
            ctx.text(font, Component.literal(s.name()), lx + 2, y, C_ORANGE, false);
            String byLine = "by " + s.author();
            ctx.text(font, Component.literal(byLine),
                px + W - 4 - font.width(byLine), y, C_DIM, false);
            y += 11;

            // Description (max 2 lines, then divider)
            String fullDesc = s.description().isEmpty() ? "No description." : s.description();
            int descLines = 0;
            for (String line : wrapText(fullDesc, 58)) {
                if (descLines >= 2) break;
                ctx.text(font, Component.literal(line), lx + 2, y, C_GREY, false);
                y += 9;
                descLines++;
            }
            y += 4;

            // Divider
            ctx.fill(lx + 2, y, px + W - 4, y + 1, C_BORDER);
            y += 4;

            // Source code header
            ctx.text(font, Component.literal("SOURCE CODE"), lx + 2, y, C_ORANGE, false);
            ctx.text(font, Component.literal("↕ scroll  ↔ shift+scroll"),
                px + W - 4 - font.width("↕ scroll  ↔ shift+scroll"), y, C_DIM, false);
            y += 10;

            // Code box — fills most of the remaining space, leaving room for scrollbar + warning
            final int CODE_LINE_H = 9;
            final int SCROLLBAR_H = 7;    // horizontal drag-scrollbar height
            final int WARN_H      = 40;   // warning banner (title + up to 2 wrapped body lines)
            final int CODE_BOX_H  = contentY + contentH - y - SCROLLBAR_H - WARN_H - 18;
            final int CODE_VIS    = Math.max(1, CODE_BOX_H / CODE_LINE_H);
            final int codeBoxX1   = lx + 2;
            final int codeBoxX2   = px + W - 4;
            final int codeBoxW    = codeBoxX2 - codeBoxX1 - 6; // inner pixel width

            ctx.fill(codeBoxX1, y, codeBoxX2, y + CODE_BOX_H, 0xFF0A0A0A);
            ctx.fill(codeBoxX1, y,              codeBoxX2, y + 1,          C_BORDER);
            ctx.fill(codeBoxX1, y + CODE_BOX_H, codeBoxX2, y + CODE_BOX_H + 1, C_BORDER);
            ctx.fill(codeBoxX1, y,              codeBoxX1 + 1, y + CODE_BOX_H, C_BORDER);
            ctx.fill(codeBoxX2 - 1, y,          codeBoxX2, y + CODE_BOX_H,     C_BORDER);

            // Enable scissor clipping so text can't bleed outside the box
            ctx.enableScissor(codeBoxX1 + 1, y + 1, codeBoxX2 - 1, y + CODE_BOX_H);

            String code = s.code();
            int maxLineLen = 0;
            int maxHScroll = 0;
            if (code == null || code.isBlank()) {
                ctx.text(font, Component.literal("Loading..."), codeBoxX1 + 4, y + 4, C_DIM, false);
            } else {
                String[] codeLines = code.split("\n", -1);
                int maxVScroll = Math.max(0, codeLines.length - CODE_VIS);
                storeDetailCodeScroll = Math.max(0, Math.min(storeDetailCodeScroll, maxVScroll));

                for (String cl : codeLines) maxLineLen = Math.max(maxLineLen, font.width(cl));
                maxHScroll = Math.max(0, maxLineLen - codeBoxW);
                storeDetailCodeHScroll = Math.max(0, Math.min(storeDetailCodeHScroll, maxHScroll));

                int cy = y + 3;
                for (int cl = storeDetailCodeScroll;
                     cl < Math.min(codeLines.length, storeDetailCodeScroll + CODE_VIS); cl++) {
                    ctx.text(font, Component.literal(codeLines[cl]),
                        codeBoxX1 + 4 - storeDetailCodeHScroll, cy, 0xFF88CC88, false);
                    cy += CODE_LINE_H;
                }
            }
            ctx.disableScissor();

            // Horizontal scrollbar (immediately below code box)
            final int sbY = y + CODE_BOX_H + 1;
            storeHBarY   = sbY;
            storeHBarX1  = codeBoxX1 + 1;
            storeHBarX2  = codeBoxX2 - 1;
            storeHBarMax = maxHScroll;
            ctx.fill(storeHBarX1, sbY, storeHBarX2, sbY + SCROLLBAR_H, 0xFF141414);
            if (maxHScroll > 0) {
                int trackW = storeHBarX2 - storeHBarX1;
                int thumbW = Math.max(16, trackW * codeBoxW / Math.max(maxLineLen, 1));
                thumbW = Math.min(thumbW, trackW);
                int thumbX = storeHBarX1 + (int)((long)(trackW - thumbW) * storeDetailCodeHScroll / maxHScroll);
                ctx.fill(thumbX, sbY + 1, thumbX + thumbW, sbY + SCROLLBAR_H - 1,
                    storeHBarDrag ? 0xFF888888 : 0xFF555555);
            } else {
                storeHBarY = -1; // disable drag if no horizontal overflow
            }

            // Line indicator right-aligned in scrollbar strip
            if (code != null && !code.isBlank()) {
                String[] codeLines = code.split("\n", -1);
                String ind = (storeDetailCodeScroll + 1) + "-"
                    + Math.min(codeLines.length, storeDetailCodeScroll + CODE_VIS)
                    + "/" + codeLines.length;
                ctx.text(font, Component.literal(ind),
                    codeBoxX2 - font.width(ind) - 2, sbY + 1, C_DIM, false);
            }

            y += CODE_BOX_H + SCROLLBAR_H + 4;

            // Warning banner
            final int warnX1 = lx + 2;
            final int warnX2 = px + W - 4;
            final int warnInnerW = warnX2 - warnX1 - 10; // available text width
            ctx.fill(warnX1, y, warnX2, y + WARN_H, 0xFF180E00);
            ctx.fill(warnX1, y, warnX1 + 2, y + WARN_H, 0xFFFF8800);
            ctx.text(font,
                Component.literal("§6⚠  USE AT YOUR OWN RISK — Community Scripts"),
                warnX1 + 6, y + 4, 0xFFFFAA00, false);
            int wy = y + 14;
            for (String wl : wrapTextPx(
                    "The author of Peripheral is not liable for any damage caused by scripts from this store.",
                    warnInnerW)) {
                ctx.text(font, Component.literal("§7" + wl), warnX1 + 6, wy, C_DIM, false);
                wy += 9;
            }

            // Status
            if (!storeStatus.isEmpty())
                ctx.text(font, Component.literal(storeStatus),
                    lx, py + H - BH + 9, C_GREEN, false);
            if (storeSubmitOpen) renderStoreSubmit(ctx);
            return;
        }

        // ── List view ─────────────────────────────────────────────────────────
        if (storeScripts == null) {
            ctx.text(font, Component.literal(storeStatus.isEmpty() ? "Loading..." : storeStatus),
                lx, ly + 4, C_GREY, false);
            if (storeSubmitOpen) renderStoreSubmit(ctx);
            return;
        }

        String q = storeSearch.toLowerCase();
        java.util.List<StoreClient.ScriptEntry> visible = storeScripts.stream()
            .filter(s -> q.isEmpty()
                || s.name().toLowerCase().contains(q)
                || s.description().toLowerCase().contains(q)
                || s.author().toLowerCase().contains(q))
            .toList();

        if (visible.isEmpty()) {
            if (storeScripts.isEmpty()) {
                if (storeLoadFailed) {
                    ctx.text(font, Component.literal("§cFailed to load store."), lx, ly + 4, C_GREY, false);
                    ctx.text(font, Component.literal("§eTry hitting Refresh."), lx, ly + 14, C_DIM, false);
                    ctx.text(font, Component.literal("§7If that doesn't work, check your internet connection."), lx, ly + 24, C_DIM, false);
                } else {
                    ctx.text(font, Component.literal("No scripts found."), lx, ly + 4, C_GREY, false);
                    ctx.text(font, Component.literal("§7Try hitting Refresh — the store may not have loaded."), lx, ly + 14, C_DIM, false);
                }
            } else {
                ctx.text(font, Component.literal("No results."), lx, ly + 4, C_GREY, false);
            }
            if (storeSubmitOpen) renderStoreSubmit(ctx);
            return;
        }

        int maxRows = (contentH - 6) / lineH;
        int total   = visible.size();
        storeScroll = Math.max(0, Math.min(storeScroll, Math.max(0, total - maxRows)));

        int y = ly;
        for (int i = storeScroll; i < Math.min(total, storeScroll + maxRows); i++) {
            StoreClient.ScriptEntry s = visible.get(i);

            ctx.fill(px + 1, y, px + W - 1, y + lineH - 1,
                i % 2 == 0 ? 0xFF111111 : 0xFF161616);
            ctx.fill(px + 1, y + lineH - 1, px + W - 1, y + lineH, C_BORDER);

            // Name left, author right
            ctx.text(font, Component.literal(s.name()), lx + 2, y + 2, C_ORANGE, false);
            String byLine = "by " + s.author();
            ctx.text(font, Component.literal(byLine),
                px + W - 4 - font.width(byLine), y + 2, C_DIM, false);

            // Description snippet
            String desc = s.description();
            if (desc.length() > 62) desc = desc.substring(0, 59) + "...";
            ctx.text(font, Component.literal(desc), lx + 2, y + 10, C_GREY, false);

            y += lineH;
            if (y >= contentY + contentH - 2) break;
        }

        // Row count hint
        if (total > maxRows) {
            String hint = (storeScroll + 1) + "-" + Math.min(total, storeScroll + maxRows) + " / " + total;
            ctx.text(font, Component.literal(hint),
                px + W - 4 - font.width(hint), py + H - BH + 9, C_DIM, false);
        }

        if (!storeStatus.isEmpty())
            ctx.text(font, Component.literal(storeStatus),
                lx, py + H - BH + 9, C_GREY, false);

        if (storeSubmitOpen) renderStoreSubmit(ctx);
    }

    /** Word-wrap text to maxChars per line. */
    /** Wrap text to fit within maxPx pixels using the current textRenderer. */
    private java.util.List<String> wrapTextPx(String text, int maxPx) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String w : words) {
            String test = line.length() > 0 ? line + " " + w : w;
            if (font.width(test) > maxPx && line.length() > 0) {
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

    private java.util.List<String> wrapText(String text, int maxChars) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String w : words) {
            if (line.length() + w.length() + 1 > maxChars && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder();
            }
            if (line.length() > 0) line.append(' ');
            line.append(w);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        super.tick();
        if (saveTick > 0) saveTick--;
        // Pick up file access requests from running scripts
        String far = ScriptRunner.fileAccessRequestScript;
        if (far != null && fileAccessConfirmPath == null) {
            ScriptRunner.fileAccessRequestScript = null;
            fileAccessConfirmPath = far;
        }
        if (currentTab == TAB_BUILD) {
            if (buildSendBtn != null) buildSendBtn.active = (s_build == null || !s_build.busy);
            if (s_build != null) {
                if (buildRunBtn != null) {
                    boolean running = ScriptRunner.isRunning(s_build.getScriptRelPath());
                    buildRunBtn.setMessage(Component.literal(running ? "Running" : "Run"));
                }
                int msgCount = s_build.getMessages().size();
                if (msgCount != s_lastMsgCount) {
                    s_lastMsgCount = msgCount;
                    s_chatScroll   = 0;
                    s_codeScroll   = 0;
                }
            }
        }
        if (currentTab == TAB_SCRIPTS) {
            // Poll search field for query changes and rebuild the list if needed
            if (searchField != null) {
                String q = searchField.getValue().toLowerCase();
                if (!q.equals(searchQuery)) {
                    searchQuery  = q;
                    scriptScroll = 0;
                    scriptScrollPx = 0;
                    scriptBtnsDirty = true;
                }
            }
            if (++scriptRefreshTick >= 10) {
                scriptRefreshTick = 0;
                refreshScripts();
            }
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        // Suppress the blur pass — applyBlur() can only fire once per frame.
        // We draw our own dark veil in extractRenderState() instead.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mx, int my, float delta) {
        ctx.fill(0, 0, width, height, 0x88000000);
        ctx.fill(px, py, px + W, py + H, C_PANEL);
        ctx.fill(px, py, px + W, py + TH, C_TITLE);
        ctx.text(font, Component.literal("■"), px + 4, py + 3, C_ORANGE, false);
        ctx.text(font, Component.literal("PERIPHERAL"), px + 4 + font.width("■ "), py + 3, C_WHITE, false);
        String st = PeripheralStateTracker.status.toUpperCase();
        if (PeripheralStateTracker.iterations > 0) st += " #" + PeripheralStateTracker.iterations;
        ctx.text(font, Component.literal(st), px + 4 + font.width("■ PERIPHERAL  "), py + 3, C_YELLOW, false);

        ctx.fill(px, py + TH, px + W, py + TH + TB, C_TABBAR);
        int[] tabX = {px + 2, px + 44, px + 106, px + 173, px + 223};
        int[] tabW = {40,      60,       65,       48,       44};
        ctx.fill(tabX[currentTab], py + TH, tabX[currentTab] + tabW[currentTab], py + TH + TB, C_TABACT);
        // Orange 2px bottom edge on active tab
        ctx.fill(tabX[currentTab], py + TH + TB - 2, tabX[currentTab] + tabW[currentTab], py + TH + TB, C_ORANGE);

        ctx.fill(px + 1, contentY, px + W - 1, contentY + contentH, C_CONTENT);
        ctx.fill(px, contentY + contentH, px + W, contentY + contentH + 1, C_BORDER);

        // Flush any pending script-button rebuild exactly once per frame.
        if (scriptBtnsDirty && currentTab == TAB_SCRIPTS) {
            scriptBtnsDirty = false;
            rebuildScriptBtns();
        }

        switch (currentTab) {
            case TAB_LOG      -> renderLog(ctx, mx, my);
            case TAB_SCRIPTS  -> renderScripts(ctx);
            case TAB_SETTINGS -> renderSettings(ctx);
            case TAB_STORE    -> renderStore(ctx);
            case TAB_BUILD    -> renderBuild(ctx, mx, my);
        }

        // Bottom status strip
        if (currentTab == TAB_SCRIPTS) {
            long total   = scripts.stream().filter(i -> !i.isFolder()).count();
            long running = scripts.stream().filter(i -> !i.isFolder() && i.running()).count();
            String strip = total + (total == 1 ? " script" : " scripts");
            if (running > 0) strip += "  ·  " + running + " running";
            ctx.text(font, Component.literal(strip), px + 116, py + H - BH + 9, C_GREY, false);
        }

        super.extractRenderState(ctx, mx, my, delta);

        // File access warning dialog — drawn before delete confirm so delete is on top
        if (fileAccessConfirmPath != null) renderFileAccessConfirm(ctx, mx, my);
        // Delete confirmation dialog
        if (deleteConfirmPath != null) renderDeleteConfirm(ctx, mx, my);
        // Rename overlay — drawn last so it's always on top of all buttons
        if (renamingScript != null) renderRenameOverlay(ctx, mx, my, delta);
    }

    // ── Tab renders ───────────────────────────────────────────────────────────



    private void renderLog(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
        logUrlByY.clear();

        List<String> log = PeripheralStateTracker.getLog(80);
        int lineH = 9;
        int maxW  = W - 10;

        // Expand each log entry into wrapped display lines
        List<String>  dispText = new ArrayList<>();
        List<Integer> dispIdx  = new ArrayList<>();
        for (int i = 0; i < log.size(); i++) {
            for (String seg : wrapLogLine(log.get(i), maxW)) {
                dispText.add(seg);
                dispIdx.add(i);
            }
        }

        int vis    = (contentH - 4) / lineH;
        int maxScr = Math.max(0, dispText.size() - vis);
        logScroll  = Math.min(logScroll, maxScr);
        int start  = Math.max(0, dispText.size() - vis - logScroll);
        int end    = Math.min(dispText.size(), start + vis);

        // Cache for selection hit-testing
        cachedLogDisp.clear();
        cachedLogDisp.addAll(dispText);
        logRenderStart = start;
        logRenderLineH = lineH;
        logRenderY0    = contentY + 3;

        boolean mouseInLog = mouseX >= px + 2 && mouseX < px + W - 2;

        for (int i = start; i < end; i++) {
            String line      = dispText.get(i);
            int    ty        = contentY + 3 + (i - start) * lineH;
            int    baseColor = logColor(log.get(dispIdx.get(i)));
            boolean hovered  = mouseInLog && mouseY >= ty && mouseY < ty + lineH;

            // Selection highlight
            if (logSelLine0 >= 0) {
                int selS = Math.min(logSelLine0, logSelLine1);
                int selE = Math.max(logSelLine0, logSelLine1);
                if (i >= selS && i <= selE)
                    ctx.fill(px + 2, ty - 1, px + W - 6, ty + lineH - 1, C_SEL_BG);
            }

            if (hovered)
                ctx.fill(px + 2, ty - 1, px + W - 2, ty + lineH - 1, 0x22FFFFFF);

            Matcher m = URL_PAT.matcher(line);
            if (m.find()) {
                String url    = m.group();
                logUrlByY.put(ty, url);
                int urlColor  = hovered ? C_URL_HOVER : C_URL;
                String before = line.substring(0, m.start());
                String after  = line.substring(m.end());

                int x = px + 4;
                if (!before.isEmpty()) {
                    ctx.text(font, Component.literal(before), x, ty, baseColor, false);
                    x += font.width(before);
                }
                ctx.text(font, Component.literal(url), x, ty, urlColor, false);
                int uw = font.width(url);
                ctx.fill(x, ty + lineH - 1, x + uw, ty + lineH, urlColor);
                if (!after.isEmpty()) {
                    ctx.text(font, Component.literal(after), x + uw, ty, baseColor, false);
                }
            } else {
                ctx.text(font, Component.literal(line), px + 4, ty, baseColor, false);
            }
        }

    }

    /** Splits a log line into segments that each fit within maxW pixels. */
    private List<String> wrapLogLine(String line, int maxW) {
        if (line.isEmpty()) return List.of("");
        List<String> result = new ArrayList<>();
        while (font.width(line) > maxW) {
            int cut = line.length();
            while (cut > 0 && font.width(line.substring(0, cut)) > maxW) cut--;
            if (cut == 0) cut = 1;
            int space = line.lastIndexOf(' ', cut - 1);
            if (space > 0) cut = space + 1;
            result.add(line.substring(0, cut));
            line = line.substring(cut);
        }
        if (!line.isEmpty()) result.add(line);
        return result;
    }

    private static int logColor(String l) {
        if (l.contains("BARITONE"))                            return C_CYAN;
        if (l.contains("CRAFT"))                               return C_YELLOW;
        if (l.contains("DONE"))                                return 0xFF00FF00;
        if (l.contains("ERROR") || l.contains("FAIL"))        return C_RED;
        if (l.contains("WAIT"))                                return 0xFFFFFF00;
        if (l.contains("SCRIPT") || l.contains("script"))     return 0xFFAAEEFF;
        if (l.contains("STARTING") || l.contains("starting")) return 0xFF88FFAA;
        return C_WHITE;
    }

    private void renderScripts(GuiGraphicsExtractor ctx) {
        // ── Breadcrumb ────────────────────────────────────────────────────────
        String crumb = "scripts";
        if (!currentFolder.toString().isEmpty())
            crumb += " / " + currentFolder.toString()
                .replace(java.io.File.separatorChar, '/').replace("/", " / ");
        ctx.text(font, Component.literal("▶ " + crumb), px + 4, contentY + 2, C_GREY, false);

        // ── Script rows (below breadcrumb + search bar) ───────────────────────
        boolean hasParent = !currentFolder.toString().isEmpty() && searchQuery.isEmpty();
        int rowH    = 18;
        int firstY  = contentY + BREAD_H + SEARCH_H + 4;
        int maxRows = (contentH - BREAD_H - SEARCH_H - 6) / rowH;

        // Apply same filter as rebuildScriptBtns
        java.util.List<ScriptRunner.ScriptInfo> visible = searchQuery.isEmpty()
            ? scripts
            : scripts.stream()
                .filter(s -> {
                    String fn = s.name();
                    if (fn.contains("/")) fn = fn.substring(fn.lastIndexOf('/') + 1);
                    return fn.toLowerCase().contains(searchQuery);
                })
                .collect(Collectors.toList());

        int totalItems = (hasParent ? 1 : 0) + visible.size();

        if (totalItems == 0) {
            String hint = searchQuery.isEmpty()
                ? "No scripts or folders here.  Press '+ New' to create one."
                : "No scripts match \"" + searchQuery + "\".";
            ctx.text(font, Component.literal(hint), px + 8, firstY + 4, C_GREY, false);
            return;
        }

        // Button x-positions mirror rebuildScriptBtns
        int xRename = px + W - 4 - 38 - 4 - 40 - 4 - 36 - 4 - 28 - 4 - 44;

        int visIdx = 0;

        // ".." back row
        if (hasParent) {
            if (0 >= scriptScroll && visIdx < maxRows) {
                int ry = firstY + visIdx * rowH;
                ctx.fill(px + 2, ry - 1, xRename - 2, ry + rowH - 3, 0xCC111111);
                ctx.text(font, Component.literal("← .."), px + 8, ry + 2, C_GREY, false);
                visIdx++;
            }
        }

        // Folder / script rows
        for (int i = 0; i < visible.size(); i++) {
            int virtualIdx = (hasParent ? 1 : 0) + i;
            if (virtualIdx < scriptScroll) continue;
            if (visIdx >= maxRows) break;

            ScriptRunner.ScriptInfo info = visible.get(i);
            int ry = firstY + visIdx * rowH;

            if (info.isFolder()) {
                ctx.fill(px + 2, ry - 1, xRename - 2, ry + rowH - 3, 0xCC141408);
                ctx.text(font, Component.literal("/" + info.name()),
                    px + 8, ry + 2, C_YELLOW, false);
            } else {
                boolean run   = info.running();
                String arMode = PeripheralConfig.getAutoRun(info.name());
                boolean arOn  = !arMode.equals("off");

                int rowBg = run ? 0xCC0D1E0D : (arOn ? 0xCC0D0D1C : 0xCC111111);
                ctx.fill(px + 2, ry - 1, xRename - 2, ry + rowH - 3, rowBg);
                // Status dot: green = running, blue = auto-run enabled, dim = idle
                ctx.fill(px + 5, ry + 3, px + 10, ry + 9,
                    run ? 0xFF00FF88 : (arOn ? 0xFF3399FF : C_DIM));

                // File name — strip folder prefix, max 22 chars before buttons
                String fn = info.name();
                if (fn.contains("/")) fn = fn.substring(fn.lastIndexOf('/') + 1);
                // Check if this script has a hotkey slot assigned
                String slotPrefix = "";
                for (int si = 1; si <= 5; si++) {
                    if (PeripheralConfig.getScriptSlot(si).equals(info.name())) {
                        slotPrefix = "§6[" + si + "] ";
                        break;
                    }
                }
                String label = fn.length() > 22 ? fn.substring(0, 19) + "..." : fn;
                ctx.text(font, Component.literal(slotPrefix + label),
                    px + 14, ry + 2, run ? 0xFFCCFFDD : C_WHITE, false);

                // Runtime counter (while running) or auto-run slot badge
                if (run && info.startedAt() > 0) {
                    long s = Instant.now().getEpochSecond() - info.startedAt();
                    ctx.text(font,
                        Component.literal(s < 60 ? s + "s" : (s / 60) + "m" + (s % 60) + "s"),
                        px + 145, ry + 2, C_GREY, false);
                } else {
                    PeripheralConfig.getScriptSlots().forEach((slot, fname) -> {
                        if (fname.equals(info.name()))
                            ctx.text(font, Component.literal("[" + slot + "]"),
                                px + 145, ry + 2, C_DIM, false);
                    });
                }
            }
            visIdx++;
        }

    }

    private void renderRenameOverlay(GuiGraphicsExtractor ctx, int mx, int my, float delta) {
        if (renamingScript == null) return;
        // Dim over everything (including script buttons rendered by super.render())
        ctx.fill(px + 1, contentY, px + W - 1, contentY + contentH, 0xBB000000);
        int bx  = px + W / 2 - 90;
        int bby = contentY + contentH / 2 - 25;
        int bw  = 180, bh = 50;
        ctx.fill(bx, bby, bx + bw, bby + bh, 0xFF1A1A1A);
        ctx.fill(bx,          bby,          bx + bw, bby + 1,    C_BORDER);
        ctx.fill(bx,          bby + bh - 1, bx + bw, bby + bh,   C_BORDER);
        ctx.fill(bx,          bby,          bx + 1,  bby + bh,    C_BORDER);
        ctx.fill(bx + bw - 1, bby,          bx + bw, bby + bh,   C_BORDER);
        // Orange title bar
        ctx.fill(bx + 1, bby + 1, bx + bw - 1, bby + 14, 0xFF1C1C1C);
        ctx.fill(bx + 1, bby + 13, bx + bw - 1, bby + 14, C_BORDER);
        ctx.centeredText(font, Component.literal("Rename Script"), bx + bw / 2, bby + 3, C_ORANGE);
        // Re-render widgets on top of the overlay background
        if (renameField     != null) renameField.extractRenderState(ctx, mx, my, delta);
        if (renameOkBtn     != null) renameOkBtn.extractRenderState(ctx, mx, my, delta);
        if (renameCancelBtn != null) renameCancelBtn.extractRenderState(ctx, mx, my, delta);
    }

    private void renderSettings(GuiGraphicsExtractor ctx) {
        int lx = px + 8, fy = contentY + 12;
        ctx.text(font, Component.literal("API Key:"),   lx, fy + 3,   C_ORANGE, false);
        ctx.text(font, Component.literal("Agent URL:"), lx, fy + 29,  C_ORANGE, false);
        ctx.text(font, Component.literal("Model:"),     lx, fy + 55,  C_ORANGE, false);
        if (saveTick > 0)
            ctx.text(font, Component.literal(saveMsg),
                px + W / 2 - 20, fy + 100, saveMsg.startsWith("Error") ? C_RED : C_GREEN, false);
    }

    private void renderBuild(GuiGraphicsExtractor ctx, int mx, int my) {
        int divX  = px + BUILD_DIV_X;
        int lineH = 9;

        // ── Layout bounds ─────────────────────────────────────────────────────
        int topSep  = contentY + BUILD_TOP_H;          // separator under script bar
        int hdrY    = topSep + 1;                      // panel header row
        int hdrSep  = hdrY + BUILD_HDR_H;              // separator under headers
        int panelY0 = hdrSep + 1;                      // content starts here
        int panelY1 = contentY + contentH;

        // Chat panel bounds (left of divider)
        int chatX0 = px + 1;
        int chatX1 = divX;
        int chatW  = chatX1 - chatX0;

        // Code panel bounds (right of divider)
        int codeX0 = divX + 1;
        int codeX1 = px + W - 1;
        int codeW  = codeX1 - codeX0;

        // ── Structural lines ──────────────────────────────────────────────────
        ctx.fill(px + 1, topSep,  px + W - 1, topSep + 1,  C_BORDER);  // under top bar
        ctx.fill(divX,   hdrY,    divX + 1,   panelY1,      C_BORDER);  // vertical divider
        ctx.fill(px + 1, hdrSep,  px + W - 1, hdrSep + 1,  C_BORDER);  // under panel headers

        // ── Panel header labels ────────────────────────────────────────────────
        ctx.text(font, Component.literal("CHAT"),
            chatX0 + 3, hdrY + 2, C_ORANGE, false);
        ctx.text(font, Component.literal("CODE"),
            codeX0 + 3, hdrY + 2, C_ORANGE, false);

        // Running status in code header (between CODE label and buttons)
        if (s_build != null) {
            boolean running = ScriptRunner.isRunning(s_build.getScriptRelPath());
            String dot = running ? "§a●" : "§7○";
            ctx.text(font, Component.literal(dot),
                codeX0 + 3 + font.width("CODE") + 5, hdrY + 2, C_WHITE, false);
        }

        int visLines = Math.max(1, (panelY1 - panelY0) / lineH);

        // ── No session: placeholder ───────────────────────────────────────────
        if (s_build == null) {
            int wrapW = chatW - 8;
            ctx.enableScissor(chatX0, panelY0, chatX1, panelY1);
            int ty = panelY0 + 3;
            for (String hint : new String[]{
                    "Type a script name and press Open.",
                    "Chat history reloads each session.",
                    "AI writes code directly to the file.",
                    "Use Fix after running to send errors."}) {
                for (String wl : wrapTextPx(hint, wrapW)) {
                    ctx.text(font, Component.literal("§7" + wl),
                        chatX0 + 4, ty, C_DIM, false);
                    ty += lineH;
                }
                ty += 2; // gap between hints
            }
            ctx.disableScissor();
            return;
        }

        // ── Chat: build flat line list ────────────────────────────────────────
        int chatWrapW = chatW - 12; // leave room for scroll bar + padding
        java.util.List<String>  flatLines  = new java.util.ArrayList<>();
        java.util.List<Integer> flatColors = new java.util.ArrayList<>();

        java.util.List<BuildSession.ChatMessage> msgs = s_build.getMessages();
        for (int mi = 0; mi < msgs.size(); mi++) {
            BuildSession.ChatMessage m = msgs.get(mi);
            boolean isUser = m.role().equals("user");
            flatLines.add(isUser ? "§eYOU" : "§bAI");
            flatColors.add(isUser ? C_YELLOW : C_CYAN);
            boolean inCode = false;
            int codeCount  = 0;
            for (String raw : m.content().split("\n", -1)) {
                if (raw.startsWith("```")) {
                    if (!inCode) { inCode = true; codeCount = 0; }
                    else {
                        inCode = false;
                        if (codeCount > 0) {
                            flatLines.add("  §7[" + codeCount + "L written to script]");
                            flatColors.add(C_DIM);
                        }
                    }
                    continue;
                }
                if (inCode) { codeCount++; continue; }
                if (raw.isBlank()) { flatLines.add(""); flatColors.add(0); continue; }
                for (String wl : wrapTextPx(raw, chatWrapW)) {
                    flatLines.add("  " + wl); flatColors.add(C_WHITE);
                }
            }
            flatLines.add(""); flatColors.add(0); // blank gap after message
        }
        if (s_build.busy) { flatLines.add("  §7Thinking…"); flatColors.add(C_GREY); }
        if (!s_build.status.isEmpty() && !s_build.busy) {
            for (String wl : wrapTextPx(s_build.status, chatWrapW)) {
                flatLines.add("  §c" + wl); flatColors.add(C_RED);
            }
        }

        // Cache for selection hit-testing
        cachedChatFlat.clear();
        cachedChatFlat.addAll(flatLines);
        chatRenderStart = 0; // will be set after startLine is computed
        chatRenderLineH = lineH;
        chatRenderY0    = panelY0 + 2;
        chatRenderX0    = chatX0;
        chatRenderX1    = chatX1;
        chatRenderY1    = panelY1;

        buildChatTotalLines = flatLines.size();
        int chatMaxScr = Math.max(0, buildChatTotalLines - visLines);
        s_chatScroll   = Math.max(0, Math.min(s_chatScroll, chatMaxScr));

        int startLine = Math.max(0, buildChatTotalLines - visLines - s_chatScroll);
        chatRenderStart = startLine;

        ctx.enableScissor(chatX0, panelY0, chatX1, panelY1);
        for (int i = startLine; i < Math.min(flatLines.size(), startLine + visLines); i++) {
            int ry = panelY0 + 2 + (i - startLine) * lineH;
            // Selection highlight
            if (chatSelLine0 >= 0) {
                int selS = Math.min(chatSelLine0, chatSelLine1);
                int selE = Math.max(chatSelLine0, chatSelLine1);
                if (i >= selS && i <= selE)
                    ctx.fill(chatX0 + 1, ry - 1, chatX1 - 4, ry + lineH - 1, C_SEL_BG);
            }
            String line = flatLines.get(i);
            if (!line.isEmpty())
                ctx.text(font, Component.literal(line),
                    chatX0 + 4, ry,
                    flatColors.get(i), false);
        }
        ctx.disableScissor();

        // Chat scroll bar
        if (chatMaxScr > 0) {
            int sbH  = panelY1 - panelY0;
            int barH = Math.max(8, sbH * visLines / Math.max(buildChatTotalLines, 1));
            float frac = (float) s_chatScroll / chatMaxScr;
            int barY = panelY0 + (int) ((sbH - barH) * (1f - frac));
            ctx.fill(chatX1 - 3, panelY0, chatX1, panelY1, C_SCROLL);
            ctx.fill(chatX1 - 3, barY,    chatX1, barY + barH, C_SCROLL_THUMB);
        }

        // ── Code panel ────────────────────────────────────────────────────────
        String   code    = s_build.getCode();
        String[] codeArr = code.isEmpty()
            ? new String[]{"§7(send a message to generate code)"}
            : code.split("\n", -1);

        // Cache for selection hit-testing
        cachedCodeLines.clear();
        for (String cl : codeArr) cachedCodeLines.add(cl);
        codeRenderStart = s_codeScroll;
        codeRenderLineH = lineH;
        codeRenderY0    = panelY0 + 2;
        codeRenderX0    = codeX0;
        codeRenderX1    = codeX1;
        codeRenderY1    = panelY1;

        buildCodeTotalLines = codeArr.length;
        int codeMaxScr = Math.max(0, codeArr.length - visLines);
        s_codeScroll   = Math.max(0, Math.min(s_codeScroll, codeMaxScr));

        ctx.enableScissor(codeX0, panelY0, codeX1, panelY1);
        for (int i = s_codeScroll; i < Math.min(codeArr.length, s_codeScroll + visLines); i++) {
            int cly = panelY0 + 2 + (i - s_codeScroll) * lineH;
            // Selection highlight
            if (codeSelLine0 >= 0) {
                int selS = Math.min(codeSelLine0, codeSelLine1);
                int selE = Math.max(codeSelLine0, codeSelLine1);
                if (i >= selS && i <= selE)
                    ctx.fill(codeX0 + 1, cly - 1, codeX1 - 4, cly + lineH - 1, C_SEL_BG);
            }
            ctx.text(font, Component.literal(codeArr[i]), codeX0 + 4, cly, 0xFF88CC88, false);
        }
        ctx.disableScissor();

        // Code scroll bar
        if (codeMaxScr > 0) {
            int sbH  = panelY1 - panelY0;
            int barH = Math.max(8, sbH * visLines / Math.max(codeArr.length, 1));
            float frac = (float) s_codeScroll / codeMaxScr;
            int barY = panelY0 + (int) ((sbH - barH) * frac);
            ctx.fill(codeX1 - 3, panelY0, codeX1, panelY1, C_SCROLL);
            ctx.fill(codeX1 - 3, barY,    codeX1, barY + barH, C_SCROLL_THUMB);
        }

        // AI busy status in bottom strip
        if (s_build.busy)
            ctx.text(font, Component.literal("§7Thinking…"),
                px + 4, py + H - BH + 5, C_GREY, false);
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean focused) {
        // Left-click on a log row — open URL if one exists, otherwise start selection
        if (click.button() == 0 && currentTab == TAB_LOG) {
            double mx = click.x(), my = click.y();
            if (mx >= px + 2 && mx < px + W - 2) {
                for (Map.Entry<Integer, String> entry : logUrlByY.entrySet()) {
                    int rowY = entry.getKey();
                    if (my >= rowY && my < rowY + 10) {
                        try { Util.getPlatform().openUri(URI.create(entry.getValue())); }
                        catch (Exception ignored) {}
                        return true;
                    }
                }
                // Start selection
                if (!cachedLogDisp.isEmpty() && my >= logRenderY0 && my < contentY + contentH) {
                    int li = Math.max(0, Math.min(
                        logRenderStart + (int)((my - logRenderY0) / Math.max(1, logRenderLineH)),
                        cachedLogDisp.size() - 1));
                    logSelLine0 = li; logSelLine1 = li; logSelDrag = true;
                }
            }
        }
        // Left-click in build panels — start selection
        if (click.button() == 0 && currentTab == TAB_BUILD && s_build != null) {
            double mx = click.x(), my = click.y();
            if (my >= chatRenderY0 && my < chatRenderY1) {
                if (mx >= chatRenderX0 && mx < chatRenderX1 && !cachedChatFlat.isEmpty()) {
                    int li = Math.max(0, Math.min(
                        chatRenderStart + (int)((my - chatRenderY0) / Math.max(1, chatRenderLineH)),
                        cachedChatFlat.size() - 1));
                    chatSelLine0 = li; chatSelLine1 = li; chatSelDrag = true;
                    codeSelLine0 = -1;
                } else if (mx >= codeRenderX0 && mx < codeRenderX1 && !cachedCodeLines.isEmpty()) {
                    int li = Math.max(0, Math.min(
                        codeRenderStart + (int)((my - codeRenderY0) / Math.max(1, codeRenderLineH)),
                        cachedCodeLines.size() - 1));
                    codeSelLine0 = li; codeSelLine1 = li; codeSelDrag = true;
                    chatSelLine0 = -1;
                }
            }
        }
        // File access warning dialog — intercept all clicks
        if (click.button() == 0 && fileAccessConfirmPath != null) {
            int dlgX = px + (W - FA_DLG_W) / 2;
            int dlgY = py + (H - FA_DLG_H) / 2;
            double cx = click.x(), cy = click.y();
            // Allow button
            int enX = dlgX + 8, enY = dlgY + FA_DLG_H - 20, enW = 84, enH = 14;
            if (cx >= enX && cx < enX + enW && cy >= enY && cy < enY + enH) {
                PeripheralConfig.setFileAccess(fileAccessConfirmPath, true);
                fileAccessConfirmPath = null;
                buildContent();
                return true;
            }
            // Deny button
            int ccX = dlgX + FA_DLG_W - 8 - 72, ccY = enY, ccW = 72, ccH = 14;
            if (cx >= ccX && cx < ccX + ccW && cy >= ccY && cy < ccY + ccH) {
                fileAccessConfirmPath = null;
                buildContent();
                return true;
            }
            // Click outside dialog → cancel
            if (cx < dlgX || cx >= dlgX + FA_DLG_W || cy < dlgY || cy >= dlgY + FA_DLG_H) {
                fileAccessConfirmPath = null;
                buildContent();
            }
            return true;
        }
        // Delete confirmation dialog — intercept all clicks
        if (click.button() == 0 && deleteConfirmPath != null) {
            int dlgH = Files.isDirectory(ScriptRunner.SCRIPTS_DIR.resolve(deleteConfirmPath)) ? DLG_H_FOLDER : DLG_H;
            int dlgX = px + (W - DLG_W) / 2;
            int dlgY = py + (H - dlgH) / 2;
            double cx = click.x(), cy = click.y();
            // Confirm button
            int cfmX = dlgX + 8, cfmY = dlgY + dlgH - 18, cfmW = 78, cfmH = 14;
            if (cx >= cfmX && cx < cfmX + cfmW && cy >= cfmY && cy < cfmY + cfmH) {
                deleteScript(deleteConfirmPath);
                deleteConfirmPath = null;
                buildContent();
                return true;
            }
            // Cancel button
            int cclX = dlgX + DLG_W - 8 - 72, cclY = cfmY, cclW = 72, cclH = 14;
            if (cx >= cclX && cx < cclX + cclW && cy >= cclY && cy < cclY + cclH) {
                deleteConfirmPath = null;
                buildContent();
                return true;
            }
            // Click outside dialog → cancel
            if (cx < dlgX || cx >= dlgX + DLG_W || cy < dlgY || cy >= dlgY + dlgH) {
                deleteConfirmPath = null;
                buildContent();
            }
            return true;
        }
        // Submit overlay script-list clicks
        if (click.button() == 0 && currentTab == TAB_STORE && storeSubmitOpen) {
            int bx    = px + (W - SUB_W) / 2;
            int by    = contentY + (contentH - SUB_H) / 2;
            int listY = by + 26;
            double cx = click.x(), cy = click.y();
            if (cx >= bx + 4 && cx < bx + SUB_W - 4 && cy >= listY && cy < listY + SUB_VIS * SUB_ROW) {
                int row = (int)((cy - listY) / SUB_ROW) + submitListScroll;
                if (row >= 0 && row < submitScripts.size()) {
                    submitPickedScript = submitScripts.get(row);
                    submitMdContent    = checkForMd(submitPickedScript);
                    submitOwnedEntry   = loadOwnedEntry(submitPickedScript);
                    if (submitOkBtn != null)
                        submitOkBtn.setMessage(Component.literal(submitOwnedEntry != null ? "Update" : "Submit"));
                }
                return true;
            }
            // Dispatch to buttons/text-field first, then swallow the rest of the panel
            if (cx >= bx && cx < bx + SUB_W && cy >= by && cy < by + SUB_H) {
                super.mouseClicked(click, focused);
                return true;
            }
        }
        // Store tab — list row clicks open detail view
        if (click.button() == 0 && currentTab == TAB_STORE && !storeSubmitOpen
                && storeDetailEntry == null && storeScripts != null) {
            String q = storeSearch.toLowerCase();
            java.util.List<StoreClient.ScriptEntry> visible = storeScripts.stream()
                .filter(s -> q.isEmpty()
                    || s.name().toLowerCase().contains(q)
                    || s.description().toLowerCase().contains(q)
                    || s.author().toLowerCase().contains(q))
                .toList();
            int lineH = 18, y = contentY + 3;
            int maxRows = (contentH - 6) / lineH;
            for (int i = storeScroll; i < Math.min(visible.size(), storeScroll + maxRows); i++) {
                StoreClient.ScriptEntry s = visible.get(i);
                if (click.y() >= y && click.y() < y + lineH) {
                    // Open detail view — fetch full script for install
                    storeScrollBeforeDetail = storeScroll;
                    storeDetailEntry = s;
                    storeStatus = "";
                    StoreClient.fetchScript(s.id(), full -> {
                        if (full != null) storeDetailEntry = full;
                    });
                    buildContent(); // rebuild buttons for detail view
                    return true;
                }
                y += lineH;
                if (y >= contentY + contentH - 2) break;
            }
        }
        // Horizontal scrollbar drag start
        if (click.button() == 0 && storeDetailEntry != null && storeHBarY >= 0) {
            int cx = (int) click.x(), cy = (int) click.y();
            if (cy >= storeHBarY && cy < storeHBarY + 7 && cx >= storeHBarX1 && cx < storeHBarX2) {
                storeHBarDrag    = true;
                storeHBarDragX   = cx;
                storeHBarDragVal = storeDetailCodeHScroll;
                return true;
            }
        }
        return super.mouseClicked(click, focused);
    }

    private void installStoreScript(StoreClient.ScriptEntry entry) {
        if (entry == null || entry.code() == null) { storeStatus = "No code to install."; return; }
        storeStatus = "Installing...";
        java.nio.file.Path scriptsDir = java.nio.file.Paths.get("config", "peripheral", "scripts");
        StoreClient.install(entry, scriptsDir, result -> {
            if (result != null) {
                storeStatus = "Installed: " + result;
                // Refresh script list so it shows up immediately
                scripts.clear();
                scriptBtnsDirty = true;
                refreshScripts();
            } else {
                storeStatus = "Install failed.";
            }
        });
    }

    // ── Store submit overlay ──────────────────────────────────────────────────

    // Panel dimensions: 340 wide × 174 tall, centred in content area
    private static final int SUB_W  = 340;
    private static final int SUB_H  = 174;
    private static final int SUB_ROW = 13; // script list row height
    private static final int SUB_VIS = 4;  // visible script rows

    /** Open the submit overlay: load local script list and create widgets. */
    // ── Store ownership helpers ───────────────────────────────────────────────
    private static final Path OWNED_FILE =
        java.nio.file.Paths.get("config", "peripheral", "store_owned.json");

    /** Returns [storeId, uuid] for a local script path, or null if not recorded. */
    private static String[] loadOwnedEntry(String localScript) {
        try {
            if (!Files.exists(OWNED_FILE)) return null;
            com.google.gson.JsonObject obj = com.google.gson.JsonParser
                .parseString(Files.readString(OWNED_FILE)).getAsJsonObject();
            if (!obj.has(localScript)) return null;
            com.google.gson.JsonObject e = obj.getAsJsonObject(localScript);
            return new String[]{e.get("id").getAsString(), e.get("uuid").getAsString()};
        } catch (Exception ignored) { return null; }
    }

    private static void saveOwnedEntry(String localScript, String storeId, String uuid) {
        try {
            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            if (Files.exists(OWNED_FILE))
                obj = com.google.gson.JsonParser
                    .parseString(Files.readString(OWNED_FILE)).getAsJsonObject();
            com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
            entry.addProperty("id",   storeId);
            entry.addProperty("uuid", uuid);
            obj.add(localScript, entry);
            Files.createDirectories(OWNED_FILE.getParent());
            Files.writeString(OWNED_FILE,
                new com.google.gson.GsonBuilder().create().toJson(obj));
        } catch (Exception e) {
            PeripheralClient.LOGGER.warn("[Store] save owned failed: {}", e.getMessage());
        }
    }

    private void openStoreSubmit() {
        submitKeyWarnAcknowledged = false;
        closeStoreSubmit();
        submitScripts = ScriptRunner.listAllScripts().stream()
            .filter(s -> !s.startsWith("examples/") && !s.equals("examples"))
            .collect(java.util.stream.Collectors.toList());
        if (!submitScripts.isEmpty()) {
            submitPickedScript = submitScripts.get(0);
            submitMdContent    = checkForMd(submitPickedScript);
            submitOwnedEntry   = loadOwnedEntry(submitPickedScript);
        } else {
            submitOwnedEntry = null;
        }

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        submitRealName = (mc.player != null) ? mc.player.getName().getString() : "Unknown";

        storeSubmitOpen = true;

        int bx = px + (W - SUB_W) / 2;
        int by = contentY + (contentH - SUB_H) / 2;

        // Description field — below "DESCRIPTION" label at by+85
        submitDescField = new EditBox(font, bx + 4, by + 97, SUB_W - 8, 14, Component.empty());
        submitDescField.setMaxLength(200);
        submitDescField.setCanLoseFocus(false);
        add(submitDescField);
        setFocused(submitDescField);

        // Anonymous toggle — "Post as:" row
        submitAnonBtn = btn("Anon: OFF", bx + 4, by + 119, 60, 12, b -> {
            submitAnon = !submitAnon;
            submitAnonBtn.setMessage(Component.literal("Anon: " + (submitAnon ? "ON " : "OFF")));
        });
        add(submitAnonBtn);

        // Action buttons — bottom-right
        int btnY = by + SUB_H - 18;
        submitOkBtn     = btn(submitOwnedEntry != null ? "Update" : "Submit",
                              bx + SUB_W - 4 - 58, btnY, 58, 14, b -> doSubmit());
        submitCancelBtn = btn("Cancel", bx + SUB_W - 4 - 58 - 4 - 46, btnY, 46, 14, b -> closeStoreSubmit());
        add(submitOkBtn);
        add(submitCancelBtn);
    }

    /** Close the submit overlay and remove its widgets. */
    private void closeStoreSubmit() {
        if (submitDescField   != null) { removeWidget(submitDescField);   contentWgt.remove(submitDescField);   submitDescField   = null; }
        if (submitAnonBtn     != null) { removeWidget(submitAnonBtn);     contentWgt.remove(submitAnonBtn);     submitAnonBtn     = null; }
        if (submitOkBtn       != null) { removeWidget(submitOkBtn);       contentWgt.remove(submitOkBtn);       submitOkBtn       = null; }
        if (submitCancelBtn   != null) { removeWidget(submitCancelBtn);   contentWgt.remove(submitCancelBtn);   submitCancelBtn   = null; }
        storeSubmitOpen = false; submitPickedScript = null; submitRealName = "";
        submitStatus = ""; submitAnon = false; submitListScroll = 0; submitMdContent = null;
        submitOwnedEntry = null;
    }

    /** Render the submit overlay — Litematica-inspired dark panel with orange accents. */
    private void renderStoreSubmit(GuiGraphicsExtractor ctx) {
        // Litematica palette
        final int BG       = 0xFF111111; // panel background
        final int TITLE_BG = 0xFF1C1C1C; // title bar
        final int ORANGE   = 0xFFFF8800; // section headers + title
        final int BORDER   = 0xFF333333; // panel + list borders
        final int LIST_BG  = 0xFF0D0D0D; // list area background
        final int ROW_ALT  = 0xFF161616; // alternating row shade
        final int ROW_SEL  = 0xFF1A2E1A; // selected row
        final int TXT      = 0xFFCCCCCC; // primary text
        final int TXT_DIM  = 0xFF666666; // secondary / disclaimer text
        final int TXT_SEL  = 0xFFAAFF88; // selected row text
        final int TXT_PATH = 0xFF555555; // folder path in list

        int bx = px + (W - SUB_W) / 2;
        int by = contentY + (contentH - SUB_H) / 2;

        // ── Backdrop ──────────────────────────────────────────────────────────
        ctx.fill(px + 1, contentY, px + W - 1, contentY + contentH, 0xCC000000);

        // ── Panel ─────────────────────────────────────────────────────────────
        ctx.fill(bx, by, bx + SUB_W, by + SUB_H, BG);
        ctx.fill(bx,             by,             bx + SUB_W,     by + 1,        BORDER);
        ctx.fill(bx,             by + SUB_H - 1, bx + SUB_W,     by + SUB_H,    BORDER);
        ctx.fill(bx,             by,             bx + 1,         by + SUB_H,    BORDER);
        ctx.fill(bx + SUB_W - 1, by,             bx + SUB_W,     by + SUB_H,    BORDER);

        // ── Title bar ─────────────────────────────────────────────────────────
        ctx.fill(bx + 1, by + 1, bx + SUB_W - 1, by + 15, TITLE_BG);
        ctx.fill(bx + 1, by + 14, bx + SUB_W - 1, by + 15, BORDER);
        ctx.centeredText(font,
            Component.literal(submitOwnedEntry != null ? "Update Script in Store" : "Submit Script to Store"),
            bx + SUB_W / 2, by + 4, ORANGE);

        // ── SCRIPT section ────────────────────────────────────────────────────
        ctx.text(font, Component.literal("SCRIPT"), bx + 4, by + 19, ORANGE, false);
        ctx.fill(bx + 40, by + 22, bx + SUB_W - 4, by + 23, BORDER);

        int listTop = by + 26;
        int total   = submitScripts.size();
        submitListScroll = Math.max(0, Math.min(submitListScroll, Math.max(0, total - SUB_VIS)));

        // List box
        ctx.fill(bx + 4, listTop, bx + SUB_W - 4, listTop + SUB_VIS * SUB_ROW, LIST_BG);
        ctx.fill(bx + 4, listTop,                          bx + SUB_W - 4, listTop + 1,              BORDER);
        ctx.fill(bx + 4, listTop + SUB_VIS * SUB_ROW - 1,  bx + SUB_W - 4, listTop + SUB_VIS * SUB_ROW, BORDER);
        ctx.fill(bx + 4, listTop, bx + 5, listTop + SUB_VIS * SUB_ROW, BORDER);
        ctx.fill(bx + SUB_W - 5, listTop, bx + SUB_W - 4, listTop + SUB_VIS * SUB_ROW, BORDER);

        if (total == 0) {
            ctx.text(font, Component.literal("No local scripts found."),
                bx + 9, listTop + 4, TXT_DIM, false);
        } else {
            for (int i = submitListScroll; i < Math.min(total, submitListScroll + SUB_VIS); i++) {
                String s    = submitScripts.get(i);
                int    ry   = listTop + (i - submitListScroll) * SUB_ROW;
                boolean sel = s.equals(submitPickedScript);
                // Row background
                ctx.fill(bx + 5, ry, bx + SUB_W - 5, ry + SUB_ROW,
                    sel ? ROW_SEL : (i % 2 == 0 ? LIST_BG : ROW_ALT));
                // Script name + folder
                String fname  = s.contains("/") ? s.substring(s.lastIndexOf('/') + 1) : s;
                if (fname.endsWith(".py")) fname = fname.substring(0, fname.length() - 3);
                String folder = s.contains("/") ? "  " + s.substring(0, s.lastIndexOf('/')) : "";
                ctx.text(font, Component.literal(fname), bx + 9, ry + 3, sel ? TXT_SEL : TXT, false);
                if (!folder.isEmpty())
                    ctx.text(font, Component.literal(folder), bx + 9 + font.width(fname), ry + 3, TXT_PATH, false);
            }
            // Scroll hint (only if list overflows)
            if (total > SUB_VIS) {
                String hint = (submitListScroll + 1) + "-" + Math.min(total, submitListScroll + SUB_VIS) + "/" + total;
                ctx.text(font, Component.literal(hint),
                    bx + SUB_W - 4 - font.width(hint) - 1,
                    listTop + SUB_VIS * SUB_ROW - 10, TXT_DIM, false);
            }
        }

        // ── DESCRIPTION section ───────────────────────────────────────────────
        ctx.text(font, Component.literal("DESCRIPTION"), bx + 4, by + 83, ORANGE, false);
        ctx.fill(bx + 68, by + 86, bx + SUB_W - 4, by + 87, BORDER);
        // If a .md file was found, show its name instead of hinting at the text field
        if (submitMdContent != null && submitPickedScript != null) {
            String mdName = submitPickedScript.contains("/")
                ? submitPickedScript.substring(submitPickedScript.lastIndexOf('/') + 1)
                : submitPickedScript;
            mdName = mdName.replaceFirst("\\.py$", "") + ".md";
            ctx.text(font, Component.literal("Using " + mdName),
                bx + 4, by + 100, 0xFF44FF44, false);
            ctx.text(font, Component.literal("(text field ignored)"),
                bx + 4 + font.width("Using " + mdName) + 4, by + 100, TXT_DIM, false);
        }
        // submitDescField always rendered — active when no .md found, ignored when one is

        // ── POST AS row ───────────────────────────────────────────────────────
        // submitAnonBtn rendered by widget system
        String name = submitAnon ? "Anonymous" : submitRealName;
        ctx.text(font, Component.literal("Post as:"), bx + 68, by + 121, TXT_DIM, false);
        ctx.text(font, Component.literal(name), bx + 68 + font.width("Post as: "), by + 121,
            submitAnon ? TXT_DIM : TXT, false);
        if (submitAnon)
            ctx.text(font, Component.literal("  (admins see real name)"),
                bx + 68 + font.width("Post as: " + name), by + 121, TXT_DIM, false);

        // ── Ownership / disclaimer ────────────────────────────────────────────
        ctx.fill(bx + 4, by + 134, bx + SUB_W - 4, by + 135, BORDER);
        if (submitOwnedEntry != null) {
            ctx.text(font, Component.literal("§aPreviously submitted · will go back to pending review"),
                bx + 4, by + 139, TXT_DIM, false);
        } else {
            ctx.text(font, Component.literal("Scripts become public once approved. The mod author is not liable."),
                bx + 4, by + 139, TXT_DIM, false);
        }

        // ── Status / validation ────────────────────────────────────────────────
        if (!submitStatus.isEmpty()) {
            boolean ok = submitStatus.startsWith("Submitted");
            ctx.text(font, Component.literal(submitStatus),
                bx + 4, by + SUB_H - 16, ok ? 0xFF44FF44 : 0xFFFF4444, false);
        }
    }

    /** Returns the content of a .md file next to the given script, or null if none exists. */
    private String checkForMd(String relPath) {
        if (relPath == null) return null;
        String mdRel = relPath.endsWith(".py") ? relPath.substring(0, relPath.length() - 3) + ".md" : relPath + ".md";
        java.nio.file.Path p = ScriptRunner.SCRIPTS_DIR.resolve(mdRel);
        try {
            if (java.nio.file.Files.exists(p))
                return java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        return null;
    }

    /** Read the picked script and POST it to the store. */
    private void doSubmit() {
        if (submitPickedScript == null)   { submitStatus = "Pick a script first."; return; }
        // Prefer .md file content; fall back to text field
        String desc = (submitMdContent != null && !submitMdContent.isBlank())
            ? submitMdContent
            : (submitDescField != null ? submitDescField.getValue().trim() : "");
        if (desc.isEmpty())               { submitStatus = "Add a description or a .md file."; return; }

        java.nio.file.Path path = ScriptRunner.SCRIPTS_DIR.resolve(submitPickedScript);
        String content;
        try {
            content = java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            submitStatus = "Could not read script.";
            return;
        }

        // Warn if script appears to contain API keys
        java.util.regex.Matcher keyMatcher = java.util.regex.Pattern.compile(
            "sk-[a-zA-Z0-9\\-_]{20,}|api[_\\-]?key\\s*[=:]\\s*['\"][^'\"]{10,}['\"]",
            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(content);
        if (keyMatcher.find() && !submitKeyWarnAcknowledged) {
            submitStatus = "§c\u26a0 Script may contain an API key! Remove it before submitting. Click Submit again to override.";
            submitKeyWarnAcknowledged = true;
            return;
        }

        // Strip .py and folder prefix for the display name
        String scriptName = submitPickedScript;
        if (scriptName.contains("/")) scriptName = scriptName.substring(scriptName.lastIndexOf('/') + 1);
        if (scriptName.endsWith(".py")) scriptName = scriptName.substring(0, scriptName.length() - 3);

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        String realName    = (mc.player != null) ? mc.player.getName().getString() : "Unknown";
        String uuid        = (mc.player != null) ? mc.player.getUUID().toString()  : "";

        if (submitOwnedEntry != null) {
            // Update existing submission
            String storeId  = submitOwnedEntry[0];
            String ownUuid  = submitOwnedEntry[1];
            submitStatus = "Updating...";
            StoreClient.update(storeId, ownUuid, scriptName, desc, content, ok -> {
                submitStatus = ok ? "Updated! Pending re-review." : "Update failed — UUID mismatch or server error.";
            });
        } else {
            // New submission
            String displayName = submitAnon ? "Anonymous" : realName;
            String finalScript = submitPickedScript;
            submitStatus = "Submitting...";
            StoreClient.submit(scriptName, desc, displayName, realName, content, uuid, id -> {
                if (id != null) {
                    submitStatus = "Submitted! Pending review.";
                    saveOwnedEntry(finalScript, id, uuid);
                    submitOwnedEntry = new String[]{id, uuid};
                    if (submitOkBtn != null)
                        submitOkBtn.setMessage(Component.literal("Update"));
                } else {
                    submitStatus = "Failed. Try again.";
                }
            });
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        // Mirrors Minecraft's EntryListWidget:
        //   setScrollAmount(scrollAmount - verticalAmount * itemHeight / 2)
        // Keeping a double scroll-pixel position means even the tiny fractional
        // deltas a Mac trackpad sends (0.05, 0.1, …) accumulate without being
        // truncated to zero.  We derive the integer line/row index from it after
        // each event.
        //
        // LOG rows are 9 px tall  → scale = 9 * 0.5 = 4.5
        // SCRIPT rows are 18 px tall → scale = 18 * 0.5 = 9
        if (currentTab == TAB_LOG) {
            int lineH = 9, maxW = W - 10;
            List<String> log = PeripheralStateTracker.getLog(80);
            int dispCount = 0;
            for (String ln : log) dispCount += wrapLogLine(ln, maxW).size();
            int vis    = (contentH - 4) / lineH;
            int maxScr = Math.max(0, dispCount - vis);
            logScrollPx = Math.max(0, Math.min(logScrollPx - vAmt * (lineH * 0.5), maxScr * lineH));
            logScroll   = (int)(logScrollPx / lineH);
        } else if (currentTab == TAB_SCRIPTS) {
            int rowH = 18;
            boolean hasParent = !currentFolder.toString().isEmpty();
            int totalItems = (hasParent ? 1 : 0) + scripts.size();
            int maxRows    = (contentH - BREAD_H - 4) / rowH;
            int maxScr     = Math.max(0, totalItems - maxRows);
            scriptScrollPx  = Math.max(0, Math.min(scriptScrollPx - vAmt * (rowH * 0.5), maxScr * rowH));
            scriptScroll    = (int)(scriptScrollPx / rowH);
            scriptBtnsDirty = true;
        } else if (currentTab == TAB_STORE && storeSubmitOpen) {
            int maxScr = Math.max(0, submitScripts.size() - SUB_VIS);
            submitListScroll = (int) Math.max(0, Math.min(submitListScroll - vAmt, maxScr));
        } else if (currentTab == TAB_STORE && storeDetailEntry != null) {
            // Scroll code block in detail view
            String code = storeDetailEntry.code();
            if (code != null) {
                if (hAmt != 0) {
                    storeDetailCodeHScroll = (int) Math.max(0, storeDetailCodeHScroll + hAmt * 6);
                } else {
                    int lines  = code.split("\n", -1).length;
                    int maxScr = Math.max(0, lines - 6);
                    storeDetailCodeScroll = (int) Math.max(0, Math.min(storeDetailCodeScroll - vAmt, maxScr));
                }
            }
        } else if (currentTab == TAB_STORE && storeScripts != null) {
            int rowH    = 18;
            int maxRows = (contentH - 6) / rowH;
            int maxScr  = Math.max(0, storeScripts.size() - maxRows);
            storeScroll = (int) Math.max(0, Math.min(storeScroll - vAmt, maxScr));
        } else if (currentTab == TAB_BUILD) {
            int lineH  = 9;
            int panelH = contentY + contentH - (contentY + BUILD_TOP_H + BUILD_HDR_H + 2);
            int vis    = Math.max(1, panelH / lineH);
            if (mx < px + BUILD_DIV_X) {
                // Left panel — chat (0 = latest at bottom)
                int maxScr = Math.max(0, buildChatTotalLines - vis);
                s_chatScroll = (int) Math.max(0, Math.min(s_chatScroll - vAmt, maxScr));
            } else {
                // Right panel — code (0 = top)
                int maxScr = Math.max(0, buildCodeTotalLines - vis);
                s_codeScroll = (int) Math.max(0, Math.min(s_codeScroll - vAmt, maxScr));
            }
        }
        return true;
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (input.hasControlDown() && input.input() == GLFW.GLFW_KEY_C) {
            String text = null;
            if (currentTab == TAB_LOG && logSelLine0 >= 0 && !cachedLogDisp.isEmpty()) {
                text = extractSelLines(cachedLogDisp, logSelLine0, logSelLine1);
            } else if (currentTab == TAB_BUILD) {
                if (chatSelLine0 >= 0 && !cachedChatFlat.isEmpty())
                    text = extractSelLines(cachedChatFlat, chatSelLine0, chatSelLine1);
                else if (codeSelLine0 >= 0 && !cachedCodeLines.isEmpty())
                    text = extractSelLines(cachedCodeLines, codeSelLine0, codeSelLine1);
            }
            if (text != null) {
                Minecraft.getInstance().keyboardHandler.setClipboard(text);
                return true;
            }
        }
        return super.keyPressed(input);
    }

    private String extractSelLines(List<String> lines, int l0, int l1) {
        int s = Math.max(0, Math.min(l0, l1));
        int e = Math.min(lines.size() - 1, Math.max(l0, l1));
        if (s > e) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = s; i <= e; i++) {
            if (i > s) sb.append('\n');
            sb.append(lines.get(i).replaceAll("§.", ""));
        }
        return sb.toString();
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent click, double dx, double dy) {
        if (click.button() == 0 && storeHBarDrag && storeHBarMax > 0) {
            int trackW = storeHBarX2 - storeHBarX1;
            if (trackW > 0) {
                int delta = (int) click.x() - storeHBarDragX;
                storeDetailCodeHScroll = Math.max(0,
                    Math.min(storeHBarDragVal + delta * storeHBarMax / trackW, storeHBarMax));
            }
            return true;
        }
        // Selection drag — extend selection line
        if (click.button() == 0) {
            double my = click.y();
            if (logSelDrag && !cachedLogDisp.isEmpty()) {
                logSelLine1 = Math.max(0, Math.min(
                    logRenderStart + (int)((my - logRenderY0) / Math.max(1, logRenderLineH)),
                    cachedLogDisp.size() - 1));
                return true;
            }
            if (chatSelDrag && !cachedChatFlat.isEmpty()) {
                chatSelLine1 = Math.max(0, Math.min(
                    chatRenderStart + (int)((my - chatRenderY0) / Math.max(1, chatRenderLineH)),
                    cachedChatFlat.size() - 1));
                return true;
            }
            if (codeSelDrag && !cachedCodeLines.isEmpty()) {
                codeSelLine1 = Math.max(0, Math.min(
                    codeRenderStart + (int)((my - codeRenderY0) / Math.max(1, codeRenderLineH)),
                    cachedCodeLines.size() - 1));
                return true;
            }
        }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent click) {
        if (click.button() == 0 && storeHBarDrag) {
            storeHBarDrag = false;
            return true;
        }
        if (click.button() == 0) {
            logSelDrag = false;
            chatSelDrag = false;
            codeSelDrag = false;
        }
        return super.mouseReleased(click);
    }

    // ── Agent communication ───────────────────────────────────────────────────

    private void sendTask() {
        if (taskInput == null) return;
        String t = taskInput.getValue().trim();
        if (t.isEmpty()) return;
        agentPost("/task", "{\"task\":\"" + esc(t) + "\"}");
        taskInput.setValue("");
    }

    private void sendStop() { agentPost("/stop", "{\"type\":\"stop\"}"); }

    private void agentPost(String path, String json) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(PeripheralConfig.agentUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignored) {}
    }

    // ── Settings save ─────────────────────────────────────────────────────────

    private void saveSettings() {
        try {
            if (apiKeyField   != null) PeripheralConfig.apiKey   = apiKeyField.getValue().trim();
            if (agentUrlField != null) PeripheralConfig.agentUrl = agentUrlField.getValue().trim();
            if (modelField    != null) PeripheralConfig.model    = modelField.getValue().trim();
            PeripheralConfig.save();
            saveMsg  = "Saved!";
            saveTick = 60;
        } catch (Exception e) {
            saveMsg  = "Error: " + e.getMessage();
            saveTick = 80;
        }
    }

    // ── Folder navigation ─────────────────────────────────────────────────────

    private void navigateInto(String folderName) {
        currentFolder  = currentFolder.resolve(folderName);
        scriptScroll   = 0;  scriptScrollPx = 0;
        refreshScripts();
    }

    private void navigateUp() {
        Path parent   = currentFolder.getParent();
        currentFolder = (parent == null) ? Path.of("") : parent;
        scriptScroll  = 0;  scriptScrollPx = 0;
        refreshScripts();
    }

    private void createNewFolder() {
        String name = "folder";
        int n = 2;
        Path dir = ScriptRunner.SCRIPTS_DIR.resolve(currentFolder);
        while (Files.exists(dir.resolve(name))) {
            name = "folder_" + n++;
        }
        try {
            Files.createDirectories(dir.resolve(name));
            refreshScripts();
        } catch (Exception ignored) {}
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void refreshScripts() {
        scripts = ScriptRunner.listEntries(currentFolder);
        if (currentTab == TAB_SCRIPTS) rebuildScriptBtns();
    }

    private void add(Button b)    { addRenderableWidget(b); contentWgt.add(b); }
    private void add(EditBox f) { addRenderableWidget(f); contentWgt.add(f); }

    private Button btn(String label, int x, int y, int w, int h,
                              Button.OnPress action) {
        return Button.builder(Component.literal(label), action).bounds(x, y, w, h).build();
    }

    private EditBox field(int x, int y, int w, String initial) {
        EditBox f = new EditBox(font, x, y, w, 14, Component.empty());
        f.setMaxLength(512);
        f.setCanLoseFocus(false);
        f.setValue(initial);
        return f;
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // ── Rename overlay ────────────────────────────────────────────────────────

    private void startRename(String relPath) {
        cancelRename();
        renamingScript = relPath;
        int bx  = px + W / 2 - 90;
        int bby = contentY + contentH / 2 - 25;

        // Pre-fill with just the filename (not full relative path)
        String fn = relPath.contains("/") ? relPath.substring(relPath.lastIndexOf('/') + 1) : relPath;
        renameField = new EditBox(font, bx + 4, bby + 14, 172, 14, Component.empty());
        renameField.setMaxLength(64);
        renameField.setCanLoseFocus(false);
        renameField.setValue(fn);
        renameField.moveCursorToStart(false);
        renameField.setHighlightPos(fn.length());
        add(renameField);
        setFocused(renameField);
        renameOkBtn     = btn("OK",     bx + 34, bby + 33, 44, 13, x -> finishRename());
        renameCancelBtn = btn("Cancel", bx + 84, bby + 33, 50, 13, x -> cancelRename());
        add(renameOkBtn);
        add(renameCancelBtn);
    }

    private void finishRename() {
        if (renamingScript == null || renameField == null) return;
        String newName = renameField.getValue().trim();
        String old = renamingScript;
        cancelRename();
        if (!newName.isEmpty()) renameScript(old, newName);
    }

    private void cancelRename() {
        if (renameField     != null) { removeWidget(renameField);     contentWgt.remove(renameField);     renameField     = null; }
        if (renameOkBtn     != null) { removeWidget(renameOkBtn);     contentWgt.remove(renameOkBtn);     renameOkBtn     = null; }
        if (renameCancelBtn != null) { removeWidget(renameCancelBtn); contentWgt.remove(renameCancelBtn); renameCancelBtn = null; }
        renamingScript = null;
    }

    private void renameScript(String oldRelPath, String newName) {
        // Strip path traversal
        newName = newName.replace("/", "").replace("\\", "").replace("..", "").trim();
        if (newName.isEmpty() || newName.equals(".py")) return;
        boolean isDir = Files.isDirectory(ScriptRunner.SCRIPTS_DIR.resolve(oldRelPath));
        if (!isDir && !newName.endsWith(".py")) newName += ".py";
        // Keep the same folder
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

    // ── Script file helpers ───────────────────────────────────────────────────

    private void createNewScript() {
        String name = "script.py";
        int n = 2;
        Path dir = ScriptRunner.SCRIPTS_DIR.resolve(currentFolder);
        while (Files.exists(dir.resolve(name))) {
            name = "script_" + n++ + ".py";
        }
        try {
            String template = "from mc import *\n\n# Your script here\nmsg(\"Hello!\")\n";
            Files.writeString(dir.resolve(name), template);
            // Build relative path from SCRIPTS_DIR
            String relPath = ScriptRunner.SCRIPTS_DIR.relativize(dir.resolve(name))
                             .toString().replace(java.io.File.separatorChar, '/');
            refreshScripts();
            minecraft.setScreen(new ScriptEditorScreen(this, relPath));
        } catch (Exception ignored) {}
    }

    private static final int DLG_W = 190, DLG_H = 62, DLG_H_FOLDER = 74;
    private static final int FA_DLG_W = W - 20, FA_DLG_H = 100;

    private void renderFileAccessConfirm(GuiGraphicsExtractor ctx, int mx, int my) {
        int dlgX = px + (W - FA_DLG_W) / 2;
        int dlgY = py + (H - FA_DLG_H) / 2;
        int innerW = FA_DLG_W - 16;

        ctx.fill(px, py, px + W, py + H, 0x88000000);

        ctx.fill(dlgX, dlgY, dlgX + FA_DLG_W, dlgY + FA_DLG_H, C_PANEL);
        ctx.fill(dlgX,                 dlgY,                dlgX + FA_DLG_W, dlgY + 1,           C_BORDER);
        ctx.fill(dlgX,                 dlgY + FA_DLG_H - 1, dlgX + FA_DLG_W, dlgY + FA_DLG_H,   C_BORDER);
        ctx.fill(dlgX,                 dlgY,                dlgX + 1,         dlgY + FA_DLG_H,   C_BORDER);
        ctx.fill(dlgX + FA_DLG_W - 1, dlgY,                dlgX + FA_DLG_W,  dlgY + FA_DLG_H,   C_BORDER);
        ctx.fill(dlgX + 1, dlgY + 1, dlgX + FA_DLG_W - 1, dlgY + 3, C_ORANGE);

        // Script name (short label)
        String scriptLabel = fileAccessConfirmPath;
        if (scriptLabel != null && scriptLabel.contains("/"))
            scriptLabel = scriptLabel.substring(scriptLabel.lastIndexOf('/') + 1);

        ctx.text(font, Component.literal("§6\u26a0 File Access Requested"), dlgX + 8, dlgY + 7, C_ORANGE, false);

        int wy = dlgY + 18;
        ctx.text(font,
            Component.literal("§c" + scriptLabel + " §7tried to write a file."),
            dlgX + 8, wy, C_RED, false);
        wy += 10;

        for (String line : wrapTextPx("If you allow this, the script can read and write ANY file on your computer. Only allow scripts you fully trust.", innerW)) {
            ctx.text(font, Component.literal("§7" + line), dlgX + 8, wy, C_DIM, false);
            wy += 9;
        }

        // Enable button
        int enX = dlgX + 8, enY = dlgY + FA_DLG_H - 20, enW = 84, enH = 14;
        boolean enHover = mx >= enX && mx < enX + enW && my >= enY && my < enY + enH;
        ctx.fill(enX, enY, enX + enW, enY + enH, enHover ? 0xFFCC5500 : 0xFFAA4400);
        ctx.fill(enX, enY, enX + enW, enY + 1, 0xFFFF7700);
        ctx.text(font, Component.literal("Allow"),
            enX + (enW - font.width("Allow")) / 2, enY + 3, C_WHITE, false);

        // Deny button
        int ccX = dlgX + FA_DLG_W - 8 - 72, ccY = enY, ccW = 72, ccH = 14;
        boolean ccHover = mx >= ccX && mx < ccX + ccW && my >= ccY && my < ccY + ccH;
        ctx.fill(ccX, ccY, ccX + ccW, ccY + ccH, ccHover ? 0xFF444444 : 0xFF2A2A2A);
        ctx.fill(ccX, ccY, ccX + ccW, ccY + 1, 0xFF666666);
        ctx.text(font, Component.literal("Deny"),
            ccX + (ccW - font.width("Deny")) / 2, ccY + 3, C_DIM, false);
    }

    private void renderDeleteConfirm(GuiGraphicsExtractor ctx, int mx, int my) {
        boolean isFolder = Files.isDirectory(ScriptRunner.SCRIPTS_DIR.resolve(deleteConfirmPath));
        int dlgH = isFolder ? DLG_H_FOLDER : DLG_H;
        int dlgX = px + (W - DLG_W) / 2;
        int dlgY = py + (H - dlgH) / 2;

        // Dim everything behind the dialog
        ctx.fill(px, py, px + W, py + H, 0x88000000);

        // Dialog box
        ctx.fill(dlgX, dlgY, dlgX + DLG_W, dlgY + dlgH, C_PANEL);
        ctx.fill(dlgX,              dlgY,           dlgX + DLG_W, dlgY + 1,       C_BORDER);
        ctx.fill(dlgX,              dlgY + dlgH - 1, dlgX + DLG_W, dlgY + dlgH,  C_BORDER);
        ctx.fill(dlgX,              dlgY,           dlgX + 1,      dlgY + dlgH,   C_BORDER);
        ctx.fill(dlgX + DLG_W - 1, dlgY,           dlgX + DLG_W,  dlgY + dlgH,  C_BORDER);
        // Red top accent bar
        ctx.fill(dlgX + 1, dlgY + 1, dlgX + DLG_W - 1, dlgY + 3, 0xFFCC2222);

        ctx.text(font, Component.literal(isFolder ? "§cDelete folder?" : "§cDelete script?"),
            dlgX + 8, dlgY + 7, C_WHITE, false);

        String display = deleteConfirmPath;
        if (font.width(display) > DLG_W - 16)
            display = font.plainSubstrByWidth(display, DLG_W - 24) + "…";
        ctx.text(font, Component.literal("§7" + display), dlgX + 8, dlgY + 18, C_DIM, false);
        if (isFolder)
            ctx.text(font, Component.literal("§cAll scripts inside will be deleted."), dlgX + 8, dlgY + 28, C_DIM, false);
        ctx.text(font, Component.literal("§7This cannot be undone."), dlgX + 8, isFolder ? dlgY + 38 : dlgY + 28, C_DIM, false);

        // Confirm button (red)
        int cfmX = dlgX + 8, cfmY = dlgY + dlgH - 18, cfmW = 78, cfmH = 14;
        boolean cfmHover = mx >= cfmX && mx < cfmX + cfmW && my >= cfmY && my < cfmY + cfmH;
        ctx.fill(cfmX, cfmY, cfmX + cfmW, cfmY + cfmH, cfmHover ? 0xFFAA1111 : 0xFF881111);
        ctx.fill(cfmX, cfmY, cfmX + cfmW, cfmY + 1, 0xFFCC3333);
        ctx.text(font, Component.literal("Confirm"),
            cfmX + (cfmW - font.width("Confirm")) / 2, cfmY + 3, C_WHITE, false);

        // Cancel button (grey)
        int cclX = dlgX + DLG_W - 8 - 72, cclY = cfmY, cclW = 72, cclH = 14;
        boolean cclHover = mx >= cclX && mx < cclX + cclW && my >= cclY && my < cclY + cclH;
        ctx.fill(cclX, cclY, cclX + cclW, cclY + cclH, cclHover ? 0xFF444444 : 0xFF2A2A2A);
        ctx.fill(cclX, cclY, cclX + cclW, cclY + 1, 0xFF666666);
        ctx.text(font, Component.literal("Cancel"),
            cclX + (cclW - font.width("Cancel")) / 2, cclY + 3, C_DIM, false);
    }

    private void deleteScript(String relPath) {
        Path target = ScriptRunner.SCRIPTS_DIR.resolve(relPath);
        if (Files.isDirectory(target)) {
            // Stop all scripts inside, delete their chats, then delete recursively
            try {
                Files.walk(target)
                    .filter(p -> !Files.isDirectory(p) && p.toString().endsWith(".py"))
                    .forEach(p -> {
                        String rel = ScriptRunner.SCRIPTS_DIR.relativize(p).toString()
                            .replace(java.io.File.separatorChar, '/');
                        ScriptRunner.stopScript(rel);
                        PeripheralHud.clearIfOwner(rel);
                        BuildSession.deleteChatFor(rel);
                    });
                Files.walk(target)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
            } catch (Exception ignored) {}
        } else {
            ScriptRunner.stopScript(relPath);
            PeripheralHud.clearIfOwner(relPath);
            BuildSession.deleteChatFor(relPath);
            try { Files.deleteIfExists(target); } catch (Exception ignored) {}
            try { Files.deleteIfExists(ScriptRunner.SCRIPTS_DIR.resolve(relPath + ".log")); }
            catch (Exception ignored) {}
        }
        // If the open Build session was just deleted, clear it
        if (s_build != null && s_build.getScriptRelPath().equals(relPath)) {
            s_build = null;
            s_buildName = "";
            s_lastMsgCount = 0;
        }
        refreshScripts();
    }
}
