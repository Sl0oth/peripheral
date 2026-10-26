package slooth.peripheral.overlay;

import slooth.peripheral.hud.PeripheralHud;
import slooth.peripheral.util.PeripheralStateTracker;

import dev.pane.ui.UiHud;
import dev.pane.ui.UiHudPlacement;
import dev.pane.ui.UiMinecraftTextStyle;
import dev.pane.ui.UiTheme;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** The external agent's status line in the top-left corner, as a PaneLib HUD players can move. */
public class PeripheralOverlay {

    private static final UiHudPlacement DEFAULT = new UiHudPlacement(UiHudPlacement.Anchor.TOP_LEFT, 4, 4, 1);
    /** Where a moved status line is remembered, beside the script HUD layouts. */
    private static final String OWNER = "peripheral", ID = "agent";
    private static UiHud hud;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(PeripheralOverlay::tick);
    }

    private static void tick(Minecraft client) {
        if (client.font == null) return;
        if (hud == null) {
            hud = new UiHud(1, 12, UiTheme.dark(), ui -> {
                ui.canvas().fill(0xAA000000, 0, 0, ui.width(), ui.height());
                ui.nativeSlot("text", 4, 2, ui.width() - 8, 9);
            }, overlay -> overlay.text("text", text(), UiMinecraftTextStyle.hud().color(0xFF00FFAA).shadow(false)));
            hud.setScalesWithGui(true);
            hud.setMotionScale(0);
            hud.setEditable("Agent status", DEFAULT, moved -> PeripheralHud.recordPlacement(OWNER, ID, moved, DEFAULT));
            hud.attach(Identifier.fromNamespaceAndPath("peripheral", "agent"));
        }
        String task = PeripheralStateTracker.currentTask;
        boolean show = task != null && !task.isEmpty();
        hud.setVisible(show);
        if (!show) return;
        if (!(client.gui.screen() instanceof dev.pane.ui.UiHudEditor)) {
            UiHudPlacement saved = PeripheralHud.savedPlacement(OWNER, ID);
            hud.setPlacement(saved != null ? saved : DEFAULT);
        }
        String text = text();
        hud.setSize(Math.max(1, Math.min(1024, client.font.width(text) + 8)), 12);
        hud.setValue("text", text);
    }

    private static String text() {
        String status     = PeripheralStateTracker.status;
        String lastAction = PeripheralStateTracker.lastAction;
        String text       = "[PERIPHERAL] " + status.toUpperCase() + " | " + PeripheralStateTracker.currentTask;
        if (lastAction != null && !lastAction.isEmpty()) {
            String trimmed = lastAction.length() > 50
                ? lastAction.substring(0, 47) + "..."
                : lastAction;
            text += " → " + trimmed;
        }
        return text;
    }
}
