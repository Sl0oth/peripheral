package slooth.peripheral;

/** Colour palette for the Peripheral dark theme. */
public final class UiStyle {

    // ── Surfaces ──────────────────────────────────────────────────────────────
    public static final int BG_VEIL    = 0x88000000; // full-screen dim
    public static final int PANEL      = 0xEE111111;
    public static final int TITLE_BAR  = 0xEE1A1A1A;
    public static final int TAB_BAR    = 0xEE0F0F0F;
    public static final int TAB_ACTIVE = 0xDD252525;
    public static final int CONTENT    = 0xCC0C0C0C;

    // ── Borders & shadow ─────────────────────────────────────────────────────
    public static final int BORDER        = 0xFF2A2A2A;
    public static final int SHADOW_DARK   = 0x66000000;
    public static final int SHADOW_SOFT   = 0x33000000;

    // ── Accent ────────────────────────────────────────────────────────────────
    public static final int ACCENT        = 0xFFFF8800;
    public static final int ACCENT_DIM    = 0xFFAA5500;

    // ── Text ──────────────────────────────────────────────────────────────────
    public static final int TEXT          = 0xFFDDDDDD;
    public static final int TEXT_GREY     = 0xFF888888;
    public static final int TEXT_DIM      = 0xFF555555;
    public static final int TEXT_GREEN    = 0xFF00FFAA;
    public static final int TEXT_YELLOW   = 0xFFFFAA00;
    public static final int TEXT_CYAN     = 0xFF00CCFF;
    public static final int TEXT_RED      = 0xFFFF4444;

    // ── Buttons ───────────────────────────────────────────────────────────────
    public static final int BTN_BG        = 0xFF1C1C1C;
    public static final int BTN_BG_HOVER  = 0xFF2C2C2C;
    public static final int BTN_BG_PRESS  = 0xFF111111;
    public static final int BTN_BORDER    = 0xFF363636;
    public static final int BTN_BORDER_HV = 0xFF555555;

    // ── Scrollbar ─────────────────────────────────────────────────────────────
    public static final int SCROLL_TRACK  = 0xFF1A1A1A;
    public static final int SCROLL_THUMB  = 0xFF444444;
    public static final int SCROLL_THUMB_HV = 0xFF666666;

    private UiStyle() {}
}
