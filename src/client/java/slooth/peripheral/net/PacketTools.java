package slooth.peripheral.net;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;

/**
 * Tools for testing how a server handles the client's network traffic: a packet log, blocking
 * packets by type in either direction, holding sent packets to send or drop later, and sending a
 * packet from its bytes (the log keeps each sent packet's bytes, so one can be copied, edited and
 * sent again).
 *
 * <p>Blocking, holding and sending work only in singleplayer and on servers that opt in by
 * registering the {@code peripheral:testing} plugin channel, so they can't be used against a server
 * that didn't ask to be tested. The log works everywhere: it only reads.
 *
 * <p>Everything is off at launch and nothing persists. Keep-alive and ping packets are never blocked
 * or held, so the connection itself stays up. Only the client's own connection is touched, never the
 * integrated server's side of it. Safe from any thread: packets arrive on network threads.
 */
public final class PacketTools {
    private PacketTools() {}

    public enum Direction { SENT, RECEIVED }

    /**
     * One logged packet. {@code blocked} and {@code held} say what the tools did with it. {@code data}
     * is a sent packet's bytes in hex, ready for {@link #send(String)}; "" for received packets and
     * for packets sent before the game started (while logging in or configuring).
     */
    public record Entry(long timeMs, Direction direction, String name, String summary, boolean blocked, boolean held, String data) {}

    /** A packet type seen this session and how many times. */
    public record Seen(Direction direction, String name, long count) {}

    /** Packets that keep the connection alive; blocking or holding them would only get you disconnected. */
    private static final Set<String> PROTECTED = Set.of("keep_alive", "pong", "ping", "disconnect", "finish_configuration",
        "select_known_packs", "client_information", "custom_query_answer", "login_acknowledged", "configuration_acknowledged");

    /** A server registers this plugin channel to allow blocking and holding on it. */
    public static final net.minecraft.resources.Identifier OPT_IN = net.minecraft.resources.Identifier.fromNamespaceAndPath("peripheral", "testing");
    private static volatile boolean allowed;

    /** Per direction, so a flood of received chunk data never pushes out what you sent. */
    private static final int LOG_LIMIT = 2000;
    private static final ArrayDeque<Entry> sentLog = new ArrayDeque<>(), receivedLog = new ArrayDeque<>();
    private static final Object log = new Object();
    private static volatile boolean logging;
    private static final Map<String, String> blockedSent = new ConcurrentHashMap<>(), blockedReceived = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> seenSent = new ConcurrentHashMap<>(), seenReceived = new ConcurrentHashMap<>();
    private static volatile boolean holding;
    private static volatile String holdOwner = "";
    private static final ConcurrentLinkedQueue<Packet<?>> held = new ConcurrentLinkedQueue<>();
    private static volatile Connection heldOn;
    private static final ThreadLocal<Boolean> releasing = ThreadLocal.withInitial(() -> false);

    // ── Where blocking and holding are allowed ──────────────────────────────

    /** Rechecked every tick: singleplayer, or a server that registered {@link #OPT_IN}. Leaving it turns everything off. */
    public static void refreshAllowed(Minecraft client) {
        boolean now = false;
        if (client.getConnection() != null) {
            try { now = client.hasSingleplayerServer() || net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(OPT_IN); }
            catch (IllegalStateException | IllegalArgumentException ignored) {}
        }
        if (allowed && !now) { blockedSent.clear(); blockedReceived.clear(); release(true); }
        allowed = now;
    }

    /** Whether blocking and holding work here: singleplayer, or a server that opted in. */
    public static boolean allowed() { return allowed; }

    /** Why blocking, holding and sending are off, in words for the player. */
    public static final String NOT_ALLOWED = "Blocking, holding and sending packets work in singleplayer, and on servers that turn them on "
        + "for testing by installing Peripheral Testing.";

    // ── Settings ────────────────────────────────────────────────────────────

    public static boolean logging() { return logging; }
    public static void setLogging(boolean on) { logging = on; }

    /** Blocks {@code name} in {@code direction}. {@code owner} is the script that asked, or "" for the player. Returns false for protected packets. */
    public static boolean block(Direction direction, String name, String owner) {
        String n = clean(name);
        if (n.isEmpty() || PROTECTED.contains(n) || !allowed) return false;
        (direction == Direction.SENT ? blockedSent : blockedReceived).put(n, owner == null ? "" : owner);
        return true;
    }

    public static void unblock(Direction direction, String name) {
        (direction == Direction.SENT ? blockedSent : blockedReceived).remove(clean(name));
    }

    public static boolean blocked(Direction direction, String name) {
        return (direction == Direction.SENT ? blockedSent : blockedReceived).containsKey(clean(name));
    }

    public static Set<String> blockedNames(Direction direction) {
        return Set.copyOf((direction == Direction.SENT ? blockedSent : blockedReceived).keySet());
    }

