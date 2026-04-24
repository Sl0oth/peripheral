package slooth.peripheral;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * Wraps the player's keyboard Input so PeripheralNavigator can inject
 * movement each tick without mixins. The original Input is still ticked so
 * all keybinding logic stays alive. When overriding is false, control passes
 * back to the keyboard transparently.
 *
 * 1.21.11: Input.tick() takes no arguments; movement is expressed via
 * the PlayerInput record (playerInput field) and Vec2f movementVector.
 */
public class NavigatorInput extends ClientInput {

    private final ClientInput original;

    // Navigator (pathfinder) overrides
    volatile float   navForward = 0f;
    volatile boolean navJump    = false;
    volatile boolean overriding = false;

    // Script action overrides (jump/sprint/sneak/move)
    volatile float   scriptForward   = 0f;
    volatile float   scriptStrafe    = 0f;
    volatile boolean scriptSprint    = false;
    volatile boolean scriptSneak     = false;
    volatile boolean scriptJump      = false;   // consumed after one tick
    volatile boolean scriptOverriding = false;

    public NavigatorInput(ClientInput original) {
        this.original = original;
    }

    @Override
    public void tick() {
        original.tick();

        if (overriding) {
            // Navigator takes full control
            this.keyPresses = new Input(
                navForward > 0,   // forward
                false,            // backward
                false,            // left
                false,            // right
                navJump,          // jump
                false,            // sneak
                true              // sprint
            );
            this.moveVector = new Vec2(0f, navForward);
        } else if (scriptOverriding) {
            // Script action overrides keyboard
            boolean fwd  = scriptForward > 0;
            boolean back = scriptForward < 0;
            boolean right = scriptStrafe > 0;
            boolean left  = scriptStrafe < 0;
            boolean jump  = scriptJump;
            scriptJump = false;  // consume jump after one tick
            this.keyPresses = new Input(fwd, back, left, right, jump, scriptSneak, scriptSprint);
            this.moveVector = new Vec2(scriptStrafe, scriptForward);
        } else {
            this.keyPresses = original.keyPresses;
            this.moveVector = original.moveVector;
        }
    }

    public ClientInput getOriginal() { return original; }
}
