package slooth.peripheral.ui;

/**
 * Tiny frame-rate-independent animator: eases a {@code current} value toward a {@code target}
 * using ease-out cubic. Screens keep one {@code Anim} per animated quantity (hover alpha,
 * scroll offset, popup expansion) and call {@link #update(float)} each frame with the
 * render {@code delta} (in 20ths-of-a-second "ticks", the value Minecraft passes to
 * {@code extractRenderState}).
 *
 * <p>Animation is purely visual; callers must always hit-test against the logical target,
 * never the interpolated value, so input stays correct while things slide.
 */
public final class Anim {

    private float current;
    private float target;
    /** Time constant in ticks (1 tick = 50ms). ~2.4 ticks ≈ 120ms. */
    private final float speed;

    public Anim(float initial)             { this(initial, 2.4f); }
    public Anim(float initial, float speed) { this.current = initial; this.target = initial; this.speed = speed; }

    /** Set the goal without snapping. */
    public void target(float t) { this.target = t; }

    /** Snap instantly to a value (e.g. when a screen (re)opens). */
    public void snap(float v) { this.current = v; this.target = v; }

    public float value()  { return current; }
    public float goal()   { return target; }

    /**
     * Advance by {@code deltaTicks} render frames; returns the new value.
     *
     * <p>Exponential smoothing toward the target — the step covers a fixed fraction of the
     * remaining distance each unit of time, which is naturally ease-out (fast then slow) and
     * frame-rate independent (the {@code pow} makes two half-length frames equal one full frame).
     */
    public float update(float deltaTicks) {
        float diff = target - current;
        if (Math.abs(diff) < 0.001f) { current = target; return current; }
        float k = 1f - (float) Math.pow(0.02f, Math.max(0f, deltaTicks) / Math.max(0.05f, speed));
        current += diff * Math.min(1f, Math.max(0f, k));
        return current;
    }
}