    public static boolean canBlock(String name) { return !PROTECTED.contains(clean(name)); }

    /** Starts or stops holding sent packets. Stopping sends what was held; use {@link #release} to drop them instead. */
    public static void setHolding(boolean on, String owner) {
        if (on) { if (!allowed) return; holdOwner = owner == null ? "" : owner; holding = true; }
        else release(true);
    }

    public static boolean holding() { return holding; }
    public static int heldCount() { return held.size(); }

    /** Stops holding and sends the held packets in order, or drops them. Call on the client thread. */
    public static void release(boolean send) {
        holding = false;
        Connection conn = heldOn;
        List<Packet<?>> out = new ArrayList<>();
        for (Packet<?> p; (p = held.poll()) != null; ) out.add(p);
        heldOn = null;
        if (!send || conn == null || !conn.isConnected()) return;
        releasing.set(true);
        try { for (Packet<?> p : out) conn.send(p); }
        finally { releasing.set(false); }
    }

    /** Turns every tool off: no blocking, nothing held (held packets are sent), no log. */
    public static void allOff() {
        blockedSent.clear(); blockedReceived.clear();
        release(true);
        logging = false;
    }

    /** Whether anything is changing traffic right now, for the on-screen warning. */
    public static boolean active() { return holding || !blockedSent.isEmpty() || !blockedReceived.isEmpty(); }

    /** Removes the rules a script added, and sends what it held, when it stops. */
    public static void releaseOwner(String owner) {
        if (owner == null || owner.isEmpty()) return;
        blockedSent.values().removeIf(owner::equals);
        blockedReceived.values().removeIf(owner::equals);
        if (holding && owner.equals(holdOwner)) Minecraft.getInstance().execute(() -> release(true));
    }

