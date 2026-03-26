package slooth.peripheral;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;

/**
 * Manages one AI-assisted script-building session for a single .py file.
 *
 * Chat history is persisted to config/peripheral/chats/<scriptname>.json so
 * sessions can be resumed across game sessions.
 *
 * When the AI response contains a ```python code block it is automatically
 * written to config/peripheral/scripts/<scriptRelPath>.
 *
 * Provider is detected from PeripheralConfig.model:
 *   - starts with "claude" → Anthropic Messages API
 *   - anything else         → OpenAI chat completions API
 */
public class BuildSession {

    // ── Chat storage ──────────────────────────────────────────────────────────
    public static final Path CHATS_DIR = Paths.get("config", "peripheral", "chats");

    public record ChatMessage(String role, String content) {}

    // ── State ─────────────────────────────────────────────────────────────────
    private final String scriptRelPath;
    private final List<ChatMessage> messages = new ArrayList<>();

    public volatile boolean busy   = false;
    public volatile String  status = "";

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Gson       GSON = new GsonBuilder().create();

    // ── System prompt ─────────────────────────────────────────────────────────
    private static final String SYSTEM_PROMPT =
        "You are an assistant that writes scripts for the Peripheral Minecraft mod.\n" +
        "Peripheral is a Fabric client-side mod. Scripts are Python files that use the `mc` module.\n\n" +
        "IMPORTANT: When providing a script, ALWAYS include a complete ```python code block.\n" +
        "The code will be automatically written to the script file when you reply.\n\n" +
        "Available functions (from mc import *):\n\n" +
        "Game state:\n" +
        "  state()       -> dict: health, hunger, x, y, z, dimension, is_day, is_raining, time_ticks, yaw, pitch\n" +
        "  pos()         -> (x, y, z)\n" +
        "  health()      -> float 0-20\n" +
        "  inventory()   -> list of {slot, slot_type, item, count, durability_pct}\n" +
        "  nearby()      -> list of {type, x, y, z, distance, hostile, name}\n" +
        "  chat_log(n=20)-> list of {text, sender, type}\n\n" +
        "Actions:\n" +
        "  say(text)               - public chat\n" +
        "  msg(text)               - client-side only message\n" +
        "  look(yaw, pitch)\n" +
        "  use_item()\n" +
        "  equip(item_id)\n" +
        "  jump()  sprint(on)  sneak(on)\n" +
        "  move(forward, strafe, duration)\n" +
        "  click_block(x, y, z, face)\n" +
        "  attack()\n" +
        "  drop_item(slot)\n\n" +
        "HUD overlays (persist while script runs):\n" +
        "  hud_set(elements)       - set full HUD layout\n" +
        "  hud_update(id, **kwargs)- update one element\n" +
        "  Element types: label, bar, rect, divider, item\n" +
        "  Anchors: top_left, top_right, bottom_left, bottom_right, center\n\n" +
        "Custom in-game GUI:\n" +
        "  open_gui(layout)        - open screen (player presses H)\n" +
        "  get_gui_state()         -> dict of widget values\n\n" +
        "Navigation (requires Baritone):\n" +
        "  goto(x, z)\n" +
        "  baritone(command)\n" +
        "  baritone_stop()\n" +
        "  nav_status() -> dict\n\n" +
        "HTTP:\n" +
        "  http_get(url)           -> parsed JSON\n" +
        "  http_post(url, data)    -> parsed JSON\n\n" +
        "Timing:\n" +
        "  wait(seconds)           - sleep (use instead of time.sleep)\n" +
        "  wait_for_chat(pattern, timeout=60) -> message dict or None\n\n" +
        "Every script must start with: from mc import *\n" +
        "Use wait() not time.sleep().";

