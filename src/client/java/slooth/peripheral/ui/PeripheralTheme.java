package slooth.peripheral.ui;

import slooth.peripheral.PeripheralClient;
import slooth.peripheral.util.PeripheralConfig;

import com.google.gson.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Named colour palette + button styling shared by every Peripheral screen and the HUD.
 *
 * <p>A theme is a flat set of int-ARGB "roles" (semantic slots such as {@code panel},
 * {@code accent}, {@code text}) plus a small amount of button styling
 * ({@link ButtonStyle}, bevel colours, text shadow). Screens read the active theme via
 * {@link #current()} and map their own historical colour constants onto these roles.
 *
 * <p>The default theme is <b>terminal</b> — its role values reproduce the mod's original
 * dark-green look exactly, so nothing changes for existing users. <b>vanilla</b> mimics
 * classic Minecraft (light bevelled panels, grey bevelled buttons). Extra themes can be
 * dropped into {@code config/peripheral/themes/&lt;name&gt;.json} as {@code role -> "#AARRGGBB"}
 * and are loaded at startup, selectable by filename.
 */
public final class PeripheralTheme {

    public enum ButtonStyle { FLAT, BEVEL }

    public final String name;

    // ── Roles (int ARGB) ──────────────────────────────────────────────────────
    public int bg, panel, panelBorder, titleBar, accent, text, textDim, textOnAccent,
               hover, press, selection, rowHover, inputBg, inputBorder,
               scrollTrack, scrollThumb, success, warning, error, barBg, divider;

    // ── Button styling ────────────────────────────────────────────────────────
    public boolean     textShadow  = false;
    public ButtonStyle buttonStyle = ButtonStyle.FLAT;
    public int bevelLight, bevelDark;
    public int buttonFace, buttonHover, buttonBevelLight, buttonBevelDark, buttonText;

    private PeripheralTheme(String name) { this.name = name; }

    // ══════════════════════════════════════════════════════════════════════════
    // Registry
    // ══════════════════════════════════════════════════════════════════════════

    private static final Map<String, PeripheralTheme> THEMES = new LinkedHashMap<>();

    static {
        PeripheralTheme terminal = terminal();
        PeripheralTheme vanilla  = vanilla();
        THEMES.put(terminal.name, terminal);
        THEMES.put(vanilla.name,  vanilla);
    }

    /** Load custom themes from {@code config/peripheral/themes/*.json}. Call once at startup. */
    public static void loadCustom() {
        Path dir = Paths.get("config", "peripheral", "themes");
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(PeripheralTheme::loadOne);
        } catch (Exception e) {
            PeripheralClient.LOGGER.warn("[Peripheral] Theme scan failed: {}", e.getMessage());
        }
    }

    private static void loadOne(Path file) {
        String fn   = file.getFileName().toString();
        String name = fn.substring(0, fn.length() - ".json".length());
        try (var r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            THEMES.put(name, fromJson(name, o));
        } catch (Exception e) {
            PeripheralClient.LOGGER.warn("[Peripheral] Theme '{}' failed: {}", name, e.getMessage());
        }
    }

    /** The active theme, chosen by the {@code theme} config key (defaults to terminal). */
    public static PeripheralTheme current() {
        PeripheralTheme t = THEMES.get(PeripheralConfig.theme);
        return t != null ? t : THEMES.get("terminal");
    }

    /** Look up a theme by name, or terminal if unknown. Used for per-script overrides. */
    public static PeripheralTheme byName(String name) {
        if (name == null) return current();
        PeripheralTheme t = THEMES.get(name);
        return t != null ? t : current();
    }

    /** Registered theme names in insertion order (built-ins first). */
    public static List<String> names() { return new ArrayList<>(THEMES.keySet()); }

    /** Advance the config theme to the next registered theme and persist. Returns the new name. */
    public static String cycle() {
        List<String> all = names();
        int i = all.indexOf(PeripheralConfig.theme);
        String next = all.get((i + 1) % all.size());
        PeripheralConfig.theme = next;
        PeripheralConfig.save();
        return next;
    }

    public boolean isTerminal() { return "terminal".equals(name); }
    public boolean bevel()      { return buttonStyle == ButtonStyle.BEVEL; }

    // ══════════════════════════════════════════════════════════════════════════
    // Built-ins
    // ══════════════════════════════════════════════════════════════════════════

    private static PeripheralTheme terminal() {
        return applyTerminal(new PeripheralTheme("terminal"));
    }

    private static PeripheralTheme applyTerminal(PeripheralTheme t) {
        t.bg           = 0xEE0D0D0D;
        t.panel        = 0xEE0D0D0D;
        t.panelBorder  = 0xFF153020;
        t.titleBar     = 0xDD0E1F11;
        t.accent       = 0xFF00FFAA;
        t.text         = 0xFFDDDDDD;
        t.textDim      = 0xFF888888;
        t.textOnAccent = 0xFF06120B;
        t.hover        = 0x22FFFFFF;
        t.press        = 0x33000000;
        t.selection    = 0xCC1A3A24;
        t.rowHover     = 0x22FFFFFF;
        t.inputBg      = 0xFF1A1A1A;
        t.inputBorder  = 0xFF153020;
        t.scrollTrack  = 0xFF1A1A1A;
        t.scrollThumb  = 0xFF404040;
        t.success      = 0xFF00FFAA;
        t.warning      = 0xFFFFAA00;
        t.error        = 0xFFFF4444;
        t.barBg        = 0xFF2A2A2A;
        t.divider      = 0xFF444444;
        t.textShadow   = false;
        t.buttonStyle  = ButtonStyle.FLAT;
        t.bevelLight   = 0x33FFFFFF;
        t.bevelDark    = 0x33000000;
        t.buttonFace   = 0xFF181818;
        t.buttonHover  = 0x22FFFFFF;
        t.buttonBevelLight = 0x33FFFFFF;
        t.buttonBevelDark  = 0x33000000;
        t.buttonText   = 0xFF00FFAA;
        return t;
    }

    private static PeripheralTheme vanilla() {
        PeripheralTheme t = new PeripheralTheme("vanilla");
        t.bg           = 0xC0101010;
        t.panel        = 0xFFC6C6C6;
        t.panelBorder  = 0xFF000000;
        t.titleBar     = 0xFFC6C6C6;
        t.accent       = 0xFF7E8BC0;
        t.text         = 0xFF404040;
        t.textDim      = 0xFF6F6F6F;
        t.textOnAccent = 0xFFFFFFFF;
        t.hover        = 0x407E8BC0;
        t.press        = 0x40000000;
        t.selection    = 0xFF5B7EC6;
        t.rowHover     = 0x33000000;
        t.inputBg      = 0xFF000000;
        t.inputBorder  = 0xFFA0A0A0;
        t.scrollTrack  = 0xFF8B8B8B;
        t.scrollThumb  = 0xFFC6C6C6;
        t.success      = 0xFF3FA33F;
        t.warning      = 0xFFD8A000;
        t.error        = 0xFFAA0000;
        t.barBg        = 0xFF8B8B8B;
        t.divider      = 0xFF555555;
        t.textShadow   = true;
        t.buttonStyle  = ButtonStyle.BEVEL;
        t.bevelLight   = 0xFFFFFFFF;
        t.bevelDark    = 0xFF555555;
        t.buttonFace   = 0xFF6F6F6F;
        t.buttonHover  = 0x407E8BC0;
        t.buttonBevelLight = 0xFFA0A0A0;
        t.buttonBevelDark  = 0xFF3F3F3F;
        t.buttonText   = 0xFFFFFFFF;
        return t;
    }

    private static PeripheralTheme fromJson(String name, JsonObject o) {
        PeripheralTheme t = applyTerminal(new PeripheralTheme(name)); // terminal defaults, override present roles
        t.bg           = col(o, "bg",           t.bg);
        t.panel        = col(o, "panel",        t.panel);
        t.panelBorder  = col(o, "panelBorder",  t.panelBorder);
        t.titleBar     = col(o, "titleBar",     t.titleBar);
        t.accent       = col(o, "accent",       t.accent);
        t.text         = col(o, "text",         t.text);
        t.textDim      = col(o, "textDim",      t.textDim);
        t.textOnAccent = col(o, "textOnAccent", t.textOnAccent);
        t.hover        = col(o, "hover",        t.hover);
        t.press        = col(o, "press",        t.press);
        t.selection    = col(o, "selection",    t.selection);
        t.rowHover     = col(o, "rowHover",     t.rowHover);
        t.inputBg      = col(o, "inputBg",      t.inputBg);
        t.inputBorder  = col(o, "inputBorder",  t.inputBorder);
        t.scrollTrack  = col(o, "scrollTrack",  t.scrollTrack);
        t.scrollThumb  = col(o, "scrollThumb",  t.scrollThumb);
        t.success      = col(o, "success",      t.success);
        t.warning      = col(o, "warning",      t.warning);
        t.error        = col(o, "error",        t.error);
        t.barBg        = col(o, "barBg",        t.barBg);
        t.divider      = col(o, "divider",      t.divider);
        t.bevelLight   = col(o, "bevelLight",   t.bevelLight);
        t.bevelDark    = col(o, "bevelDark",    t.bevelDark);
        t.buttonFace   = col(o, "buttonFace",   t.buttonFace);
        t.buttonHover  = col(o, "buttonHover",  t.buttonHover);
        t.buttonBevelLight = col(o, "buttonBevelLight", t.buttonBevelLight);
        t.buttonBevelDark  = col(o, "buttonBevelDark",  t.buttonBevelDark);
        t.buttonText   = col(o, "buttonText",   t.buttonText);
        if (o.has("textShadow"))  t.textShadow  = o.get("textShadow").getAsBoolean();
        if (o.has("buttonStyle")) t.buttonStyle =
            "bevel".equalsIgnoreCase(o.get("buttonStyle").getAsString())
                ? ButtonStyle.BEVEL : ButtonStyle.FLAT;
        return t;
    }

    private static int col(JsonObject o, String key, int def) {
        if (!o.has(key)) return def;
        try {
            String s = o.get(key).getAsString().trim();
            if (s.startsWith("#")) s = s.substring(1);
            long v = Long.parseLong(s, 16);
            return s.length() > 6 ? (int) v : (int) (0xFF000000L | v);
        } catch (Exception e) { return def; }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Draw helpers
    // ══════════════════════════════════════════════════════════════════════════

    /** Draw a bevelled box: face fill, light top/left edge, dark bottom/right edge, black outer border. */
    public void bevelBox(GuiGraphicsExtractor ctx, int x1, int y1, int x2, int y2,
                         int face, int light, int dark) {
        ctx.fill(x1, y1, x2, y2, face);
        // outer 1px black border
        ctx.fill(x1, y1, x2, y1 + 1, panelBorder);
        ctx.fill(x1, y2 - 1, x2, y2, panelBorder);
        ctx.fill(x1, y1, x1 + 1, y2, panelBorder);
        ctx.fill(x2 - 1, y1, x2, y2, panelBorder);
        // inner bevel highlight (top/left) and shadow (bottom/right)
        ctx.fill(x1 + 1, y1 + 1, x2 - 1, y1 + 2, light);
        ctx.fill(x1 + 1, y1 + 1, x1 + 2, y2 - 1, light);
        ctx.fill(x1 + 1, y2 - 2, x2 - 1, y2 - 1, dark);
        ctx.fill(x2 - 2, y1 + 1, x2 - 1, y2 - 1, dark);
    }

    /** Bevelled panel (uses panel/bevel colours). */
    public void bevelPanel(GuiGraphicsExtractor ctx, int x1, int y1, int x2, int y2) {
        bevelBox(ctx, x1, y1, x2, y2, panel, bevelLight, bevelDark);
    }
}
