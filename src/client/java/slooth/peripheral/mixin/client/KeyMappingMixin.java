package slooth.peripheral.mixin.client;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import slooth.peripheral.record.Recorder;

/** Tells the recorder each time the game uses a key press, such as an attack or a hotbar key. */
@Mixin(KeyMapping.class)
public class KeyMappingMixin {
    @Inject(method = "consumeClick", at = @At("RETURN"))
    private void peripheral$recordPress(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) Recorder.consumed((KeyMapping) (Object) this);
    }
}
