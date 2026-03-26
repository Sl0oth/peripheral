package slooth.peripheral;

import com.google.gson.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Handles all communication with the Peripheral Script Store server.
 *
 * All network calls are made on a background thread — never the render thread.
 *
 * Usage:
 *   StoreClient.fetchList(scripts -> { ... });           // list of ScriptEntry
 *   StoreClient.fetchScript(id, entry -> { ... });       // full script with code
 *   StoreClient.install(entry, scriptsDir, callback);    // write to disk
 */
public class StoreClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private static final Gson GSON = new Gson();
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "peripheral-store");
        t.setDaemon(true);
        return t;
    });

    // ── Data model ────────────────────────────────────────────────────────────

    public record ScriptEntry(
        String id,
        String name,
        String description,
        String author,
        String code,          // null when loaded from list endpoint
        String createdAt
    ) {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetch the list of approved scripts (no code).
     * Callback is invoked on the calling thread pool (NOT the render thread).
     * On error, callback receives null.
     */
    // ── List cache — reuse for 60 s to avoid hammering Cloudflare ───────────────
    private static volatile List<ScriptEntry> cachedList     = null;
    private static volatile long              cachedListTime  = 0;
    private static final long                 CACHE_TTL_MS    = 60_000;

    public static void invalidateCache() {
        cachedList    = null;
        cachedListTime = 0;
    }

    public static void fetchList(Consumer<List<ScriptEntry>> callback) {
        // Return cached list if it's fresh
        long now = System.currentTimeMillis();
        if (cachedList != null && now - cachedListTime < CACHE_TTL_MS) {
            callback.accept(cachedList);
            return;
        }
        POOL.submit(() -> {
            try {
                String url  = PeripheralConfig.storeUrl.replaceAll("/+$", "") + "/api/store/scripts";
                String body = get(url);
                JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
                List<ScriptEntry> list = new ArrayList<>();
                for (JsonElement el : arr) {
                    JsonObject o = el.getAsJsonObject();
                    list.add(new ScriptEntry(
                        str(o, "id"), str(o, "name"), str(o, "description"),
                        str(o, "author"), null, str(o, "created_at")
                    ));
                }
                cachedList     = list;
                cachedListTime = System.currentTimeMillis();
                callback.accept(list);
            } catch (Exception e) {
                PeripheralClient.LOGGER.warn("[Store] fetchList failed: {}", e.getMessage());
                callback.accept(null);
            }
        });
    }

    /**
     * Fetch a single script by ID, including its code.
     * Callback receives null on error.
     */
    public static void fetchScript(String id, Consumer<ScriptEntry> callback) {
        POOL.submit(() -> {
            try {
                String url  = PeripheralConfig.storeUrl.replaceAll("/+$", "") + "/api/store/scripts/" + id;
                String body = get(url);
                JsonObject o = JsonParser.parseString(body).getAsJsonObject();
                // Server may use "content" or "code" — try both
                String codeField = str(o, "content");
                if (codeField.isEmpty()) codeField = str(o, "code");
                callback.accept(new ScriptEntry(
                    str(o, "id"), str(o, "name"), str(o, "description"),
                    str(o, "author"), codeField, str(o, "created_at")
                ));
            } catch (Exception e) {
                PeripheralClient.LOGGER.warn("[Store] fetchScript failed: {}", e.getMessage());
                callback.accept(null);
            }
        });
    }

    /**
     * Write a script to the store sub-directory of the scripts folder.
     * The file is named <sanitized_script_name>.py inside scripts/store/.
     * Callback receives the installed path string, or null on error.
     */
    public static void install(ScriptEntry entry, Path scriptsDir, Consumer<String> callback) {
        if (entry.code() == null || entry.code().isBlank()) {
            callback.accept(null);
            return;
        }
        POOL.submit(() -> {
            try {
                Path storeDir = scriptsDir.resolve("store");
                Files.createDirectories(storeDir);
                String safeName = entry.name()
                    .toLowerCase()
                    .replaceAll("[^a-z0-9_]", "_")
                    .replaceAll("_+", "_")
                    .replaceAll("^_|_$", "");
                if (safeName.isBlank()) safeName = entry.id();
                // Avoid overwriting — append _2, _3 etc. if needed
                Path target = storeDir.resolve(safeName + ".py");
                int n = 2;
                while (Files.exists(target)) {
                    target = storeDir.resolve(safeName + "_" + n + ".py");
                    n++;
                }
                // Prepend a comment header
                String header = String.format(
                    "# ── Installed from Peripheral Store ──────────────────────────────────────────\n" +
                    "# Name   : %s\n" +
                    "# Author : %s\n" +
                    "# Store  : %s\n" +
                    "#\n" +
                    "# The mod author is NOT responsible for community scripts. Use at your own risk.\n" +
                    "# ─────────────────────────────────────────────────────────────────────────────\n\n",
                    entry.name(), entry.author(), PeripheralConfig.storeUrl
                );
                Files.writeString(target, header + entry.code(), StandardCharsets.UTF_8);
                // Return relative path from scripts dir (e.g. "store/my_script.py")
                callback.accept("store/" + target.getFileName().toString());
            } catch (Exception e) {
                PeripheralClient.LOGGER.warn("[Store] install failed: {}", e.getMessage());
                callback.accept(null);
            }
        });
    }

    /**
     * Submit a script to the store for review.
     * displayAuthor is shown publicly; realAuthor is stored server-side for admins only.
     * Callback receives true on HTTP 201, false on any error.
     */
    public static void submit(String name, String description,
                               String displayAuthor, String realAuthor,
                               String content, java.util.function.Consumer<Boolean> callback) {
        POOL.submit(() -> {
            try {
                String url = PeripheralConfig.storeUrl.replaceAll("/+$", "") + "/api/store/submit";
                JsonObject body = new JsonObject();
                body.addProperty("name",         name);
                body.addProperty("description",  description);
                body.addProperty("author",        displayAuthor);
                body.addProperty("submitted_by",  realAuthor);
                body.addProperty("content",       content);
                body.addProperty("category",      "general");
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", UA)
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                callback.accept(resp.statusCode() == 201);
            } catch (Exception e) {
                PeripheralClient.LOGGER.warn("[Store] submit failed: {}", e.getMessage());
                callback.accept(false);
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static String get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", UA)
            .GET()
            .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new IOException("HTTP " + resp.statusCode() + " from " + url);
        return resp.body();
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsString() : "";
    }

}
