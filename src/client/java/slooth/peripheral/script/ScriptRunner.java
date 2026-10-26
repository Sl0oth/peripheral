package slooth.peripheral.script;

import slooth.peripheral.PeripheralClient;
import slooth.peripheral.api.PeripheralHttpServer;
import slooth.peripheral.hud.PeripheralHud;
import slooth.peripheral.overlay.PeripheralOverlayRenderer;
import slooth.peripheral.ui.PeripheralScreen;
import slooth.peripheral.ui.ScriptableScreen;
import slooth.peripheral.util.PeripheralConfig;
import slooth.peripheral.util.PeripheralStateTracker;

import java.io.*;
import java.nio.file.*;
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
 * and the bundled {@code mc} module is available via {@code PYTHONPATH} so
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
 *   PERIPHERAL_LOCAL_TOKEN – private per-run local API capability; never a provider key
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

    private static final class Execution {
        final String name, owner, token;
        final Process process;
        final long startedAt = Instant.now().getEpochSecond();
        final java.util.Set<ProcessHandle> children = ConcurrentHashMap.newKeySet();
        final java.util.concurrent.atomic.AtomicBoolean cleaned = new java.util.concurrent.atomic.AtomicBoolean();
        volatile boolean stopping;
        Execution(String name, String owner, String token, Process process) { this.name=name; this.owner=owner; this.token=token; this.process=process; }
    }
    private static final Map<String, Execution> running = new ConcurrentHashMap<>();
    private static final Map<String, Long> startTimes = new ConcurrentHashMap<>();
    private static final Map<String, String> outcomes = new ConcurrentHashMap<>();
    private static final Map<String, ArrayDeque<String>> tails = new ConcurrentHashMap<>();
    private static final java.util.concurrent.ScheduledExecutorService WATCHER = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { Thread t=new Thread(r,"peripheral-process-watch"); t.setDaemon(true); return t; });
    private static String cachedPython;
    public static volatile String fileAccessRequestScript; // retained for old screen binary/source compatibility
    public static volatile boolean sessionOpen = true;
    public static volatile long worldGeneration;
    public static String outcome(String name) { return outcomes.getOrDefault(name, "Ready"); }
    public static List<String> logTail(String name) { ArrayDeque<String> q=tails.get(name); if(q==null) return List.of(); synchronized(q) { return List.copyOf(q); } }
    private static void log(String name, String line) {
        // Never persist local capability/provider secrets, even if a script accidentally prints one.
        line=slooth.peripheral.api.LocalApiSecurity.redact(line);
        if (!PeripheralConfig.apiKey.isEmpty()) line=line.replace(PeripheralConfig.apiKey,"[redacted]");
        if (tails.size()>256 && !tails.containsKey(name)) tails.keySet().stream().filter(n -> !running.containsKey(n)).findFirst().ifPresent(tails::remove);
        ArrayDeque<String> q=tails.computeIfAbsent(name,k->new ArrayDeque<>());
        synchronized(q) { if(q.size()>=160)q.removeFirst(); q.addLast(line); }
        PeripheralStateTracker.pushScriptLog("["+name+"] "+line);
    }

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
     * Writes mc.py — the simple scripting helper library.
     * Loaded from the classpath resource /peripheral/mc.py and always
     * overwritten so updates ship to existing installs.
     */
    private static void writeMcLib() throws IOException {
        try (java.io.InputStream in = ScriptRunner.class.getResourceAsStream("/peripheral/mc.py")) {
            if (in == null) {
                PeripheralClient.LOGGER.warn("[Peripheral] mc.py resource missing from classpath");
                return;
            }
            Files.write(ScriptPaths.resolve("mc.py"), in.readAllBytes());
        }
    }

    private static void writeExampleScripts() throws IOException {
        // Example scripts are classpath resources under /peripheral/examples/.
        // Each is copied into config/peripheral/scripts/examples/ only if it is
        // missing, so a user's edits are never clobbered. To change the shipped
        // set, edit the resource files and update this list.
        String[] examples = {
            "basics/welcome.py",             // one-shot tour: state, rich chat, toast, sound
            "hud/dashboard.py",              // HUD showcase: rect/label/bar/divider/item/graph/compass
            "gui/terminal.py",              // bounded local command/log panel, no shell
            "hud/trail.py",                  // read-only travel/vitals HUD
            "gui/control_center.py",         // GUI showcase: tabs/buttons/slider/checkbox/dropdown/list
            "chat/commands.py",              // chat commands, waypoints, find_blocks, world markers
            "overlay/radar.py",              // live ESP boxes + entity nametags
            "automation/survival_assistant.py", // on_health/on_tick/on_death: auto-eat, alerts, respawn
        };
        for (String rel : examples)
            copyResourceIfMissing("/peripheral/examples/" + rel, "examples/" + rel);
    }

    private static void writeIfMissing(String relPath, String content) throws IOException {
        Path target = ScriptPaths.resolve(relPath);
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
            java.nio.file.Path dir = ScriptPaths.resolve(subdir.toString());
            if (!Files.exists(dir)) return Collections.emptyList();
            List<ScriptInfo> result = new ArrayList<>();
            List<java.nio.file.Path> all;
            try (var s = Files.list(dir)) { all = s.filter(p -> !Files.isSymbolicLink(p)).collect(Collectors.toList()); }

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
                   String rel = ScriptPaths.root().relativize(p).toString()
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
        try (var paths = Files.walk(ScriptPaths.resolve(""))) {
            return paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                .filter(p -> p.toString().endsWith(".py") && !p.getFileName().toString().equals("mc.py"))
                .map(p -> ScriptPaths.root().relativize(p).toString().replace('\\','/'))
                .filter(n -> { try { ScriptPaths.script(n); return true; } catch (IllegalArgumentException e) { return false; } })
                .sorted().toList();
        } catch (Exception e) { return List.of(); }
    }

    /** Flat list of all script relative-paths (no folders) in the root. */
    public static List<String> listScripts() {
        return listEntries(java.nio.file.Path.of("")).stream()
               .filter(i -> !i.isFolder())
               .map(ScriptInfo::name)
               .collect(Collectors.toList());
    }

    // ── Run / stop ────────────────────────────────────────────────────────────

    /** Atomic registration; ownership is unique for each execution, never just the filename. */
    public static synchronized boolean runScript(String filename) {
        if (!sessionOpen) { outcomes.put(filename,"Join a world before starting a script"); return false; }
        final String name;
        final Path scriptPath;
        try { scriptPath=ScriptPaths.script(filename); name=ScriptPaths.name(filename); }
        catch (IllegalArgumentException e) { outcomes.put(filename,e.getMessage()); return false; }
        if (isRunning(name)) return false;
        String owner="run:"+UUID.randomUUID();
        String token=slooth.peripheral.api.LocalApiSecurity.issue(owner);
        try {
            Path logFile=ScriptPaths.resolve(name+".log");
            Files.writeString(logFile,"=== Starting "+name+" ===\n",StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
            ProcessBuilder pb=new ProcessBuilder(findPython(),"-u",scriptPath.toString());
            pb.directory(ScriptPaths.root().toFile());
            var env=pb.environment();
            env.remove("PERIPHERAL_API_KEY");
            env.put("PERIPHERAL_STATE_PORT",String.valueOf(PeripheralHttpServer.PORT));
            env.put("PERIPHERAL_SOCKET_PORT",String.valueOf(PeripheralConfig.socketPort));
            env.put("PERIPHERAL_SOCKET_AVAILABLE",slooth.peripheral.api.PeripheralSocketServer.status.equals("Ready")?"1":"0");
            env.put("PERIPHERAL_LOCAL_TOKEN",token);
            env.put("PERIPHERAL_AGENT_URL",PeripheralConfig.agentUrl);
            env.put("PERIPHERAL_SCRIPTS_DIR",ScriptPaths.root().toString());
            env.put("PERIPHERAL_SCRIPT_NAME",name);
            env.put("PYTHONPATH",ScriptPaths.root().toString());
            env.put("PYTHONDONTWRITEBYTECODE","1"); env.put("PYTHONUNBUFFERED","1");
            pb.redirectErrorStream(true);
            Execution run=new Execution(name,owner,token,pb.start());
            running.put(name,run); startTimes.put(name,run.startedAt); outcomes.put(name,"Running"); tails.remove(name);
            log(name,"Started. Python scripts run as trusted local programs.");
            var watcher=WATCHER.scheduleAtFixedRate(() -> run.process.descendants().forEach(run.children::add),0,100,java.util.concurrent.TimeUnit.MILLISECONDS);
            run.process.onExit().thenRun(() -> { watcher.cancel(false); finish(run); });
            Thread.ofPlatform().daemon().name("peripheral-read-"+name).start(() -> {
                try (var reader=new BufferedReader(new InputStreamReader(run.process.getInputStream(),java.nio.charset.StandardCharsets.UTF_8))) {
                    StringBuilder part=new StringBuilder(); int ch; long bytes=0;
                    while ((ch=reader.read())!=-1) {
                        if (ch!='\n' && part.length()<8192) { part.append((char)ch); continue; }
                        String line=part.toString(); part.setLength(0); log(name,line);
                        if (bytes<4_194_304) { line=slooth.peripheral.api.LocalApiSecurity.redact(line); if(!PeripheralConfig.apiKey.isEmpty())line=line.replace(PeripheralConfig.apiKey,"[redacted]"); Files.writeString(logFile,line+"\n",StandardOpenOption.APPEND); bytes+=line.length()+1; }
                    }
                    if (!part.isEmpty()) log(name,part.toString());
                } catch (IOException e) { if(!run.stopping) log(name,"Log stream closed; process status is still tracked."); }
            });
            return true;
        } catch(Exception e) {
            slooth.peripheral.api.LocalApiSecurity.revoke(token);
            outcomes.put(name,"Could not start Python 3. Install Python 3 and make python3 available, then restart Minecraft.");
            log(name,outcomes.get(name)); return false;
        }
    }
    private static void terminateChildren(Execution run) {
        run.process.descendants().forEach(run.children::add);
        run.children.forEach(p -> { if(p.isAlive())p.destroyForcibly(); });
    }
    private static void finish(Execution run) {
        if(!run.cleaned.compareAndSet(false,true))return;
        terminateChildren(run);
        slooth.peripheral.api.LocalApiSecurity.revoke(run.token);
        synchronized(ScriptRunner.class) {
            if(running.remove(run.name,run)) {
                startTimes.remove(run.name);
                String result=run.stopping?"Stopped":run.process.exitValue()==0?"Finished":"Exited with error ("+run.process.exitValue()+"). Open the log below.";
                outcomes.put(run.name,result); log(run.name,result);
            }
        }
        net.minecraft.client.Minecraft.getInstance().execute(() -> {
            ScriptActions.release(run.owner);
            slooth.peripheral.record.Recorder.releaseOwner(run.owner);
            slooth.peripheral.net.PacketTools.releaseOwner(run.owner);
            PeripheralHud.clearIfOwner(run.owner); ScriptableScreen.clearIfOwner(run.owner); PeripheralOverlayRenderer.clearIfOwner(run.owner);
        });
    }
    public static synchronized void stopScript(String filename) {
        final String name;
        try { name=ScriptPaths.name(filename); } catch(IllegalArgumentException e) { return; }
        Execution run=running.get(name); if(run==null)return;
        run.stopping=true; slooth.peripheral.api.LocalApiSecurity.revoke(run.token);
        // Revoke before enqueuing release: already-queued API actions recheck the capability.
        net.minecraft.client.Minecraft.getInstance().execute(() -> ScriptActions.release(run.owner));
        terminateChildren(run); run.process.destroyForcibly();
        outcomes.put(name,"Stopping…");
    }
    public static void stopAll() { new ArrayList<>(running.keySet()).forEach(ScriptRunner::stopScript); }
    public static List<String> listRunningScripts() { return running.entrySet().stream().filter(e->e.getValue().process.isAlive()).map(Map.Entry::getKey).sorted().toList(); }
    public static boolean isRunning(String filename) { Execution r=running.get(filename); return r!=null && r.process.isAlive(); }

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
        // Canonical docs live as classpath resources; PERIPHERAL_API.md is a
        // starter reference (write-if-missing) while AI_PROMPT.md is always
        // overwritten so it tracks the installed mod version.
        copyResourceIfMissing("/peripheral/PERIPHERAL_API.md", "PERIPHERAL_API.md");
        // Existing users may have edited their starter reference. Keep that file,
        // and publish a clearly named current reference on every update instead.
        copyResourceOverwrite("/peripheral/PERIPHERAL_API.md", "PERIPHERAL_API_CURRENT.md");
        copyResourceOverwrite("/peripheral/AI_PROMPT.md", "AI_PROMPT.md");
        copyResourceOverwrite("/peripheral/LOCAL_API.md", "LOCAL_API.md");
    }

    private static void copyResourceIfMissing(String resource, String relPath) throws IOException {
        Path target = ScriptPaths.resolve(relPath);
        if (Files.exists(target)) return;
        try (java.io.InputStream in = ScriptRunner.class.getResourceAsStream(resource)) {
            if (in == null) { PeripheralClient.LOGGER.warn("[Peripheral] resource missing: {}", resource); return; }
            Files.createDirectories(target.getParent());
            Files.write(target, in.readAllBytes());
        }
    }

    private static void copyResourceOverwrite(String resource, String relPath) throws IOException {
        Path target = ScriptPaths.resolve(relPath);
        try (java.io.InputStream in = ScriptRunner.class.getResourceAsStream(resource)) {
            if (in == null) { PeripheralClient.LOGGER.warn("[Peripheral] resource missing: {}", resource); return; }
            Files.createDirectories(target.getParent());
            Files.write(target, in.readAllBytes());
        }
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