    /** Called when the player leaves a server: held packets belong to the old connection. */
    public static void disconnected() {
        holding = false; held.clear(); heldOn = null;
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    /** The newest {@code limit} logged packets, both directions, oldest first. */
    public static List<Entry> recent(int limit) {
        List<Entry> all;
        synchronized (log) { all = new ArrayList<>(sentLog); all.addAll(receivedLog); }
        all.sort(Comparator.comparingLong(Entry::timeMs)); // stable, so same-millisecond packets keep their order per direction
        return List.copyOf(all.subList(Math.max(0, all.size() - limit), all.size()));
    }

    public static void clearLog() { synchronized (log) { sentLog.clear(); receivedLog.clear(); } }

    /** Packet types seen this session, most frequent first. */
    public static List<Seen> seen() {
        List<Seen> out = new ArrayList<>();
        seenSent.forEach((n, c) -> out.add(new Seen(Direction.SENT, n, c.get())));
        seenReceived.forEach((n, c) -> out.add(new Seen(Direction.RECEIVED, n, c.get())));
        out.sort(Comparator.comparingLong(Seen::count).reversed().thenComparing(Seen::name));
        return out;
    }

    public static void clearSeen() { seenSent.clear(); seenReceived.clear(); }

    // ── Hooks from the Connection mixin ─────────────────────────────────────

    /** Whether {@code conn} is the client's own connection, not the integrated server's end of it. */
    public static boolean clientSide(Connection conn) { return conn.getReceiving() == PacketFlow.CLIENTBOUND; }

    /** A packet the client is about to send. Returns true to stop it. */
    public static boolean sending(Connection conn, Packet<?> packet) {
        if (releasing.get()) return false;
        String name = name(packet);
        count(seenSent, name);
        boolean blocked = allowed && blockedSent.containsKey(name);
        boolean hold = allowed && !blocked && holding && !PROTECTED.contains(name);
        if (hold) { heldOn = conn; held.add(packet); }
        record(Direction.SENT, name, packet, blocked, hold);
        return blocked || hold;
    }

    // ── Sending from bytes ──────────────────────────────────────────────────

    private static net.minecraft.network.ProtocolInfo<?> playCodec;
    private static net.minecraft.core.RegistryAccess playCodecFor;

    /** The game's own serverbound codec for this world, the one its network encoder uses. Null outside a world. */
    private static net.minecraft.network.ProtocolInfo<?> playCodec() {
        var listener = Minecraft.getInstance().getConnection();
        if (listener == null) return null;
        var registries = listener.registryAccess();
        if (playCodec == null || playCodecFor != registries) {
            playCodec = net.minecraft.network.protocol.game.GameProtocols.SERVERBOUND_TEMPLATE.bind(
                net.minecraft.network.RegistryFriendlyByteBuf.decorator(registries), () -> {
                    var player = Minecraft.getInstance().player;
                    return player != null && player.hasInfiniteMaterials();
                });
            playCodecFor = registries;
        }
        return playCodec;
    }

    /** A sent packet's bytes in hex: its id, then its fields, exactly as they go over the wire. "" when it can't be encoded here. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static String encode(Packet<?> packet) {
        var codec = playCodec();
        if (codec == null) return "";
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
        try {
            ((net.minecraft.network.codec.StreamCodec) codec.codec()).encode(buf, packet);
            return java.util.HexFormat.of().formatHex(buf.array(), buf.arrayOffset() + buf.readerIndex(), buf.arrayOffset() + buf.writerIndex());
        } catch (RuntimeException e) {
            return ""; // not a game packet: logging in or configuring
        } finally { buf.release(); }
    }

    /** What {@link #send(String)} sent, or why it couldn't. */
    public record Sent(String name, String summary) {}

    /**
     * Sends the packet whose bytes are {@code hex} (id then fields, as {@link Entry#data()} gives them)
     * to the server, past any blocks and holds. Client thread.
     *
     * @throws IllegalStateException when not allowed here or not in a world
     * @throws IllegalArgumentException when the bytes aren't a whole packet
     */
    public static Sent send(String hex) {
        if (!allowed) throw new IllegalStateException(NOT_ALLOWED);
        var listener = Minecraft.getInstance().getConnection();
        var codec = playCodec();
        if (listener == null || codec == null) throw new IllegalStateException("Not in a world.");
        byte[] bytes;
        try { bytes = java.util.HexFormat.of().parseHex(hex.replaceAll("\\s+", "")); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Not hex: " + e.getMessage()); }
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.wrappedBuffer(bytes);
        Packet<?> packet;
        try {
            packet = codec.codec().decode(buf);
            if (buf.isReadable()) throw new IllegalArgumentException(buf.readableBytes() + " bytes left over after the packet");
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not a packet the server accepts: " + e.getMessage(), e);
        } finally { buf.release(); }
        String name = name(packet);
        if (PROTECTED.contains(name)) throw new IllegalArgumentException(name + " keeps the connection alive and can't be sent by hand.");
        count(seenSent, name);
        record(Direction.SENT, name, packet, false, false);
        releasing.set(true);
        try { listener.getConnection().send(packet); }
        finally { releasing.set(false); }
        return new Sent(name, summary(packet));
    }

    /** A packet the client received. Returns true to drop it before the game sees it. */
    public static boolean receiving(Packet<?> packet) {
        if (packet instanceof ClientboundBundlePacket) return false; // its packets are checked one by one
        String name = name(packet);
        count(seenReceived, name);
        boolean blocked = allowed && blockedReceived.containsKey(name);
        record(Direction.RECEIVED, name, packet, blocked, false);
        return blocked;
    }

    /** A bundle with its blocked packets taken out, each one logged like a lone packet. */
    public static Packet<?> filterBundle(Packet<?> packet) {
        if (!(packet instanceof ClientboundBundlePacket bundle)) return packet;
        List<Packet<? super ClientGamePacketListener>> kept = new ArrayList<>();
        boolean changed = false;
        for (Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
            if (receiving(sub)) changed = true;
            else kept.add(sub);
        }
        return changed ? new ClientboundBundlePacket(kept) : packet;
    }

    private static void record(Direction direction, String name, Packet<?> packet, boolean blocked, boolean held) {
        if (!logging) return;
        String data = direction == Direction.SENT ? encode(packet) : "";
        Entry e = new Entry(System.currentTimeMillis(), direction, name, summary(packet), blocked, held, data);
        synchronized (log) {
            ArrayDeque<Entry> ring = direction == Direction.SENT ? sentLog : receivedLog;
            ring.addLast(e);
            while (ring.size() > LOG_LIMIT) ring.removeFirst();
        }
    }

    /** A packet's fields in one short line, or "" when it doesn't print them. */
    public static String summary(Packet<?> packet) {
        String summary;
        try { summary = packet.toString(); } catch (RuntimeException e) { return ""; }
        // Records print their fields; anything else prints "Class@hash", which says nothing the name doesn't.
        if (summary.matches("^[\\w.$]+@[0-9a-f]+$")) return "";
        summary = summary.replaceAll("net\\.minecraft\\.[\\w.]+\\.(\\w+)", "$1");
        return summary.length() > 240 ? summary.substring(0, 237) + "…" : summary;
    }

    private static void count(Map<String, AtomicLong> seen, String name) {
        seen.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();
    }

    /** A packet's short name, like "move_player_pos", or "namespace:name" for mods' packets. */
    public static String name(Packet<?> packet) {
        try {
            var id = packet.type().id();
            return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
        } catch (RuntimeException e) { return packet.getClass().getSimpleName(); }
    }

    private static String clean(String name) {
        String n = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
        return n.startsWith("minecraft:") ? n.substring(10) : n;
    }
}
