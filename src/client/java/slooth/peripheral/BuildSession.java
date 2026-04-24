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
        "  say(text)                    - public chat\n" +
        "  msg(text)                    - client-side only message\n" +
        "  look('north')                - face a direction: north/south/east/west/up/down\n" +
        "  look(yaw, pitch)             - set exact rotation (yaw: 0=S 90=W 180=N 270=E; pitch: -90=up 90=down)\n" +
        "  jump()                       - jump (works when on the ground)\n" +
        "  sprint(on=True)              - toggle sprinting\n" +
        "  sneak(on=True)               - toggle sneaking\n" +
        "  move(forward, strafe=0, duration=0)  - move player (1.0=forward/-1.0=back; duration blocks until done)\n" +
        "  attack()                     - left-click attack at crosshair\n" +
        "  use_item()                   - right-click held item\n" +
        "  equip(item_id)\n" +
        "  mine(x, y, z)                - start breaking block\n" +
        "  place(x, y, z, face='up')    - place/right-click block\n" +
        "  drop(all=False)              - drop held item\n\n" +
        "HUD overlays (persist while script runs):\n" +
        "  hud_set(elements)       - set full HUD layout\n" +
        "  hud_update(id, **kwargs)- update one element\n" +
        "  Element types: label, bar, rect, divider, item\n" +
        "  Anchors: top_left, top_right, bottom_left, bottom_right, center\n\n" +
        "Custom in-game GUI (native Minecraft screen, opens/closes with H):\n" +
        "  open_gui(layout)              - open the screen with a widget layout\n" +
        "  gui_update(id, **props)       - update a widget's properties while open\n" +
        "  gui_close()                   - close the screen\n" +
        "  gui_state()   -> {open, event, inputs}  — event = last clicked button id (or None)\n" +
        "  gui_poll()    -> str | None             — pops last button click event\n" +
        "  gui_input(id) -> str                    — current text of an input field\n" +
        "  gui_is_open() -> bool\n\n" +
        "  Layout format: {'title':'Title','w':300,'h':200,'widgets':[...]}\n" +
        "  Widget fields: type (required), id (required), x, y (relative to content area)\n" +
        "  Widget types:\n" +
        "    label   : text, color\n" +
        "    bar     : value (0.0-1.0), w, h, color, bg_color\n" +
        "    button  : label, w, h    — click fires event with widget id\n" +
        "    input   : w, h, placeholder, value — readable via gui_input(id)\n" +
        "    divider : w, color\n" +
        "    rect    : w, h, color\n" +
        "  Colors: 'red' 'green' 'yellow' 'blue' 'white' 'grey' 'orange' 'purple'\n" +
        "          or hex '#RRGGBB' / '#AARRGGBB'\n\n" +
        "  Example GUI script:\n" +
        "    open_gui({'title':'Stats','w':260,'h':100,'widgets':[\n" +
        "      {'type':'label',  'id':'hp_lbl', 'x':0,  'y':0,  'text':'Health', 'color':'red'},\n" +
        "      {'type':'bar',    'id':'hp_bar', 'x':52, 'y':2,  'w':155,'h':5, 'value':1.0, 'color':'red'},\n" +
        "      {'type':'button', 'id':'close',  'x':0,  'y':20, 'w':60, 'h':14, 'label':'Close'},\n" +
        "    ]})\n" +
        "    while True:\n" +
        "      if gui_poll() == 'close': gui_close(); break\n" +
        "      gui_update('hp_bar', value=health()/20)\n" +
        "      wait(0.5)\n\n" +
        "Navigation:\n" +
        "  goto(x, z)               - walk to coordinates using built-in pathfinder (no Baritone needed)\n" +
        "  walk(blocks, direction)  - walk N blocks forward or in a direction\n" +
        "  nav_status()             -> dict with status, baritone (bool), pos\n\n" +
        "Baritone navigation (REQUIRES Baritone mod — modrinth.com/mod/baritone):\n" +
        "  baritone(command)        - send any Baritone command\n" +
        "  mine_auto(block, count)  - auto-mine a block type\n" +
        "  baritone_goto(x, y, z)   - pathfind to exact coordinates\n" +
        "  baritone_stop()          - stop Baritone\n" +
        "  IMPORTANT: If Baritone is not installed these will fail. Prefer goto() for basic navigation.\n\n" +
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
