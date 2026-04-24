package slooth.peripheral;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Discovers, launches, and manages Python scripts stored under
 * {@code config/peripheral/scripts/}.
 *
 * <h2>How scripts run</h2>
 * <p>Each script runs in its own daemon thread via the system {@code python3}
 * interpreter.  The script's working directory is set to the scripts folder
 * and a minimal {@code mc} module is injected (via {@code PYTHONSTARTUP}) so
 * that scripts can call helper functions like {@code state()}, {@code say()},
 * {@code msg()}, {@code rich_msg()}, {@code open_gui()}, etc. without any
 * extra imports beyond {@code from mc import *}.
 *
 * <h2>Python API (mc module)</h2>
 * <pre>
 *   state()              – dict of current game state
 *   pos()                – [x, y, z] player position
 *   health()             – current health (float)
 *   say(text)            – chat message; "/command" is executed silently
 *   msg(text)            – system message in chat (URLs become clickable links)
 *   rich_msg(json)       – rich text with click/hover events (list or dict)
 *   wait(seconds)        – sleep
 *   open_gui(layout)     – load widget layout into ScriptableScreen
 *   gui_update(id, **kw) – update a widget's properties
 *   gui_close()          – close the scriptable GUI
 *   gui_poll()           – pop the next button-click event (or None)
 *   gui_input(id)        – read the current value of a TextFieldWidget
 *   gui_is_open()        – bool: is the GUI currently visible?
 *   print(...)           – writes to the Peripheral log with a [script] prefix
 * </pre>
 *
 * <h2>Environment variables injected into each script process</h2>
 * <pre>
 *   PERIPHERAL_STATE_PORT  – HTTP server port (default 25585)
 *   PERIPHERAL_API_KEY     – API key from settings
 *   PERIPHERAL_AGENT_URL   – AI agent base URL from settings
 *   PERIPHERAL_SCRIPTS_DIR – Absolute path to the scripts folder
 * </pre>
 *
 * <p>Script stdout and stderr are captured and saved to
 * {@code <scriptname>.log} next to the script file, and also forwarded to the
 * Peripheral log viewer (Log tab) with a {@code [script]} prefix so that
 * {@code print()} output is visible in-game.
 */
public class ScriptRunner {

    public static final Path SCRIPTS_DIR = Paths.get("config", "peripheral", "scripts");

    /** isFolder=true → this entry is a directory, not a script. */
    public record ScriptInfo(String name, boolean running, long startedAt, boolean isFolder) {}

    private static final Map<String, Process> running    = new ConcurrentHashMap<>();
    /** Set when a script requests file access — PeripheralScreen polls this each tick. */
    public static volatile String fileAccessRequestScript = null;
    private static final Map<String, Long>    startTimes = new ConcurrentHashMap<>();
    private static String cachedPython = null;

    // ── Init ──────────────────────────────────────────────────────────────────

    public static void init() {
        try {
            Files.createDirectories(SCRIPTS_DIR);
            Files.createDirectories(SCRIPTS_DIR.resolve("examples"));
            writeMcLib();
            writeExampleScripts();
            writeDocumentation();
        } catch (Exception e) {
            PeripheralClient.LOGGER.warn("[Peripheral] ScriptRunner init failed: {}", e.getMessage());
        }
    }

