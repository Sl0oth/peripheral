package slooth.peripheral.net;

import dev.pane.ui.UiHud;
import dev.pane.ui.UiHudEditor;
import dev.pane.ui.UiHudPlacement;
import dev.pane.ui.UiMinecraftTextStyle;
import dev.pane.ui.UiTheme;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import slooth.peripheral.hud.PeripheralHud;

/**
 * Says on screen, whenever a script's packet tools are changing traffic, what they are doing, so
 * they're never left on by accident.
 */
public final class PacketHud {
    private PacketHud() {}

    private static final UiHudPlacement DEFAULT = new UiHudPlacement(UiHudPlacement.Anchor.TOP_RIGHT, 4, 20, 1);
    private static final String OWNER = "peripheral", ID = "packets";
    private static UiHud hud;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(PacketHud::tick);
    }

    private static void tick(Minecraft client) {
        PacketTools.refreshAllowed(client);

        if (client.font == null) return;
        if (hud == null) {
            hud = new UiHud(1, 13, UiTheme.dark(), ui -> {
                var c = ui.canvas();
                c.roundRect(0xC0101010, 0, 0, ui.width(), ui.height(), 3);
                c.roundRect(0xFFE7C37E, 4, 4, 5, 5, 1);
                ui.nativeSlot("text", 13, 2, ui.width() - 17, 9);
            }, overlay -> overlay.text("text", text(), UiMinecraftTextStyle.hud().color(0xFFE7C37E).shadow(false)));
            hud.setScalesWithGui(true);
            hud.setMotionScale(0);
            hud.setEditable("Packet tools status", DEFAULT, moved -> PeripheralHud.recordPlacement(OWNER, ID, moved, DEFAULT));
            hud.attach(Identifier.fromNamespaceAndPath("peripheral", "packets"));
        }
        boolean show = PacketTools.active() || client.gui.screen() instanceof UiHudEditor;
        hud.setVisible(show);
        if (!show) return;
        if (!(client.gui.screen() instanceof UiHudEditor)) {
            UiHudPlacement saved = PeripheralHud.savedPlacement(OWNER, ID);
            hud.setPlacement(saved != null ? saved : DEFAULT);
        }
        String text = text();
        hud.setSize(Math.max(20, Math.min(1024, client.font.width(text) + 17)), 13);
        hud.setValue("text", text);
    }

    private static String text() {
        int blocked = PacketTools.blockedNames(PacketTools.Direction.SENT).size() + PacketTools.blockedNames(PacketTools.Direction.RECEIVED).size();
        StringBuilder s = new StringBuilder("Packets");
        if (blocked > 0) s.append(" · blocking ").append(blocked).append(blocked == 1 ? " type" : " types");
        if (PacketTools.holding()) s.append(" · holding ").append(PacketTools.heldCount());
        if (blocked == 0 && !PacketTools.holding()) s.append(" · off");
        return s.toString();
    }
}
