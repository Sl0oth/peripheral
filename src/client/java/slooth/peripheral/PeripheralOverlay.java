package slooth.peripheral;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class PeripheralOverlay {

    public static void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("peripheral", "overlay"),
            (ctx, tickCounter) -> {
                String task = PeripheralStateTracker.currentTask;
                if (task == null || task.isEmpty()) return;

                Minecraft client = Minecraft.getInstance();
                if (client.player == null)     return;
                if (client.screen != null)     return; // hide behind any open screen

                String status     = PeripheralStateTracker.status;
                String lastAction = PeripheralStateTracker.lastAction;
                String text       = "[PERIPHERAL] " + status.toUpperCase() + " | " + task;
                if (lastAction != null && !lastAction.isEmpty()) {
                    String trimmed = lastAction.length() > 50
                        ? lastAction.substring(0, 47) + "..."
                        : lastAction;
                    text += " \u2192 " + trimmed;
                }

                int textW = client.font.width(text);
                ctx.fill(4, 4, textW + 12, 16, 0xAA000000);
                ctx.text(client.font, Component.literal(text), 8, 6, 0xFF00FFAA, false);
            });
    }
}
