package slooth.peripheral.record;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import slooth.peripheral.NavigatorInput;
import slooth.peripheral.mixin.client.KeyMappingAccess;
import slooth.peripheral.record.Recording.Frame;

/**
 * Records what the player does each tick and plays it back.
 *
 * <p>A recording keeps positions, not just key presses: every tick stores where the player ended up,
 * where they looked, and what they pressed. Playback presses the same keys on the same ticks through
 * Minecraft's own input path, and steers toward the recorded position, so small differences never add
 * up over many loops. If something pushes the player well off the path, playback stops advancing,
 * walks back to the block it should be on, and carries on. Before each loop it returns to the exact
 * starting spot.
 *
 * <p>Client thread only.
 */
public final class Recorder {
    private Recorder() {}

    public enum State { IDLE, RECORDING, PLAYING }
    /** WORLD replays at the recorded coordinates; RELATIVE shifts the whole path by whole blocks to start where the player stands. */
    public enum Mode { WORLD, RELATIVE }

    /** Off the path by more than this (blocks), playback pauses and walks back. */
    static final double RECOVER_DISTANCE = .75, RECOVER_HEIGHT = 1.25;
    /** Back within this, the path continues. */
    static final double RESUME_DISTANCE = .12;
    /** A loop starts only once the player stands this close to the start, nearly still. */
    static final double START_DISTANCE = .02, START_SPEED = .004;
    /** Closer to the path than this, playback presses exactly the recorded keys with no steering. */
    static final double STEER_DEADZONE = .002;
    /** Gives up walking back after this many ticks. */
    static final int RECOVER_LIMIT = 400;

    private static State state = State.IDLE;

    // ── Recording ───────────────────────────────────────────────────────────
    private static String recordName;
    private static Recording resumeFrom;
    private static final List<Frame> taking = new ArrayList<>();
    private static final List<String> tickEvents = new ArrayList<>();
    private static double startX, startY, startZ;
    private static float startYaw, startPitch, tickYaw, tickPitch;
    private static int tickSlot, tickHeld;
    private static boolean tickStarted;

    // ── Playback ────────────────────────────────────────────────────────────
    private static Recording playing;
    private static Mode mode = Mode.WORLD;
    private static int index, loop, loops, recoverTicks, waitTicks, corrections;
    private static boolean returning, recovering;
    private static double offX, offY, offZ, loopError, worstError;
    private static String message = "";
    /** The script that started playback, if one did; its playback stops when it stops. */
    private static String owner;
    private static final List<double[]> loopEnds = new ArrayList<>();
    private static final List<Double> loopErrors = new ArrayList<>();

