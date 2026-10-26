package slooth.peripheral.api;

import slooth.peripheral.PeripheralClient;
import slooth.peripheral.util.PeripheralConfig;
import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Authenticated loopback JSON lines. Event producers never perform socket IO. */
public final class PeripheralSocketServer {
    private static volatile ServerSocket server;
    private static final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    public static volatile String status = "Not started";
    public static void start() {
        if(!PeripheralHttpServer.status.equals("Ready")) { status="Socket disabled because HTTP API startup failed."; return; }
        try {
            LocalApiSecurity.init();
            ServerSocket ss = new ServerSocket(); ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress("127.0.0.1", PeripheralConfig.socketPort)); server = ss;
            Thread.ofPlatform().daemon().name("peripheral-socket-accept").start(() -> {
                while (!ss.isClosed()) try {
                    Socket sock = ss.accept();
                    if (connections.size() >= 32) { sock.close(); continue; }
                    Connection c = new Connection(sock); connections.add(c); c.start();
                } catch (IOException e) { if (!ss.isClosed()) PeripheralClient.LOGGER.warn("[Peripheral] Socket accept failed"); }
            });
            status = "Ready";
        } catch (IOException e) { status = "Socket API unavailable. Check for another Peripheral client and restart."; PeripheralClient.LOGGER.error("[Peripheral] Socket API could not start"); }
    }
    public static void stop() {
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        connections.forEach(Connection::close);
    }
    private static final class Connection implements PeripheralEvents.Listener {
        final Socket socket;
        final BlockingQueue<JsonObject> outgoing = new ArrayBlockingQueue<>(64);
        final AtomicReference<JsonObject> latestTick = new AtomicReference<>();
        volatile LocalApiSecurity.Access access;
        volatile Set<String> events = Set.of();
        volatile int hz;
        long lastTick;
        Connection(Socket socket) throws IOException { this.socket = socket; socket.setSoTimeout(5000); socket.setSendBufferSize(32768); }
        void start() {
            Thread.ofPlatform().daemon().name("peripheral-socket-writer").start(this::writeLoop);
            Thread.ofPlatform().daemon().name("peripheral-socket-reader").start(this::readLoop);
        }
        void close() {
            PeripheralEvents.unsubscribe(this); connections.remove(this);
            try { socket.close(); } catch (IOException ignored) {}
        }
        void readLoop() {
            try (Reader r = new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)) {
                StringBuilder line = new StringBuilder(); int ch;
                while ((ch = r.read()) != -1) {
                    if (ch != '\n') { if (line.length() >= LocalApiSecurity.MAX_BODY) break; line.append((char) ch); continue; }
                    JsonObject msg = JsonParser.parseString(line.toString()).getAsJsonObject(); line.setLength(0);
                    String token = msg.has("token") ? msg.get("token").getAsString() : "";
                    LocalApiSecurity.Access requestAccess = LocalApiSecurity.authenticate(token);
                    if (requestAccess == null || access != null && !access.equals(requestAccess)) break;
                    if (access == null) { access = requestAccess; socket.setSoTimeout(0); PeripheralEvents.subscribe(this); }
                    LocalApiSecurity.as(access, () -> { handle(msg); return null; });
                }
            } catch (Exception ignored) {} finally { close(); }
        }
        void handle(JsonObject msg) {
            String method = msg.get("method").getAsString();
            JsonObject p = msg.has("params") ? LocalApiSecurity.owned(msg.getAsJsonObject("params")) : LocalApiSecurity.owned(new JsonObject());
            if (method.equals("subscribe")) {
                Set<String> types = new HashSet<>();
                if (p.has("events")) p.getAsJsonArray("events").forEach(e -> types.add(e.getAsString()));
                events = Set.copyOf(types); hz = Math.clamp(p.has("state_hz") ? p.get("state_hz").getAsInt() : 0, 0, 20); return;
            }
            if (!msg.has("id")) return;
            JsonObject reply = new JsonObject(); reply.add("id", msg.get("id"));
            try { reply.add("result", PeripheralApi.handle(method,p)); }
            catch (Exception e) { reply.addProperty("error", e instanceof PeripheralApi.ApiException ? e.getMessage() : "invalid_request"); }
            offer(reply);
        }
        void offer(JsonObject obj) { if (!outgoing.offer(obj)) close(); }
        public void onEvent(String type, JsonObject data) {
            if (access == null || !LocalApiSecurity.active(access.owner())) { close(); return; }
            if (!events.contains(type)) return;
            if (type.equals("gui") && data.has("_owner") && !data.get("_owner").getAsString().equals(access.owner())) return;
            JsonObject frame = new JsonObject(); frame.addProperty("event",type); frame.add("data",data);
            if (type.equals("tick")) {
                long now = System.currentTimeMillis();
                if (hz <= 0 || now-lastTick < 1000/hz) return;
                lastTick = now; latestTick.set(frame);
            } else offer(frame);
        }
        void writeLoop() {
            try (Writer w = new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8)) {
                while (!socket.isClosed()) {
                    JsonObject o = outgoing.poll(50, TimeUnit.MILLISECONDS);
                    if (o == null) o = latestTick.getAndSet(null);
                    if (o != null) { w.write(o.toString()); w.write('\n'); w.flush(); }
                }
            } catch (Exception ignored) {} finally { close(); }
        }
    }
}
