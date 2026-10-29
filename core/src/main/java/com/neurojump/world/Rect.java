package com.neurojump.world;

/** 轴对齐矩形。自写而非用 libGDX 类型，使世界层与训练层不依赖图形库，可离线验证。 */
public final class Rect {
    public float x, y, w, h;

    public Rect() {}

    public Rect(float x, float y, float w, float h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public float right() { return x + w; }
    public float top() { return y + h; }
    public float centerX() { return x + w * 0.5f; }

    public Rect set(float x, float y, float w, float h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        return this;
    }

    public boolean overlaps(float ax, float ay, float aw, float ah) {
        return ax + aw > x && ax < x + w && ay + ah > y && ay < y + h;
    }

    public boolean containsX(float px) {
        return px >= x && px <= x + w;
    }
}
