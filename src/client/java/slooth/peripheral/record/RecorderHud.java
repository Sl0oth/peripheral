package slooth.peripheral.record;

import com.mojang.blaze3d.platform.InputConstants;
import dev.pane.ui.UiHud;
import dev.pane.ui.UiHudEditor;
import dev.pane.ui.UiHudPlacement;
import dev.pane.ui.UiMinecraftTextStyle;
import dev.pane.ui.UiTheme;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import slooth.peripheral.hud.PeripheralHud;

/**
 * The recorder's keys (F7 record, F8 play) and its status in the top-right corner, a PaneLib HUD
 * players can move. It shows only while recording or playing, so nobody records without knowing.
 */
public final class RecorderHud {
    private RecorderHud() {}

    private static final UiHudPlacement DEFAULT = new UiHudPlacement(UiHudPlacement.Anchor.TOP_RIGHT, 4, 4, 1);
    private static final String OWNER = "peripheral", ID = "recorder";
    private static final int RED = 0xFFF0605A, LIME = 0xFFC7EF88, AMBER = 0xFFE7C37E;
    private static UiHud hud;
    private static KeyMapping recordKey, playKey;

    static void register() {
        recordKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peripheral.record", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F7, KeyMapping.Category.GAMEPLAY));
        playKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.peripheral.play_recording", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8, KeyMapping.Category.GAMEPLAY));
        ClientTickEvents.END_CLIENT_TICK.register(RecorderHud::tick);
    }

    private static void tick(Minecraft client) {
        while (recordKey.consumeClick()) {
            if (Recorder.recording()) say(client, Recorder.stopRecording() == null ? Recorder.message() : Recorder.message());
            else { String err = Recorder.startRecording(Recording.nextName(), false); say(client, err != null ? err : "Recording. Press " + recordKey.getTranslatedKeyMessage().getString() + " to stop."); }
        }
        while (playKey.consumeClick()) {
            if (Recorder.playing()) { Recorder.stopPlayback("Stopped playback."); say(client, Recorder.message()); }
            else {
                var names = Recording.names();
                if (names.isEmpty()) say(client, "Nothing recorded yet. Press " + recordKey.getTranslatedKeyMessage().getString() + " to record.");
                else { String err = Recorder.play(names.getFirst(), 0, Recorder.Mode.WORLD); say(client, err != null ? err : "Looping " + names.getFirst() + ". Press " + playKey.getTranslatedKeyMessage().getString() + " to stop."); }
            }
        }
        if (client.font == null) return;
        if (hud == null) {
            hud = new UiHud(1, 13, UiTheme.dark(), ui -> {
                var c = ui.canvas();
                c.roundRect(0xC0101010, 0, 0, ui.width(), ui.height(), 3);
                c.circle(colour(), 7, ui.height() / 2F, 2.5F);
                ui.nativeSlot("text", 13, 2, ui.width() - 17, 9);
            }, overlay -> overlay.text("text", text(), UiMinecraftTextStyle.hud().color(0xFFEDE9E2).shadow(false)));
            hud.setScalesWithGui(true);
            hud.setMotionScale(0);
            hud.setEditable("Recorder status", DEFAULT, moved -> PeripheralHud.recordPlacement(OWNER, ID, moved, DEFAULT));
            hud.attach(Identifier.fromNamespaceAndPath("peripheral", "recorder"));
        }
        boolean show = Recorder.state() != Recorder.State.IDLE || client.gui.screen() instanceof UiHudEditor;
        hud.setVisible(show);
        if (!show) return;
        if (!(client.gui.screen() instanceof UiHudEditor)) {
            UiHudPlacement saved = PeripheralHud.savedPlacement(OWNER, ID);
            hud.setPlacement(saved != null ? saved : DEFAULT);
        }
        String text = text();
        hud.setSize(Math.max(20, Math.min(1024, client.font.width(text) + 17)), 13);
        hud.setValue("text", text);
        hud.setValue("colour", colour());
    }

    /** The record key as Controls shows it, or "" when unbound. */
    public static String recordKeyName() { return recordKey == null || recordKey.isUnbound() ? "" : recordKey.getTranslatedKeyMessage().getString(); }
    /** The play key as Controls shows it, or "" when unbound. */
    public static String playKeyName() { return playKey == null || playKey.isUnbound() ? "" : playKey.getTranslatedKeyMessage().getString(); }

    private static int colour() {
        return Recorder.recording() ? RED : Recorder.returning() ? AMBER : LIME;
    }

    private static String text() {
        if (Recorder.recording()) return "REC  " + Recorder.recordingName() + " · " + Recorder.seconds(Recorder.recordedTicks());
        if (Recorder.playing()) {
            String loops = Recorder.loops() == 0 ? "loop " + (Recorder.loop() + 1) : "loop " + (Recorder.loop() + 1) + " of " + Recorder.loops();
            if (Recorder.returning()) return Recorder.playingName() + " · " + loops + " · getting back on the path";
            int pct = Recorder.playLength() == 0 ? 0 : Recorder.playIndex() * 100 / Recorder.playLength();
            return Recorder.playingName() + " · " + loops + " · " + pct + "%";
        }
        return "Recorder";
    }

    private static void say(Minecraft client, String message) {
        if (client.player != null && message != null) client.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(message));
    }
}
