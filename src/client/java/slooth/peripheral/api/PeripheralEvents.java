package slooth.peripheral.api;

import com.google.gson.JsonObject;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Static event bus used to push game events to subscribers (currently the
 * {@link PeripheralSocketServer}).  Emitters call {@link #emit(String, JsonObject)};
 * {@link #clientTick(Minecraft)} is driven each client tick and produces the
 * {@code tick}, {@code health}, {@code death} and {@code respawn} events.
 *
 * <p>Event types: {@code chat}, {@code gui}, {@code tick}, {@code health},
 * {@code death}, {@code respawn}.
 */
public final class PeripheralEvents {

    private PeripheralEvents() {}

    /** A subscriber. {@link #onEvent} may be called from any thread. */
    public interface Listener {
        void onEvent(String type, JsonObject data);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static float   lastHealth = Float.NaN;
    private static boolean lastDead   = false;

    public static void subscribe(Listener l)   { LISTENERS.add(l); }
    public static void unsubscribe(Listener l) { LISTENERS.remove(l); }
    public static boolean hasListeners()       { return !LISTENERS.isEmpty(); }

    /** Fan an event out to every subscriber. Listener exceptions are swallowed. */
    public static void emit(String type, JsonObject data) {
        if (LISTENERS.isEmpty()) return;
        for (Listener l : LISTENERS) {
            try { l.onEvent(type, data); } catch (Exception ignored) {}
        }
    }

    /**
     * Driven from the client tick-end event.  Emits a compact {@code tick} state
     * snapshot every tick and diff-based {@code health}/{@code death}/{@code respawn}
     * events.  Does nothing (and resets tracking) when no one is subscribed or the
     * player is absent, so it is essentially free when idle.
     */
    public static void clientTick(Minecraft client) {
        if (LISTENERS.isEmpty() || client.player == null) {
            lastHealth = Float.NaN;
            lastDead   = false;
            return;
        }
        LocalPlayer player = client.player;

        emit("tick", tickSnapshot(client));

        float hp = player.getHealth();
        if (!Float.isNaN(lastHealth) && hp != lastHealth) {
            JsonObject d = new JsonObject();
            d.addProperty("health",   hp);
            d.addProperty("previous", lastHealth);
            emit("health", d);
        }
        lastHealth = hp;

        boolean dead = !player.isAlive();
        if (dead && !lastDead)  emit("death",   new JsonObject());
        if (!dead && lastDead)  emit("respawn", new JsonObject());
        lastDead = dead;
    }

    private static JsonObject tickSnapshot(Minecraft client) {
        LocalPlayer p = client.player;
        JsonObject o = new JsonObject();
        JsonObject pos = new JsonObject();
        pos.addProperty("x", p.getX());
        pos.addProperty("y", p.getY());
        pos.addProperty("z", p.getZ());
        o.add("position", pos);
        o.addProperty("health", p.getHealth());
        o.addProperty("food",   p.getFoodData().getFoodLevel());
        o.addProperty("yaw",    p.getYRot());
        o.addProperty("pitch",  p.getXRot());
        if (client.level != null)
            o.addProperty("dimension", client.level.dimension().identifier().toString());
        return o;
    }
}
