package com.neurojump.world;

/** 会动的平台：正弦往复。位置是时间的纯函数，多个线程可各自独立推算。 */
public final class Mover {
    public final float baseX, baseY, w, h;
    public final boolean vertical;
    public final float amplitude;
    public final float period;
    public final float phase;

    public Mover(float baseX, float baseY, float w, float h, boolean vertical,
                 float amplitude, float period, float phase) {
        this.baseX = baseX;
        this.baseY = baseY;
        this.w = w;
        this.h = h;
        this.vertical = vertical;
        this.amplitude = amplitude;
        this.period = period;
        this.phase = phase;
    }

    public float xAt(float t) {
        return vertical ? baseX : baseX + offset(t);
    }

    public float yAt(float t) {
        return vertical ? baseY + offset(t) : baseY;
    }

    public float topAt(float t) { return yAt(t) + h; }

    private float offset(float t) {
        return amplitude * (float) Math.sin(t * 2.0 * Math.PI / period + phase);
    }
}
