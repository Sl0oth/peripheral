package slooth.peripheral.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.pane.ui.UiScreen;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;
import slooth.peripheral.mixin.client.ContainerScreenAccess;
import slooth.peripheral.mixin.client.KeyboardHandlerAccess;
import slooth.peripheral.mixin.client.MouseHandlerAccess;

/**
 * Drives screens the way a player does. Clicks, moves, scrolls, keys and typed text go into
 * Minecraft's own mouse and keyboard callbacks, the ones the operating system calls, so every screen
 * and every mod sees exactly what a real click or key press produces. Coordinates are GUI pixels,
 * the ones {@link #describe()} reports.
 *
 * <p>Client thread only.
 */
public final class GuiControl {
    private GuiControl() {}

    /** One thing on screen a script can click. */
    public record Control(String label, String kind, double x, double y, double width, double height, boolean active) {
        double centerX() { return x + width / 2; }
        double centerY() { return y + height / 2; }
    }

    // ── Reading the screen ──────────────────────────────────────────────────

    /** The open screen, its buttons and fields, and container slots, all in GUI pixels. */
    public static JsonObject describe() {
        Minecraft c = Minecraft.getInstance();
        Screen screen = c.gui.screen();
        JsonObject out = new JsonObject();
        out.addProperty("open", screen != null);
        out.addProperty("width", c.getWindow().getGuiScaledWidth());
        out.addProperty("height", c.getWindow().getGuiScaledHeight());
        double[] mouse = mouse();
        out.addProperty("mouse_x", mouse[0]);
        out.addProperty("mouse_y", mouse[1]);
        if (screen == null) return out;
        out.addProperty("title", screen.getTitle().getString());
        out.addProperty("kind", screen.getClass().getSimpleName());
        JsonArray list = new JsonArray();
        for (Control control : controls(screen)) {
            JsonObject o = new JsonObject();
            o.addProperty("label", control.label());
            o.addProperty("kind", control.kind());
            o.addProperty("x", control.x()); o.addProperty("y", control.y());
            o.addProperty("width", control.width()); o.addProperty("height", control.height());
            o.addProperty("active", control.active());
            list.add(o);
        }
        out.add("controls", list);
        if (screen instanceof AbstractContainerScreen<?> container) {
            JsonArray slots = new JsonArray();
            var access = (ContainerScreenAccess) container;
            List<Slot> menuSlots = container.getMenu().slots;
            for (int i = 0; i < menuSlots.size(); i++) {
                Slot slot = menuSlots.get(i);
                JsonObject o = new JsonObject();
                o.addProperty("slot", i);
                o.addProperty("x", access.peripheral$leftPos() + slot.x);
                o.addProperty("y", access.peripheral$topPos() + slot.y);
                var stack = slot.getItem();
                o.addProperty("item", stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                o.addProperty("count", stack.getCount());
                slots.add(o);
            }
            out.add("slots", slots);
        }
        return out;
    }

    /** Buttons, fields and other widgets on {@code screen}, including PaneLib controls. */
    public static List<Control> controls(Screen screen) {
        List<Control> out = new ArrayList<>();
        if (screen instanceof UiScreen pane) {
            float scale = Math.max(.0001F, pane.layoutScale());
            for (var control : pane.controls()) {
                var b = control.bounds();
                String label = control.narration() != null && control.narration().label() != null && !control.narration().label().isBlank()
                    ? control.narration().label() : control.id();
                String kind = control.narration() != null ? control.narration().role().name().toLowerCase(Locale.ROOT) : "control";
                out.add(new Control(label, kind, b.x() / scale, b.y() / scale, b.width() / scale, b.height() / scale, true));
            }
            return out;
        }
        collect(screen.children(), out);
        return out;
    }

    private static void collect(List<? extends GuiEventListener> children, List<Control> out) {
        for (GuiEventListener child : children) {
            if (child instanceof AbstractWidget w && w.visible) {
                String kind = w instanceof EditBox ? "text_field" : w.getClass().getSimpleName().toLowerCase(Locale.ROOT).replace("widget", "");
                String label = w instanceof EditBox box ? box.getValue() : w.getMessage().getString();
                out.add(new Control(label, kind, w.getX(), w.getY(), w.getWidth(), w.getHeight(), w.isActive()));
            }
            if (child instanceof ContainerEventHandler nested && !(child instanceof AbstractWidget)) collect(nested.children(), out);
        }
    }

    /** The control whose label matches {@code label}: an exact match first (ignoring case), then one that contains it. */
    public static Control find(String label) {
        Screen screen = Minecraft.getInstance().gui.screen();
        if (screen == null || label == null) return null;
        String want = label.trim().toLowerCase(Locale.ROOT);
        List<Control> all = controls(screen);
        for (Control control : all) if (control.label().trim().toLowerCase(Locale.ROOT).equals(want)) return control;
        for (Control control : all) if (control.label().toLowerCase(Locale.ROOT).contains(want)) return control;
        return null;
    }

    // ── Mouse ───────────────────────────────────────────────────────────────

    /** Moves the game's pointer to GUI pixel {@code x}, {@code y}. With no screen open this turns the camera, as moving a real mouse would. */
    public static void move(double x, double y) {
        Minecraft c = Minecraft.getInstance();
        var w = c.getWindow();
        double sx = x * w.getScreenWidth() / (double) w.getGuiScaledWidth(), sy = y * w.getScreenHeight() / (double) w.getGuiScaledHeight();
        ((MouseHandlerAccess) c.mouseHandler).peripheral$onMove(w.handle(), sx, sy);
    }

    /** Presses or releases mouse {@code button} (0 left, 1 right, 2 middle) where the pointer is. */
    public static void button(int button, boolean down, int modifiers) {
        Minecraft c = Minecraft.getInstance();
        ((MouseHandlerAccess) c.mouseHandler).peripheral$onButton(c.getWindow().handle(), new MouseButtonInfo(button, modifiers),
            down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE);
    }

    /** Moves there (when a screen is open), presses and releases: one ordinary click. */
    public static void click(double x, double y, int button, int modifiers) {
        if (Minecraft.getInstance().gui.screen() != null) move(x, y);
        button(button, true, modifiers);
        button(button, false, modifiers);
    }

    /** Scrolls the wheel by {@code amount} notches (positive is up) where the pointer is. */
    public static void scroll(double amount) {
        Minecraft c = Minecraft.getInstance();
        ((MouseHandlerAccess) c.mouseHandler).peripheral$onScroll(c.getWindow().handle(), 0, amount);
    }

    /** Clicks the middle of container slot {@code slot} (its index in the open menu). Returns false when there's no such slot. */
    public static boolean clickSlot(int slot, int button, boolean shift) {
        if (!(Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> container)) return false;
        List<Slot> slots = container.getMenu().slots;
        if (slot < 0 || slot >= slots.size()) return false;
        var access = (ContainerScreenAccess) container;
        Slot s = slots.get(slot);
        int mods = shift ? GLFW.GLFW_MOD_SHIFT : 0;
        if (shift) key(GLFW.GLFW_KEY_LEFT_SHIFT, true, 0);
        click(access.peripheral$leftPos() + s.x + 8, access.peripheral$topPos() + s.y + 8, button, mods);
        if (shift) key(GLFW.GLFW_KEY_LEFT_SHIFT, false, 0);
        return true;
    }

    /** The pointer's position in GUI pixels. */
    public static double[] mouse() {
        Minecraft c = Minecraft.getInstance();
        return new double[]{c.mouseHandler.getScaledXPos(c.getWindow()), c.mouseHandler.getScaledYPos(c.getWindow())};
    }

    // ── Keyboard ────────────────────────────────────────────────────────────

    /** Presses or releases a key by GLFW code. */
    public static void key(int key, boolean down, int modifiers) {
        Minecraft c = Minecraft.getInstance();
        int scancode = GLFW.glfwGetKeyScancode(key);
        ((KeyboardHandlerAccess) c.keyboardHandler).peripheral$keyPress(c.getWindow().handle(), down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE,
            new KeyEvent(key, scancode, modifiers));
    }

    /** Types {@code text} character by character, as a keyboard would. */
    public static void type(String text) {
        Minecraft c = Minecraft.getInstance();
        var keyboard = (KeyboardHandlerAccess) c.keyboardHandler;
        text.codePoints().forEach(cp -> keyboard.peripheral$charTyped(c.getWindow().handle(), new CharacterEvent(cp)));
    }

    /**
     * A key code from a name: "enter", "escape", "tab", "backspace", "space", "up", "f3", "left_shift",
     * single letters and digits, or Minecraft's own names like "key.keyboard.e". Returns -1 when unknown.
     */
    public static int keyCode(String name) {
        if (name == null || name.isBlank()) return -1;
        String n = name.trim().toLowerCase(Locale.ROOT);
        String full = n.startsWith("key.") ? n : "key.keyboard." + switch (n) {
            case "esc" -> "escape";
            case "return" -> "enter";
            case "shift" -> "left.shift";
            case "ctrl", "control" -> "left.control";
            case "alt" -> "left.alt";
            default -> n.replace('_', '.');
        };
        try {
            InputConstants.Key key = InputConstants.getKey(full);
            return key.getType() == InputConstants.Type.KEYSYM ? key.getValue() : -1;
        } catch (IllegalArgumentException e) { return -1; }
    }
}