    // ── Constructor ───────────────────────────────────────────────────────────
    public BuildSession(String scriptRelPath) {
        this.scriptRelPath = scriptRelPath.endsWith(".py") ? scriptRelPath : scriptRelPath + ".py";
        load();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public String getScriptRelPath() { return scriptRelPath; }

    public List<ChatMessage> getMessages() { return Collections.unmodifiableList(messages); }

    /** Read the current content of the script file, or empty string if it doesn't exist. */
    public String getCode() {
        try {
            Path p = ScriptRunner.SCRIPTS_DIR.resolve(scriptRelPath);
            if (Files.exists(p)) return Files.readString(p);
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * Send a user message, call the AI API in the background, write any code to
     * the script file, and persist the chat.  Calls {@code onDone} when complete
     * (from the background thread).
     */
    public void send(String userMsg, Runnable onDone) {
        if (busy) return;
        busy   = true;
        status = "Thinking...";
        messages.add(new ChatMessage("user", userMsg));
        save();

        Thread t = new Thread(() -> {
            try {
                String response = callApi(new ArrayList<>(messages));
                messages.add(new ChatMessage("assistant", response));

                // Extract and write code to the script file
                String code = extractCode(response);
                if (code != null && !code.isBlank()) {
                    Path scriptPath = ScriptRunner.SCRIPTS_DIR.resolve(scriptRelPath);
                    Files.createDirectories(scriptPath.getParent());
                    Files.writeString(scriptPath, code);
                }
                save();
                status = "";
            } catch (Exception e) {
                // Roll back the user message so they can retry
                if (!messages.isEmpty() &&
                        messages.get(messages.size() - 1).role().equals("user")) {
                    messages.remove(messages.size() - 1);
                }
                status = "Error: " + e.getMessage();
                PeripheralClient.LOGGER.warn("[Peripheral] Build API error: {}", e.getMessage());
            } finally {
                busy = false;
                if (onDone != null) onDone.run();
            }
        }, "peripheral-build");
        t.setDaemon(true);
        t.start();
    }

    /** Delete this session's chat history file and clear messages. */
    public void clearChat() {
        messages.clear();
        status = "";
        try { Files.deleteIfExists(chatFile()); } catch (Exception ignored) {}
    }

    /**
     * Delete the chat file associated with a script relative path, if one exists.
     * Safe to call even if no chat file exists.
     */
    public static void deleteChatFor(String scriptRelPath) {
        try {
            String safe = scriptRelPath.replace("/", "_").replace("\\", "_");
            Files.deleteIfExists(CHATS_DIR.resolve(safe + ".json"));
        } catch (Exception ignored) {}
    }

    // ── API calls ─────────────────────────────────────────────────────────────

    private String callApi(List<ChatMessage> msgs) throws Exception {
        String model = PeripheralConfig.model;
        if (model.startsWith("claude")) return callClaude(msgs);
        else                             return callOpenAI(msgs);
    }

    private String callClaude(List<ChatMessage> msgs) throws Exception {
        JsonArray msgsArr = new JsonArray();
        for (ChatMessage m : msgs) {
            JsonObject o = new JsonObject();
            o.addProperty("role",    m.role());
            o.addProperty("content", m.content());
            msgsArr.add(o);
        }
        JsonObject body = new JsonObject();
        body.addProperty("model",      PeripheralConfig.model);
        body.addProperty("max_tokens", 4096);
        body.addProperty("system",     SYSTEM_PROMPT);
        body.add("messages", msgsArr);

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.anthropic.com/v1/messages"))
            .header("x-api-key",          PeripheralConfig.apiKey)
            .header("anthropic-version",  "2023-06-01")
            .header("content-type",       "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new Exception("HTTP " + resp.statusCode() + ": " + resp.body().substring(0, Math.min(200, resp.body().length())));

        JsonObject r = JsonParser.parseString(resp.body()).getAsJsonObject();
        return r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    private String callOpenAI(List<ChatMessage> msgs) throws Exception {
        JsonArray msgsArr = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role",    "system");
        sys.addProperty("content", SYSTEM_PROMPT);
        msgsArr.add(sys);
        for (ChatMessage m : msgs) {
            JsonObject o = new JsonObject();
            o.addProperty("role",    m.role());
            o.addProperty("content", m.content());
            msgsArr.add(o);
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", PeripheralConfig.model);
        body.add("messages", msgsArr);

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/chat/completions"))
            .header("Authorization", "Bearer " + PeripheralConfig.apiKey)
            .header("content-type",  "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new Exception("HTTP " + resp.statusCode() + ": " + resp.body().substring(0, Math.min(200, resp.body().length())));

        JsonObject r = JsonParser.parseString(resp.body()).getAsJsonObject();
        return r.getAsJsonArray("choices").get(0).getAsJsonObject()
               .getAsJsonObject("message").get("content").getAsString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Extract the first ```python (or ```) code block from the response. */
    private String extractCode(String response) {
        int start = response.indexOf("```python\n");
        int skip  = 9; // length of "```python\n"
        if (start == -1) { start = response.indexOf("```\n"); skip = 4; }
        if (start == -1) return null;
        int codeStart = start + skip;
        int end = response.indexOf("\n```", codeStart);
        if (end == -1) return null;
        return response.substring(codeStart, end).stripTrailing();
    }

    private void save() {
        try {
            Files.createDirectories(CHATS_DIR);
            JsonObject obj = new JsonObject();
            obj.addProperty("script", scriptRelPath);
            JsonArray arr = new JsonArray();
            for (ChatMessage m : messages) {
                JsonObject o = new JsonObject();
                o.addProperty("role",    m.role());
                o.addProperty("content", m.content());
                arr.add(o);
            }
            obj.add("messages", arr);
            Files.writeString(chatFile(), GSON.toJson(obj));
        } catch (Exception e) {
            PeripheralClient.LOGGER.warn("[Peripheral] Chat save failed: {}", e.getMessage());
        }
    }

    private void load() {
        Path f = chatFile();
        if (!Files.exists(f)) return;
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            JsonArray  arr = obj.getAsJsonArray("messages");
            for (JsonElement el : arr) {
                JsonObject m = el.getAsJsonObject();
                messages.add(new ChatMessage(
                    m.get("role").getAsString(),
                    m.get("content").getAsString()));
            }
        } catch (Exception e) {
            PeripheralClient.LOGGER.warn("[Peripheral] Chat load failed: {}", e.getMessage());
        }
    }

    /** Filesystem-safe filename for this session's chat JSON. */
    private Path chatFile() {
        String safe = scriptRelPath.replace("/", "_").replace("\\", "_");
        return CHATS_DIR.resolve(safe + ".json");
    }
}
