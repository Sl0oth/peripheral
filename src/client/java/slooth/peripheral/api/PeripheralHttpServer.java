package slooth.peripheral.api;

import slooth.peripheral.PeripheralClient;
import slooth.peripheral.PeripheralNavigator;
import slooth.peripheral.script.ScriptRunner;
import slooth.peripheral.ui.ScriptableScreen;
import slooth.peripheral.util.PeripheralStateTracker;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Embedded HTTP server that exposes Minecraft's game state and accepts actions
 * from external Python scripts.  Listens on {@code localhost:25585}.
 *
 * <h2>Endpoints</h2>
 * <pre>
 *   GET  /state        – full game-state snapshot (health, pos, inventory, …)
 *   POST /action       – perform an in-game action (see action types below)
 *   GET  /gui          – poll the scriptable GUI (open?, pending event, inputs)
 *   GET  /log          – last N lines from the peripheral log
 *   GET  /inventory    – detailed inventory contents
 *   GET  /nav_scan     – nearby solid blocks for pathfinding
 *   POST /goto         – start Baritone navigation to (x, y, z)
 *   GET  /nav_status   – current navigation state
 *   POST /nav_stop     – cancel navigation
 *   POST /overlay/set  – add/replace a world-space draw command (see overlay types below)
 *   POST /overlay/clear – remove this script's world-space overlay commands
 * </pre>
 *
 * <h2>POST /overlay/set – overlay types</h2>
 * <pre>
 *   esp_box      – wireframe box at world position
 *   world_text   – floating text at world position
 *   line         – line between two world positions
 *   waypoint     – vertical stem + label
 *   player_line  – line from live player eye to world position
 *   live_esp     – auto-updating boxes/labels for nearby entities (render-thread)
 *   billboard    – camera-facing panel with multiple text rows ({text, color} per row)
 *   health_bar   – camera-facing progress bar with optional value/max label
 * </pre>
 *
 * <h2>POST /action – action types</h2>
 * <pre>
 *   send_chat          – send a plain-text chat message
 *   send_command       – run a command silently (no chat echo, no validator)
 *   display_message    – show a system message in chat (URLs auto-linked)
 *   send_rich          – show a JSON text-component with full click/hover events
 *   open_gui           – load a widget layout into ScriptableScreen
 *   update_gui         – update a widget's properties by id
 *   close_gui          – close the scriptable GUI screen
 *   use_item           – right-click the held item (eat, drink, bow, …)
 *   click_block        – left- or right-click a block face
 *   attack             – swing arm / attack entity in front of player
 *   drop_item          – drop held item (Q)
 *   equip              – move an inventory item to an equipment slot
 *   move               – apply a movement vector to the player
 *   look               – set yaw/pitch
 *   sprint             – toggle sprinting
 *   sneak              – toggle sneaking
 *   jump               – press space once
 * </pre>
 *
 * <p>All requests and responses use UTF-8 JSON.  The server runs on a single
 * virtual-thread executor so each request blocks independently without
 * starving the Minecraft render thread.  Actions that must run on the game
 * thread are submitted via {@code MinecraftClient.execute()} and awaited with
 * a 3-second {@code CountDownLatch}.
 */
public class PeripheralHttpServer {

    public static final int  PORT = 25585;
    private static final Gson GSON = new Gson();
    private static HttpServer instance;
    private static ExecutorService executor;
    public static volatile String status = "Not started";
    public static void stop() { if (instance != null) instance.stop(0); if (executor != null) executor.shutdownNow(); }

