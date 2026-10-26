package slooth.peripheral.ui;

import slooth.peripheral.PeripheralClient;
import slooth.peripheral.script.ScriptRunner;
import slooth.peripheral.util.PeripheralConfig;

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
    private static final String SYSTEM_PROMPT = loadSystemPrompt();

    private static String loadSystemPrompt() {
        // The builder and exported authoring guide must describe the same API.
        // Read the bundled reference, not a potentially edited runtime file.
        try (var in = BuildSession.class.getResourceAsStream("/peripheral/AI_PROMPT.md")) {
            if (in == null) throw new IllegalStateException("Bundled Peripheral API guide is missing");
            return "You write Python scripts for the Peripheral Minecraft mod. "
                + "Use the installed API reference below. Do not invent unavailable capabilities. "
                + "Return complete scripts in a python fenced code block; code is saved to the selected script file.\n\n"
                + new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not read the bundled Peripheral API guide", e);
        }
    }

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
            Path p = slooth.peripheral.script.ScriptPaths.resolve(scriptRelPath);
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
                    Path scriptPath = slooth.peripheral.script.ScriptPaths.resolve(scriptRelPath);
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
