package slooth.peripheral.mixin.client;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import slooth.peripheral.record.Recorder;

/** Tells the recorder about every container slot click, however it was made. */
@Mixin(MultiPlayerGameMode.class)
public class GameModeMixin {
    @Inject(method = "handleContainerInput", at = @At("HEAD"))
    private void peripheral$recordContainerClick(int containerId, int slot, int button, ContainerInput input, Player player, CallbackInfo ci) {
        if (player.containerMenu != null && player.containerMenu.containerId == containerId)
            Recorder.containerClicked(player.containerMenu, slot, button, input);
    }
}
