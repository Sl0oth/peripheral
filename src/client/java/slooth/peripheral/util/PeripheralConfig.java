package slooth.peripheral.util;

import slooth.peripheral.PeripheralClient;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists user settings to config/peripheral.json.
 * Script slot assignments map a hotkey slot (1-5) to a .py filename.
 */
public class PeripheralConfig {

    private static final Path CONFIG_PATH = Paths.get("config", "peripheral.json");
    private static final Gson GSON        = new GsonBuilder().setPrettyPrinting().create();

    public static volatile boolean mcpEnabled = false;
    public static volatile String  apiKey                = "";
    public static volatile String  agentUrl              = "http://127.0.0.1:25586";
    public static volatile String  model                 = "claude-opus-4-6";
    public static volatile int     socketPort            = 25587;
    public static volatile String  theme                 = "terminal";
    public static volatile boolean baritoneWarnDismissed = false;
    public static volatile boolean termsAccepted         = false;

    // Slot 1-5 → script relative path (e.g. "farm.py" or "examples/farm.py")
    private static final Map<Integer, String> scriptSlots = new ConcurrentHashMap<>();

    // Auto-run mode per script: "off" | "world" (SP only) | "multi" (MP only) | "both"
    private static final Map<String, String> scriptAutoRun = new ConcurrentHashMap<>();

    // File access permission per script: true = allowed to read/write files
    private static final Map<String, Boolean> scriptFileAccess = new ConcurrentHashMap<>();

    /** Returns whether file access is enabled for the given script (default false). */
    public static boolean getFileAccess(String scriptName) {
        return scriptFileAccess.getOrDefault(scriptName, false);
    }

    /** Sets file access permission for the given script and saves config. */
    public static void setFileAccess(String scriptName, boolean allowed) {
        if (!allowed) scriptFileAccess.remove(scriptName);
        else scriptFileAccess.put(scriptName, true);
        save();
    }

    /** Returns the auto-run mode for a script: "off", "world", "multi", or "both". */
    public static String getAutoRun(String scriptName) {
        return scriptAutoRun.getOrDefault(scriptName, "off");
    }

    /** Cycles the auto-run mode: off → world → multi → both → off, then saves. */
    public static String cycleAutoRun(String scriptName) {
        String current = getAutoRun(scriptName);
        String next = switch (current) {
            case "off"   -> "world";
            case "world" -> "multi";
            case "multi" -> "both";
            default      -> "off";
        };
        if (next.equals("off")) scriptAutoRun.remove(scriptName);
        else scriptAutoRun.put(scriptName, next);
        save();
        return next;
    }

    /** Sets the auto-run mode ("off", "world", "multi" or "both") and saves. */
    public static void setAutoRun(String scriptName, String mode) {
        if (mode == null || mode.equals("off")) scriptAutoRun.remove(scriptName);
        else scriptAutoRun.put(scriptName, mode);
        save();
    }

    /** The hotkey slot (1–5) bound to a script, or 0 when it has none. */
    public static int slotOf(String filename) {
        for (var e : scriptSlots.entrySet()) if (e.getValue().equals(filename)) return e.getKey();
        return 0;
    }

    /** Binds a script to one hotkey slot (0 unbinds it), replacing any slot it held before, and saves. */
    public static void assignHotkey(String filename, int slot) {
        scriptSlots.values().removeIf(filename::equals);
        if (slot >= 1 && slot <= 5) scriptSlots.put(slot, filename);
        save();
    }

    /** Moves auto-run and hotkey settings after a script or folder is renamed from {@code from} to {@code to}, and saves. */
    public static void renameScript(String from, String to) {
        java.util.function.UnaryOperator<String> moved = name ->
            name.equals(from) ? to : name.startsWith(from + "/") ? to + name.substring(from.length()) : name;
        for (String name : new java.util.ArrayList<>(scriptAutoRun.keySet())) {
            String next = moved.apply(name);
            if (!next.equals(name)) scriptAutoRun.put(next, scriptAutoRun.remove(name));
        }
        scriptSlots.replaceAll((slot, name) -> moved.apply(name));
        save();
    }

    public static String getScriptSlot(int slot) {
        return scriptSlots.getOrDefault(slot, "");
    }

    public static void setScriptSlot(int slot, String filename) {
        if (filename == null || filename.isBlank()) scriptSlots.remove(slot);
        else scriptSlots.put(slot, filename);
    }

    public static Map<Integer, String> getScriptSlots() {
        return Collections.unmodifiableMap(scriptSlots);
    }

    public static void load() {
        mcpEnabled = false;
        if (!Files.exists(CONFIG_PATH)) { save(); return; }
        try (Reader r = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
            JsonObject obj = JsonParser.parseReader(r).getAsJsonObject();
            if (obj.has("mcp_enabled")) mcpEnabled = obj.get("mcp_enabled").getAsBoolean();
            if (obj.has("api_key"))                  apiKey                = obj.get("api_key").getAsString();
            if (obj.has("agent_url"))               agentUrl              = obj.get("agent_url").getAsString();
            if (obj.has("model"))                   model                 = obj.get("model").getAsString();
            if (obj.has("socket_port"))              socketPort            = obj.get("socket_port").getAsInt();
            if (obj.has("theme"))                    theme                 = obj.get("theme").getAsString();
            if (obj.has("baritone_warn_dismissed"))  baritoneWarnDismissed = obj.get("baritone_warn_dismissed").getAsBoolean();
            if (obj.has("terms_accepted"))           termsAccepted         = obj.get("terms_accepted").getAsBoolean();
            if (obj.has("script_slots")) {
                for (var e : obj.getAsJsonObject("script_slots").entrySet()) {
                    try { scriptSlots.put(Integer.parseInt(e.getKey()), e.getValue().getAsString()); }
                    catch (NumberFormatException ignored) {}
                }
            }
            if (obj.has("script_autorun")) {
                for (var e : obj.getAsJsonObject("script_autorun").entrySet()) {
                    String mode = e.getValue().getAsString();
                    if (!mode.equals("off")) scriptAutoRun.put(e.getKey(), mode);
                }
            }
            if (obj.has("script_file_access")) {
                for (var e : obj.getAsJsonObject("script_file_access").entrySet()) {
                    if (e.getValue().getAsBoolean()) scriptFileAccess.put(e.getKey(), true);
                }
            }
        } catch (Exception e) {
            PeripheralClient.LOGGER.warn("[Peripheral] Config load failed: {}", e.getMessage());
        }
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("mcp_enabled", mcpEnabled);
            obj.addProperty("api_key",                  apiKey);
            obj.addProperty("agent_url",               agentUrl);
            obj.addProperty("model",                   model);
            obj.addProperty("socket_port",             socketPort);
            obj.addProperty("theme",                   theme);
            obj.addProperty("baritone_warn_dismissed", baritoneWarnDismissed);
            obj.addProperty("terms_accepted",          termsAccepted);
            JsonObject slots = new JsonObject();
            scriptSlots.forEach((k, v) -> slots.addProperty(String.valueOf(k), v));
            obj.add("script_slots", slots);
            JsonObject autoRun = new JsonObject();
            scriptAutoRun.forEach((k, v) -> autoRun.addProperty(k, v));
            obj.add("script_autorun", autoRun);
            JsonObject fileAccess = new JsonObject();
            scriptFileAccess.forEach((k, v) -> fileAccess.addProperty(k, v));
            obj.add("script_file_access", fileAccess);
            try (Writer w = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(obj, w);
            }
        } catch (Exception e) {
            PeripheralClient.LOGGER.warn("[Peripheral] Config save failed: {}", e.getMessage());
        }
    }
}
