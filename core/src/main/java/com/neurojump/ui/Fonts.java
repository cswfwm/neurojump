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

    /**
     * 字号量化档位。
     *
     * 每个字号都要独立生成一张字体图集，14 个字号在低内存设备上要吃掉约 50MB。
     * 把请求字号吸附到最近的档位，视觉差异很小，图集数量却减到 6 张。
     */
    private static final int[] STEPS = {16, 20, 24, 28, 34, 64};

    public static BitmapFont of(int requested) {
        int size = quantize(requested);
        BitmapFont f = CACHE.get(size);
        if (f != null) return f;

        FreeTypeFontGenerator gen = new FreeTypeFontGenerator(Gdx.files.internal(PATH));
        FreeTypeFontGenerator.FreeTypeFontParameter p =
                new FreeTypeFontGenerator.FreeTypeFontParameter();
        p.size = size;
        p.color = Color.WHITE;
        p.minFilter = Texture.TextureFilter.Linear;
        p.magFilter = Texture.TextureFilter.Linear;
        // 单通道：字形只用到透明度，RGBA8888 会白白多占 4 倍显存
        p.genMipMaps = false;
        // 大字号用精简字符集，小字号用全量
        p.characters = size >= 64 ? bigCharacters(gen) : characters(gen);
        f = gen.generateFont(p);
        gen.dispose();
        CACHE.put(size, f);
        return f;
    }

    /**
     * 只请求字体里真实存在的字形。
     *
     * 之前把整个 CJK 区间（两万多字）都列进去，FreeType 会按这个规模排版图集，
     * 而我们的子集字体只有几百个字形——图集被撑到远超实际需要。
     * 这里用 hasGlyph 逐个确认，只保留真正能画出来的字。
     */
    private static String characters(FreeTypeFontGenerator gen) {
        if (characters != null) return characters;
        StringBuilder sb = new StringBuilder(1024);
        for (int c = 0x20; c <= 0x7E; c++) sb.append((char) c);
        for (int c = 0x3000; c <= 0x9FA5; c++) {
            if (gen.hasGlyph(c)) sb.append((char) c);
        }
        for (int c = 0xFF00; c <= 0xFF65; c++) {
            if (gen.hasGlyph(c)) sb.append((char) c);
        }
        characters = sb.toString();
        return characters;
    }

    /**
     * 大字号（64 号）的字符集。
     *
     * 该档位只用于主标题「神经网络寻路」这类几个字的大字。
     * 若也按全部 384 个字形排版，图集要 16MB，几乎占满低配机的预算。
     * 这里只放标题实际用到的字，图集随之降到几 MB。
     */
    private static final String BIG_TITLE_CHARS = "神经网络寻路";

    private static String bigCharacters(FreeTypeFontGenerator gen) {
        if (bigChars != null) return bigChars;
        StringBuilder sb = new StringBuilder(64);
        for (int c = 0x20; c <= 0x7E; c++) sb.append((char) c);
        for (int i = 0; i < BIG_TITLE_CHARS.length(); i++) {
            char ch = BIG_TITLE_CHARS.charAt(i);
            if (gen.hasGlyph(ch) && sb.indexOf(String.valueOf(ch)) < 0) sb.append(ch);
        }
        bigChars = sb.toString();
        return bigChars;
    }

    private static String bigChars;

    /** 把请求字号吸附到最近的档位。 */
    private static int quantize(int requested) {
        int best = STEPS[0];
        int bestDiff = Math.abs(requested - best);
        for (int s : STEPS) {
            int d = Math.abs(requested - s);
            if (d < bestDiff) {
                bestDiff = d;
                best = s;
            }
        }
        return best;
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
