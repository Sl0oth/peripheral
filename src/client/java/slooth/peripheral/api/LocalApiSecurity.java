package slooth.peripheral.api;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import slooth.peripheral.PeripheralClient;

/** Local bearer capabilities. These protect the API, not the OS from trusted Python code. */
public final class LocalApiSecurity {
    public record Access(String owner, boolean control) {}
    private static final Map<String, Access> TOKENS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Access> CURRENT = new ThreadLocal<>();
    private static final Set<String> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final Set<String> REDACT = ConcurrentHashMap.newKeySet();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Path TOKEN_FILE = Path.of("config", "peripheral", "local-api.token");
    public static final int MAX_BODY = 1_048_576;
    private static String controlToken;
    private LocalApiSecurity() {}
    private static String randomToken() { byte[] b = new byte[32]; RANDOM.nextBytes(b); return HexFormat.of().formatHex(b); }
    public static synchronized void init() throws IOException {
        if (controlToken != null) return;
        Files.createDirectories(TOKEN_FILE.getParent());
        if (Files.isSymbolicLink(TOKEN_FILE)) throw new IOException("Remove the linked local API token file before starting Peripheral");
        controlToken = randomToken(); REDACT.add(controlToken);
        Access external=new Access("external:"+UUID.randomUUID(),true);
        TOKENS.put(controlToken,external); ACTIVE.add(external.owner());
        // Create a private temporary file before writing, then replace the previous session's token.
        Path tmp;
        try { tmp = Files.createTempFile(TOKEN_FILE.getParent(), ".local-api-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
        catch (UnsupportedOperationException e) { tmp = Files.createTempFile(TOKEN_FILE.getParent(), ".local-api-", ".tmp"); }
        try {
            Files.writeString(tmp, controlToken, StandardCharsets.UTF_8);
            Files.move(tmp, TOKEN_FILE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }
    public static String issue(String owner) { String t = randomToken(); REDACT.add(t); TOKENS.put(t, new Access(owner, false)); ACTIVE.add(owner); return t; }
    public static void redactToken(String token) { REDACT.add(token); }
    public static String redact(String text) { for(String token:REDACT) text=text.replace(token,"[redacted]"); return text; }
    public static void revoke(String token) { Access old=TOKENS.remove(token); if(old!=null)ACTIVE.remove(old.owner()); }
    public static Access authenticate(String token) { return token == null ? null : TOKENS.get(token); }
    public static String owner() { Access a = CURRENT.get(); return a == null ? "local-ui" : a.owner(); }
    public static boolean active(String owner) { return owner.equals("local-ui") || owner.equals("mcp") && PeripheralMcp.active() || ACTIVE.contains(owner); }
    public static boolean control() { Access a = CURRENT.get(); return a == null || a.control(); }
    public static <T> T as(Access access, Supplier<T> action) {
        Access old = CURRENT.get(); CURRENT.set(access);
        try { return action.get(); } finally { if (old == null) CURRENT.remove(); else CURRENT.set(old); }
    }
    public static JsonObject owned(JsonObject params) { JsonObject p = params.deepCopy(); p.addProperty("_script", owner()); return p; }
    public static void stop() {
        TOKENS.clear(); ACTIVE.clear(); controlToken = null;
        try { Files.deleteIfExists(TOKEN_FILE); } catch (IOException ignored) {}
    }
    public static HttpHandler protect(String path, HttpHandler next) {
        return ex -> {
            try {
                String host = ex.getRequestHeaders().getFirst("Host");
                if (!ex.getRemoteAddress().getAddress().isLoopbackAddress() || ex.getRequestHeaders().containsKey("Origin")
                        || !("127.0.0.1:" + PeripheralHttpServer.PORT).equals(host) && !("localhost:" + PeripheralHttpServer.PORT).equals(host)) {
                    reply(ex, 403, "local_requests_only"); return;
                }
                if (!ex.getRequestURI().getPath().equals(path)) { reply(ex,404,"not_found"); return; }
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                Access access = authenticate(auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : null);
                if (access == null) { reply(ex,401,"authorization_required"); return; }
                String method = ex.getRequestMethod();
                if (!method.equals("GET") && !method.equals("POST")) { reply(ex,405,"method_not_allowed"); return; }
                if (method.equals("POST")) {
                    String type = ex.getRequestHeaders().getFirst("Content-Type");
                    if (type == null || !type.toLowerCase(Locale.ROOT).split(";",2)[0].trim().equals("application/json")) {
                        reply(ex,415,"json_required"); return;
                    }
                }
                CURRENT.set(access);
                next.handle(ex);
            } catch (IllegalArgumentException e) { reply(ex,400,"invalid_request"); }
              catch (PeripheralApi.ApiException e) { reply(ex,400,e.getMessage()); }
              catch (Exception e) { PeripheralClient.LOGGER.warn("[Peripheral] Local API request failed ({})", e.getClass().getSimpleName()); reply(ex,500,"request_failed"); }
            finally { CURRENT.remove(); ex.close(); }
        };
    }
    private static void reply(HttpExchange ex, int code, String error) throws IOException {
        JsonObject o = new JsonObject(); o.addProperty("error",error);
        byte[] b = o.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type","application/json"); ex.sendResponseHeaders(code,b.length);
        ex.getResponseBody().write(b);
    }
}