    public static void start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            instance=server;
            LocalApiSecurity.init();
            PeripheralMcp.configure();
            server.createContext("/mcp", PeripheralMcp::handle);
            server.createContext("/state", LocalApiSecurity.protect("/state", PeripheralHttpServer::handleState));
            server.createContext("/chat", LocalApiSecurity.protect("/chat", PeripheralHttpServer::handleChat));
            server.createContext("/inventory", LocalApiSecurity.protect("/inventory", PeripheralHttpServer::handleInventory));
            server.createContext("/protection", LocalApiSecurity.protect("/protection", PeripheralHttpServer::handleProtection));
            server.createContext("/craft", LocalApiSecurity.protect("/craft", PeripheralHttpServer::handleCraft));
            server.createContext("/action", LocalApiSecurity.protect("/action", PeripheralHttpServer::handleAction));
            server.createContext("/push_log", LocalApiSecurity.protect("/push_log", PeripheralHttpServer::handlePushLog));
            server.createContext("/nav_scan", LocalApiSecurity.protect("/nav_scan", PeripheralHttpServer::handleNavScan));
            server.createContext("/goto", LocalApiSecurity.protect("/goto", PeripheralHttpServer::handleGoto));
            server.createContext("/nav_status", LocalApiSecurity.protect("/nav_status", PeripheralHttpServer::handleNavStatus));
            server.createContext("/nav_stop", LocalApiSecurity.protect("/nav_stop", PeripheralHttpServer::handleNavStop));
            server.createContext("/chat_log", LocalApiSecurity.protect("/chat_log", PeripheralHttpServer::handleChatLog));
            server.createContext("/ping", LocalApiSecurity.protect("/ping", PeripheralHttpServer::handlePing));
            server.createContext("/config", LocalApiSecurity.protect("/config", PeripheralHttpServer::handleConfig));
            server.createContext("/run_script", LocalApiSecurity.protect("/run_script", PeripheralHttpServer::handleRunScript));
            server.createContext("/script_status", LocalApiSecurity.protect("/script_status", PeripheralHttpServer::handleScriptStatus));
            server.createContext("/gui", LocalApiSecurity.protect("/gui", PeripheralHttpServer::handleGui));
            server.createContext("/gui_peek", LocalApiSecurity.protect("/gui_peek", PeripheralHttpServer::handleGuiPeek));
            server.createContext("/block", LocalApiSecurity.protect("/block", PeripheralHttpServer::handleBlock));
            server.createContext("/target", LocalApiSecurity.protect("/target", PeripheralHttpServer::handleTarget));
            server.createContext("/hud/set", LocalApiSecurity.protect("/hud/set", PeripheralHttpServer::handleHudSet));
            server.createContext("/hud/update", LocalApiSecurity.protect("/hud/update", PeripheralHttpServer::handleHudUpdate));
            server.createContext("/hud/clear", LocalApiSecurity.protect("/hud/clear", PeripheralHttpServer::handleHudClear));
            server.createContext("/hud", LocalApiSecurity.protect("/hud", PeripheralHttpServer::handleHudGet));
            server.createContext("/overlay/set", LocalApiSecurity.protect("/overlay/set", PeripheralHttpServer::handleOverlaySet));
            server.createContext("/overlay/clear", LocalApiSecurity.protect("/overlay/clear", PeripheralHttpServer::handleOverlayClear));
            server.createContext("/find_blocks", LocalApiSecurity.protect("/find_blocks", PeripheralHttpServer::handleFindBlocks));
            server.createContext("/notify", LocalApiSecurity.protect("/notify", PeripheralHttpServer::handleNotify));
            server.createContext("/play_sound", LocalApiSecurity.protect("/play_sound", PeripheralHttpServer::handlePlaySound));
            // Newer methods share one handler: the path names the API method.
            for (String method : java.util.List.of("recorder", "recordings", "record_start", "record_stop", "play_recording", "stop_playback",
                    "screen", "click", "click_button", "click_slot", "mouse_move", "mouse_button", "scroll", "key", "type_text",
                    "packets", "packet_log", "packet_block", "packet_hold", "packet_send"))
                server.createContext("/" + method, LocalApiSecurity.protect("/" + method, ex -> handleMethod(ex, method)));
            executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), r -> { Thread t = new Thread(r, "peripheral-http"); t.setDaemon(true); return t; });
            server.setExecutor(executor);
            instance = server;
            server.start();
            status = "Ready";
            PeripheralClient.LOGGER.info("[Peripheral] HTTP server on port {}", PORT);
        } catch (IOException e) {
            if(instance!=null)instance.stop(0);
            status = "Local API unavailable: port " + PORT + " may already be in use. Close the other Peripheral client and restart.";
            PeripheralClient.LOGGER.error("[Peripheral] HTTP server failed: {}", e.getMessage());
        }
    }

    // ── GET /state ────────────────────────────────────────────────────────────
    private static void handleState(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, err("method_not_allowed")); return; }
        send(ex, 200, GSON.toJson(PeripheralApi.state()));
    }

    // ── POST /chat ────────────────────────────────────────────────────────────
    private static void handleChat(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, err("method_not_allowed")); return; }
        JsonObject req = parseBody(ex);
        String message = req.has("message") ? req.get("message").getAsString() : "";
        if (message.isEmpty()) { send(ex, 400, err("empty_message")); return; }
        if (message.startsWith("/")) { send(ex, 403, err("commands_not_allowed")); return; }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) { send(ex, 503, err("not_in_game")); return; }
        client.execute(() -> client.player.connection.sendChat(message));
        JsonObject resp = new JsonObject();
        resp.addProperty("ok", true); resp.addProperty("sent", message);
        send(ex, 200, GSON.toJson(resp));
    }

    // ── GET /inventory ────────────────────────────────────────────────────────
    private static void handleInventory(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, err("method_not_allowed")); return; }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) { send(ex, 503, err("not_in_game")); return; }
        send(ex, 200, GSON.toJson(PeripheralApi.inventory()));
    }

    // ── GET /protection ───────────────────────────────────────────────────────
    private static void handleProtection(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, err("method_not_allowed")); return; }
        send(ex, 200, GSON.toJson(PeripheralApi.protection()));
    }

    // ── GET /chat_log?since=<epochMs>&limit=<n> ───────────────────────────────
    private static void handleChatLog(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, err("method_not_allowed")); return; }
        long since = 0;
        int  limit = 20;
        String query = ex.getRequestURI().getQuery();
        if (query != null) {
            for (String p : query.split("&")) {
                if (p.startsWith("since="))  try { since = Long.parseLong(p.substring(6));   } catch (NumberFormatException ignored) {}
                if (p.startsWith("limit="))  try { limit = Math.min(200, Integer.parseInt(p.substring(6))); } catch (NumberFormatException ignored) {}
            }
        }
        JsonObject params = new JsonObject();
        params.addProperty("since", since);
        params.addProperty("limit", limit);
        send(ex, 200, GSON.toJson(PeripheralApi.chatLog(params)));
    }

    // ── GET /ping ─────────────────────────────────────────────────────────────
    private static void handlePing(HttpExchange ex) throws IOException {
        send(ex, 200, "{\"ok\":true}");
    }

    // ── POST /push_log ────────────────────────────────────────────────────────
    private static void handlePushLog(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, err("method_not_allowed")); return; }
        JsonObject req = parseBody(ex);
        String task   = req.has("task")      ? req.get("task").getAsString()      : null;
        String status = req.has("status")    ? req.get("status").getAsString()    : null;
        String action = req.has("action")    ? req.get("action").getAsString()    : null;
        int    iter   = req.has("iteration") ? req.get("iteration").getAsInt()    : -1;
        PeripheralStateTracker.pushLog(task, status, action, iter);
        send(ex, 200, "{\"ok\":true}");
    }

    // ── GET /config ───────────────────────────────────────────────────────────
    // Returns non-sensitive config so scripts can read agent URL and model.
    // API key is included — scripts need it to call Claude directly.
    private static void handleConfig(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, err("method_not_allowed")); return; }
        send(ex, 200, GSON.toJson(PeripheralApi.config()));
    }

    // ── POST /run_script { "name": "script.py" } ──────────────────────────────
    private static void handleRunScript(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, err("method_not_allowed")); return; }
        if (!LocalApiSecurity.control()) { send(ex, 403, err("control_authorization_required")); return; }
        JsonObject req  = parseBody(ex);
        String     name = req.has("name") ? req.get("name").getAsString() : "";
        if (name.isEmpty()) { send(ex, 400, err("missing_name")); return; }
        boolean    stop = req.has("stop") && req.get("stop").getAsBoolean();
        if (stop) {
            ScriptRunner.stopScript(name);
            send(ex, 200, "{\"ok\":true,\"action\":\"stopped\"}");
        } else {
            boolean ok = ScriptRunner.runScript(name);
            send(ex, 200, "{\"ok\":" + ok + ",\"action\":\"started\",\"name\":\"" + esc(name) + "\"}");
        }
    }

    // ── GET /script_status ────────────────────────────────────────────────────
    private static void handleScriptStatus(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, err("method_not_allowed")); return; }
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (ScriptRunner.ScriptInfo info : ScriptRunner.getStatus()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name",       info.name());
            obj.addProperty("running",    info.running());
            obj.addProperty("started_at", info.startedAt());
            arr.add(obj);
        }
        JsonObject resp = new JsonObject();
        resp.add("scripts", arr);
        send(ex, 200, GSON.toJson(resp));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ── POST /craft ───────────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════

    private record Recipe(String[] ingredients, int[][] placements, boolean needsTable) {}

    private static final java.util.Map<String, Recipe> RECIPES = new java.util.LinkedHashMap<>();

    static {
        // 2×2 (inventory)
        RECIPES.put("oak_planks",      new Recipe(new String[]{"oak_log"},      new int[][]{{1,0}}, false));
        RECIPES.put("spruce_planks",   new Recipe(new String[]{"spruce_log"},   new int[][]{{1,0}}, false));
        RECIPES.put("birch_planks",    new Recipe(new String[]{"birch_log"},    new int[][]{{1,0}}, false));
        RECIPES.put("jungle_planks",   new Recipe(new String[]{"jungle_log"},   new int[][]{{1,0}}, false));
        RECIPES.put("acacia_planks",   new Recipe(new String[]{"acacia_log"},   new int[][]{{1,0}}, false));
        RECIPES.put("dark_oak_planks", new Recipe(new String[]{"dark_oak_log"}, new int[][]{{1,0}}, false));
        RECIPES.put("mangrove_planks", new Recipe(new String[]{"mangrove_log"}, new int[][]{{1,0}}, false));
        RECIPES.put("cherry_planks",   new Recipe(new String[]{"cherry_log"},   new int[][]{{1,0}}, false));
        RECIPES.put("stick",           new Recipe(new String[]{"planks"},       new int[][]{{1,0},{3,0}}, false));
        RECIPES.put("crafting_table",  new Recipe(new String[]{"planks"},       new int[][]{{1,0},{2,0},{3,0},{4,0}}, false));
        // 3×3 (crafting table)
        RECIPES.put("wooden_pickaxe",  new Recipe(new String[]{"planks","planks","planks","stick","stick"},            new int[][]{{1,0},{2,1},{3,2},{5,3},{8,4}}, true));
        RECIPES.put("stone_pickaxe",   new Recipe(new String[]{"cobblestone","cobblestone","cobblestone","stick","stick"}, new int[][]{{1,0},{2,1},{3,2},{5,3},{8,4}}, true));
        RECIPES.put("iron_pickaxe",    new Recipe(new String[]{"iron_ingot","iron_ingot","iron_ingot","stick","stick"}, new int[][]{{1,0},{2,1},{3,2},{5,3},{8,4}}, true));
        RECIPES.put("golden_pickaxe",  new Recipe(new String[]{"gold_ingot","gold_ingot","gold_ingot","stick","stick"}, new int[][]{{1,0},{2,1},{3,2},{5,3},{8,4}}, true));
        RECIPES.put("diamond_pickaxe", new Recipe(new String[]{"diamond","diamond","diamond","stick","stick"},          new int[][]{{1,0},{2,1},{3,2},{5,3},{8,4}}, true));
        RECIPES.put("wooden_axe",      new Recipe(new String[]{"planks","planks","planks","stick","stick"},            new int[][]{{1,0},{2,1},{4,2},{5,3},{8,4}}, true));
        RECIPES.put("stone_axe",       new Recipe(new String[]{"cobblestone","cobblestone","cobblestone","stick","stick"}, new int[][]{{1,0},{2,1},{4,2},{5,3},{8,4}}, true));
        RECIPES.put("iron_axe",        new Recipe(new String[]{"iron_ingot","iron_ingot","iron_ingot","stick","stick"}, new int[][]{{1,0},{2,1},{4,2},{5,3},{8,4}}, true));
        RECIPES.put("wooden_shovel",   new Recipe(new String[]{"planks","stick","stick"},      new int[][]{{2,0},{5,1},{8,2}}, true));
        RECIPES.put("stone_shovel",    new Recipe(new String[]{"cobblestone","stick","stick"}, new int[][]{{2,0},{5,1},{8,2}}, true));
        RECIPES.put("iron_shovel",     new Recipe(new String[]{"iron_ingot","stick","stick"},  new int[][]{{2,0},{5,1},{8,2}}, true));
        RECIPES.put("wooden_sword",    new Recipe(new String[]{"planks","planks","stick"},              new int[][]{{2,0},{5,1},{8,2}}, true));
        RECIPES.put("stone_sword",     new Recipe(new String[]{"cobblestone","cobblestone","stick"},    new int[][]{{2,0},{5,1},{8,2}}, true));
        RECIPES.put("iron_sword",      new Recipe(new String[]{"iron_ingot","iron_ingot","stick"},      new int[][]{{2,0},{5,1},{8,2}}, true));
        RECIPES.put("furnace",         new Recipe(new String[]{"cobblestone","cobblestone","cobblestone","cobblestone","cobblestone","cobblestone","cobblestone","cobblestone"},
                                           new int[][]{{1,0},{2,1},{3,2},{4,3},{6,4},{7,5},{8,6},{9,7}}, true));
        RECIPES.put("chest",           new Recipe(new String[]{"planks","planks","planks","planks","planks","planks","planks","planks"},
                                           new int[][]{{1,0},{2,1},{3,2},{4,3},{6,4},{7,5},{8,6},{9,7}}, true));
        RECIPES.put("torch",           new Recipe(new String[]{"coal","stick"}, new int[][]{{2,0},{5,1}}, true));
        RECIPES.put("iron_helmet",     new Recipe(new String[]{"iron_ingot","iron_ingot","iron_ingot","iron_ingot","iron_ingot"},
                                           new int[][]{{1,0},{2,1},{3,2},{4,3},{6,4}}, true));
        RECIPES.put("iron_chestplate", new Recipe(new String[]{"iron_ingot","iron_ingot","iron_ingot","iron_ingot","iron_ingot","iron_ingot","iron_ingot","iron_ingot"},
                                           new int[][]{{1,0},{3,1},{4,2},{5,3},{6,4},{7,5},{8,6},{9,7}}, true));
        RECIPES.put("iron_leggings",   new Recipe(new String[]{"iron_ingot","iron_ingot","iron_ingot","iron_ingot","iron_ingot","iron_ingot","iron_ingot"},
                                           new int[][]{{1,0},{2,1},{3,2},{4,3},{6,4},{7,5},{9,6}}, true));
        RECIPES.put("iron_boots",      new Recipe(new String[]{"iron_ingot","iron_ingot","iron_ingot","iron_ingot"},
                                           new int[][]{{4,0},{6,1},{7,2},{9,3}}, true));
        RECIPES.put("smithing_table",  new Recipe(new String[]{"iron_ingot","iron_ingot","planks","planks","planks","planks"},
                                           new int[][]{{1,0},{2,1},{4,2},{5,3},{7,4},{8,5}}, true));
    }

    private static void handleCraft(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, err("method_not_allowed")); return; }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) { send(ex, 503, err("not_in_game")); return; }

        JsonObject req   = parseBody(ex);
        String     rawId = req.has("item") ? req.get("item").getAsString() : "";
        if (rawId.isEmpty()) { send(ex, 400, err("no_item")); return; }

        String itemKey = rawId.replace("minecraft:", "").toLowerCase();
        Recipe recipe  = null;
        for (var entry : RECIPES.entrySet()) {
            if (itemKey.equals(entry.getKey()) || itemKey.endsWith(entry.getKey())) {
                recipe = entry.getValue(); break;
            }
        }
        if (recipe == null) { send(ex, 200, "{\"success\":false,\"error\":\"unknown_recipe:" + itemKey + "\"}"); return; }

        final Recipe fRecipe = recipe;
        java.util.Map<String, Integer> needed = new java.util.LinkedHashMap<>();
        for (String ing : fRecipe.ingredients()) needed.merge(ing, 1, Integer::sum);

        java.util.Map<String, Integer> ingToSlot = new java.util.LinkedHashMap<>();
        int invSize = client.player.getInventory().getNonEquipmentItems().size();
        for (var entry : needed.entrySet()) {
            String keyword = entry.getKey(); int count = entry.getValue(); int found = 0;
            for (int i = 0; i < invSize; i++) {
                String id = client.player.getInventory().getNonEquipmentItems().get(i).getItem().toString().replace("minecraft:","").toLowerCase();
                if (id.contains(keyword)) {
                    if (!ingToSlot.containsKey(keyword)) ingToSlot.put(keyword, i);
                    found += client.player.getInventory().getNonEquipmentItems().get(i).getCount();
                }
            }
            if (found < count) {
                send(ex, 200, "{\"success\":false,\"error\":\"missing_ingredients\",\"missing\":{\"" + keyword + "\":" + (count - found) + "}}");
                return;
            }
        }

        if (fRecipe.needsTable()) {
            boolean alreadyOpen = client.gui.screen() != null;
            if (!alreadyOpen) {
                BlockPos tablePos = findNearby(client, "crafting_table", 6);
                if (tablePos == null) { send(ex, 200, "{\"success\":false,\"error\":\"no_crafting_table_nearby\"}"); return; }
                CountDownLatch openLatch = new CountDownLatch(1);
                final BlockPos fPos = tablePos;
                client.execute(() -> {
                    try {
                        lookAt(client.player, Vec3.atCenterOf(fPos));
                        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(fPos), Direction.UP, fPos, false));
                    } catch (Exception ignored) { } finally { openLatch.countDown(); }
                });
                try { openLatch.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                sleep(700);
            }
            if (client.gui.screen() == null) { send(ex, 200, "{\"success\":false,\"error\":\"crafting_table_did_not_open\"}"); return; }
        }

        java.util.Map<String, java.util.List<Integer>> keywordToGridSlots = new java.util.LinkedHashMap<>();
        for (int[] p : fRecipe.placements())
            keywordToGridSlots.computeIfAbsent(fRecipe.ingredients()[p[1]], k -> new java.util.ArrayList<>()).add(p[0]);

        AtomicBoolean craftOk  = new AtomicBoolean(false);
        AtomicReference<String> craftErr = new AtomicReference<>("");
        CountDownLatch clickLatch = new CountDownLatch(1);
        client.execute(() -> {
            try {
                AbstractContainerMenu sh = client.player.containerMenu;
                if (sh == null) { craftErr.set("no_screen_handler"); return; }
                int syncId = sh.containerId;
                for (var kv : keywordToGridSlots.entrySet()) {
                    int mainIdx = ingToSlot.getOrDefault(kv.getKey(), -1);
                    if (mainIdx < 0) { craftErr.set("ingredient_not_found:" + kv.getKey()); return; }
                    int containerSlot = mainIdxToContainerSlot(mainIdx, fRecipe.needsTable());
                    client.gameMode.handleContainerInput(syncId, containerSlot, 0, ContainerInput.PICKUP, client.player);
                    for (int gs : kv.getValue())
                        client.gameMode.handleContainerInput(syncId, gs, 1, ContainerInput.PICKUP, client.player);
                    client.gameMode.handleContainerInput(syncId, containerSlot, 0, ContainerInput.PICKUP, client.player);
                }
                craftOk.set(true);
            } catch (Exception e) { craftErr.set(e.getMessage()); } finally { clickLatch.countDown(); }
        });
        try { clickLatch.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        if (!craftOk.get()) { send(ex, 200, "{\"success\":false,\"error\":\"" + craftErr.get() + "\"}"); return; }

        sleep(600);
        CountDownLatch resultLatch = new CountDownLatch(1);
        AtomicBoolean  tookResult  = new AtomicBoolean(false);
        client.execute(() -> {
            try {
                AbstractContainerMenu sh = client.player.containerMenu;
                if (sh != null) { client.gameMode.handleContainerInput(sh.containerId, 0, 0, ContainerInput.QUICK_MOVE, client.player); tookResult.set(true); }
            } finally { resultLatch.countDown(); }
        });
        try { resultLatch.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        sleep(300);

        if (fRecipe.needsTable()) {
            CountDownLatch closeLatch = new CountDownLatch(1);
            client.execute(() -> { try { if (client.gui.screen() != null) client.player.closeContainer(); } finally { closeLatch.countDown(); } });
            try { closeLatch.await(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
        send(ex, 200, tookResult.get() ? "{\"success\":true}" : "{\"success\":false,\"error\":\"no_result_in_slot\"}");
    }

    private static int mainIdxToContainerSlot(int i, boolean needsTable) {
        return needsTable ? (i < 9 ? 37 + i : i + 1) : (i < 9 ? 36 + i : i);
    }

    // ── POST /action ──────────────────────────────────────────────────────────
    private static void handleAction(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, err("method_not_allowed")); return; }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) { send(ex, 503, err("not_in_game")); return; }
        JsonObject result = PeripheralApi.action(parseBody(ex));
        boolean ok = result.get("ok").getAsBoolean();
        send(ex, 200, ok ? "{\"ok\":true}"
            : "{\"ok\":false,\"error\":\"" + esc(result.get("error").getAsString()) + "\"}");
    }

    // ── GET /nav_scan ─────────────────────────────────────────────────────────
    private static void handleNavScan(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, err("method_not_allowed")); return; }
        int radius = 24;
        String query = ex.getRequestURI().getQuery();
        if (query != null) for (String p : query.split("&"))
            if (p.startsWith("radius=")) try { radius = Math.min(32, Integer.parseInt(p.substring(7))); } catch (NumberFormatException ignored) {}

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) { send(ex, 503, err("not_in_game")); return; }

        BlockPos origin = client.player.blockPosition();
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        StringBuilder sb = new StringBuilder(65536);
        sb.append("{\"origin\":{\"x\":").append(ox).append(",\"y\":").append(oy).append(",\"z\":").append(oz).append("},\"blocks\":[");
        boolean first = true;
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) for (int y = oy - 4; y <= oy + 8; y++) {
            BlockPos pos = new BlockPos(ox + dx, y, oz + dz);
            BlockState state = client.level.getBlockState(pos);
            if (!state.isAir() && !state.getCollisionShape(client.level, pos).isEmpty()) {
                if (!first) sb.append(',');
                sb.append('[').append(ox + dx).append(',').append(y).append(',').append(oz + dz).append(']');
                first = false;
            }
        }
        sb.append("]}");
        send(ex, 200, sb.toString());
    }

    // ── POST /goto ────────────────────────────────────────────────────────────
    private static void handleGoto(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, err("method_not_allowed")); return; }
        try {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) { send(ex, 503, err("not_in_game")); return; }
            PeripheralApi.gotoAction(parseBody(ex));
            send(ex, 200, "{\"ok\":true,\"status\":\"planning\"}");
        } catch (Throwable t) { send(ex, 500, "{\"error\":\"" + t.getMessage() + "\"}"); }
    }

    // ── GET /nav_status ───────────────────────────────────────────────────────
    private static void handleNavStatus(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, err("method_not_allowed")); return; }
        PeripheralNavigator nav = PeripheralNavigator.INSTANCE;
        Minecraft client  = Minecraft.getInstance();
        String posStr = "null";
        if (client.player != null)
            posStr = String.format("{\"x\":%.2f,\"y\":%.2f,\"z\":%.2f}", client.player.getX(), client.player.getY(), client.player.getZ());
        boolean hasBaritone = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("baritone");
        send(ex, 200, "{\"status\":\"" + nav.getNavStatus().name().toLowerCase()
            + "\",\"reason\":\"" + esc(nav.getFailReason())
            + "\",\"waypoints_left\":" + nav.getWpLeft()
            + ",\"baritone\":" + hasBaritone
            + ",\"pos\":" + posStr + "}");
    }

    // ── POST /nav_stop ────────────────────────────────────────────────────────
    private static void handleNavStop(HttpExchange ex) throws IOException {
        if(!ex.getRequestMethod().equals("POST")) { send(ex,405,err("method_not_allowed"));return; }
        try { PeripheralApi.navStop(); send(ex, 200, "{\"ok\":true}"); }
        catch (Throwable t) { send(ex, 500, "{\"error\":\"" + esc(t.getMessage()) + "\"}"); }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static BlockPos findNearby(Minecraft c, String keyword, int radius) {
        BlockPos origin = c.player.blockPosition();
        for (int dx = -radius; dx <= radius; dx++) for (int dy = -3; dy <= 3; dy++) for (int dz = -radius; dz <= radius; dz++) {
            BlockPos p = origin.offset(dx, dy, dz);
            if (c.level.getBlockState(p).getBlock().toString().contains(keyword)) return p;
        }
        return null;
    }

    static void lookAt(LocalPlayer player, Vec3 target) {
        Vec3 dir = target.subtract(player.getEyePosition()).normalize();
        player.setYRot((float) Math.toDegrees(Math.atan2(-dir.x, dir.z)));
        player.setXRot((float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, dir.y)))));
    }

    static Direction dirFrom(String s) {
        return switch (s.toLowerCase()) {
            case "north" -> Direction.NORTH; case "south" -> Direction.SOUTH;
            case "east"  -> Direction.EAST;  case "west"  -> Direction.WEST;
            case "down"  -> Direction.DOWN;  default      -> Direction.UP;
        };
    }

    // ── GET /block?x=&y=&z= ──────────────────────────────────────────────────
    private static void handleBlock(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, err("method_not_allowed")); return; }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) { send(ex, 503, err("not_in_game")); return; }
        int x = 0, y = 64, z = 0;
        String query = ex.getRequestURI().getQuery();
        if (query != null) {
            for (String p : query.split("&")) {
                if (p.startsWith("x=")) try { x = Integer.parseInt(p.substring(2)); } catch (NumberFormatException ignored) {}
                if (p.startsWith("y=")) try { y = Integer.parseInt(p.substring(2)); } catch (NumberFormatException ignored) {}
                if (p.startsWith("z=")) try { z = Integer.parseInt(p.substring(2)); } catch (NumberFormatException ignored) {}
            }
        }
        JsonObject params = new JsonObject();
        params.addProperty("x", x);
        params.addProperty("y", y);
        params.addProperty("z", z);
        send(ex, 200, GSON.toJson(PeripheralApi.block(params)));
    }

    // ── GET /target ───────────────────────────────────────────────────────────
    private static void handleTarget(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, err("method_not_allowed")); return; }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) { send(ex, 503, err("not_in_game")); return; }
        send(ex, 200, GSON.toJson(PeripheralApi.target()));
    }

    // ── GET /gui ─────────────────────────────────────────────────────────────
    // Returns: { open: bool, event: "widget_id" | null, inputs: {id: text} }
    // Calling this pops one button-click event from the queue (if any).
    private static void handleGui(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, err("method_not_allowed")); return; }
        send(ex, 200, GSON.toJson(PeripheralApi.gui()));
    }

    private static void handleGuiPeek(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, err("method_not_allowed")); return; }
        send(ex, 200, GSON.toJson(PeripheralApi.guiSnapshot(false)));
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ── HUD overlay endpoints ─────────────────────────────────────────────────

    private static void handleHudSet(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, err("method_not_allowed")); return; }
        JsonObject req = parseBody(ex);
        if (!req.has("elements")) { send(ex, 400, err("missing_elements")); return; }
        PeripheralApi.hudSet(req);
        send(ex, 200, "{\"ok\":true}");
    }

    private static void handleHudUpdate(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, err("method_not_allowed")); return; }
        JsonObject req = parseBody(ex);
        if (!req.has("id")) { send(ex, 400, err("missing_id")); return; }
        PeripheralApi.hudUpdate(req);
        send(ex, 200, "{\"ok\":true}");
    }

    private static void handleHudClear(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, err("method_not_allowed")); return; }
        PeripheralApi.hudClear(parseBody(ex));
        send(ex, 200, "{\"ok\":true}");
    }

    private static void handleHudGet(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, err("method_not_allowed")); return; }
        send(ex, 200, GSON.toJson(PeripheralApi.hudGet()));
    }

    // ── POST /find_blocks, /notify, /play_sound ──────────────────────────────

    private static void handleFindBlocks(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, err("method_not_allowed")); return; }
        try { send(ex, 200, GSON.toJson(PeripheralApi.findBlocks(parseBody(ex)))); }
        catch (PeripheralApi.ApiException e) { send(ex, 400, err(e.getMessage())); }
    }

    private static void handleNotify(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, err("method_not_allowed")); return; }
        try { send(ex, 200, GSON.toJson(PeripheralApi.notifyToast(parseBody(ex)))); }
        catch (PeripheralApi.ApiException e) { send(ex, 400, err(e.getMessage())); }
    }

    private static void handlePlaySound(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, err("method_not_allowed")); return; }
        try { send(ex, 200, GSON.toJson(PeripheralApi.playSound(parseBody(ex)))); }
        catch (PeripheralApi.ApiException e) { send(ex, 400, err(e.getMessage())); }
    }

    /** Runs one {@link PeripheralApi} method: GET without a body, or POST with JSON parameters. */
    private static void handleMethod(HttpExchange ex, String method) throws IOException {
        try {
            JsonObject params = ex.getRequestMethod().equals("POST") ? parseBody(ex) : LocalApiSecurity.owned(new JsonObject());
            send(ex, 200, GSON.toJson(PeripheralApi.handle(method, params)));
        } catch (PeripheralApi.ApiException e) {
            send(ex, 400, err(e.getMessage()));
        }
    }

    private static JsonObject parseBody(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        return LocalApiSecurity.owned(JsonParser.parseString(body).getAsJsonObject());
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody(); ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096]; int n;
            while ((n = is.read(chunk)) != -1) {
                if (buf.size() + n > LocalApiSecurity.MAX_BODY) throw new IllegalArgumentException("Request too large");
                buf.write(chunk, 0, n);
            }
            return buf.toString(StandardCharsets.UTF_8);
        }
    }

    private static void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static String err(String msg) { return "{\"error\":\"" + msg + "\"}"; }
    private static String esc(String s)   { return s.replace("\\","\\\\").replace("\"","\\\""); }

    // ── URL-aware chat text builder ───────────────────────────────────────────
    // Splits `raw` on http(s):// URLs and gives each URL segment a ClickEvent
    // so it opens in the player's default browser when clicked in chat.
    private static final Pattern CHAT_URL_PAT = Pattern.compile("https?://[^\\s§]+");

    static MutableComponent buildChatText(String raw) {
        MutableComponent out = Component.empty();
        Matcher m = CHAT_URL_PAT.matcher(raw);
        int last = 0;
        while (m.find()) {
            if (m.start() > last)
                out.append(Component.literal(raw.substring(last, m.start())));
            String url = m.group();
            out.append(Component.literal(url).withStyle(s -> s
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(url)))
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                    Component.literal("Open in browser")))));
            last = m.end();
        }
        if (last < raw.length())
            out.append(Component.literal(raw.substring(last)));
        return out;
    }

    // ── Rich text builder (JSON text component → MutableText) ─────────────────
    // Parses our Python-style JSON schema directly using Gson + the Java API.
    // Handles arrays (sibling list), objects (single component), and the four
    // main clickEvent action types.  Accepts both the old "value" key and the
    // 1.21.5+ renamed keys ("command", "url") for each action.
    static MutableComponent buildRichText(com.google.gson.JsonElement element) {
        if (element == null || element.isJsonNull()) return Component.empty();

        // Array → concatenate siblings
        if (element.isJsonArray()) {
            MutableComponent out = Component.empty();
            for (com.google.gson.JsonElement child : element.getAsJsonArray())
                out.append(buildRichText(child));
            return out;
        }

        // Primitive string → plain text
        if (element.isJsonPrimitive()) return Component.literal(element.getAsString());

        // Object → full component
        com.google.gson.JsonObject obj = element.getAsJsonObject();
        String text = obj.has("text") ? obj.get("text").getAsString() : "";
        MutableComponent comp = Component.literal(text);

        net.minecraft.network.chat.Style style = net.minecraft.network.chat.Style.EMPTY;

        // Color
        if (obj.has("color")) {
            // Color names are the lowercase ChatFormatting colors ("dark_red"), BLACK through WHITE; others are ignored.
            String name = obj.get("color").getAsString().trim().toUpperCase(java.util.Locale.ROOT);
            for (ChatFormatting fmt : ChatFormatting.values())
                if (fmt.ordinal() <= ChatFormatting.WHITE.ordinal() && fmt.name().equals(name)) { style = style.withColor(fmt); break; }
        }
        // Decorations
        if (obj.has("bold")        && obj.get("bold").getAsBoolean())        style = style.withBold(true);
        if (obj.has("italic")      && obj.get("italic").getAsBoolean())      style = style.withItalic(true);
        if (obj.has("underlined")  && obj.get("underlined").getAsBoolean())  style = style.withUnderlined(true);
        if (obj.has("obfuscated")  && obj.get("obfuscated").getAsBoolean())  style = style.withObfuscated(true);
        if (obj.has("strikethrough") && obj.get("strikethrough").getAsBoolean()) style = style.withStrikethrough(true);

        // clickEvent
        if (obj.has("clickEvent")) {
            com.google.gson.JsonObject ce = obj.get("clickEvent").getAsJsonObject();
            String action = ce.has("action") ? ce.get("action").getAsString() : "";
            // helper: read "command" key, falling back to legacy "value"
            String cmd = ce.has("command") ? ce.get("command").getAsString()
                       : ce.has("value")   ? ce.get("value").getAsString() : "";
            String url = ce.has("url")   ? ce.get("url").getAsString()
                       : ce.has("value") ? ce.get("value").getAsString() : "";
            try {
                style = switch (action) {
                    case "open_url"          -> style.withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(url)));
                    case "run_command"       -> style.withClickEvent(new ClickEvent.RunCommand(cmd));
                    case "suggest_command"   -> style.withClickEvent(new ClickEvent.SuggestCommand(cmd));
                    case "copy_to_clipboard" -> style.withClickEvent(new ClickEvent.CopyToClipboard(
                                                   ce.has("value") ? ce.get("value").getAsString() : cmd));
                    default -> style;
                };
            } catch (Exception ignored) {}
        }

        // hoverEvent — only show_text supported (item/entity needs registries)
        if (obj.has("hoverEvent")) {
            com.google.gson.JsonObject he = obj.get("hoverEvent").getAsJsonObject();
            String action = he.has("action") ? he.get("action").getAsString() : "";
            if ("show_text".equals(action)) {
                com.google.gson.JsonElement contents = he.has("contents") ? he.get("contents")
                                                     : he.has("value")    ? he.get("value") : null;
                if (contents != null)
                    style = style.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                        buildRichText(contents)));
            }
        }

        comp.setStyle(style);

        // Extra (child components appended inline)
        if (obj.has("extra") && obj.get("extra").isJsonArray())
            for (com.google.gson.JsonElement child : obj.get("extra").getAsJsonArray())
                comp.append(buildRichText(child));

        return comp;
    }

    // ── World-space overlay endpoints ─────────────────────────────────────────

    private static void handleOverlaySet(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, err("method_not_allowed")); return; }
        JsonObject req = parseBody(ex);
        if (!req.has("type")) { send(ex, 400, err("missing_type")); return; }
        PeripheralApi.overlaySet(req);
        send(ex, 200, "{\"ok\":true}");
    }

    private static void handleOverlayClear(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, err("method_not_allowed")); return; }
        PeripheralApi.overlayClear(parseBody(ex));
        send(ex, 200, "{\"ok\":true}");
    }
}
