package slooth.peripheral;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import java.net.URI;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    public static void start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            server.createContext("/state",         PeripheralHttpServer::handleState);
            server.createContext("/chat",          PeripheralHttpServer::handleChat);
            server.createContext("/inventory",     PeripheralHttpServer::handleInventory);
            server.createContext("/protection",    PeripheralHttpServer::handleProtection);
            server.createContext("/craft",         PeripheralHttpServer::handleCraft);
            server.createContext("/action",        PeripheralHttpServer::handleAction);
            server.createContext("/push_log",      PeripheralHttpServer::handlePushLog);
            server.createContext("/nav_scan",      PeripheralHttpServer::handleNavScan);
            server.createContext("/goto",          PeripheralHttpServer::handleGoto);
            server.createContext("/nav_status",    PeripheralHttpServer::handleNavStatus);
            server.createContext("/nav_stop",      PeripheralHttpServer::handleNavStop);
            server.createContext("/chat_log",      PeripheralHttpServer::handleChatLog);
            server.createContext("/ping",          PeripheralHttpServer::handlePing);
            server.createContext("/config",        PeripheralHttpServer::handleConfig);
            server.createContext("/run_script",    PeripheralHttpServer::handleRunScript);
            server.createContext("/script_status", PeripheralHttpServer::handleScriptStatus);
            server.createContext("/gui",           PeripheralHttpServer::handleGui);
            server.createContext("/hud/set",       PeripheralHttpServer::handleHudSet);
            server.createContext("/hud/update",    PeripheralHttpServer::handleHudUpdate);
            server.createContext("/hud/clear",     PeripheralHttpServer::handleHudClear);
            server.createContext("/hud",           PeripheralHttpServer::handleHudGet);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            PeripheralClient.LOGGER.info("[Peripheral] HTTP server on port {}", PORT);
        } catch (IOException e) {
            PeripheralClient.LOGGER.error("[Peripheral] HTTP server failed: {}", e.getMessage());
        }
    }

    // ── GET /state ────────────────────────────────────────────────────────────
    private static void handleState(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, err("method_not_allowed")); return; }
        send(ex, 200, GSON.toJson(GameStateReader.getState()));
    }

    // ── POST /chat ────────────────────────────────────────────────────────────
    private static void handleChat(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, err("method_not_allowed")); return; }
        JsonObject req = parseBody(ex);
        String message = req.has("message") ? req.get("message").getAsString() : "";
        if (message.isEmpty()) { send(ex, 400, err("empty_message")); return; }
        if (message.startsWith("/")) { send(ex, 403, err("commands_not_allowed")); return; }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) { send(ex, 503, err("not_in_game")); return; }
        client.execute(() -> client.player.networkHandler.sendChatMessage(message));
        JsonObject resp = new JsonObject();
        resp.addProperty("ok", true); resp.addProperty("sent", message);
        send(ex, 200, GSON.toJson(resp));
    }

    // ── GET /inventory ────────────────────────────────────────────────────────
    private static void handleInventory(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, err("method_not_allowed")); return; }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) { send(ex, 503, err("not_in_game")); return; }
        ClientPlayerEntity player = client.player;
        com.google.gson.JsonArray slots = new com.google.gson.JsonArray();
        for (int i = 0; i < player.getInventory().getMainStacks().size(); i++) {
            JsonObject item = GameStateReader.itemStackToJson(player.getInventory().getMainStacks().get(i));
            item.addProperty("slot", i);
            item.addProperty("slot_type", i < 9 ? "hotbar" : "main");
            slots.add(item);
        }
        String[] armourNames = {"feet", "legs", "chest", "head"};
        EquipmentSlot[] armorSlots = {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};
        for (int i = 0; i < armorSlots.length; i++) {
            JsonObject item = GameStateReader.itemStackToJson(player.getEquippedStack(armorSlots[i]));
            item.addProperty("slot", 100 + i); item.addProperty("slot_type", "armor_" + armourNames[i]);
            slots.add(item);
        }
        JsonObject offhand = GameStateReader.itemStackToJson(player.getOffHandStack());
        offhand.addProperty("slot", 40); offhand.addProperty("slot_type", "offhand");
        slots.add(offhand);
        JsonObject resp = new JsonObject();
        resp.add("slots", slots); resp.addProperty("selected_slot", player.getInventory().selectedSlot);
        send(ex, 200, GSON.toJson(resp));
    }

    // ── GET /protection ───────────────────────────────────────────────────────
    private static void handleProtection(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, err("method_not_allowed")); return; }
        send(ex, 200, GSON.toJson(BlockTracker.getProtectionState()));
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
        java.util.List<ChatLog.Entry> entries = since > 0
            ? ChatLog.since(since, limit)
            : ChatLog.recent(limit);
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (ChatLog.Entry e : entries) {
            JsonObject obj = new JsonObject();
            obj.addProperty("time",   e.timeMs());
            obj.addProperty("type",   e.type());
            obj.addProperty("sender", e.sender());
            obj.addProperty("text",   e.text());
            arr.add(obj);
        }
        JsonObject resp = new JsonObject();
        resp.add("messages", arr);
        resp.addProperty("count", arr.size());
        send(ex, 200, GSON.toJson(resp));
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
        JsonObject resp = new JsonObject();
        resp.addProperty("api_key",          PeripheralConfig.apiKey);
        resp.addProperty("agent_url",        PeripheralConfig.agentUrl);
        resp.addProperty("model",            PeripheralConfig.model);
        resp.addProperty("state_port",       PORT);
        resp.addProperty("scripts_dir",      ScriptRunner.SCRIPTS_DIR.toAbsolutePath().toString());
        send(ex, 200, GSON.toJson(resp));
    }

    // ── POST /run_script { "name": "script.py" } ──────────────────────────────
    private static void handleRunScript(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, err("method_not_allowed")); return; }
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
        MinecraftClient client = MinecraftClient.getInstance();
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
        int invSize = client.player.getInventory().getMainStacks().size();
        for (var entry : needed.entrySet()) {
            String keyword = entry.getKey(); int count = entry.getValue(); int found = 0;
            for (int i = 0; i < invSize; i++) {
                String id = client.player.getInventory().getMainStacks().get(i).getItem().toString().replace("minecraft:","").toLowerCase();
                if (id.contains(keyword)) {
                    if (!ingToSlot.containsKey(keyword)) ingToSlot.put(keyword, i);
                    found += client.player.getInventory().getMainStacks().get(i).getCount();
                }
            }
            if (found < count) {
                send(ex, 200, "{\"success\":false,\"error\":\"missing_ingredients\",\"missing\":{\"" + keyword + "\":" + (count - found) + "}}");
                return;
            }
        }

        if (fRecipe.needsTable()) {
            boolean alreadyOpen = client.currentScreen != null;
            if (!alreadyOpen) {
                BlockPos tablePos = findNearby(client, "crafting_table", 6);
                if (tablePos == null) { send(ex, 200, "{\"success\":false,\"error\":\"no_crafting_table_nearby\"}"); return; }
                CountDownLatch openLatch = new CountDownLatch(1);
                final BlockPos fPos = tablePos;
                client.execute(() -> {
                    try {
                        lookAt(client.player, Vec3d.ofCenter(fPos));
                        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND,
                            new BlockHitResult(Vec3d.ofCenter(fPos), Direction.UP, fPos, false));
                    } catch (Exception ignored) { } finally { openLatch.countDown(); }
                });
                try { openLatch.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                sleep(700);
            }
            if (client.currentScreen == null) { send(ex, 200, "{\"success\":false,\"error\":\"crafting_table_did_not_open\"}"); return; }
        }

        java.util.Map<String, java.util.List<Integer>> keywordToGridSlots = new java.util.LinkedHashMap<>();
        for (int[] p : fRecipe.placements())
            keywordToGridSlots.computeIfAbsent(fRecipe.ingredients()[p[1]], k -> new java.util.ArrayList<>()).add(p[0]);

        AtomicBoolean craftOk  = new AtomicBoolean(false);
        AtomicReference<String> craftErr = new AtomicReference<>("");
        CountDownLatch clickLatch = new CountDownLatch(1);
        client.execute(() -> {
            try {
                ScreenHandler sh = client.player.currentScreenHandler;
                if (sh == null) { craftErr.set("no_screen_handler"); return; }
                int syncId = sh.syncId;
                for (var kv : keywordToGridSlots.entrySet()) {
                    int mainIdx = ingToSlot.getOrDefault(kv.getKey(), -1);
                    if (mainIdx < 0) { craftErr.set("ingredient_not_found:" + kv.getKey()); return; }
                    int containerSlot = mainIdxToContainerSlot(mainIdx, fRecipe.needsTable());
                    client.interactionManager.clickSlot(syncId, containerSlot, 0, SlotActionType.PICKUP, client.player);
                    for (int gs : kv.getValue())
                        client.interactionManager.clickSlot(syncId, gs, 1, SlotActionType.PICKUP, client.player);
                    client.interactionManager.clickSlot(syncId, containerSlot, 0, SlotActionType.PICKUP, client.player);
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
                ScreenHandler sh = client.player.currentScreenHandler;
                if (sh != null) { client.interactionManager.clickSlot(sh.syncId, 0, 0, SlotActionType.QUICK_MOVE, client.player); tookResult.set(true); }
            } finally { resultLatch.countDown(); }
        });
        try { resultLatch.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        sleep(300);

        if (fRecipe.needsTable()) {
            CountDownLatch closeLatch = new CountDownLatch(1);
            client.execute(() -> { try { if (client.currentScreen != null) client.player.closeHandledScreen(); } finally { closeLatch.countDown(); } });
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
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) { send(ex, 503, err("not_in_game")); return; }

        JsonObject req  = parseBody(ex);
        String     type = req.has("type") ? req.get("type").getAsString().toLowerCase() : "";

        CountDownLatch latch  = new CountDownLatch(1);
        AtomicBoolean  ok     = new AtomicBoolean(false);
        AtomicReference<String> errMsg = new AtomicReference<>("");

        client.execute(() -> {
            try {
                switch (type) {
                    case "set_slot" -> {
                        int slot = Math.max(0, Math.min(8, req.has("slot") ? req.get("slot").getAsInt() : 0));
                        client.player.getInventory().selectedSlot = slot;
                        client.player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(slot));
                        ok.set(true);
                    }
                    case "look_at" -> {
                        lookAt(client.player, new Vec3d(
                            req.has("x") ? req.get("x").getAsDouble() : 0,
                            req.has("y") ? req.get("y").getAsDouble() : 64,
                            req.has("z") ? req.get("z").getAsDouble() : 0));
                        ok.set(true);
                    }
                    case "use_block", "place_block" -> {
                        BlockPos  bpos = new BlockPos(req.has("x") ? req.get("x").getAsInt() : 0, req.has("y") ? req.get("y").getAsInt() : 64, req.has("z") ? req.get("z").getAsInt() : 0);
                        Direction face = dirFrom(req.has("face") ? req.get("face").getAsString() : "up");
                        Vec3d hit = Vec3d.ofCenter(bpos).add(face.getOffsetX() * .5, face.getOffsetY() * .5, face.getOffsetZ() * .5);
                        lookAt(client.player, Vec3d.ofCenter(bpos));
                        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, new BlockHitResult(hit, face, bpos, false));
                        ok.set(true);
                    }
                    case "start_break" -> {
                        BlockPos bpos = new BlockPos(req.has("x") ? req.get("x").getAsInt() : 0, req.has("y") ? req.get("y").getAsInt() : 64, req.has("z") ? req.get("z").getAsInt() : 0);
                        lookAt(client.player, Vec3d.ofCenter(bpos));
                        client.interactionManager.attackBlock(bpos, Direction.UP);
                        ok.set(true);
                    }
                    case "stop_break" -> ok.set(true);
                    case "click_slot" -> {
                        ScreenHandler sh = client.player.currentScreenHandler;
                        if (sh == null) { errMsg.set("no_screen_open"); break; }
                        SlotActionType sat = switch (req.has("mode") ? req.get("mode").getAsString().toLowerCase() : "") {
                            case "shift" -> SlotActionType.QUICK_MOVE;
                            case "drop"  -> SlotActionType.THROW;
                            case "clone" -> SlotActionType.CLONE;
                            default      -> SlotActionType.PICKUP;
                        };
                        client.interactionManager.clickSlot(sh.syncId, req.has("slot") ? req.get("slot").getAsInt() : 0, req.has("button") ? req.get("button").getAsInt() : 0, sat, client.player);
                        ok.set(true);
                    }
                    case "close_container" -> { if (client.currentScreen != null) client.player.closeHandledScreen(); ok.set(true); }
                    case "drop_item"       -> {
                        boolean dropAll = req.has("all") && req.get("all").getAsBoolean();
                        net.minecraft.item.ItemStack held = client.player.getMainHandStack();
                        if (!held.isEmpty()) {
                            net.minecraft.item.ItemStack toDrop = held.split(dropAll ? held.getCount() : 1);
                            if (!toDrop.isEmpty()) client.player.dropItem(toDrop, false);
                        }
                        ok.set(true);
                    }
                    case "swap_hands" -> {
                        ScreenHandler sh2 = client.player.currentScreenHandler;
                        if (sh2 != null) client.interactionManager.clickSlot(sh2.syncId, 36 + client.player.getInventory().selectedSlot, 40, SlotActionType.SWAP, client.player);
                        ok.set(true);
                    }
                    case "send_chat" -> {
                        String msg = req.has("message") ? req.get("message").getAsString() : "";
                        if (!msg.isEmpty()) { client.player.networkHandler.sendChatMessage(msg); ok.set(true); }
                        else errMsg.set("empty_message");
                    }
                    case "send_command" -> {
                        // Run a command silently — no chat echo, no chat validator.
                        // 1.21.4 API: executeWithPrefix (renamed to parseAndExecute in 1.21.11)
                        String raw = req.has("message") ? req.get("message").getAsString() : "";
                        if (!raw.isEmpty()) {
                            String bare = raw.startsWith("/") ? raw.substring(1) : raw;
                            // Singleplayer: execute directly on the integrated server as the player.
                            // This is completely silent — no chat echo, no packet validator.
                            net.minecraft.server.integrated.IntegratedServer srv = client.getServer();
                            if (srv != null) {
                                String finalBare = bare;
                                srv.execute(() -> {
                                    net.minecraft.server.network.ServerPlayerEntity sp =
                                        srv.getPlayerManager().getPlayer(client.player.getUuid());
                                    if (sp != null) {
                                        srv.getCommandManager().executeWithPrefix(
                                            sp.getCommandSource(), "/" + finalBare);
                                    }
                                });
                            } else {
                                // Multiplayer fallback: CommandExecutionC2SPacket (bare command, no leading /)
                                client.player.networkHandler.sendPacket(
                                    new net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket(bare));
                            }
                            ok.set(true);
                        } else errMsg.set("empty_message");
                    }
                    case "open_gui" -> {
                        // Open the scriptable GUI screen with the given layout.
                        // layout: { title, w, h, widgets: [{type, id, x, y, ...}] }
                        JsonObject layout = req.has("layout") ? req.get("layout").getAsJsonObject() : new JsonObject();
                        ScriptableScreen.openWithLayout(layout);
                        ok.set(true);
                    }
                    case "update_gui" -> {
                        // Update one widget's properties while the screen is open.
                        // id: widget id; props: { key: value, ... }
                        String guiId = req.has("id") ? req.get("id").getAsString() : "";
                        JsonObject props = req.has("props") ? req.get("props").getAsJsonObject() : new JsonObject();
                        if (!guiId.isEmpty()) { ScriptableScreen.update(guiId, props); ok.set(true); }
                        else errMsg.set("missing_id");
                    }
                    case "close_gui" -> {
                        ScriptableScreen.closeScreen();
                        ok.set(true);
                    }
                    case "display_message" -> {
                        String raw = req.has("message") ? req.get("message").getAsString() : "";
                        client.player.sendMessage(buildChatText(raw), false);
                        ok.set(true);
                    }
                    case "send_rich" -> {
                        // Build a Text component directly from our JSON schema using
                        // Gson + the Java API — no codec, no version-specific format.
                        String jsonStr = req.has("json") ? req.get("json").getAsString() : "";
                        if (!jsonStr.isEmpty()) {
                            try {
                                Text richText = buildRichText(JsonParser.parseString(jsonStr));
                                client.player.sendMessage(richText, false);
                                ok.set(true);
                            } catch (Exception e) {
                                errMsg.set("parse_error: " + e.getMessage());
                            }
                        } else {
                            errMsg.set("empty_json");
                        }
                    }
                    case "use_item" -> {
                        // Right-click the held item (eating, drinking potions, using bows, etc.)
                        // For food: call this then wait ~1.6s; the server completes the eating.
                        var result = client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                        ok.set(true);
                    }
                    case "equip_item" -> {
                        // Find item by keyword in inventory; equip it to hand.
                        // Hotbar → switch slot. Main inventory → SWAP with current hotbar slot.
                        String keyword = req.has("item") ? req.get("item").getAsString().toLowerCase().replace("minecraft:", "") : "";
                        if (keyword.isEmpty()) { errMsg.set("no_item_keyword"); break; }
                        var inv = client.player.getInventory();
                        int sel = inv.selectedSlot;
                        var stacks = inv.getMainStacks();
                        // Already in hand?
                        String heldId = stacks.get(sel).getItem().toString().toLowerCase().replace("minecraft:", "");
                        if (heldId.contains(keyword)) { ok.set(true); break; }
                        // Search hotbar (slots 0-8)
                        boolean found = false;
                        for (int hi = 0; hi < 9; hi++) {
                            String id = stacks.get(hi).getItem().toString().toLowerCase().replace("minecraft:", "");
                            if (id.contains(keyword)) {
                                inv.selectedSlot = hi;
                                client.player.networkHandler.sendPacket(
                                    new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(hi));
                                ok.set(true); found = true; break;
                            }
                        }
                        if (found) break;
                        // Search main inventory (slots 9-35) → SWAP with current hotbar slot
                        for (int mi = 9; mi < stacks.size(); mi++) {
                            String id = stacks.get(mi).getItem().toString().toLowerCase().replace("minecraft:", "");
                            if (id.contains(keyword)) {
                                ScreenHandler sh = client.player.currentScreenHandler;
                                // Container slot for main inventory index mi = mi (same index for PlayerScreenHandler)
                                // Button = hotbar target slot (0-8) swaps that hotbar slot with the given container slot
                                client.interactionManager.clickSlot(sh.syncId, mi, sel, SlotActionType.SWAP, client.player);
                                ok.set(true); found = true; break;
                            }
                        }
                        if (!found) { ok.set(false); errMsg.set("item_not_found:" + keyword); }
                    }
                    default -> errMsg.set("unknown_action_type:" + type);
                }
            } catch (Exception e) { errMsg.set(e.getClass().getSimpleName() + ": " + e.getMessage()); }
            finally { latch.countDown(); }
        });

        try { latch.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        send(ex, 200, ok.get() ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"" + errMsg.get() + "\"}");
    }

    // ── GET /nav_scan ─────────────────────────────────────────────────────────
    private static void handleNavScan(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, err("method_not_allowed")); return; }
        int radius = 24;
        String query = ex.getRequestURI().getQuery();
        if (query != null) for (String p : query.split("&"))
            if (p.startsWith("radius=")) try { radius = Math.min(32, Integer.parseInt(p.substring(7))); } catch (NumberFormatException ignored) {}

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) { send(ex, 503, err("not_in_game")); return; }

        BlockPos origin = client.player.getBlockPos();
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        StringBuilder sb = new StringBuilder(65536);
        sb.append("{\"origin\":{\"x\":").append(ox).append(",\"y\":").append(oy).append(",\"z\":").append(oz).append("},\"blocks\":[");
        boolean first = true;
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) for (int y = oy - 4; y <= oy + 8; y++) {
            BlockPos pos = new BlockPos(ox + dx, y, oz + dz);
            BlockState state = client.world.getBlockState(pos);
            if (!state.isAir() && !state.getCollisionShape(client.world, pos).isEmpty()) {
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
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) { send(ex, 503, err("not_in_game")); return; }
            JsonObject req = parseBody(ex);
            PeripheralNavigator.INSTANCE.navigateTo(
                req.has("x") ? req.get("x").getAsInt() : 0,
                req.has("y") ? req.get("y").getAsInt() : (int) client.player.getY(),
                req.has("z") ? req.get("z").getAsInt() : 0,
                req.has("tolerance") ? req.get("tolerance").getAsInt() : 2);
            send(ex, 200, "{\"ok\":true,\"status\":\"planning\"}");
        } catch (Throwable t) { send(ex, 500, "{\"error\":\"" + t.getMessage() + "\"}"); }
    }

    // ── GET /nav_status ───────────────────────────────────────────────────────
    private static void handleNavStatus(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, err("method_not_allowed")); return; }
        PeripheralNavigator nav = PeripheralNavigator.INSTANCE;
        MinecraftClient client  = MinecraftClient.getInstance();
        String posStr = "null";
        if (client.player != null)
            posStr = String.format("{\"x\":%.2f,\"y\":%.2f,\"z\":%.2f}", client.player.getX(), client.player.getY(), client.player.getZ());
        send(ex, 200, "{\"status\":\"" + nav.getNavStatus().name().toLowerCase()
            + "\",\"reason\":\"" + nav.getFailReason().replace("\"","\\\"")
            + "\",\"waypoints_left\":" + nav.getWpLeft()
            + ",\"pos\":" + posStr + "}");
    }

    // ── POST /nav_stop ────────────────────────────────────────────────────────
    private static void handleNavStop(HttpExchange ex) throws IOException {
        try { PeripheralNavigator.INSTANCE.stop(); send(ex, 200, "{\"ok\":true}"); }
        catch (Throwable t) { send(ex, 500, "{\"error\":\"" + t.getMessage() + "\"}"); }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static BlockPos findNearby(MinecraftClient c, String keyword, int radius) {
        BlockPos origin = c.player.getBlockPos();
        for (int dx = -radius; dx <= radius; dx++) for (int dy = -3; dy <= 3; dy++) for (int dz = -radius; dz <= radius; dz++) {
            BlockPos p = origin.add(dx, dy, dz);
            if (c.world.getBlockState(p).getBlock().toString().contains(keyword)) return p;
        }
        return null;
    }

    private static void lookAt(ClientPlayerEntity player, Vec3d target) {
        Vec3d dir = target.subtract(player.getEyePos()).normalize();
        player.setYaw((float) Math.toDegrees(Math.atan2(-dir.x, dir.z)));
        player.setPitch((float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, dir.y)))));
    }

    private static Direction dirFrom(String s) {
        return switch (s.toLowerCase()) {
            case "north" -> Direction.NORTH; case "south" -> Direction.SOUTH;
            case "east"  -> Direction.EAST;  case "west"  -> Direction.WEST;
            case "down"  -> Direction.DOWN;  default      -> Direction.UP;
        };
    }

    // ── GET /gui ─────────────────────────────────────────────────────────────
    // Returns: { open: bool, event: "widget_id" | null, inputs: {id: text} }
    // Calling this pops one button-click event from the queue (if any).
    private static void handleGui(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, err("method_not_allowed")); return; }
        JsonObject resp = new JsonObject();
        resp.addProperty("open", ScriptableScreen.isOpen());
        String event = ScriptableScreen.pollEvent();
        if (event != null) resp.addProperty("event", event);
        JsonObject inputs = new JsonObject();
        ScriptableScreen.getInputValues().forEach(inputs::addProperty);
        resp.add("inputs", inputs);
        send(ex, 200, GSON.toJson(resp));
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ── HUD overlay endpoints ─────────────────────────────────────────────────

    private static void handleHudSet(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, err("method_not_allowed")); return; }
        JsonObject req = parseBody(ex);
        if (!req.has("elements")) { send(ex, 400, err("missing_elements")); return; }
        java.util.List<com.google.gson.JsonObject> list = new java.util.ArrayList<>();
        req.getAsJsonArray("elements").forEach(e -> {
            if (e.isJsonObject()) list.add(e.getAsJsonObject());
        });
        String script = req.has("_script") ? req.get("_script").getAsString() : "";
        PeripheralHud.setElements(list, script);
        send(ex, 200, "{\"ok\":true}");
    }

    private static void handleHudUpdate(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, err("method_not_allowed")); return; }
        JsonObject req = parseBody(ex);
        if (!req.has("id")) { send(ex, 400, err("missing_id")); return; }
        String id = req.get("id").getAsString();
        req.remove("id");
        PeripheralHud.updateElement(id, req);
        send(ex, 200, "{\"ok\":true}");
    }

    private static void handleHudClear(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, err("method_not_allowed")); return; }
        JsonObject req = parseBody(ex);
        String script = req.has("_script") ? req.get("_script").getAsString() : "";
        if (!script.isEmpty()) PeripheralHud.clearIfOwner(script);
        else PeripheralHud.clearElements();
        send(ex, 200, "{\"ok\":true}");
    }

    private static void handleHudGet(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, err("method_not_allowed")); return; }
        JsonObject resp = new JsonObject();
        resp.add("elements", PeripheralHud.getElementsJson());
        send(ex, 200, GSON.toJson(resp));
    }

    private static JsonObject parseBody(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        try { return JsonParser.parseString(body).getAsJsonObject(); }
        catch (Exception e) { return new JsonObject(); }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody(); ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096]; int n;
            while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
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

    private static MutableText buildChatText(String raw) {
        MutableText out = Text.empty();
        Matcher m = CHAT_URL_PAT.matcher(raw);
        int last = 0;
        while (m.find()) {
            if (m.start() > last)
                out.append(Text.literal(raw.substring(last, m.start())));
            String url = m.group();
            out.append(Text.literal(url).styled(s -> s
                .withColor(Formatting.AQUA)
                .withUnderline(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal("Open in browser")))));
            last = m.end();
        }
        if (last < raw.length())
            out.append(Text.literal(raw.substring(last)));
        return out;
    }

    // ── Rich text builder (JSON text component → MutableText) ─────────────────
    // Parses our Python-style JSON schema directly using Gson + the Java API.
    // Handles arrays (sibling list), objects (single component), and the four
    // main clickEvent action types.  Accepts both the old "value" key and the
    // 1.21.5+ renamed keys ("command", "url") for each action.
    private static MutableText buildRichText(com.google.gson.JsonElement element) {
        if (element == null || element.isJsonNull()) return Text.empty();

        // Array → concatenate siblings
        if (element.isJsonArray()) {
            MutableText out = Text.empty();
            for (com.google.gson.JsonElement child : element.getAsJsonArray())
                out.append(buildRichText(child));
            return out;
        }

        // Primitive string → plain text
        if (element.isJsonPrimitive()) return Text.literal(element.getAsString());

        // Object → full component
        com.google.gson.JsonObject obj = element.getAsJsonObject();
        String text = obj.has("text") ? obj.get("text").getAsString() : "";
        MutableText comp = Text.literal(text);

        net.minecraft.text.Style style = net.minecraft.text.Style.EMPTY;

        // Color
        if (obj.has("color")) {
            Formatting fmt = Formatting.byName(obj.get("color").getAsString());
            if (fmt != null) style = style.withColor(fmt);
        }
        // Decorations
        if (obj.has("bold")        && obj.get("bold").getAsBoolean())        style = style.withBold(true);
        if (obj.has("italic")      && obj.get("italic").getAsBoolean())      style = style.withItalic(true);
        if (obj.has("underlined")  && obj.get("underlined").getAsBoolean())  style = style.withUnderline(true);
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
                    case "open_url"          -> style.withClickEvent(new ClickEvent.OpenUrl(URI.create(url)));
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
                    style = style.withHoverEvent(new HoverEvent.ShowText(buildRichText(contents)));
            }
        }

        comp.setStyle(style);

        // Extra (child components appended inline)
        if (obj.has("extra") && obj.get("extra").isJsonArray())
            for (com.google.gson.JsonElement child : obj.get("extra").getAsJsonArray())
                comp.append(buildRichText(child));

        return comp;
    }
}
