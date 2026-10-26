package slooth.peripheral.mixin.client;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import slooth.peripheral.record.Recorder;

/** Tells the recorder when the player closes a container. */
@Mixin(LocalPlayer.class)
public class LocalPlayerMixin {
    @Inject(method = "closeContainer", at = @At("HEAD"))
    private void peripheral$recordClose(CallbackInfo ci) { Recorder.containerClosed(); }
}
