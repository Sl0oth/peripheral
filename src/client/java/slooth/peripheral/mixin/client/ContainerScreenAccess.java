package slooth.peripheral.mixin.client;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a container screen draws its slots, so scripts can click a slot by number. */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccess {
    @Accessor("leftPos") int peripheral$leftPos();
    @Accessor("topPos") int peripheral$topPos();
}