    public static State state() { return state; }
    public static boolean recording() { return state == State.RECORDING; }
    public static boolean playing() { return state == State.PLAYING; }
    /** What happened last, such as why playback stopped. */
    public static String message() { return message; }

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(Recorder::startTick);
        ClientTickEvents.END_CLIENT_TICK.register(Recorder::endTick);
        RecorderHud.register();
    }

    // ── Recording ───────────────────────────────────────────────────────────

    /** Starts recording under {@code name}. With {@code resume}, adds to that recording instead of replacing it. */
    public static String startRecording(String name, boolean resume) {
        Minecraft c = Minecraft.getInstance();
        LocalPlayer p = c.player;
        if (p == null) return "Join a world first.";
        if (state != State.IDLE) return state == State.RECORDING ? "Already recording." : "Stop playback first.";
        String clean = Recording.cleanName(name);
        resumeFrom = null;
        if (resume) {
            try { resumeFrom = Recording.load(clean); }
            catch (IOException e) { return "Couldn't open " + clean + " to resume it."; }
        }
        recordName = clean;
        taking.clear();
        startX = p.getX(); startY = p.getY(); startZ = p.getZ();
        startYaw = p.getYRot(); startPitch = p.getXRot();
        tickStarted = false;
        state = State.RECORDING;
        message = (resume ? "Resumed " : "Recording ") + clean;
        return null;
    }

    /** Stops recording and saves it. Returns the saved recording, or null when nothing was recorded. */
    public static Recording stopRecording() {
        if (state != State.RECORDING) return null;
        state = State.IDLE;
        if (taking.isEmpty()) { message = "Nothing was recorded."; return null; }
        String dimension = dimension();
        Recording rec = resumeFrom != null ? resumeFrom.append(taking)
            : new Recording(recordName, dimension, startX, startY, startZ, startYaw, startPitch, taking);
        taking.clear();
        try {
            rec.save();
            message = "Saved " + rec.name + " (" + seconds(rec.ticks()) + ")";
        } catch (IOException e) {
            message = "Couldn't save " + rec.name + ": " + e.getMessage();
        }
        return rec;
    }

    /** Ticks recorded so far, while recording. */
    public static int recordedTicks() { return taking.size() + (resumeFrom == null ? 0 : resumeFrom.ticks()); }
    public static String recordingName() { return recordName; }

    /** A gameplay key's press was used this tick; called from the KeyMapping mixin. */
    public static void consumed(KeyMapping key) {
        if (state != State.RECORDING) return;
        String name = keyName(key);
        if (name != null) tickEvents.add(name);
    }

    /** A container slot was clicked; called from the MultiPlayerGameMode mixin. */
    public static void containerClicked(AbstractContainerMenu menu, int slot, int button, ContainerInput input) {
        if (state != State.RECORDING) return;
        tickEvents.add("container:" + menuName(menu) + ":" + slot + ":" + button + ":" + input.name());
    }

    /** The player closed a container; called from the LocalPlayer mixin. */
    public static void containerClosed() {
        if (state == State.RECORDING) tickEvents.add("close");
    }

    // ── Playback ────────────────────────────────────────────────────────────

    /** Plays {@code name}. {@code loops} of 0 repeats until stopped. */
    public static String play(String name, int loops, Mode mode) {
        Minecraft c = Minecraft.getInstance();
        LocalPlayer p = c.player;
        if (p == null) return "Join a world first.";
        if (state == State.RECORDING) return "Stop recording first.";
        Recording rec;
        try { rec = Recording.load(Recording.cleanName(name)); }
        catch (IOException e) { return "Couldn't open " + name + "."; }
        if (mode == Mode.WORLD && !rec.dimension.isEmpty() && !rec.dimension.equals(dimension()))
            return rec.name + " was recorded in " + rec.dimension + ". Play it there, or play it relative to where you stand.";
        stopPlayback(null);
        owner = null;
        playing = rec;
        Recorder.mode = mode == null ? Mode.WORLD : mode;
        Recorder.loops = Math.max(0, loops);
        // Relative playback moves the path by whole blocks, so it keeps the same spot within each block.
        if (Recorder.mode == Mode.RELATIVE) {
            offX = Math.floor(p.getX()) - Math.floor(rec.startX);
            offY = Math.floor(p.getY() + 1e-6) - Math.floor(rec.startY + 1e-6);
            offZ = Math.floor(p.getZ()) - Math.floor(rec.startZ);
        } else offX = offY = offZ = 0;
        index = 0; loop = 0; corrections = 0; worstError = 0; loopError = 0;
        loopEnds.clear(); loopErrors.clear();
        returning = true; recovering = false; recoverTicks = 0; waitTicks = 0;
        state = State.PLAYING;
        message = "Playing " + rec.name;
        return null;
    }

    /** Stops playback and lets go of every key it held. {@code why} becomes the message, when given. */
    public static void stopPlayback(String why) {
        if (state != State.PLAYING) return;
        state = State.IDLE;
        release();
        if (why != null) message = why;
        playing = null;
        owner = null;
    }

    /** Marks playback as started by script {@code owner}. */
    public static void setOwner(String owner) { Recorder.owner = owner; }

    /** Stops playback started by script {@code owner}, when that script stops. */
    public static void releaseOwner(String scriptOwner) {
        if (state == State.PLAYING && scriptOwner != null && scriptOwner.equals(owner))
            stopPlayback("Playback stopped with the script that started it.");
    }

    public static String playingName() { return playing == null ? null : playing.name; }
    public static int playIndex() { return index; }
    public static int playLength() { return playing == null ? 0 : playing.ticks(); }
    public static int loop() { return loop; }
    public static int loops() { return loops; }
    public static boolean returning() { return returning || recovering; }
    /** The furthest the player has been from the recorded path at the end of a tick, in blocks, this loop and overall. */
    public static double loopError() { return loopError; }
    public static double worstError() { return worstError; }
    /** How many times playback had to walk back onto the path. */
    public static int corrections() { return corrections; }
    /** The furthest off the path each finished loop got, in blocks. */
    public static List<Double> loopErrors() { return List.copyOf(loopErrors); }
    /** Where the player stood at the end of each finished loop. */
    public static List<double[]> loopEnds() { return List.copyOf(loopEnds); }

    // ── Ticks ───────────────────────────────────────────────────────────────

    private static void startTick(Minecraft c) {
        LocalPlayer p = c.player;
        if (state == State.RECORDING) {
            if (p == null) { stopRecording(); return; }
            // What this tick runs with: the look and held buttons as the tick begins.
            tickYaw = p.getYRot(); tickPitch = p.getXRot();
            tickSlot = p.getInventory().getSelectedSlot();
            tickHeld = (c.options.keyAttack.isDown() ? Recording.ATTACK : 0) | (c.options.keyUse.isDown() ? Recording.USE : 0);
            tickEvents.clear();
            tickStarted = true;
        } else if (state == State.PLAYING) {
            if (p == null) { stopPlayback("Playback stopped: you left the world."); return; }
            try { playStart(c, p); }
            catch (RuntimeException e) { stopPlayback("Playback stopped: " + e.getMessage()); }
        }
    }

    private static void endTick(Minecraft c) {
        LocalPlayer p = c.player;
        if (state == State.RECORDING && tickStarted && p != null) {
            tickStarted = false;
            Input in = p.input.keyPresses;
            Vec2 mv = p.input.getMoveVector();
            int keys = tickHeld | (in.forward() ? Recording.FORWARD : 0) | (in.backward() ? Recording.BACKWARD : 0)
                | (in.left() ? Recording.LEFT : 0) | (in.right() ? Recording.RIGHT : 0) | (in.jump() ? Recording.JUMP : 0)
                | (in.shift() ? Recording.SNEAK : 0) | (in.sprint() ? Recording.SPRINT : 0);
            taking.add(new Frame(p.getX(), p.getY(), p.getZ(), tickYaw, tickPitch, mv.x, mv.y, keys, tickSlot,
                p.onGround(), List.copyOf(tickEvents)));
        } else if (state == State.PLAYING && p != null && !returning && !recovering && waitTicks == 0 && playing != null) {
            playEnd(p);
        }
    }

    /** Before the tick: aim, press the keys for frame {@code index}, or walk back to where it starts. */
    private static void playStart(Minecraft c, LocalPlayer p) {
        NavigatorInput input = input(p);
        Frame f = playing.frames.get(index);
        Vec3 from = expected(index);
        double dx = from.x - p.getX(), dy = from.y - p.getY(), dz = from.z - p.getZ(), dist = Math.hypot(dx, dz);

        if (returning) {
            // Between loops: back to the exact starting spot, looking the recorded way, nearly still.
            float yaw = index == 0 ? playing.startYaw : f.yaw();
            Vec3 v = p.getDeltaMovement();
            if (dist < START_DISTANCE && Math.abs(dy) < .1 && Math.hypot(v.x, v.z) < START_SPEED && p.onGround()) {
                returning = false; recoverTicks = 0; loopError = 0;
                p.setYRot(playing.startYaw); p.setXRot(playing.startPitch);
            } else {
                walkBack(c, p, input, dx, dy, dz, dist, yaw, playing.startPitch, true);
                return;
            }
        } else if (recovering || dist > RECOVER_DISTANCE || Math.abs(dy) > RECOVER_HEIGHT) {
            if (!recovering) { recovering = true; corrections++; recoverTicks = 0; }
            if (dist < RESUME_DISTANCE && Math.abs(dy) < .5) recovering = false;
            else { walkBack(c, p, input, dx, dy, dz, dist, f.yaw(), f.pitch(), false); return; }
        }

        // Wait for a container the recording clicked in, which the server may open a little later.
        for (String e : f.events()) if (e.startsWith("container:") && !menuName(p.containerMenu).equals(e.split(":")[1])) {
            input.replaying = true; input.replayKeys = Input.EMPTY; input.replayMove = Vec2.ZERO;
            if (++waitTicks > 60) stopPlayback("Playback stopped: the " + e.split(":")[1] + " screen never opened.");
            return;
        }
        waitTicks = 0;

        // Look exactly as recorded, and press what was pressed, plus a small steer back onto the path.
        p.setYRot(f.yaw()); p.setXRot(f.pitch());
        p.yRotO = f.yaw(); p.xRotO = f.pitch();
        float rad = f.yaw() * ((float) Math.PI / 180F), sin = (float) Math.sin(rad), cos = (float) Math.cos(rad);
        // World error to the player's own axes (x = left, y = forward), the inverse of Minecraft's input rotation.
        float ex = (float) (dx * cos + dz * sin), ez = (float) (-dx * sin + dz * cos);
        // On the path the inputs are exactly the recorded ones; off it by more than a hair, steer back.
        boolean steer = dist > STEER_DEADZONE;
        float sx = f.moveX() + (steer ? 3F * ex : 0), sz = f.moveZ() + (steer ? 3F * ez : 0);
        float len = (float) Math.hypot(sx, sz);
        if (len > 1) { sx /= len; sz /= len; }
        Input keys = new Input(f.has(Recording.FORWARD), f.has(Recording.BACKWARD), f.has(Recording.LEFT), f.has(Recording.RIGHT),
            f.has(Recording.JUMP), f.has(Recording.SNEAK), f.has(Recording.SPRINT));
        if (steer) {
            // The keys the server sees match the way the player actually moves.
            boolean fwd = sz > 1e-5F, back = sz < -1e-5F, left = sx > 1e-5F, right = sx < -1e-5F;
            keys = new Input(fwd, back, left, right, keys.jump(), keys.shift(), keys.sprint() && fwd);
        }
        input.replaying = true;
        input.replayKeys = keys;
        input.replayMove = new Vec2(sx, sz);

        if (p.getInventory().getSelectedSlot() != f.slot()) p.getInventory().setSelectedSlot(f.slot());
        c.options.keyAttack.setDown(f.has(Recording.ATTACK));
        c.options.keyUse.setDown(f.has(Recording.USE));
        for (String e : f.events()) replay(c, p, e);
    }

    /** After the tick: how far off the path the player ended, and on to the next frame or loop. */
    private static void playEnd(LocalPlayer p) {
        Frame f = playing.frames.get(index);
        double err = Math.sqrt(sq(f.x() + offX - p.getX()) + sq(f.y() + offY - p.getY()) + sq(f.z() + offZ - p.getZ()));
        loopError = Math.max(loopError, err);
        worstError = Math.max(worstError, err);
        if (++index < playing.ticks()) return;
        loopEnds.add(new double[]{p.getX(), p.getY(), p.getZ()});
        loopErrors.add(loopError);
        loop++;
        if (loops > 0 && loop >= loops) {
            stopPlayback("Finished " + playing.name + " (" + loop + (loop == 1 ? " loop)" : " loops)"));
            return;
        }
        index = 0;
        returning = true; recoverTicks = 0;
        release();
    }

    /** Walks toward where frame {@code index} starts, jumping up steps, and gives up after a while. */
    private static void walkBack(Minecraft c, LocalPlayer p, NavigatorInput input, double dx, double dy, double dz,
                                 double dist, float lookYaw, float lookPitch, boolean precise) {
        if (++recoverTicks > RECOVER_LIMIT) {
            Vec3 at = expected(index);
            stopPlayback(String.format("Playback stopped: couldn't get back to the path at %.1f %.1f %.1f.", at.x, at.y, at.z));
            return;
        }
        c.options.keyAttack.setDown(false);
        c.options.keyUse.setDown(false);
        Vec3 v = p.getDeltaMovement();
        if (dist > .6) {
            // Far: face the spot and walk (sprint when it's a long way), jumping up anything in the way.
            float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
            p.setYRot(yaw); p.setXRot(0);
            boolean jump = p.onGround() && (dy > .5 || p.horizontalCollision);
            input.replaying = true;
            input.replayKeys = new Input(true, false, false, false, jump, false, dist > 4);
            input.replayMove = new Vec2(0, (float) Math.min(1, dist * 2));
            return;
        }
        // Near: look the recorded way and slide in, braking against the current speed.
        p.setYRot(lookYaw); p.setXRot(lookPitch);
        float rad = lookYaw * ((float) Math.PI / 180F), sin = (float) Math.sin(rad), cos = (float) Math.cos(rad);
        float ex = (float) (dx * cos + dz * sin), ez = (float) (-dx * sin + dz * cos);
        float vx = (float) (v.x * cos + v.z * sin), vz = (float) (-v.x * sin + v.z * cos);
        float gain = precise ? 2.2F : 3F, brake = precise ? 9F : 6F;
        float sx = gain * ex - brake * vx, sz = gain * ez - brake * vz;
        float len = (float) Math.hypot(sx, sz);
        if (len > 1) { sx /= len; sz /= len; }
        input.replaying = true;
        input.replayKeys = new Input(sz > 1e-5F, sz < -1e-5F, sx > 1e-5F, sx < -1e-5F, p.onGround() && dy > .5, false, false);
        input.replayMove = new Vec2(sx, sz);
    }

    /** Where the player should stand before frame {@code i} runs: the start, or where frame i-1 ended. */
    private static Vec3 expected(int i) {
        if (i == 0) return new Vec3(playing.startX + offX, playing.startY + offY, playing.startZ + offZ);
        Frame f = playing.frames.get(i - 1);
        return new Vec3(f.x() + offX, f.y() + offY, f.z() + offZ);
    }

    private static void replay(Minecraft c, LocalPlayer p, String event) {
        if (event.equals("close")) { if (p.containerMenu != p.inventoryMenu || c.gui.screen() != null) p.closeContainer(); return; }
        if (event.startsWith("container:")) {
            String[] part = event.split(":");
            ContainerInput type = ContainerInput.valueOf(part[4]);
            c.gameMode.handleContainerInput(p.containerMenu.containerId, Integer.parseInt(part[2]), Integer.parseInt(part[3]), type, p);
            return;
        }
        KeyMapping key = keyByName(c, event);
        if (key != null) ((KeyMappingAccess) key).peripheral$setClickCount(((KeyMappingAccess) key).peripheral$getClickCount() + 1);
    }

    private static void release() {
        Minecraft c = Minecraft.getInstance();
        c.options.keyAttack.setDown(false);
        c.options.keyUse.setDown(false);
        if (c.player != null && c.player.input instanceof NavigatorInput n) {
            n.replaying = false; n.replayKeys = Input.EMPTY; n.replayMove = Vec2.ZERO;
        }
    }

    private static NavigatorInput input(LocalPlayer p) {
        if (!(p.input instanceof NavigatorInput)) p.input = new NavigatorInput(p.input);
        return (NavigatorInput) p.input;
    }

    // ── Names ───────────────────────────────────────────────────────────────

    private static String keyName(KeyMapping key) {
        var o = Minecraft.getInstance().options;
        if (key == o.keyAttack) return "attack";
        if (key == o.keyUse) return "use";
        if (key == o.keyPickItem) return "pick";
        if (key == o.keyDrop) return "drop";
        if (key == o.keySwapOffhand) return "swap";
        if (key == o.keyInventory) return "inventory";
        for (int i = 0; i < o.keyHotbarSlots.length; i++) if (key == o.keyHotbarSlots[i]) return "hotbar" + (i + 1);
        return null;
    }

    private static KeyMapping keyByName(Minecraft c, String name) {
        var o = c.options;
        return switch (name) {
            case "attack" -> o.keyAttack;
            case "use" -> o.keyUse;
            case "pick" -> o.keyPickItem;
            case "drop" -> o.keyDrop;
            case "swap" -> o.keySwapOffhand;
            case "inventory" -> o.keyInventory;
            default -> name.startsWith("hotbar") ? o.keyHotbarSlots[Math.clamp(Integer.parseInt(name.substring(6)) - 1, 0, 8)] : null;
        };
    }

    /** The open menu's registry name, or "inventory" for the player's own. */
    static String menuName(AbstractContainerMenu menu) {
        if (menu == null || menu instanceof InventoryMenu) return "inventory";
        try {
            var id = BuiltInRegistries.MENU.getKey(menu.getType());
            return id == null ? "unknown" : id.getPath();
        } catch (UnsupportedOperationException e) { return "inventory"; }
    }

    private static String dimension() {
        var level = Minecraft.getInstance().level;
        return level == null ? "" : level.dimension().identifier().toString();
    }

    public static String seconds(int ticks) {
        int s = ticks / 20;
        return s >= 60 ? s / 60 + ":" + String.format("%02d", s % 60) : String.format("%.1f s", ticks / 20.0);
    }

    private static double sq(double v) { return v * v; }
}
