package slooth.peripheral.mixin.client;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Minecraft's own mouse callbacks, so a script's click takes exactly the path a real one does. */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccess {
    @Invoker("onButton") void peripheral$onButton(long window, MouseButtonInfo button, int action);
    @Invoker("onMove") void peripheral$onMove(long window, double x, double y);
    @Invoker("onScroll") void peripheral$onScroll(long window, double x, double y);
}
