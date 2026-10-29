package com.neurojump.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;

/**
 * 矩形按钮：文字居中，触摸判定用矩形命中。屏幕坐标原点在左下。
 * 图形与文字分两步绘制——ShapeRenderer 与 SpriteBatch 不能嵌套 begin/end。
 */
public final class Button {
    public final Rectangle rect = new Rectangle();
    public String label;
    public final int id;

    public Button(int id, String label, float x, float y, float w, float h) {
        this.id = id;
        this.label = label;
        this.rect.set(x, y, w, h);
    }

    public boolean hit(float wx, float wy) {
        return rect.contains(wx, wy);
    }

    /** 底框：在 ShapeRenderer 批次内调用。 */
    public void drawShape(ShapeRenderer sr, boolean hover, boolean enabled) {
        Color fill = !enabled ? new Color(0.20f, 0.22f, 0.30f, 1f)
                : hover ? new Color(0.24f, 0.52f, 0.86f, 1f)
                : new Color(0.15f, 0.18f, 0.27f, 1f);
        Draw.rect(sr, rect.x, rect.y, rect.width, rect.height, fill);
        Draw.border(sr, rect.x, rect.y, rect.width, rect.height, 2f,
                enabled ? new Color(0.35f, 0.55f, 0.85f, 1f)
                        : new Color(0.28f, 0.30f, 0.36f, 1f));
    }

    /** 文字：在 SpriteBatch 批次内调用。 */
    public void drawLabel(SpriteBatch batch, BitmapFont font, boolean enabled) {
        Draw.textCentered(batch, font, label,
                rect.x + rect.width * 0.5f,
                rect.y + rect.height * 0.5f + font.getCapHeight() * 0.5f,
                enabled ? Color.WHITE : new Color(0.55f, 0.57f, 0.62f, 1f));
    }
}
