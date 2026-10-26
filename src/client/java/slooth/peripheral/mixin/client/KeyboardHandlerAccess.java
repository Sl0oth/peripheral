package slooth.peripheral.mixin.client;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Minecraft's own keyboard callbacks, so a script's key press takes exactly the path a real one does. */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerAccess {
    @Invoker("keyPress") void peripheral$keyPress(long window, int action, KeyEvent event);
    @Invoker("charTyped") void peripheral$charTyped(long window, CharacterEvent event);
}
