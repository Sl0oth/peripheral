package slooth.peripheral.record;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * One recorded run, tick by tick. Each frame keeps where the player stood when the tick ended, where
 * they looked and what they pressed while it ran, so playback can both repeat the inputs and steer
 * back onto the path when something pushes the player off it.
 */
public final class Recording {

    /** Key bits in {@link Frame#keys}. */
    public static final int FORWARD = 1, BACKWARD = 2, LEFT = 4, RIGHT = 8, JUMP = 16, SNEAK = 32, SPRINT = 64,
        ATTACK = 128, USE = 256;

    /**
     * One client tick. {@code x, y, z} is where the player was when the tick ended; {@code yaw},
     * {@code pitch}, the keys, slot and move vector are what the tick ran with. {@code events} are
     * the presses consumed during the tick, like "attack" or "hotbar3", and container clicks, like
     * "container:12:0:PICKUP".
     */
    public record Frame(double x, double y, double z, float yaw, float pitch, float moveX, float moveZ,
                        int keys, int slot, boolean onGround, List<String> events) {
        public boolean has(int key) { return (keys & key) != 0; }
    }

    public static final Path DIR = Paths.get("config", "peripheral", "recordings");

    public final String name;
    public final String dimension;
    /** Where the player stood, and looked, before the first tick ran. */
    public final double startX, startY, startZ;
    public final float startYaw, startPitch;
    public final List<Frame> frames;

    public Recording(String name, String dimension, double startX, double startY, double startZ,
                     float startYaw, float startPitch, List<Frame> frames) {
        this.name = name;
        this.dimension = dimension == null ? "" : dimension;
        this.startX = startX; this.startY = startY; this.startZ = startZ;
        this.startYaw = startYaw; this.startPitch = startPitch;
        this.frames = List.copyOf(frames);
    }

    public int ticks() { return frames.size(); }


    /** Horizontal distance walked, in blocks. */
    public double distance() {
        double d = 0;
        for (int i = 1; i < frames.size(); i++) {
            Frame a = frames.get(i - 1), b = frames.get(i);
            d += Math.hypot(b.x() - a.x(), b.z() - a.z());
        }
        return d;
    }

    /** Presses and container clicks in the whole run. */
    public int actions() { return frames.stream().mapToInt(f -> f.events().size()).sum(); }

    /** Frames {@code from} (inclusive) to {@code to} (exclusive), as a new recording with the same name. */
    public Recording trimmed(int from, int to) {
        from = Math.clamp(from, 0, frames.size() - 1);
        to = Math.clamp(to, from + 1, frames.size());
        // The new start is where the player stood before the first kept tick.
        Frame before = from == 0 ? null : frames.get(from - 1);
        return before == null ? new Recording(name, dimension, startX, startY, startZ, startYaw, startPitch, frames.subList(from, to))
            : new Recording(name, dimension, before.x(), before.y(), before.z(), frames.get(from).yaw(), frames.get(from).pitch(), frames.subList(from, to));
    }

    /** This recording followed by {@code more}, for resuming a recording. */
    public Recording append(List<Frame> more) {
        List<Frame> all = new ArrayList<>(frames);
        all.addAll(more);
        return new Recording(name, dimension, startX, startY, startZ, startYaw, startPitch, all);
    }

    public Recording renamed(String newName) { return new Recording(newName, dimension, startX, startY, startZ, startYaw, startPitch, frames); }

    // ── Files ───────────────────────────────────────────────────────────────

    /** A recording name safe to use as a file name: letters, digits, dash, underscore and space. */
    public static String cleanName(String name) {
        String clean = name == null ? "" : name.trim().replaceAll("[^A-Za-z0-9 _-]", "").replaceAll("\\s+", " ");
        return clean.isEmpty() ? "recording" : clean.length() > 48 ? clean.substring(0, 48).trim() : clean;
    }

    public static Path file(String name) { return DIR.resolve(cleanName(name) + ".json"); }

    public void save() throws IOException {
        Files.createDirectories(DIR);
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        root.addProperty("name", name);
        root.addProperty("dimension", dimension);
        JsonArray start = new JsonArray();
        start.add(startX); start.add(startY); start.add(startZ); start.add(startYaw); start.add(startPitch);
        root.add("start", start);
        JsonArray list = new JsonArray();
        for (Frame f : frames) {
            JsonArray a = new JsonArray();
            a.add(f.x()); a.add(f.y()); a.add(f.z()); a.add(f.yaw()); a.add(f.pitch());
            a.add(f.moveX()); a.add(f.moveZ()); a.add(f.keys()); a.add(f.slot()); a.add(f.onGround() ? 1 : 0);
            if (!f.events().isEmpty()) { JsonArray e = new JsonArray(); f.events().forEach(e::add); a.add(e); }
            list.add(a);
        }
        root.add("frames", list);
        // Write beside, then move, so a crash mid-save never leaves half a recording.
        Path target = file(name), temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, root.toString(), StandardCharsets.UTF_8);
        Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    public static Recording load(String name) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(file(name), StandardCharsets.UTF_8)).getAsJsonObject();
        List<Frame> frames = new ArrayList<>();
        for (var el : root.getAsJsonArray("frames")) {
            JsonArray a = el.getAsJsonArray();
            List<String> events = new ArrayList<>();
            if (a.size() > 10) for (var e : a.get(10).getAsJsonArray()) events.add(e.getAsString());
            frames.add(new Frame(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(),
                a.get(3).getAsFloat(), a.get(4).getAsFloat(), a.get(5).getAsFloat(), a.get(6).getAsFloat(),
                a.get(7).getAsInt(), a.get(8).getAsInt(), a.get(9).getAsInt() != 0, List.copyOf(events)));
        }
        if (frames.isEmpty()) throw new IOException("Recording has no frames");
        String stored = root.has("name") ? root.get("name").getAsString() : name;
        JsonArray start = root.getAsJsonArray("start");
        return new Recording(stored, root.has("dimension") ? root.get("dimension").getAsString() : "",
            start.get(0).getAsDouble(), start.get(1).getAsDouble(), start.get(2).getAsDouble(),
            start.get(3).getAsFloat(), start.get(4).getAsFloat(), frames);
    }

    /** "Recording 1", "Recording 2"… the first name not already saved. */
    public static String nextName() {
        for (int i = 1; ; i++) if (!exists("Recording " + i)) return "Recording " + i;
    }

    public static boolean exists(String name) { return Files.exists(file(name)); }

    public static void delete(String name) throws IOException { Files.deleteIfExists(file(name)); }

    /** Saved recording names, newest first. */
    public static List<String> names() {
        if (!Files.isDirectory(DIR)) return List.of();
        try (Stream<Path> files = Files.list(DIR)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                .sorted((a, b) -> {
                    try { return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)); }
                    catch (IOException e) { return 0; }
                })
                .map(p -> { String n = p.getFileName().toString(); return n.substring(0, n.length() - 5); })
                .toList();
        } catch (IOException e) { return List.of(); }
    }
}
