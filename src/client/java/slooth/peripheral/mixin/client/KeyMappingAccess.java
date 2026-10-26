package slooth.peripheral.mixin.client;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Pending presses of a key, so the recorder can press a key exactly the way a real key press does. */
@Mixin(KeyMapping.class)
public interface KeyMappingAccess {
    @Accessor("clickCount") int peripheral$getClickCount();
    @Accessor("clickCount") void peripheral$setClickCount(int count);
}
