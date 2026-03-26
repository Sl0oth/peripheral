package slooth.peripheral;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

import java.util.List;

public class GameStateReader {

    public static JsonObject getState() {
        MinecraftClient client = MinecraftClient.getInstance();
        JsonObject state = new JsonObject();

        if (client.player == null || client.world == null) {
            state.addProperty("error", "not_in_game");
            return state;
        }

        ClientPlayerEntity player = client.player;
        World world = client.world;

        // ── Health & stats ─────────────────────────────────────────────────
        state.addProperty("health",      player.getHealth());
        state.addProperty("max_health",  player.getMaxHealth());
        state.addProperty("hunger",      player.getHungerManager().getFoodLevel());
        state.addProperty("is_dead",     !player.isAlive());
        state.addProperty("xp_level",   player.experienceLevel);

        // ── Look direction ─────────────────────────────────────────────────
        state.addProperty("yaw",   player.getYaw());
        state.addProperty("pitch", player.getPitch());

        // ── Position ───────────────────────────────────────────────────────
        BlockPos pos = player.getBlockPos();
        JsonObject posObj = new JsonObject();
        posObj.addProperty("x", pos.getX());
        posObj.addProperty("y", pos.getY());
        posObj.addProperty("z", pos.getZ());
        state.add("position", posObj);

        // ── Window / GUI info ──────────────────────────────────────────────
        state.addProperty("window_x",            client.getWindow().getX());
        state.addProperty("window_y",            client.getWindow().getY());
        state.addProperty("window_width",        client.getWindow().getWidth());
        state.addProperty("window_height",       client.getWindow().getHeight());
        state.addProperty("window_scaled_width",  client.getWindow().getScaledWidth());
        state.addProperty("window_scaled_height", client.getWindow().getScaledHeight());
        state.addProperty("gui_scale",           (int) client.getWindow().getScaleFactor());
        // mouse_sensitivity removed — GameOptions.mouseSensitivity is private in 1.21.11

        // ── Current open screen ────────────────────────────────────────────
        state.addProperty("current_screen",
            client.currentScreen != null ? client.currentScreen.getClass().getSimpleName() : "none");

        // ── World info ─────────────────────────────────────────────────────
        state.addProperty("dimension", world.getRegistryKey().getValue().toString());
        long timeOfDay = world.getTimeOfDay() % 24000;
        state.addProperty("time_ticks",   timeOfDay);
        state.addProperty("is_day",       timeOfDay < 13000);
        state.addProperty("is_raining",   world.isRaining());
        state.addProperty("is_thundering", world.isThundering());
        if (client.interactionManager != null)
            state.addProperty("gamemode", client.interactionManager.getCurrentGameMode().asString());

        // ── Equipped item ──────────────────────────────────────────────────
        state.add("equipped", itemStackToJson(player.getMainHandStack()));

        // ── Inventory ──────────────────────────────────────────────────────
        JsonArray inventory = new JsonArray();
        for (int i = 0; i < player.getInventory().getMainStacks().size(); i++) {
            ItemStack stack = player.getInventory().getMainStacks().get(i);
            if (!stack.isEmpty()) {
                JsonObject item = itemStackToJson(stack);
                item.addProperty("slot", i);
                inventory.add(item);
            }
        }
        state.add("inventory", inventory);

        // ── Nearby entities (16-block radius) ─────────────────────────────
        Box searchBox = player.getBoundingBox().expand(16, 8, 16);
        List<LivingEntity> nearby = world.getEntitiesByClass(LivingEntity.class, searchBox, e -> e != player);
        JsonArray entitiesArr = new JsonArray();
        for (LivingEntity entity : nearby) {
            JsonObject ent = new JsonObject();
            ent.addProperty("type",     entity.getType().toString());
            ent.addProperty("distance", Math.round(player.distanceTo(entity) * 10.0) / 10.0);
            ent.addProperty("hostile",  entity instanceof HostileEntity);
            ent.addProperty("health",   entity.getHealth());
            ent.addProperty("x", entity.getBlockX());
            ent.addProperty("y", entity.getBlockY());
            ent.addProperty("z", entity.getBlockZ());
            if (entity instanceof TameableEntity t) {
                ent.addProperty("tamed", t.isTamed());
                if (t.isTamed() && t.getOwner() != null)
                    ent.addProperty("owner", t.getOwner().getName().getString());
            }
            if (entity.hasCustomName()) {
                ent.addProperty("custom_name", entity.getCustomName().getString());
                ent.addProperty("named_mob", true);
            }
            if (entity.isBaby()) ent.addProperty("baby", true);
            entitiesArr.add(ent);
        }
        state.add("nearby_entities", entitiesArr);

        // ── Nearby blocks of interest (6-block radius) ─────────────────────
        JsonArray nearbyBlocks = new JsonArray();
        BlockPos playerPos = player.getBlockPos();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -6; dz <= 6; dz++) {
                    BlockPos check = playerPos.add(dx, dy, dz);
                    String bn = world.getBlockState(check).getBlock().toString();
                    if (isInteresting(bn)) {
                        JsonObject block = new JsonObject();
                        block.addProperty("type", bn);
                        block.addProperty("x", check.getX());
                        block.addProperty("y", check.getY());
                        block.addProperty("z", check.getZ());
                        block.addProperty("lava", bn.contains("lava"));
                        nearbyBlocks.add(block);
                    }
                }
            }
        }
        state.add("nearby_blocks", nearbyBlocks);

        return state;
    }

    private static boolean isInteresting(String bn) {
        return bn.contains("lava") || bn.contains("_ore") || bn.contains("chest")
            || bn.contains("furnace") || bn.contains("crafting_table") || bn.contains("anvil")
            || bn.contains("enchanting") || bn.contains("wheat") || bn.contains("carrot")
            || bn.contains("potato") || bn.contains("beetroot") || bn.contains("farmland");
    }

    public static JsonObject itemStackToJson(ItemStack stack) {
        JsonObject obj = new JsonObject();
        if (stack.isEmpty()) {
            obj.addProperty("item",  "empty");
            obj.addProperty("count", 0);
        } else {
            obj.addProperty("item",  stack.getItem().toString());
            obj.addProperty("count", stack.getCount());
            if (stack.isDamageable()) {
                int maxDmg = stack.getMaxDamage();
                int dmg    = stack.getDamage();
                float pct  = maxDmg > 0 ? (1.0f - (float) dmg / maxDmg) * 100f : 100f;
                obj.addProperty("durability_pct", Math.round(pct));
                obj.addProperty("max_damage",     maxDmg);
                obj.addProperty("damage",         dmg);
            }
        }
        return obj;
    }
}
