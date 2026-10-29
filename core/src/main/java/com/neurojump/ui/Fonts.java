package com.neurojump.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;

import java.util.HashMap;
import java.util.Map;

/**
 * 字体缓存。FreeType 按字号生成开销较大，按字号缓存复用。
 * 字体文件已在构建时按源码用到的字符裁剪（约 300KB），这里直接复用其中的字形。
 */
public final class Fonts {
    private static final Map<Integer, BitmapFont> CACHE = new HashMap<>();
    private static final String PATH = "fonts/GameFont.ttf";
    private static String characters;
    private static Texture pixel;

    private Fonts() {}

    public static BitmapFont of(int size) {
        BitmapFont f = CACHE.get(size);
        if (f != null) return f;

        FreeTypeFontGenerator gen = new FreeTypeFontGenerator(Gdx.files.internal(PATH));
        FreeTypeFontGenerator.FreeTypeFontParameter p =
                new FreeTypeFontGenerator.FreeTypeFontParameter();
        p.size = size;
        p.color = Color.WHITE;
        p.minFilter = Texture.TextureFilter.Linear;
        p.magFilter = Texture.TextureFilter.Linear;
        p.characters = characters();
        f = gen.generateFont(p);
        gen.dispose();
        CACHE.put(size, f);
        return f;
    }

    /**
     * 字体覆盖的字符：使用 codepoints 区间而不是探测字形。
     * 字体已在构建时子集化到源码用到的字，直接用区间能让 FreeType 稳定生成中文。
     */
    private static String characters() {
        if (characters != null) return characters;
        StringBuilder sb = new StringBuilder(2048);
        for (int c = 0x20; c <= 0x7E; c++) sb.append((char) c);
        for (int c = 0x3000; c <= 0x303F; c++) sb.append((char) c);
        for (int c = 0xFF00; c <= 0xFF65; c++) sb.append((char) c);
        for (int c = 0x4E00; c <= 0x9FA5; c++) sb.append((char) c);
        characters = sb.toString();
        return characters;
    }

    /** 1x1 白色像素，用于所有纯色矩形与线段。 */
    public static Texture pixel() {
        if (pixel == null) {
            Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pm.setColor(Color.WHITE);
            pm.fill();
            pixel = new Texture(pm);
            pm.dispose();
        }
        return pixel;
    }

    public static void dispose() {
        for (BitmapFont f : CACHE.values()) f.dispose();
        CACHE.clear();
        if (pixel != null) { pixel.dispose(); pixel = null; }
    }
}
