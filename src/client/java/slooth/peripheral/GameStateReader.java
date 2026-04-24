package slooth.peripheral;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

public class GameStateReader {

    public static JsonObject getState() {
        Minecraft client = Minecraft.getInstance();
        JsonObject state = new JsonObject();

        if (client.player == null || client.level == null) {
            state.addProperty("error", "not_in_game");
            return state;
        }

        LocalPlayer player = client.player;
        Level world = client.level;

        // ── Health & stats ─────────────────────────────────────────────────
        state.addProperty("health",      player.getHealth());
        state.addProperty("max_health",  player.getMaxHealth());
        state.addProperty("hunger",      player.getFoodData().getFoodLevel());
        state.addProperty("is_dead",     !player.isAlive());
        state.addProperty("xp_level",   player.experienceLevel);

        // ── Look direction ─────────────────────────────────────────────────
        state.addProperty("yaw",   player.getYRot());
        state.addProperty("pitch", player.getXRot());

        // ── Position ───────────────────────────────────────────────────────
        BlockPos pos = player.blockPosition();
        JsonObject posObj = new JsonObject();
        posObj.addProperty("x", pos.getX());
        posObj.addProperty("y", pos.getY());
        posObj.addProperty("z", pos.getZ());
        state.add("position", posObj);

        // ── Window / GUI info ──────────────────────────────────────────────
        state.addProperty("window_x",            client.getWindow().getX());
        state.addProperty("window_y",            client.getWindow().getY());
        state.addProperty("window_width",        client.getWindow().getScreenWidth());
        state.addProperty("window_height",       client.getWindow().getScreenHeight());
        state.addProperty("window_scaled_width",  client.getWindow().getGuiScaledWidth());
        state.addProperty("window_scaled_height", client.getWindow().getGuiScaledHeight());
        state.addProperty("gui_scale",           (int) client.getWindow().getGuiScale());
        // mouse_sensitivity removed — GameOptions.mouseSensitivity is private in 1.21.11

        // ── Current open screen ────────────────────────────────────────────
        state.addProperty("current_screen",
            client.screen != null ? client.screen.getClass().getSimpleName() : "none");

        // ── World info ─────────────────────────────────────────────────────
        state.addProperty("dimension", world.dimension().identifier().toString());
        long timeOfDay = world.getOverworldClockTime() % 24000;
        state.addProperty("time_ticks",   timeOfDay);
        state.addProperty("is_day",       timeOfDay < 13000);
        state.addProperty("is_raining",   world.isRaining());
        state.addProperty("is_thundering", world.isThundering());
        if (client.gameMode != null)
            state.addProperty("gamemode", client.gameMode.getPlayerMode().getSerializedName());

        // ── Movement state ─────────────────────────────────────────────────
        state.addProperty("on_ground", player.onGround());
        state.addProperty("in_water",  player.isInWater());
        state.addProperty("in_lava",   player.isInLava());

        // ── Status effects ─────────────────────────────────────────────────
        JsonArray effects = new JsonArray();
        for (MobEffectInstance eff : player.getActiveEffects()) {
            JsonObject e = new JsonObject();
            net.minecraft.resources.Identifier effId =
                BuiltInRegistries.MOB_EFFECT.getKey(eff.getEffect().value());
            e.addProperty("id",             effId != null ? effId.toString() : "unknown");
            e.addProperty("duration_ticks", eff.getDuration());
            e.addProperty("amplifier",      eff.getAmplifier());
            effects.add(e);
        }
        state.add("effects", effects);

        // ── Armor & offhand ────────────────────────────────────────────────
        JsonObject armor = new JsonObject();
        armor.add("head",  itemStackToJson(player.getItemBySlot(EquipmentSlot.HEAD)));
        armor.add("chest", itemStackToJson(player.getItemBySlot(EquipmentSlot.CHEST)));
        armor.add("legs",  itemStackToJson(player.getItemBySlot(EquipmentSlot.LEGS)));
        armor.add("feet",  itemStackToJson(player.getItemBySlot(EquipmentSlot.FEET)));
        state.add("armor",   armor);
        state.add("offhand", itemStackToJson(player.getOffhandItem()));

        // ── Equipped item ──────────────────────────────────────────────────
        state.add("equipped", itemStackToJson(player.getMainHandItem()));

        // ── Inventory ──────────────────────────────────────────────────────
        JsonArray inventory = new JsonArray();
        for (int i = 0; i < player.getInventory().getNonEquipmentItems().size(); i++) {
            ItemStack stack = player.getInventory().getNonEquipmentItems().get(i);
            if (!stack.isEmpty()) {
                JsonObject item = itemStackToJson(stack);
                item.addProperty("slot", i);
                inventory.add(item);
            }
        }
        state.add("inventory", inventory);

        // ── Nearby entities (16-block radius) ─────────────────────────────
        AABB searchBox = player.getBoundingBox().inflate(16, 8, 16);
        List<LivingEntity> nearby = world.getEntitiesOfClass(LivingEntity.class, searchBox, e -> e != player);
        JsonArray entitiesArr = new JsonArray();
        for (LivingEntity entity : nearby) {
            JsonObject ent = new JsonObject();
            ent.addProperty("type",     entity.getType().toString());
            ent.addProperty("distance", Math.round(player.distanceTo(entity) * 10.0) / 10.0);
            ent.addProperty("hostile",  entity instanceof Monster);
            ent.addProperty("health",   entity.getHealth());
            ent.addProperty("x", entity.getBlockX());
            ent.addProperty("y", entity.getBlockY());
            ent.addProperty("z", entity.getBlockZ());
            if (entity instanceof TamableAnimal t) {
                ent.addProperty("tamed", t.isTame());
                if (t.isTame() && t.getOwner() != null)
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
        BlockPos playerPos = player.blockPosition();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -6; dz <= 6; dz++) {
                    BlockPos check = playerPos.offset(dx, dy, dz);
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
            if (stack.isDamageableItem()) {
                int maxDmg = stack.getMaxDamage();
                int dmg    = stack.getDamageValue();
                float pct  = maxDmg > 0 ? (1.0f - (float) dmg / maxDmg) * 100f : 100f;
                obj.addProperty("durability_pct", Math.round(pct));
                obj.addProperty("max_damage",     maxDmg);
                obj.addProperty("damage",         dmg);
            }
        }
        return obj;
    }
}
