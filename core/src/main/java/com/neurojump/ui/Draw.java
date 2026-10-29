package com.neurojump.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/** 绘制辅助：纯色矩形、描边、文字。把 ShapeRenderer 与 SpriteBatch 的切换收敛到这里。 */
public final class Draw {
    private Draw() {}

    public static void rect(ShapeRenderer sr, float x, float y, float w, float h, Color c) {
        sr.setColor(c);
        sr.rect(x, y, w, h);
    }

    public static void line(ShapeRenderer sr, float x1, float y1, float x2, float y2,
                            float width, Color c) {
        sr.setColor(c);
        sr.rectLine(x1, y1, x2, y2, width);
    }

    public static void border(ShapeRenderer sr, float x, float y, float w, float h,
                              float t, Color c) {
        sr.setColor(c);
        sr.rect(x, y, w, t);
        sr.rect(x, y + h - t, w, t);
        sr.rect(x, y, t, h);
        sr.rect(x + w - t, y, t, h);
    }

    public static void text(SpriteBatch batch, BitmapFont font, String s, float x, float y,
                            Color c) {
        font.setColor(c);
        font.draw(batch, s, x, y);
    }

    public static void textCentered(SpriteBatch batch, BitmapFont font, String s,
                                    float cx, float y, Color c) {
        font.setColor(c);
        font.draw(batch, s, cx - width(font, s) * 0.5f, y);
    }

    /** 按需创建 GlyphLayout：静态字段在类初始化时构造会受到混淆规则影响。 */
    private static GlyphLayout layout;

    public static float width(BitmapFont font, String s) {
        if (layout == null) layout = new GlyphLayout();
        layout.setText(font, s);
        return layout.width;
    }
}