    /**
     * Loads a bundled resource from assets/peripheral/scripts/ inside the mod jar.
     * Returns the file content as a UTF-8 string, or throws IOException if missing.
     */
    private static String readResource(String name) throws IOException {
        String path = "/assets/peripheral/scripts/" + name;
        try (InputStream in = ScriptRunner.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Writes mc.py — the simple scripting helper library.
     * Always overwritten so updates ship to existing installs.
     */
    private static void writeMcLib() throws IOException {
        Files.writeString(SCRIPTS_DIR.resolve("mc.py"), readResource("mc.py"));
    }

    private static void writeExampleScripts() throws IOException {
        // writeIfMissing: only written if the file doesn't already exist
        writeIfMissing("examples/hello_world.py",       readResource("examples/hello_world.py"));
        writeIfMissing("examples/auto_eat.py",          readResource("examples/auto_eat.py"));
        writeIfMissing("examples/auto_mine.py",         readResource("examples/auto_mine.py"));
        writeIfMissing("examples/follow_on_command.py", readResource("examples/follow_on_command.py"));
        writeIfMissing("examples/api_server.py",        readResource("examples/api_server.py"));
        writeIfMissing("examples/welcome_bot.py",       readResource("examples/welcome_bot.py"));
        writeIfMissing("examples/clickable_links.py",   readResource("examples/clickable_links.py"));
        writeIfMissing("examples/custom_gui.py",        readResource("examples/custom_gui.py"));
        writeIfMissing("examples/weather_display.py",   readResource("examples/weather_display.py"));

        // Always overwrite so layout/feature updates reach users on next launch
        Files.writeString(SCRIPTS_DIR.resolve("examples/armor_hud.py"),     readResource("examples/armor_hud.py"));
        Files.writeString(SCRIPTS_DIR.resolve("examples/hud_basics.py"),     readResource("examples/hud_basics.py"));
        Files.writeString(SCRIPTS_DIR.resolve("examples/health_monitor.py"), readResource("examples/health_monitor.py"));
        Files.writeString(SCRIPTS_DIR.resolve("examples/chat_bot.py"),       readResource("examples/chat_bot.py"));
        Files.writeString(SCRIPTS_DIR.resolve("examples/web_dashboard.py"),  readResource("examples/web_dashboard.py"));
    }

    private static void writeIfMissing(String relPath, String content) throws IOException {
        Path target = SCRIPTS_DIR.resolve(relPath.replace('/', java.io.File.separatorChar));
        if (!Files.exists(target)) {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
        }
    }

    // ── Script discovery ──────────────────────────────────────────────────────

    /**
     * List entries (folders first, then .py files) inside a subdirectory of SCRIPTS_DIR.
     * Folders carry isFolder=true. Scripts use their relative path from SCRIPTS_DIR
     * as the name (e.g. "examples/mining.py" or "wb.py").
     */
    public static List<ScriptInfo> listEntries(java.nio.file.Path subdir) {
        try {
            java.nio.file.Path dir = SCRIPTS_DIR.resolve(subdir);
            if (!Files.exists(dir)) return Collections.emptyList();
            List<ScriptInfo> result = new ArrayList<>();
            List<java.nio.file.Path> all;
            try (var s = Files.list(dir)) { all = s.collect(Collectors.toList()); }

            // Folders first (skip hidden + __pycache__)
            all.stream()
               .filter(p -> Files.isDirectory(p)
                         && !p.getFileName().toString().startsWith(".")
                         && !p.getFileName().toString().equals("__pycache__"))
               .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
               .forEach(p -> result.add(new ScriptInfo(p.getFileName().toString(), false, 0, true)));

            // .py files (skip mc.py)
            all.stream()
               .filter(p -> !Files.isDirectory(p)
                         && p.getFileName().toString().endsWith(".py")
                         && !p.getFileName().toString().equals("mc.py"))
               .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
               .forEach(p -> {
                   String rel = SCRIPTS_DIR.relativize(p).toString()
                                           .replace(java.io.File.separatorChar, '/');
                   result.add(new ScriptInfo(rel, isRunning(rel),
                              startTimes.getOrDefault(rel, 0L), false));
               });

            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Recursively returns ALL scripts (relative paths) across all subfolders.
     * Used by the auto-run system on world/server join.
     */
    public static List<String> listAllScripts() {
        try {
            List<String> result = new ArrayList<>();
            Files.walk(SCRIPTS_DIR)
                 .filter(p -> !Files.isDirectory(p)
                           && p.getFileName().toString().endsWith(".py")
                           && !p.getFileName().toString().equals("mc.py"))
                 .forEach(p -> result.add(
                     SCRIPTS_DIR.relativize(p).toString()
                                .replace(java.io.File.separatorChar, '/')));
            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** Flat list of all script relative-paths (no folders) in the root. */
    public static List<String> listScripts() {
        return listEntries(java.nio.file.Path.of("")).stream()
               .filter(i -> !i.isFolder())
               .map(ScriptInfo::name)
               .collect(Collectors.toList());
    }

    // ── Run / stop ────────────────────────────────────────────────────────────

    /** Returns false if already running or file not found. */
    public static boolean runScript(String filename) {
        if (isRunning(filename)) return false;
        Path scriptPath = SCRIPTS_DIR.resolve(filename);
        if (!Files.exists(scriptPath)) return false;

        try {
            Path logFile = SCRIPTS_DIR.resolve(filename + ".log");

            // Write a start-time header so the log is never blank
            String header = "=== " + filename + " started at "
                + java.time.LocalDateTime.now().toString().replace('T', ' ').substring(0, 19)
                + " ===\n";
            Files.writeString(logFile, header, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // -u = unbuffered so stdout/stderr flush immediately.
            // PYTHONPATH = SCRIPTS_DIR so "from mc import *" works from any subfolder.
            ProcessBuilder pb = new ProcessBuilder(findPython(), "-u", scriptPath.toAbsolutePath().toString());
            pb.directory(SCRIPTS_DIR.toFile());
            pb.environment().put("PERIPHERAL_STATE_PORT",  String.valueOf(PeripheralHttpServer.PORT));
            pb.environment().put("PERIPHERAL_API_KEY",     PeripheralConfig.apiKey);
            pb.environment().put("PERIPHERAL_AGENT_URL",   PeripheralConfig.agentUrl);
            pb.environment().put("PERIPHERAL_SCRIPTS_DIR", SCRIPTS_DIR.toAbsolutePath().toString());
            pb.environment().put("PERIPHERAL_FILE_ACCESS",  PeripheralConfig.getFileAccess(filename) ? "1" : "0");
            pb.environment().put("PERIPHERAL_SCRIPT_NAME",  filename);
            pb.environment().put("PYTHONUNBUFFERED",        "1");
            // PYTHONPATH: always includes the scripts root so mc.py is importable from subfolders
            pb.environment().put("PYTHONPATH",              SCRIPTS_DIR.toAbsolutePath().toString());
            pb.redirectErrorStream(true); // merge stderr into stdout

            Process proc = pb.start();
            running.put(filename, proc);
            startTimes.put(filename, Instant.now().getEpochSecond());

            final String label = filename.contains("/")
                ? filename.substring(filename.lastIndexOf('/') + 1) : filename;

            // Tee: copy each output line to the .log file AND push to the in-game log tab
            java.io.InputStream stdout = proc.getInputStream();
            Thread reader = new Thread(() -> {
                try (java.io.BufferedWriter fw = java.nio.file.Files.newBufferedWriter(
                        logFile, java.nio.charset.StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                     java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(stdout, java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.contains("__PERIPHERAL_REQUEST_FILE_ACCESS__")) {
                            fileAccessRequestScript = filename; // picked up by PeripheralScreen
                            continue; // don't log this internal marker
                        }
                        fw.write(line);
                        fw.newLine();
                        fw.flush();
                        PeripheralStateTracker.pushLog(null, null, "[" + label + "] " + line, -1);
                    }
                } catch (Exception ignored) {}
                finally {
                    running.remove(filename);
                    startTimes.remove(filename);
                    PeripheralHud.clearIfOwner(filename);
                }
            }, "peripheral-read-" + filename);
            reader.setDaemon(true);
            reader.start();

            PeripheralClient.LOGGER.info("[Peripheral] Started: {}", filename);
            return true;
        } catch (Exception e) {
            PeripheralClient.LOGGER.warn("[Peripheral] Failed to start {}: {}", filename, e.getMessage());
            return false;
        }
    }

    public static void stopScript(String filename) {
        Process p = running.remove(filename);
        if (p != null) { p.destroyForcibly(); startTimes.remove(filename); }
    }

    public static void stopAll() {
        new ArrayList<>(running.keySet()).forEach(ScriptRunner::stopScript);
    }

    public static boolean isRunning(String filename) {
        Process p = running.get(filename);
        if (p == null) return false;
        if (!p.isAlive()) { running.remove(filename); startTimes.remove(filename); return false; }
        return true;
    }

    /** Toggle: run if idle, stop if running. */
    public static void toggle(String filename) {
        if (isRunning(filename)) stopScript(filename);
        else runScript(filename);
    }

    public static List<ScriptInfo> getStatus() {
        return listEntries(java.nio.file.Path.of(""));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Writes the scripting API reference to the scripts folder (only if missing). */
    private static void writeDocumentation() throws IOException {
        writeIfMissing("PERIPHERAL_API.md", readResource("PERIPHERAL_API.md"));
        // Always overwrite AI_PROMPT.md so it stays current with the mod
        Files.writeString(SCRIPTS_DIR.resolve("AI_PROMPT.md"), readResource("AI_PROMPT.md"));
    }

    private static String findPython() {
        if (cachedPython != null) return cachedPython;
        for (String exe : new String[]{"python3", "python"}) {
            try {
                int exit = new ProcessBuilder(exe, "--version")
                    .redirectErrorStream(true).start().waitFor();
                if (exit == 0) { cachedPython = exe; return exe; }
            } catch (Exception ignored) {}
        }
        cachedPython = "python3";
        return cachedPython;
    }
}
