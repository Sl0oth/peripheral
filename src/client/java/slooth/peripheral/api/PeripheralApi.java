package slooth.peripheral.api;

import slooth.peripheral.NavigatorInput;
import slooth.peripheral.PeripheralNavigator;
import slooth.peripheral.hud.PeripheralHud;
import slooth.peripheral.overlay.PeripheralOverlayRenderer;
import slooth.peripheral.script.ScriptRunner;
import slooth.peripheral.ui.ScriptableScreen;
import slooth.peripheral.util.BlockTracker;
import slooth.peripheral.util.ChatLog;
import slooth.peripheral.util.GameStateReader;
import slooth.peripheral.util.PeripheralConfig;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Transport-agnostic dispatch for Peripheral's scriptable capabilities.
 *
 * <p>{@link PeripheralHttpServer} and {@link PeripheralSocketServer} both route
 * requests through {@link #handle(String, JsonObject)}.  Method names mirror the
 * HTTP endpoint paths ({@code state}, {@code action}, {@code inventory},
 * {@code gui}, {@code goto}, …).  Game-thread work uses
 * {@code Minecraft.getInstance().execute(...)} + {@link CountDownLatch}, matching
 * the pattern used throughout the HTTP server.
 */
public final class PeripheralApi {

    private PeripheralApi() {}

    /** Thrown when a method cannot run; carries a short error token. */
    public static final class ApiException extends RuntimeException {
        public ApiException(String message) { super(message); }
    }

    /** Dispatch a method by name. Returns the result JSON, or throws {@link ApiException}. */
    public static JsonObject handle(String method, JsonObject params) {
        if (params == null) params = new JsonObject();
        params = LocalApiSecurity.owned(params);
        return switch (method) {
            case "state"         -> state();
            case "inventory"     -> inventory();
            case "gui"           -> gui();
            case "gui_peek"      -> guiSnapshot(false);
            case "block"         -> block(params);
            case "target"        -> target();
            case "chat_log"      -> chatLog(params);
            case "action"        -> action(params);
            case "goto"          -> gotoAction(params);
            case "nav_status"    -> navStatus();
            case "nav_stop"      -> navStop();
            case "hud_set"       -> hudSet(params);
            case "hud_update"    -> hudUpdate(params);
            case "hud_clear"     -> hudClear(params);
            case "hud"           -> hudGet();
            case "overlay_set"   -> overlaySet(params);
            case "overlay_clear" -> overlayClear(params);
            case "protection"    -> protection();
            case "config"        -> config();
            case "find_blocks"   -> findBlocks(params);
            case "notify"        -> notifyToast(params);
            case "play_sound"    -> playSound(params);
            case "ping"          -> ok();
            case "recorder"      -> recorder();
            case "recordings"    -> recordings();
            case "record_start"  -> recordStart(params);
            case "record_stop"   -> onClient(() -> { var rec = slooth.peripheral.record.Recorder.stopRecording(); if (rec == null) throw new ApiException("nothing_recorded"); return recorder(); });
            case "play_recording"-> playRecording(params);
            case "screen"        -> onClient(slooth.peripheral.control.GuiControl::describe);
            case "packets"       -> packets(params);
            case "packet_log"    -> packetLog(params);
            case "packet_block"  -> packetBlock(params);
            case "packet_hold"   -> packetHold(params);
            case "packet_send"   -> packetSend(params);
            case "click"         -> uiClick(params);
            case "click_button"  -> uiClickButton(params);
            case "click_slot"    -> uiClickSlot(params);
            case "mouse_move"    -> uiMove(params);
            case "mouse_button"  -> uiMouseButton(params);
            case "scroll"        -> uiScroll(params);
            case "key"           -> uiKey(params);
            case "type_text"     -> uiType(params);
            case "stop_playback" -> onClient(() -> { slooth.peripheral.record.Recorder.stopPlayback("Stopped by a script."); return recorder(); });
            default              -> throw new ApiException("unknown_method:" + method);
        };
    }

    private static JsonObject ok() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    // ── state ─────────────────────────────────────────────────────────────────
    static JsonObject state() {
        return GameStateReader.getState();
    }

    // ── inventory ───────────────────────────────────────────────────────────────
    static JsonObject inventory() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) throw new ApiException("not_in_game");
        LocalPlayer player = client.player;
        com.google.gson.JsonArray slots = new com.google.gson.JsonArray();
        for (int i = 0; i < player.getInventory().getNonEquipmentItems().size(); i++) {
            JsonObject item = GameStateReader.itemStackToJson(player.getInventory().getNonEquipmentItems().get(i));
            item.addProperty("slot", i);
            item.addProperty("slot_type", i < 9 ? "hotbar" : "main");
            slots.add(item);
        }
        String[] armourNames = {"feet", "legs", "chest", "head"};
        EquipmentSlot[] armorSlots = {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};
        for (int i = 0; i < armorSlots.length; i++) {
            JsonObject item = GameStateReader.itemStackToJson(player.getItemBySlot(armorSlots[i]));
            item.addProperty("slot", 100 + i); item.addProperty("slot_type", "armor_" + armourNames[i]);
            slots.add(item);
        }
        JsonObject offhand = GameStateReader.itemStackToJson(player.getOffhandItem());
        offhand.addProperty("slot", 40); offhand.addProperty("slot_type", "offhand");
        slots.add(offhand);
        JsonObject resp = new JsonObject();
        resp.add("slots", slots); resp.addProperty("selected_slot", player.getInventory().getSelectedSlot());
        return resp;
    }

    // ── gui (pops one event per call) ────────────────────────────────────────────
    static JsonObject gui() {
        return guiSnapshot(true);
    }

    static JsonObject guiSnapshot(boolean consumeEvent) {
        JsonObject resp = new JsonObject();
        resp.addProperty("open", ScriptableScreen.isOpen() && ScriptableScreen.ownedBy(LocalApiSecurity.owner()));
        String event = consumeEvent && ScriptableScreen.ownedBy(LocalApiSecurity.owner()) ? ScriptableScreen.pollEvent() : null;
        if (event != null) resp.addProperty("event", event);
        JsonObject inputs = new JsonObject();
        if (ScriptableScreen.ownedBy(LocalApiSecurity.owner())) ScriptableScreen.getInputValues().forEach(inputs::addProperty);
        resp.add("inputs", inputs);
        return resp;
    }

    // ── block ────────────────────────────────────────────────────────────────────
    static JsonObject block(JsonObject params) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) throw new ApiException("not_in_game");
        int x = params.has("x") ? params.get("x").getAsInt() : 0;
        int y = params.has("y") ? params.get("y").getAsInt() : 64;
        int z = params.has("z") ? params.get("z").getAsInt() : 0;
        BlockPos pos = new BlockPos(x, y, z);
        BlockState bs = client.level.getBlockState(pos);
        JsonObject resp = new JsonObject();
        resp.addProperty("x", x);
        resp.addProperty("y", y);
        resp.addProperty("z", z);
        resp.addProperty("block", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bs.getBlock()).toString());
        resp.addProperty("is_air", bs.isAir());
        return resp;
    }

    // ── target ───────────────────────────────────────────────────────────────────
    static JsonObject target() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) throw new ApiException("not_in_game");
        JsonObject resp = new JsonObject();
        var hit = client.hitResult;
        if (hit instanceof net.minecraft.world.phys.BlockHitResult bhr) {
            BlockPos bp = bhr.getBlockPos();
            resp.addProperty("type",  "block");
            resp.addProperty("x",     bp.getX());
            resp.addProperty("y",     bp.getY());
            resp.addProperty("z",     bp.getZ());
            resp.addProperty("face",  bhr.getDirection().getName());
            if (client.level != null)
                resp.addProperty("block", client.level.getBlockState(bp).getBlock().toString());
        } else if (hit instanceof net.minecraft.world.phys.EntityHitResult ehr) {
            var entity = ehr.getEntity();
            resp.addProperty("type",        "entity");
            resp.addProperty("entity_type", entity.getType().toString());
            resp.addProperty("x",           entity.getBlockX());
            resp.addProperty("y",           entity.getBlockY());
            resp.addProperty("z",           entity.getBlockZ());
            resp.addProperty("distance",    Math.round(client.player.distanceTo(entity) * 10.0) / 10.0);
            if (entity instanceof net.minecraft.world.entity.LivingEntity le)
                resp.addProperty("health", le.getHealth());
            if (entity.hasCustomName())
                resp.addProperty("custom_name", entity.getCustomName().getString());
        } else {
            resp.addProperty("type", "miss");
        }
        return resp;
    }

    // ── chat_log ─────────────────────────────────────────────────────────────────
    static JsonObject chatLog(JsonObject params) {
        long since = params.has("since") ? params.get("since").getAsLong() : 0;
        int  limit = Math.min(200, params.has("limit") ? params.get("limit").getAsInt() : 20);
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
        return resp;
    }

    // ── goto ─────────────────────────────────────────────────────────────────────
    static JsonObject gotoAction(JsonObject req) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) throw new ApiException("not_in_game");
        String owner=LocalApiSecurity.owner(); long generation=ScriptRunner.worldGeneration;
        int x=req.has("x")?req.get("x").getAsInt():0, y=req.has("y")?req.get("y").getAsInt():(int)client.player.getY(), z=req.has("z")?req.get("z").getAsInt():0;
        int tolerance=Math.clamp(req.has("tolerance")?req.get("tolerance").getAsInt():2,1,16);
        client.execute(() -> { if(LocalApiSecurity.active(owner) && generation==ScriptRunner.worldGeneration && client.player!=null) slooth.peripheral.script.ScriptActions.navigate(owner,x,y,z,tolerance); });
        JsonObject resp = new JsonObject();
        resp.addProperty("ok", true);
        resp.addProperty("status", "planning");
        return resp;
    }

    // ── nav_status ───────────────────────────────────────────────────────────────
    // ── Recorder ──────────────────────────────────────────────────────────────

    static JsonObject recorder() {
        JsonObject r = new JsonObject();
        r.addProperty("state", slooth.peripheral.record.Recorder.state().name().toLowerCase());
        r.addProperty("message", slooth.peripheral.record.Recorder.message());
        if (slooth.peripheral.record.Recorder.recording()) {
            r.addProperty("name", slooth.peripheral.record.Recorder.recordingName());
            r.addProperty("ticks", slooth.peripheral.record.Recorder.recordedTicks());
        }
        if (slooth.peripheral.record.Recorder.playing()) {
            r.addProperty("name", slooth.peripheral.record.Recorder.playingName());
            r.addProperty("tick", slooth.peripheral.record.Recorder.playIndex());
            r.addProperty("ticks", slooth.peripheral.record.Recorder.playLength());
            r.addProperty("loop", slooth.peripheral.record.Recorder.loop() + 1);
            r.addProperty("loops", slooth.peripheral.record.Recorder.loops());
            r.addProperty("returning", slooth.peripheral.record.Recorder.returning());
        }
        r.addProperty("corrections", slooth.peripheral.record.Recorder.corrections());
        r.addProperty("worst_error", slooth.peripheral.record.Recorder.worstError());
        return r;
    }

    static JsonObject recordings() {
        JsonObject r = new JsonObject();
        com.google.gson.JsonArray list = new com.google.gson.JsonArray();
        slooth.peripheral.record.Recording.names().forEach(list::add);
        r.add("recordings", list);
        return r;
    }

    private static JsonObject started(String error) {
        if (error != null) throw new ApiException(error);
        return recorder();
    }

    static JsonObject recordStart(JsonObject p) {
        String name = str(p, "name", "");
        boolean resume = p.has("resume") && p.get("resume").getAsBoolean();
        return onClient(() -> started(slooth.peripheral.record.Recorder.startRecording(
            name.isBlank() ? slooth.peripheral.record.Recording.nextName() : name, resume)));
    }

    static JsonObject playRecording(JsonObject p) {
        String owner = LocalApiSecurity.owner();
        String name = str(p, "name", "");
        if (name.isBlank()) throw new ApiException("name_required");
        int loops = p.has("loops") ? Math.max(0, p.get("loops").getAsInt()) : 1;
        var mode = p.has("relative") && p.get("relative").getAsBoolean()
            ? slooth.peripheral.record.Recorder.Mode.RELATIVE : slooth.peripheral.record.Recorder.Mode.WORLD;
        return onClient(() -> {
            String err = slooth.peripheral.record.Recorder.play(name, loops, mode);
            if (err != null) throw new ApiException(err);
            slooth.peripheral.record.Recorder.setOwner(owner);
            return recorder();
        });
    }

    // ── Packets ───────────────────────────────────────────────────────────────

    private static slooth.peripheral.net.PacketTools.Direction direction(JsonObject p) {
        return str(p, "direction", "sent").startsWith("r") ? slooth.peripheral.net.PacketTools.Direction.RECEIVED
            : slooth.peripheral.net.PacketTools.Direction.SENT;
    }

    static JsonObject packets(JsonObject p) {
        int limit = p.has("limit") ? Math.clamp(p.get("limit").getAsInt(), 1, 2000) : 100;
        String only = str(p, "direction", "");
        String name = str(p, "name", "").toLowerCase(java.util.Locale.ROOT);
        long since = p.has("since") ? p.get("since").getAsLong() : 0;
        com.google.gson.JsonArray list = new com.google.gson.JsonArray();
        for (var e : slooth.peripheral.net.PacketTools.recent(4000)) {
            if (e.timeMs() <= since) continue;
            if (!only.isEmpty() && !e.direction().name().toLowerCase().startsWith(only.substring(0, 1))) continue;
            if (!name.isEmpty() && !e.name().contains(name)) continue;
            JsonObject o = new JsonObject();
            o.addProperty("time", e.timeMs());
            o.addProperty("direction", e.direction().name().toLowerCase());
            o.addProperty("name", e.name());
            o.addProperty("summary", e.summary());
            o.addProperty("blocked", e.blocked());
            o.addProperty("held", e.held());
            if (!e.data().isEmpty()) o.addProperty("data", e.data());
            list.add(o);
        }
        while (list.size() > limit) list.remove(0);
        JsonObject r = new JsonObject();
        r.add("packets", list);
        r.addProperty("logging", slooth.peripheral.net.PacketTools.logging());
        r.addProperty("allowed_here", slooth.peripheral.net.PacketTools.allowed());
        r.addProperty("holding", slooth.peripheral.net.PacketTools.holding());
        r.addProperty("held", slooth.peripheral.net.PacketTools.heldCount());
        com.google.gson.JsonArray sent = new com.google.gson.JsonArray(), received = new com.google.gson.JsonArray();
        slooth.peripheral.net.PacketTools.blockedNames(slooth.peripheral.net.PacketTools.Direction.SENT).forEach(sent::add);
        slooth.peripheral.net.PacketTools.blockedNames(slooth.peripheral.net.PacketTools.Direction.RECEIVED).forEach(received::add);
        r.add("blocked_sent", sent);
        r.add("blocked_received", received);
        return r;
    }

    static JsonObject packetLog(JsonObject p) {
        if (p.has("clear") && p.get("clear").getAsBoolean()) slooth.peripheral.net.PacketTools.clearLog();
        if (p.has("on")) slooth.peripheral.net.PacketTools.setLogging(p.get("on").getAsBoolean());
        return packets(new JsonObject());
    }

    static JsonObject packetBlock(JsonObject p) {
        String name = str(p, "name", "");
        if (name.isBlank()) throw new ApiException("name_required");
        var dir = direction(p);
        boolean on = !p.has("on") || p.get("on").getAsBoolean();
        if (on && !slooth.peripheral.net.PacketTools.allowed()) throw new ApiException("not_allowed_here: " + slooth.peripheral.net.PacketTools.NOT_ALLOWED);
        if (on && !slooth.peripheral.net.PacketTools.block(dir, name, LocalApiSecurity.owner()))
            throw new ApiException("cannot_block:" + name + " keeps the connection alive");
        if (!on) slooth.peripheral.net.PacketTools.unblock(dir, name);
        return packets(new JsonObject());
    }

    static JsonObject packetHold(JsonObject p) {
        String owner = LocalApiSecurity.owner();
        boolean on = !p.has("on") || p.get("on").getAsBoolean();
        boolean send = !p.has("send") || p.get("send").getAsBoolean();
        if (on && !slooth.peripheral.net.PacketTools.allowed()) throw new ApiException("not_allowed_here: " + slooth.peripheral.net.PacketTools.NOT_ALLOWED);
        return onClient(() -> {
            if (on) slooth.peripheral.net.PacketTools.setHolding(true, owner);
            else slooth.peripheral.net.PacketTools.release(send);
            return packets(new JsonObject());
        });
    }

    static JsonObject packetSend(JsonObject p) {
        String data = str(p, "data", "");
        if (data.isBlank()) throw new ApiException("data_required");
        return onClient(() -> {
            try {
                var sent = slooth.peripheral.net.PacketTools.send(data);
                JsonObject r = ok();
                r.addProperty("name", sent.name());
                r.addProperty("summary", sent.summary());
                return r;
            } catch (IllegalStateException e) {
                throw new ApiException("not_allowed_here: " + e.getMessage());
            } catch (IllegalArgumentException e) {
                throw new ApiException("bad_packet: " + e.getMessage());
            }
        });
    }

    // ── UI control ────────────────────────────────────────────────────────────

    private static JsonObject uiDone(Runnable work) {
        return onClient(() -> { work.run(); return ok(); });
    }

    private static double num(JsonObject p, String key) {
        if (!p.has(key)) throw new ApiException(key + "_required");
        double v = p.get(key).getAsDouble();
        if (!Double.isFinite(v)) throw new ApiException("invalid_" + key);
        return v;
    }

    private static int button(JsonObject p) {
        return switch (str(p, "button", "left")) {
            case "right", "1" -> 1;
            case "middle", "2" -> 2;
            default -> 0;
        };
    }

    private static int mods(JsonObject p) {
        int m = 0;
        if (p.has("shift") && p.get("shift").getAsBoolean()) m |= org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT;
        if (p.has("ctrl") && p.get("ctrl").getAsBoolean()) m |= org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL;
        if (p.has("alt") && p.get("alt").getAsBoolean()) m |= org.lwjgl.glfw.GLFW.GLFW_MOD_ALT;
        return m;
    }

    static JsonObject uiMove(JsonObject p) {
        double x = num(p, "x"), y = num(p, "y");
        return uiDone(() -> slooth.peripheral.control.GuiControl.move(x, y));
    }

    static JsonObject uiMouseButton(JsonObject p) {
        int b = button(p), m = mods(p);
        boolean down = !"up".equals(str(p, "action", "down"));
        return uiDone(() -> slooth.peripheral.control.GuiControl.button(b, down, m));
    }

    static JsonObject uiType(JsonObject p) {
        String text = str(p, "text", "");
        return uiDone(() -> slooth.peripheral.control.GuiControl.type(text));
    }

    static JsonObject uiClick(JsonObject p) {
        double x = num(p, "x"), y = num(p, "y");
        int b = button(p), m = mods(p);
        return uiDone(() -> slooth.peripheral.control.GuiControl.click(x, y, b, m));
    }

    static JsonObject uiClickButton(JsonObject p) {
        String label = str(p, "label", "");
        int b = button(p), m = mods(p);
        return onClient(() -> {
            var control = slooth.peripheral.control.GuiControl.find(label);
            if (control == null) throw new ApiException("no_control:" + label);
            if (!control.active()) throw new ApiException("control_disabled:" + control.label());
            slooth.peripheral.control.GuiControl.click(control.x() + control.width() / 2, control.y() + control.height() / 2, b, m);
            JsonObject r = ok();
            r.addProperty("clicked", control.label());
            return r;
        });
    }

    static JsonObject uiClickSlot(JsonObject p) {
        int slot = (int) num(p, "slot"), b = button(p);
        boolean shift = p.has("shift") && p.get("shift").getAsBoolean();
        return onClient(() -> {
            if (!slooth.peripheral.control.GuiControl.clickSlot(slot, b, shift)) throw new ApiException("no_slot:" + slot);
            return ok();
        });
    }

    static JsonObject uiScroll(JsonObject p) {
        double amount = num(p, "amount");
        boolean at = p.has("x") && p.has("y");
        double x = at ? num(p, "x") : 0, y = at ? num(p, "y") : 0;
        return uiDone(() -> {
            if (at) slooth.peripheral.control.GuiControl.move(x, y);
            slooth.peripheral.control.GuiControl.scroll(amount);
        });
    }

    static JsonObject uiKey(JsonObject p) {
        String name = str(p, "key", "");
        int code = p.has("code") ? p.get("code").getAsInt() : slooth.peripheral.control.GuiControl.keyCode(name);
        if (code < 0) throw new ApiException("unknown_key:" + name);
        String action = str(p, "action", "tap");
        int m = mods(p);
        return uiDone(() -> {
            if (!action.equals("up")) slooth.peripheral.control.GuiControl.key(code, true, m);
            if (!action.equals("down")) slooth.peripheral.control.GuiControl.key(code, false, m);
        });
    }

    /** Runs {@code work} on the client thread and returns its result, or rethrows its ApiException. */
    private static JsonObject onClient(java.util.function.Supplier<JsonObject> work) {
        Minecraft client = Minecraft.getInstance();
        if (client.isSameThread()) return work.get();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<JsonObject> result = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        client.execute(() -> {
            try { result.set(work.get()); } catch (RuntimeException e) { failure.set(e); } finally { done.countDown(); }
        });
        try { if (!done.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new ApiException("timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ApiException("interrupted"); }
        if (failure.get() != null) throw failure.get() instanceof ApiException a ? a : new ApiException(failure.get().getMessage());
        return result.get();
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }

    static JsonObject navStatus() {
        PeripheralNavigator nav = PeripheralNavigator.INSTANCE;
        Minecraft client = Minecraft.getInstance();
        JsonObject resp = new JsonObject();
        resp.addProperty("status", nav.getNavStatus().name().toLowerCase());
        resp.addProperty("reason", nav.getFailReason());
        resp.addProperty("waypoints_left", nav.getWpLeft());
        resp.addProperty("baritone", net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("baritone"));
        if (client.player != null) {
            JsonObject pos = new JsonObject();
            pos.addProperty("x", client.player.getX());
            pos.addProperty("y", client.player.getY());
            pos.addProperty("z", client.player.getZ());
            resp.add("pos", pos);
        }
        return resp;
    }

    // ── nav_stop ─────────────────────────────────────────────────────────────────
    static JsonObject navStop() {
        String owner=LocalApiSecurity.owner();
        Minecraft.getInstance().execute(() -> slooth.peripheral.script.ScriptActions.stopNavigation(owner));
        return ok();
    }

    /** Serialize owned render-resource mutations with exit cleanup and world transitions. */
    private static void mutateOwned(java.util.function.Consumer<String> action) {
        String owner=LocalApiSecurity.owner(); long generation=ScriptRunner.worldGeneration;
        Minecraft client=Minecraft.getInstance();
        CountDownLatch done=new CountDownLatch(1); AtomicReference<RuntimeException> failure=new AtomicReference<>();
        client.execute(() -> {
            try { if(LocalApiSecurity.active(owner) && generation==ScriptRunner.worldGeneration)action.accept(owner); else failure.set(new ApiException("capability_revoked")); }
            catch(RuntimeException e){failure.set(e);} finally {done.countDown();}
        });
        try {if(!done.await(3,TimeUnit.SECONDS))throw new ApiException("action_timeout_outcome_unknown");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new ApiException("interrupted");}
        if(failure.get()!=null)throw failure.get();
    }

    // ── HUD ──────────────────────────────────────────────────────────────────────
    static JsonObject hudSet(JsonObject req) {
        if (!req.has("elements")) throw new ApiException("missing_elements");
        java.util.List<JsonObject> list = new java.util.ArrayList<>();
        req.getAsJsonArray("elements").forEach(e -> { if (e.isJsonObject()) list.add(e.getAsJsonObject()); });
        mutateOwned(owner -> PeripheralHud.setElements(list, owner));
        return ok();
    }

    static JsonObject hudUpdate(JsonObject req) {
        if (!req.has("id")) throw new ApiException("missing_id");
        JsonObject props = req.deepCopy();
        String id = props.get("id").getAsString();
        props.remove("id");
        mutateOwned(owner -> PeripheralHud.updateElement(id, props, owner));
        return ok();
    }

    static JsonObject hudClear(JsonObject req) {
        mutateOwned(PeripheralHud::clearIfOwner);
        return ok();
    }

    static JsonObject hudGet() {
        JsonObject resp = new JsonObject();
        resp.add("elements", PeripheralHud.getElementsJson());
        return resp;
    }

    // ── overlay ──────────────────────────────────────────────────────────────────
    static JsonObject overlaySet(JsonObject req) {
        if (!req.has("type")) throw new ApiException("missing_type");
        mutateOwned(owner -> PeripheralOverlayRenderer.setCommand(req, owner));
        return ok();
    }

    static JsonObject overlayClear(JsonObject req) {
        mutateOwned(PeripheralOverlayRenderer::clearIfOwner);
        return ok();
    }

    // ── protection ───────────────────────────────────────────────────────────────
    static JsonObject protection() {
        return BlockTracker.getProtectionState();
    }

    // ── config ───────────────────────────────────────────────────────────────────
    static JsonObject config() {
        JsonObject resp = new JsonObject();
        resp.addProperty("authenticated", true);
        resp.addProperty("agent_url",   PeripheralConfig.agentUrl);
        resp.addProperty("model",       PeripheralConfig.model);
        resp.addProperty("state_port",  PeripheralHttpServer.PORT);

        return resp;
    }

    // ── find_blocks (game-thread, time-budgeted) ─────────────────────────────────
    static JsonObject findBlocks(JsonObject req) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) throw new ApiException("not_in_game");
        String block = req.has("block")
            ? req.get("block").getAsString().toLowerCase().replace("minecraft:", "") : "";
        if (block.isEmpty()) throw new ApiException("missing_block");
        int radius = Math.min(64,  Math.max(1, req.has("radius") ? req.get("radius").getAsInt() : 32));
        int max    = Math.min(512, Math.max(1, req.has("max")    ? req.get("max").getAsInt()    : 256));

        com.google.gson.JsonArray out = new com.google.gson.JsonArray();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean truncated = new AtomicBoolean(false);
        client.execute(() -> {
            try {
                BlockPos origin = client.player.blockPosition();
                long deadline = System.nanoTime() + 40_000_000L;
                java.util.List<int[]> found = new java.util.ArrayList<>();
                outer:
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            BlockPos p = origin.offset(dx, dy, dz);
                            if (client.level.getBlockState(p).getBlock().toString()
                                    .toLowerCase().contains(block)) {
                                found.add(new int[]{p.getX(), p.getY(), p.getZ(), dx*dx + dy*dy + dz*dz});
                            }
                            if (System.nanoTime() > deadline) { truncated.set(true); break outer; }
                        }
                    }
                }
                found.sort(java.util.Comparator.comparingInt(a -> a[3]));
                for (int i = 0; i < Math.min(max, found.size()); i++) {
                    int[] f = found.get(i);
                    JsonObject o = new JsonObject();
                    o.addProperty("x", f[0]);
                    o.addProperty("y", f[1]);
                    o.addProperty("z", f[2]);
                    out.add(o);
                }
            } finally { latch.countDown(); }
        });
        try { if(!latch.await(3, TimeUnit.SECONDS)) throw new ApiException("action_timeout_outcome_unknown"); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ApiException("interrupted"); }
        JsonObject resp = new JsonObject();
        resp.add("blocks", out);
        resp.addProperty("count", out.size());
        if (truncated.get()) resp.addProperty("truncated", true);
        return resp;
    }

    // ── notify (vanilla SystemToast) ─────────────────────────────────────────────
    static JsonObject notifyToast(JsonObject req) {
        Minecraft client = Minecraft.getInstance();
        String title = req.has("title") ? req.get("title").getAsString() : "";
        String text  = req.has("text")  ? req.get("text").getAsString()  : "";
        client.execute(() -> net.minecraft.client.gui.components.toasts.SystemToast.add(
            client.gui.toastManager(),
            net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
            Component.literal(title),
            text.isEmpty() ? null : Component.literal(text)));
        return ok();
    }

    // ── play_sound (client-side) ─────────────────────────────────────────────────
    static JsonObject playSound(JsonObject req) {
        Minecraft client = Minecraft.getInstance();
        String id = req.has("sound_id") ? req.get("sound_id").getAsString() : "";
        if (id.isEmpty()) throw new ApiException("missing_sound_id");
        float volume = req.has("volume") ? req.get("volume").getAsFloat() : 1f;
        float pitch  = req.has("pitch")  ? req.get("pitch").getAsFloat()  : 1f;
        net.minecraft.resources.Identifier ident = net.minecraft.resources.Identifier.tryParse(id);
        if (ident == null || !net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.containsKey(ident))
            throw new ApiException("unknown_sound:" + id);
        net.minecraft.sounds.SoundEvent ev =
            net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.getValue(ident);
        client.execute(() -> client.getSoundManager().play(
            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(ev, pitch, volume)));
        return ok();
    }

    // ── action (game-thread) ─────────────────────────────────────────────────────
    static JsonObject action(JsonObject req) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) throw new ApiException("not_in_game");

        final String owner=LocalApiSecurity.owner();
        final long worldGeneration=ScriptRunner.worldGeneration;
        String type = req.has("type") ? req.get("type").getAsString().toLowerCase() : "";

        CountDownLatch latch  = new CountDownLatch(1);
        AtomicBoolean  ok     = new AtomicBoolean(false);
        AtomicReference<String> errMsg = new AtomicReference<>("");

        client.execute(() -> {
            try {
                if (!LocalApiSecurity.active(owner) || worldGeneration!=ScriptRunner.worldGeneration || !ScriptRunner.sessionOpen || client.player == null) { errMsg.set("script_stopped_or_disconnected"); return; }
                switch (type) {
                    case "set_slot" -> {
                        int slot = Math.max(0, Math.min(8, req.has("slot") ? req.get("slot").getAsInt() : 0));
                        client.player.getInventory().selected = slot;
                        client.player.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(slot));
                        ok.set(true);
                    }
                    case "look_at" -> {
                        PeripheralHttpServer.lookAt(client.player, new Vec3(
                            req.has("x") ? req.get("x").getAsDouble() : 0,
                            req.has("y") ? req.get("y").getAsDouble() : 64,
                            req.has("z") ? req.get("z").getAsDouble() : 0));
                        ok.set(true);
                    }
                    case "use_block", "place_block" -> {
                        BlockPos  bpos = new BlockPos(req.has("x") ? req.get("x").getAsInt() : 0, req.has("y") ? req.get("y").getAsInt() : 64, req.has("z") ? req.get("z").getAsInt() : 0);
                        Direction face = PeripheralHttpServer.dirFrom(req.has("face") ? req.get("face").getAsString() : "up");
                        Vec3 hit = Vec3.atCenterOf(bpos).add(face.getStepX() * .5, face.getStepY() * .5, face.getStepZ() * .5);
                        PeripheralHttpServer.lookAt(client.player, Vec3.atCenterOf(bpos));
                        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, bpos, false));
                        ok.set(true);
                    }
                    case "start_break" -> {
                        BlockPos bpos = new BlockPos(req.has("x") ? req.get("x").getAsInt() : 0, req.has("y") ? req.get("y").getAsInt() : 64, req.has("z") ? req.get("z").getAsInt() : 0);
                        PeripheralHttpServer.lookAt(client.player, Vec3.atCenterOf(bpos));
                        client.gameMode.startDestroyBlock(bpos, Direction.UP);
                        ok.set(true);
                    }
                    case "stop_break" -> ok.set(true);
                    case "click_slot" -> {
                        AbstractContainerMenu sh = client.player.containerMenu;
                        if (sh == null) { errMsg.set("no_screen_open"); break; }
                        ContainerInput sat = switch (req.has("mode") ? req.get("mode").getAsString().toLowerCase() : "") {
                            case "shift" -> ContainerInput.QUICK_MOVE;
                            case "drop"  -> ContainerInput.THROW;
                            case "clone" -> ContainerInput.CLONE;
                            default      -> ContainerInput.PICKUP;
                        };
                        client.gameMode.handleContainerInput(sh.containerId, req.has("slot") ? req.get("slot").getAsInt() : 0, req.has("button") ? req.get("button").getAsInt() : 0, sat, client.player);
                        ok.set(true);
                    }
                    case "close_container" -> { if (client.gui.screen() != null) client.player.closeContainer(); ok.set(true); }
                    case "drop_item"       -> {
                        boolean dropAll = req.has("all") && req.get("all").getAsBoolean();
                        net.minecraft.world.item.ItemStack held = client.player.getMainHandItem();
                        if (!held.isEmpty()) {
                            net.minecraft.world.item.ItemStack toDrop = held.split(dropAll ? held.getCount() : 1);
                            if (!toDrop.isEmpty()) client.player.drop(toDrop, false);
                        }
                        ok.set(true);
                    }
                    case "swap_hands" -> {
                        AbstractContainerMenu sh2 = client.player.containerMenu;
                        if (sh2 != null) client.gameMode.handleContainerInput(sh2.containerId, 36 + client.player.getInventory().getSelectedSlot(), 40, ContainerInput.SWAP, client.player);
                        ok.set(true);
                    }
                    case "send_chat" -> {
                        String msg = req.has("message") ? req.get("message").getAsString() : "";
                        if (!msg.isEmpty()) { client.player.connection.sendChat(msg); ok.set(true); }
                        else errMsg.set("empty_message");
                    }
                    case "send_command" -> {
                        String raw = req.has("message") ? req.get("message").getAsString() : "";
                        if (!raw.isEmpty()) {
                            String bare = raw.startsWith("/") ? raw.substring(1) : raw;
                            net.minecraft.client.server.IntegratedServer srv = client.getSingleplayerServer();
                            if (srv != null) {
                                String finalBare = bare;
                                srv.execute(() -> {
                                    net.minecraft.server.level.ServerPlayer sp =
                                        srv.getPlayerList().getPlayer(client.player.getUUID());
                                    if (sp != null) {
                                        srv.getCommands().performPrefixedCommand(
                                            sp.createCommandSourceStack(), "/" + finalBare);
                                    }
                                });
                            } else {
                                client.player.connection.send(
                                    new net.minecraft.network.protocol.game.ServerboundChatCommandPacket(bare));
                            }
                            ok.set(true);
                        } else errMsg.set("empty_message");
                    }
                    case "open_gui" -> {
                        JsonObject layout = req.has("layout") ? req.get("layout").getAsJsonObject() : new JsonObject();
                        String guiOwner = owner;
                        ScriptableScreen.openWithLayout(layout, guiOwner);
                        ok.set(true);
                    }
                    case "update_gui" -> {
                        String guiId = req.has("id") ? req.get("id").getAsString() : "";
                        JsonObject props = req.has("props") ? req.get("props").getAsJsonObject() : new JsonObject();
                        if (!guiId.isEmpty() && ScriptableScreen.ownedBy(owner)) { ScriptableScreen.update(guiId, props); ok.set(true); }
                        else errMsg.set("missing_id");
                    }
                    case "close_gui" -> {
                        if (!ScriptableScreen.ownedBy(owner)) { errMsg.set("not_gui_owner"); break; }
                        ScriptableScreen.closeScreen();
                        ok.set(true);
                    }
                    case "display_message" -> {
                        String raw = req.has("message") ? req.get("message").getAsString() : "";
                        client.player.sendSystemMessage(PeripheralHttpServer.buildChatText(raw));
                        ok.set(true);
                    }
                    case "send_rich" -> {
                        String jsonStr = req.has("json") ? req.get("json").getAsString() : "";
                        if (!jsonStr.isEmpty()) {
                            try {
                                Component richText = PeripheralHttpServer.buildRichText(JsonParser.parseString(jsonStr));
                                client.player.sendSystemMessage(richText);
                                ok.set(true);
                            } catch (Exception e) {
                                errMsg.set("parse_error: " + e.getMessage());
                            }
                        } else {
                            errMsg.set("empty_json");
                        }
                    }
                    case "use_item" -> {
                        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                        ok.set(true);
                    }
                    case "equip_item" -> {
                        String keyword = req.has("item") ? req.get("item").getAsString().toLowerCase().replace("minecraft:", "") : "";
                        if (keyword.isEmpty()) { errMsg.set("no_item_keyword"); break; }
                        var inv = client.player.getInventory();
                        int sel = inv.getSelectedSlot();
                        var stacks = inv.getNonEquipmentItems();
                        String heldId = stacks.get(sel).getItem().toString().toLowerCase().replace("minecraft:", "");
                        if (heldId.contains(keyword)) { ok.set(true); break; }
                        boolean found = false;
                        for (int hi = 0; hi < 9; hi++) {
                            String id = stacks.get(hi).getItem().toString().toLowerCase().replace("minecraft:", "");
                            if (id.contains(keyword)) {
                                inv.selected = hi;
                                client.player.connection.send(
                                    new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(hi));
                                ok.set(true); found = true; break;
                            }
                        }
                        if (found) break;
                        for (int mi = 9; mi < stacks.size(); mi++) {
                            String id = stacks.get(mi).getItem().toString().toLowerCase().replace("minecraft:", "");
                            if (id.contains(keyword)) {
                                AbstractContainerMenu sh = client.player.containerMenu;
                                client.gameMode.handleContainerInput(sh.containerId, mi, sel, ContainerInput.SWAP, client.player);
                                ok.set(true); found = true; break;
                            }
                        }
                        if (!found) { ok.set(false); errMsg.set("item_not_found:" + keyword); }
                    }
                    case "look" -> {
                        client.player.setYRot(req.has("yaw")   ? req.get("yaw").getAsFloat()   : 0);
                        client.player.setXRot(req.has("pitch") ? req.get("pitch").getAsFloat() : 0);
                        ok.set(true);
                    }
                    case "jump" -> { slooth.peripheral.script.ScriptActions.jump(owner); ok.set(true); }
                    case "sprint" -> { slooth.peripheral.script.ScriptActions.sprint(owner,!req.has("on") || req.get("on").getAsBoolean()); ok.set(true); }
                    case "sneak" -> { slooth.peripheral.script.ScriptActions.sneak(owner,!req.has("on") || req.get("on").getAsBoolean()); ok.set(true); }
                    case "move" -> {
                        float f=req.has("forward")?req.get("forward").getAsFloat():0, str=req.has("strafe")?req.get("strafe").getAsFloat():0;
                        if (!Float.isFinite(f) || !Float.isFinite(str)) { errMsg.set("invalid_movement"); break; }
                        slooth.peripheral.script.ScriptActions.movement(owner,f,str); ok.set(true);
                    }
                    case "attack" -> {
                        var hit = client.hitResult;
                        if (hit instanceof net.minecraft.world.phys.EntityHitResult ehr) {
                            client.gameMode.attack(client.player, ehr.getEntity());
                        } else if (hit instanceof net.minecraft.world.phys.BlockHitResult bhr) {
                            client.gameMode.startDestroyBlock(bhr.getBlockPos(), bhr.getDirection());
                        }
                        ok.set(true);
                    }
                    case "respawn" -> {
                        client.player.connection.send(
                            new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(
                                net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                        ok.set(true);
                    }
                    case "look_at_entity" -> {
                        if (client.level == null) { errMsg.set("not_in_world"); break; }
                        String entType = req.has("entity_type") ? req.get("entity_type").getAsString().toLowerCase() : "";
                        double maxDist = req.has("max_distance") ? req.get("max_distance").getAsDouble() : 16.0;
                        var searchBox = client.player.getBoundingBox().inflate(maxDist);
                        var candidates = client.level.getEntitiesOfClass(
                            net.minecraft.world.entity.LivingEntity.class, searchBox,
                            e -> e != client.player && e.getType().toString().toLowerCase().contains(entType));
                        if (candidates.isEmpty()) {
                            ok.set(false); errMsg.set("no_entity_found:" + entType);
                        } else {
                            net.minecraft.world.entity.LivingEntity closest = candidates.stream()
                                .min(java.util.Comparator.comparingDouble(client.player::distanceTo))
                                .orElse(null);
                            if (closest != null) { PeripheralHttpServer.lookAt(client.player, closest.getEyePosition()); ok.set(true); }
                        }
                    }
                    default -> errMsg.set("unknown_action_type:" + type);
                }
            } catch (Exception e) { errMsg.set(e.getClass().getSimpleName() + ": " + e.getMessage()); }
            finally { latch.countDown(); }
        });

        try { if(!latch.await(3, TimeUnit.SECONDS)) throw new ApiException("action_timeout_outcome_unknown"); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ApiException("interrupted"); }
        JsonObject resp = new JsonObject();
        resp.addProperty("ok", ok.get());
        if (!ok.get()) resp.addProperty("error", errMsg.get());
        return resp;
    }
}
