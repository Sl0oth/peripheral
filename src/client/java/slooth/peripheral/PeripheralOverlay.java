package slooth.peripheral;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

public class PeripheralOverlay {

    public static void register() {
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            String task = PeripheralStateTracker.currentTask;
            if (task == null || task.isEmpty()) return;

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null)        return;
            if (client.currentScreen != null) return; // hide behind any open screen

            String status     = PeripheralStateTracker.status;
            String lastAction = PeripheralStateTracker.lastAction;
            String text       = "[PERIPHERAL] " + status.toUpperCase() + " | " + task;
            if (lastAction != null && !lastAction.isEmpty()) {
                String trimmed = lastAction.length() > 50
                    ? lastAction.substring(0, 47) + "..."
                    : lastAction;
                text += " → " + trimmed;
            }

            int textW = client.textRenderer.getWidth(text);
            ctx.fill(4, 4, textW + 12, 16, 0xAA000000);
            ctx.drawText(client.textRenderer, Text.literal(text), 8, 6, 0xFF00FFAA, false);
        });
    }
}
