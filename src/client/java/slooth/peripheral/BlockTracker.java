package slooth.peripheral;

import com.google.gson.*;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks blocks placed by the player so scripts can avoid breaking them.
 * Data is saved to config/peripheral_blocks.json.
 */
public class BlockTracker {

    private static final Map<String, String> placedBlocks    = new ConcurrentHashMap<>();
    private static final List<JsonObject>    unrestrictedZones = Collections.synchronizedList(new ArrayList<>());
    private static final Path  SAVE_PATH = Paths.get("config", "peripheral_blocks.json");
    private static final Gson  GSON      = new GsonBuilder().setPrettyPrinting().create();

    public static void register() {
        load();

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world instanceof net.minecraft.client.multiplayer.ClientLevel) {
                ItemStack held = player.getItemInHand(hand);
                if (held.getItem() instanceof BlockItem) {
                    BlockPos placed = hitResult.getBlockPos().relative(hitResult.getDirection());
                    placedBlocks.put(blockKey(placed, world), player.getName().getString());
                    saveAsync();
                }
            }
            return InteractionResult.PASS;
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, entity) -> {
            placedBlocks.remove(blockKey(pos, world));
            saveAsync();
        });

        PeripheralClient.LOGGER.info("[Peripheral] BlockTracker ready. {} blocks loaded.", placedBlocks.size());
    }

    public static JsonObject getProtectionState() {
        JsonObject root   = new JsonObject();
        JsonObject placed = new JsonObject();
        placedBlocks.forEach(placed::addProperty);
        root.add("placed", placed);
        JsonArray zones = new JsonArray();
        synchronized (unrestrictedZones) { unrestrictedZones.forEach(zones::add); }
        root.add("zones", zones);
        return root;
    }

    public static boolean canBreak(BlockPos pos, Level world) {
        String dim = world.dimension().identifier().toString();
        synchronized (unrestrictedZones) {
            for (JsonObject zone : unrestrictedZones) {
                if (!zone.get("dim").getAsString().equals(dim)) continue;
                int x1 = Math.min(zone.get("x1").getAsInt(), zone.get("x2").getAsInt());
                int x2 = Math.max(zone.get("x1").getAsInt(), zone.get("x2").getAsInt());
                int y1 = Math.min(zone.get("y1").getAsInt(), zone.get("y2").getAsInt());
                int y2 = Math.max(zone.get("y1").getAsInt(), zone.get("y2").getAsInt());
                int z1 = Math.min(zone.get("z1").getAsInt(), zone.get("z2").getAsInt());
                int z2 = Math.max(zone.get("z1").getAsInt(), zone.get("z2").getAsInt());
                if (pos.getX() >= x1 && pos.getX() <= x2
                    && pos.getY() >= y1 && pos.getY() <= y2
                    && pos.getZ() >= z1 && pos.getZ() <= z2) return true;
            }
        }
        return !placedBlocks.containsKey(blockKey(pos, world));
    }

    public static void addZone(String name, BlockPos c1, BlockPos c2, String dim) {
        JsonObject z = new JsonObject();
        z.addProperty("name", name);
        z.addProperty("x1", c1.getX()); z.addProperty("y1", c1.getY()); z.addProperty("z1", c1.getZ());
        z.addProperty("x2", c2.getX()); z.addProperty("y2", c2.getY()); z.addProperty("z2", c2.getZ());
        z.addProperty("dim", dim);
        synchronized (unrestrictedZones) { unrestrictedZones.add(z); }
        saveAsync();
    }

    public static void removeZone(String name) {
        synchronized (unrestrictedZones) {
            unrestrictedZones.removeIf(z -> z.get("name").getAsString().equals(name));
        }
        saveAsync();
    }

    private static void load() {
        if (!Files.exists(SAVE_PATH)) return;
        try (Reader r = Files.newBufferedReader(SAVE_PATH, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            if (root.has("placed")) {
                for (var e : root.getAsJsonObject("placed").entrySet())
                    placedBlocks.put(e.getKey(), e.getValue().getAsString());
            }
            if (root.has("zones")) {
                JsonArray zones = root.getAsJsonArray("zones");
                synchronized (unrestrictedZones) {
                    for (JsonElement el : zones) unrestrictedZones.add(el.getAsJsonObject());
                }
            }
        } catch (Exception e) {
            PeripheralClient.LOGGER.warn("[Peripheral] BlockTracker load failed: {}", e.getMessage());
        }
    }

    private static void saveAsync() {
        Thread t = new Thread(BlockTracker::save, "peripheral-block-save");
        t.setDaemon(true);
        t.start();
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(SAVE_PATH.getParent());
            JsonObject root   = new JsonObject();
            JsonObject placed = new JsonObject();
            placedBlocks.forEach(placed::addProperty);
            root.add("placed", placed);
            JsonArray zones = new JsonArray();
            synchronized (unrestrictedZones) { unrestrictedZones.forEach(zones::add); }
            root.add("zones", zones);
            try (Writer w = Files.newBufferedWriter(SAVE_PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
        } catch (Exception e) {
            PeripheralClient.LOGGER.warn("[Peripheral] BlockTracker save failed: {}", e.getMessage());
        }
    }

    private static String blockKey(BlockPos pos, Level world) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ()
            + "," + world.dimension().identifier().toString();
    }
}
