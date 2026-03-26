package slooth.peripheral;

import net.minecraft.client.input.Input;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;

/**
 * Wraps the player's keyboard Input so PeripheralNavigator can inject
 * movement each tick without mixins. The original Input is still ticked so
 * all keybinding logic stays alive. When overriding is false, control passes
 * back to the keyboard transparently.
 *
 * 1.21.11: Input.tick() takes no arguments; movement is expressed via
 * the PlayerInput record (playerInput field) and Vec2f movementVector.
 */
public class NavigatorInput extends Input {

    private final Input original;

    volatile float   navForward = 0f;
    volatile boolean navJump    = false;
    volatile boolean overriding = false;

    public NavigatorInput(Input original) {
        this.original = original;
    }

    @Override
    public void tick() {
        original.tick();

        if (overriding) {
            // Build a PlayerInput that walks forward (and optionally jumps).
            // Fields: forward, backward, left, right, jump, sneak, sprint
            this.playerInput = new PlayerInput(
                navForward > 0,   // forward
                false,            // backward
                false,            // left
                false,            // right
                navJump,          // jump
                false,            // sneak
                true              // sprint
            );
            // movementVector: x = strafe, y = forward
            this.movementVector = new Vec2f(0f, navForward);
        } else {
            this.playerInput    = original.playerInput;
            this.movementVector = original.movementVector;
        }
    }

    public Input getOriginal() { return original; }
}
