package com.neurojump.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.neurojump.world.AgentBody;
import com.neurojump.world.C;
import com.neurojump.world.Level;
import com.neurojump.world.Mover;
import com.neurojump.world.Rect;

/** 关卡与角色的绘制。只读关卡数据，可在训练可视化与手动试玩中复用。 */
public final class LevelRenderer {

    private static final Color SOLID      = new Color(0.30f, 0.86f, 0.62f, 1f);
    private static final Color SOLID_TOP  = new Color(0.55f, 1.00f, 0.80f, 1f);
    private static final Color PLATFORM   = new Color(0.42f, 0.55f, 0.95f, 1f);
    private static final Color PLATFORM_TOP = new Color(0.68f, 0.78f, 1.00f, 1f);
    private static final Color MOVER      = new Color(0.96f, 0.62f, 0.30f, 1f);
    private static final Color MOVER_TOP  = new Color(1.00f, 0.82f, 0.52f, 1f);
    private static final Color SPIKE      = new Color(0.95f, 0.32f, 0.42f, 1f);
    private static final Color AGENT      = new Color(0.98f, 0.98f, 1.00f, 1f);
    private static final Color AGENT_EYE  = new Color(0.12f, 0.14f, 0.22f, 1f);
    private static final Color GHOST      = new Color(1f, 1f, 1f, 0.12f);

    private LevelRenderer() {}

    /** 画一整层。originX/originY 为关卡左下角在屏幕上的位置，scale 为缩放。 */
    public static void drawWorld(ShapeRenderer sr, Level lv, float t, float originX,
                                 float originY, float scale) {
        for (int i = 0; i < lv.solids.size(); i++) {
            Rect r = lv.solids.get(i);
            drawSlab(sr, r, originX, originY, scale, SOLID, SOLID_TOP);
        }
        for (int i = 0; i < lv.platforms.size(); i++) {
            Rect r = lv.platforms.get(i);
            drawSlab(sr, r, originX, originY, scale, PLATFORM, PLATFORM_TOP);
        }
        for (int i = 0; i < lv.movers.size(); i++) {
            Mover m = lv.movers.get(i);
            float x = m.xAt(t), y = m.yAt(t);
            drawSlab(sr, x, y, m.w, m.h, originX, originY, scale, MOVER, MOVER_TOP);
            // 摆动轨道虚线，提示来回方向
            drawTrack(sr, m, t, originX, originY, scale);
        }
        for (int i = 0; i < lv.spikes.size(); i++) {
            drawSpike(sr, lv.spikes.get(i), originX, originY, scale);
        }
        // 终点旗
        float gx = lv.goal.x * scale + originX;
        float gy = originY;
        float gh = lv.height * scale;
        Draw.rect(sr, gx, gy, 3f, gh, new Color(0.35f, 1f, 0.55f, 0.45f));
    }

    private static void drawSlab(ShapeRenderer sr, Rect r, float ox, float oy, float s,
                                 Color body, Color top) {
        drawSlab(sr, r.x, r.y, r.w, r.h, ox, oy, s, body, top);
    }

    private static void drawSlab(ShapeRenderer sr, float x, float y, float w, float h,
                                 float ox, float oy, float s, Color body, Color top) {
        Draw.rect(sr, x * s + ox, y * s + oy, w * s, h * s, body);
        Draw.rect(sr, x * s + ox, (y + h) * s + oy - 3f * s, w * s, 3f * s, top);
    }

    private static void drawTrack(ShapeRenderer sr, Mover m, float t, float ox, float oy,
                                  float s) {
        Color c = new Color(MOVER.r, MOVER.g, MOVER.b, 0.20f);
        float x1, y1, x2, y2;
        if (m.vertical) {
            x1 = x2 = (m.baseX + m.w * 0.5f) * s + ox;
            y1 = (m.baseY - m.amplitude) * s + oy;
            y2 = (m.baseY + m.h + m.amplitude) * s + oy;
        } else {
            y1 = y2 = (m.baseY + m.h * 0.5f) * s + oy;
            x1 = (m.baseX - m.amplitude) * s + ox;
            x2 = (m.baseX + m.w + m.amplitude) * s + ox;
        }
        Draw.line(sr, x1, y1, x2, y2, 2f, c);
    }

    private static void drawSpike(ShapeRenderer sr, Rect r, float ox, float oy, float s) {
        sr.setColor(SPIKE);
        float x0 = r.x * s + ox;
        float y0 = r.y * s + oy;
        float w = r.w * s;
        float h = r.h * s;
        int n = Math.max(1, (int) (r.w / 16f));
        float step = w / n;
        for (int i = 0; i < n; i++) {
            sr.triangle(x0 + i * step, y0, x0 + (i + 1) * step, y0, x0 + i * step + step * 0.5f, y0 + h);
        }
    }

    /** 画角色本体，带朝向眼睛与着地标记。 */
    public static void drawAgent(ShapeRenderer sr, AgentBody b, float ox, float oy, float s,
                                 Color color) {
        float x = b.x * s + ox;
        float y = b.y * s + oy;
        float w = C.AGENT_W * s;
        float h = C.AGENT_H * s;
        Draw.rect(sr, x, y, w, h, color);

        // 眼睛：朝向即当前水平速度方向
        float eyeW = Math.max(3f, w * 0.22f);
        float eyeH = Math.max(3f, h * 0.16f);
        float ey = y + h * 0.62f;
        float ex = b.facing > 0 ? x + w * 0.54f : x + w * 0.46f - eyeW;
        Draw.rect(sr, ex, ey, eyeW, eyeH, AGENT_EYE);

        if (b.onGround) {
            Draw.rect(sr, x, y - 2f, w, 2f, new Color(0.4f, 1f, 0.6f, 0.7f));
        }
    }

    /** 训练时展示种群的「幽灵」：只画半透明小方块，表示其他个体当前位置。 */
    public static void drawGhost(ShapeRenderer sr, AgentBody b, float ox, float oy, float s) {
        Draw.rect(sr, b.x * s + ox, b.y * s + oy, C.AGENT_W * s, C.AGENT_H * s, GHOST);
    }
}
