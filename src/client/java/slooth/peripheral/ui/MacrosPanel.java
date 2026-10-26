package slooth.peripheral.ui;

import dev.pane.ui.*;
import java.awt.Font;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import slooth.peripheral.record.Recorder;
import slooth.peripheral.record.Recording;
import slooth.peripheral.script.ScriptPaths;

import static slooth.peripheral.ui.PeripheralUi.*;

/**
 * The Macros tab: record what you do and play it back. A card on top records or stops, with the
 * keys that do the same in game; each saved macro has Play, Loop and a More menu (rename, trim,
 * record more, make a script, delete). Playing closes the panel so you can watch it run.
 *
 * <p>Rename, trim and delete open sheets owned by {@link PeripheralScreen}; this panel emits them as
 * actions ({@code macro-rename:}, {@code macro-trim:}, {@code macro-del:}) and handles the rest itself.
 */
final class MacrosPanel {
    /** What a list row shows, read off the render thread. */
    record Info(String name, int ticks, double distance, int actions, String dimension) {}

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "peripheral-macro-list"); t.setDaemon(true); return t;
    });
    private static boolean s_here;

    private final PeripheralScreen screen;
    private volatile List<Info> list = List.of();
    private final Map<String, Info> cache = new ConcurrentHashMap<>();
    private volatile boolean scanned;
    private String note = "";
    private int noteTicks, tick;
    private String signature = "";

    MacrosPanel(PeripheralScreen screen) { this.screen = screen; }

    /** Reads the saved macros again, off the render thread. */
    void refresh() {
        IO.execute(() -> {
            List<Info> next = new ArrayList<>();
            for (String name : Recording.names()) {
                try {
                    long modified = Files.getLastModifiedTime(Recording.file(name)).toMillis();
                    Info info = cache.get(name + "@" + modified);
                    if (info == null) {
                        Recording r = Recording.load(name);
                        info = new Info(name, r.ticks(), r.distance(), r.actions(), r.dimension);
                        cache.put(name + "@" + modified, info);
                    }
                    next.add(info);
                } catch (Exception ignored) {}
            }
            list = List.copyOf(next);
            scanned = true;
            Minecraft.getInstance().execute(screen::invalidate);
        });
    }

    void tick() {
        tick++;
        if (noteTicks > 0 && --noteTicks == 0) screen.invalidate();
        if (tick % 40 == 0) refresh();
        String sig = Recorder.state() + ":" + Recorder.recordedTicks() / 10 + ":" + Recorder.playIndex() / 10 + ":" + Recorder.loop()
            + ":" + Recorder.returning() + ":" + list.size();
        if (!sig.equals(signature)) { signature = sig; screen.invalidate(); }
    }

    void say(String message) { note = message; noteTicks = 100; screen.invalidate(); }

    /** Handles this tab's actions. Returns false for actions it leaves to the screen. */
    boolean handle(String action) {
        int colon = action.indexOf(':');
        String verb = colon < 0 ? action : action.substring(0, colon), arg = colon < 0 ? "" : action.substring(colon + 1);
        Minecraft mc = Minecraft.getInstance();
        switch (verb) {
            case "macro-record" -> start(Recording.nextName(), false);
            case "macro-resume" -> start(arg, true);
            case "macro-stop" -> {
                if (Recorder.recording()) { Recorder.stopRecording(); refresh(); }
                else Recorder.stopPlayback("Stopped playback.");
                say(Recorder.message());
            }
            case "macro-play", "macro-loop" -> {
                String err = Recorder.play(arg, verb.equals("macro-loop") ? 0 : 1, s_here ? Recorder.Mode.RELATIVE : Recorder.Mode.WORLD);
                if (err != null) say(err);
                else mc.gui.setScreen(null);
            }
            case "macro-here" -> s_here = arg.equals("1");
            case "macro-script" -> makeScript(arg);
            default -> { return false; }
        }
        screen.invalidate();
        return true;
    }

    private void start(String name, boolean resume) {
        String err = Recorder.startRecording(name, resume);
        if (err != null) { say(err); return; }
        // Back to the game so the recording sees what you do.
        Minecraft.getInstance().gui.setScreen(null);
    }

    /** Writes scripts/macros/NAME.py, which loops the macro until the script is stopped. */
    private void makeScript(String name) {
        String file = "macros/" + name.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "") + ".py";
        try {
            var path = ScriptPaths.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "from mc import *\n\n"
                + "# Plays the macro \"" + name + "\" over and over. Stop the script to stop it.\n"
                + "# loops=3 plays it three times; relative=True starts it where you stand.\n"
                + "play_recording(" + pyString(name) + ", loops=0, relative=" + (s_here ? "True" : "False") + ")\n");
            say("Made scripts/" + file + ". Run it from the Scripts tab.");
        } catch (Exception e) {
            say("Couldn't make the script: " + e.getMessage());
        }
    }

    private static String pyString(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    // ── Painting ────────────────────────────────────────────────────────────

    void paint(UiContext ui, float x, float y, float w, float h) {
        var c = ui.canvas();
        float cardH = 74;
        recordCard(ui, x, y, w, cardH);
        float cy = y + cardH + 12;

        // Where playback starts, for every macro.
        mid(c, "Play", x, cy + 14, 11, MUTED, Font.PLAIN, SANS);
        float tx = x + c.textWidth("Play", 11, Font.PLAIN, SANS) + 10;
        String[] labels = {"where it was recorded", "from where I stand"};
        for (int i = 0; i < 2; i++) {
            boolean on = (i == 1) == s_here;
            float bw = ui.buttonWidth(labels[i], null, CHIP);
            ui.button("macro-here:" + i, labels[i], null, "macro-here:" + i, tx, cy, bw, 28, on ? CHIP_ON : CHIP, true);
            tx += bw + 4;
        }
        ui.tooltip("macro-here:1", "Shifts the whole macro by whole blocks so it starts on the block you're on");
        cy += 38;

        float footH = 20, footY = y + h - footH;
        String rec = slooth.peripheral.record.RecorderHud.recordKeyName(), play = slooth.peripheral.record.RecorderHud.playKeyName();
        String hint = noteTicks > 0 ? note : (rec.isEmpty() ? "" : rec + " records and stops") + (play.isEmpty() ? "" : " · " + play + " loops the newest macro")
            + " · change keys in Options › Controls";
        mid(c, c.ellipsize(hint, w, 10.5F, Font.PLAIN, SANS), x, footY + footH / 2, 10.5F, noteTicks > 0 ? LIME : FAINT, Font.PLAIN, SANS);

        UiRect view = new UiRect(x, cy, w, Math.max(40, footY - 8 - cy));
        panel(c, view.x(), view.y(), view.width(), view.height());
        List<Info> items = list;
        if (items.isEmpty()) {
            centered(c, scanned ? "No macros yet" : "Reading macros…", scanned
                ? "Press Record, then walk, look, click and use items. Press " + (rec.isEmpty() ? "Stop" : rec) + " when you're done. It plays back exactly, as many times as you like."
                : "", view);
            return;
        }
        float rowH = 52, gap = 6, pad = 8;
        float contentH = pad * 2 + items.size() * (rowH + gap) - gap;
        ui.scroll("macros", new UiRect(view.x() + 1, view.y() + 1, view.width() - 2, view.height() - 2), contentH, s -> {
            float ry = view.y() + pad;
            for (Info info : items) { row(s, info, view.x() + pad, ry, view.width() - pad * 2, rowH); ry += rowH + gap; }
        });
    }

    private void recordCard(UiContext ui, float x, float y, float w, float h) {
        var c = ui.canvas();
        boolean recording = Recorder.recording(), playing = Recorder.playing();
        c.roundRect(recording ? RED_TINT : playing ? LIME_TINT : PANEL, x, y, w, h, 4);
        c.strokeRoundRect(recording ? 0xFF6A3A36 : playing ? 0xFF4A5E33 : LINE, 1, x, y, w, h, 4);
        float bw, bx;
        String title, body;
        if (recording) {
            title = "Recording " + Recorder.recordingName() + " · " + Recorder.seconds(Recorder.recordedTicks());
            body = "Do what you want repeated, then stop. Positions are kept, so it plays back exactly.";
            bw = ui.buttonWidth("Stop", UiIcon.STOP, DANGER); bx = x + w - 14 - bw;
            ui.button("macro-stop", "Stop", UiIcon.STOP, "macro-stop", bx, y + (h - 32) / 2, bw, 32, DANGER, true);
        } else if (playing) {
            String loops = Recorder.loops() == 0 ? "loop " + (Recorder.loop() + 1) : "loop " + (Recorder.loop() + 1) + " of " + Recorder.loops();
            title = "Playing " + Recorder.playingName() + " · " + loops;
            body = Recorder.returning() ? "Walking back to the path…" : Recorder.playLength() == 0 ? "" : Recorder.playIndex() * 100 / Recorder.playLength() + "% through";
            bw = ui.buttonWidth("Stop", UiIcon.STOP, DANGER); bx = x + w - 14 - bw;
            ui.button("macro-stop", "Stop", UiIcon.STOP, "macro-stop", bx, y + (h - 32) / 2, bw, 32, DANGER, true);
        } else {
            title = "Record a macro";
            body = "Walk, look, click and use items, then stop. It plays back exactly, as many times as you like.";
            bw = ui.buttonWidth("Record", UiIcon.RECORD, PRIMARY); bx = x + w - 14 - bw;
            ui.button("macro-record", "Record", UiIcon.RECORD, "macro-record", bx, y + (h - 32) / 2, bw, 32, PRIMARY, true);
            ui.tooltip("macro-record", "Closes this panel and starts recording");
        }
        UiIcon.RECORD.draw(c, recording ? RED : playing ? LIME : MUTED, x + 14, y + 16, 16, 1.4F);
        float tx = x + 40, room = Math.max(40, bx - 12 - tx);
        c.text(c.ellipsize(title, room, 13.5F, Font.BOLD, SANS), tx, y + 15, 13.5F, TEXT, Font.BOLD, SANS);
        para(c, c.ellipsize(body, room * 2, 11, Font.PLAIN, SANS), tx, y + 36, room, 11, 15, MUTED);
    }

    private void row(UiContext s, Info info, float x, float y, float w, float h) {
        var c = s.canvas();
        boolean busy = Recorder.state() != Recorder.State.IDLE, current = info.name().equals(Recorder.playingName());
        c.roundRect(current ? LIME_TINT : 0xFF242424, x, y, w, h, 4);
        float right = x + w - 10, by = y + (h - 30) / 2;
        float moreW = s.menuButtonWidth("More", null, SECONDARY);
        s.menuButton("macro-more:" + info.name(), "More", null, right - moreW, by, moreW, 30, SECONDARY, !busy, List.of(
            new UiMenuItem("rename", "Rename…", "macro-rename:" + info.name()),
            new UiMenuItem("trim", "Trim…", "macro-trim:" + info.name()),
            new UiMenuItem("resume", "Record more on the end", "macro-resume:" + info.name()),
            new UiMenuItem("script", "Make a script that loops it", "macro-script:" + info.name()),
            new UiMenuItem("delete", "Delete…", "macro-del:" + info.name())));
        right -= moreW + 6;
        float loopW = s.buttonWidth("Loop", UiIcon.REFRESH, SECONDARY);
        s.button("macro-loop:" + info.name(), "Loop", UiIcon.REFRESH, "macro-loop:" + info.name(), right - loopW, by, loopW, 30, SECONDARY, !busy);
        s.tooltip("macro-loop:" + info.name(), "Plays it over and over until you press Stop or " + slooth.peripheral.record.RecorderHud.playKeyName());
        right -= loopW + 6;
        float playW = s.buttonWidth("Play", UiIcon.PLAY, PRIMARY);
        s.button("macro-play:" + info.name(), "Play", UiIcon.PLAY, "macro-play:" + info.name(), right - playW, by, playW, 30, PRIMARY, !busy);
        right -= playW + 10;
        float tx = x + 12, room = Math.max(40, right - tx);
        mid(c, c.ellipsize(info.name(), room, 13, Font.BOLD, SANS), tx, y + 18, 13, current ? LIME : TEXT, Font.BOLD, SANS);
        String meta = Recorder.seconds(info.ticks()) + " · " + String.format("%.0f", info.distance()) + (Math.round(info.distance()) == 1 ? " block" : " blocks")
            + " · " + info.actions() + (info.actions() == 1 ? " click" : " clicks") + dimensionLabel(info.dimension());
        mid(c, c.ellipsize(meta, room, 11, Font.PLAIN, SANS), tx, y + 36, 11, MUTED, Font.PLAIN, SANS);
    }

    private static String dimensionLabel(String dimension) {
        return switch (dimension) {
            case "minecraft:the_nether" -> " · Nether";
            case "minecraft:the_end" -> " · End";
            default -> "";
        };
    }
}
