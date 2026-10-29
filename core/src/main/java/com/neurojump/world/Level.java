package com.neurojump.world;

import java.util.ArrayList;
import java.util.List;

/** 一关的静态几何 + 移动平台 + 危险区 + 终点。生成后不再修改，可安全跨线程共享。 */
public class Level {
    public final int index;
    public float width = C.LEVEL_W;
    public final float height = C.LEVEL_H;

    public final List<Rect> solids = new ArrayList<>();
    public final List<Rect> platforms = new ArrayList<>();
    public final List<Mover> movers = new ArrayList<>();
    public final List<Rect> spikes = new ArrayList<>();

    public Rect goal;
    /** 左右硬边界：角色被挡住而不是掉出场景。 */
    public float boundLeft = 0f;
    public float boundRight = C.LEVEL_W;
    /** 无限关卡用：是否已到达终点（普通关卡按 goal 判定）。 */
    public boolean endless;
    /** 生成期临时：上一段的落脚面高度。 */
    float pendingY;
    public float startX, startY;
    /** 生成阶段脚本跳跃验证通过。 */
    public boolean verified;
    public int theme;

    public Level(int index) {
        this.index = index;
        this.theme = Math.min(7, index / 13);
    }

    /** 从 (px, py) 垂直向下探测最近可站立台面的深度（像素），无则 -1。 */
    public float groundDepth(float px, float py, float maxDepth) {
        float best = -1f;
        for (int i = 0; i < solids.size(); i++) {
            Rect r = solids.get(i);
            if (!r.containsX(px)) continue;
            float top = r.top();
            if (top > py + 0.5f) continue;
            float d = py - top;
            if (d <= maxDepth && (best < 0f || d < best)) best = d;
        }
        for (int i = 0; i < platforms.size(); i++) {
            Rect r = platforms.get(i);
            if (!r.containsX(px)) continue;
            float top = r.top();
            if (top > py + 0.5f) continue;
            float d = py - top;
            if (d <= maxDepth && (best < 0f || d < best)) best = d;
        }
        return best;
    }

    /** 向前探测最近实心墙的距离。 */
    public float wallDistance(float px, float py, float probeLen) {
        float best = probeLen;
        for (int i = 0; i < solids.size(); i++) {
            Rect r = solids.get(i);
            if (py < r.y || py > r.top()) continue;
            float d = r.x - px;
            if (d >= 0f && d < best) best = d;
        }
        return best;
    }

    /** 头顶到最近实心顶棚的距离。 */
    public float ceilingDistance(float px, float pyTop) {
        float best = 300f;
        for (int i = 0; i < solids.size(); i++) {
            Rect r = solids.get(i);
            if (!r.containsX(px)) continue;
            float d = r.y - pyTop;
            if (d >= 0f && d < best) best = d;
        }
        return best;
    }
}
