package slooth.peripheral;

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

    public static volatile String  apiKey                = "";
    public static volatile String  agentUrl              = "http://127.0.0.1:25586";
    public static volatile String  model                 = "claude-opus-4-6";
    public static final    String  storeUrl              = "https://api.sloothstuff.online";
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
        if (!Files.exists(CONFIG_PATH)) { save(); return; }
        try (Reader r = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
            JsonObject obj = JsonParser.parseReader(r).getAsJsonObject();
            if (obj.has("api_key"))                  apiKey                = obj.get("api_key").getAsString();
            if (obj.has("agent_url"))               agentUrl              = obj.get("agent_url").getAsString();
            if (obj.has("model"))                   model                 = obj.get("model").getAsString();
            // store_url is hard-coded; ignore any saved value
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
            obj.addProperty("api_key",                  apiKey);
            obj.addProperty("agent_url",               agentUrl);
            obj.addProperty("model",                   model);
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
