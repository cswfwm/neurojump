package com.neurojump.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 程序化生成 100 层，并逐层引入新要素（课程式难度曲线）：
 *   第 1~5 层    纯静态平台，间隙小、台面宽，先学会「向右走 + 跳」
 *   第 6~20 层   出现水平移动平台
 *   第 21~40 层  出现垂直移动平台
 *   第 41 层起    出现尖刺，间隙与高差随层数增大
 *
 * 每个候选关卡都用真实物理自动求解（Solvability）自检，未通过就换种子重生成，
 * 因此「100 层都可通关」由生成阶段保证，而不是靠期望。
 */
public final class LevelFactory {

    private static final float PLATFORM_TH = 18f;
    private static final float PLATFORM_MIN_W = 140f;
    private static final float PLATFORM_MAX_W = 220f;

    // 保守预算（小于理论跳跃包络，留出操作误差）
    private static final float GAP_FLAT = 165f;
    private static final float GAP_UP = 120f;
    private static final float GAP_DOWN = 200f;
    private static final float RISE_MAX = 100f;
    private static final float DROP_MAX = 190f;
    /** 上抬落点与起跳台右边缘的最小水平间隙（角色宽 30 + 余量）。 */
    private static final float MIN_CLEARANCE = 62f;

    private LevelFactory() {}

    public static List<Level> buildAll() {
        List<Level> out = new ArrayList<>(C.LEVEL_COUNT);
        for (int i = 0; i < C.LEVEL_COUNT; i++) out.add(build(i));
        return out;
    }

    /**
     * 无限模式：按层号即时生成，不预建全部关卡。
     * 难度在约第 60 层后饱和，避免后期出现物理上无法通过的参数。
     */
    public static Level buildInfinite(int index) {
        return build(index);
    }

    public static Level build(int index) {
        for (int attempt = 0; attempt < 60; attempt++) {
            Level lv = generate(index, 0xA11CE0000L + index * 7919L + attempt * 104729L);
            if (Solvability.check(lv)) {
                lv.verified = true;
                return lv;
            }
        }
        Level lv = conservative(index);
        lv.verified = Solvability.check(lv);
        return lv;
    }

    /** 该层允许出现的要素。 */
    private static boolean allowMoverH(int index) { return index >= 5; }
    private static boolean allowMoverV(int index) { return index >= 20; }
    private static boolean allowSpikes(int index) { return index >= 40; }

    private static Level generate(int index, long seed) {
        Random rnd = new Random(seed);
        Level lv = new Level(index);
        // 难度在约 60 层后饱和：再高也不会超出物理可达预算
        float difficulty = Math.min(1f, index / 60f);

        float startY = 150f;
        lv.solids.add(new Rect(40f, startY - PLATFORM_TH, 220f, PLATFORM_TH));
        lv.startX = 90f;
        lv.startY = startY;

        float goalTop = 190f + rnd.nextInt(3) * 80f;
        float goalLeft = lv.width - 320f;
        // 终点台做成右端封口的宽台：角色跑过头会被挡住，不会掉出场景
        float goalW = lv.width - goalLeft;
        lv.solids.add(new Rect(goalLeft, goalTop - PLATFORM_TH, goalW, PLATFORM_TH));
        lv.solids.add(new Rect(lv.width - 24f, goalTop, 24f, 320f));
        lv.goal = new Rect(goalLeft + 40f, 0f, 40f, C.LEVEL_H);
        lv.boundRight = lv.width - 26f;

        float cursorRight = 260f;
        float cursorTop = startY;
        int maxSegments = 8 + (int) (difficulty * 8);

        for (int i = 0; i < maxSegments && cursorRight < goalLeft - 300f; i++) {
            Anchor a = pickNext(rnd, index, difficulty, cursorRight, cursorTop, goalLeft);
            if (a == null) break;
            cursorRight = place(lv, a, rnd, index, difficulty);
            cursorTop = a.top;
        }

        closeGap(lv, rnd, cursorRight, cursorTop, goalLeft, goalTop);
        return lv;
    }

    private static final class Anchor {
        float left;
        float top;
        float width;
        int type;   // 0 静态, 1 水平移动, 2 垂直移动, 3 台阶
        int steps;
    }

    private static Anchor pickNext(Random rnd, int index, float difficulty,
                                   float right, float top, float goalLeft) {
        float scale = 0.5f + 0.5f * difficulty;
        Anchor a = new Anchor();

        // 要素按课程曲线放行：静态 → 水平移动 → 垂直移动 → 尖刺
        int pMoverH = allowMoverH(index) ? 12 : 0;
        int pMoverV = allowMoverV(index) ? 12 : 0;
        int pSteps = 10;
        int pUp = 20;
        int pDown = 16;
        int pFlat = 100 - pMoverH - pMoverV - pSteps - pUp - pDown;

        int roll = rnd.nextInt(100);
        if (roll < pFlat) {
            a.type = 0;
            a.left = right + lerp(55f, GAP_FLAT * scale, 0.35f + 0.65f * rnd.nextFloat());
            a.top = top;
        } else if ((roll -= pFlat) < pUp) {
            a.type = 0;
            a.left = right + lerp(50f, GAP_UP * scale, 0.3f + 0.7f * rnd.nextFloat());
            a.top = top + lerp(45f, RISE_MAX * scale, 0.3f + 0.7f * rnd.nextFloat());
        } else if ((roll -= pUp) < pDown) {
            a.type = 0;
            a.left = right + lerp(55f, GAP_DOWN * scale, 0.3f + 0.7f * rnd.nextFloat());
            a.top = Math.max(95f, top - lerp(40f, DROP_MAX * scale, rnd.nextFloat()));
        } else if ((roll -= pDown) < pSteps) {
            a.type = 3;
            a.steps = 2 + rnd.nextInt(2);
            a.left = right + lerp(45f, GAP_UP * scale, rnd.nextFloat());
            a.top = top + lerp(50f, RISE_MAX * 0.8f, rnd.nextFloat());
        } else if ((roll -= pSteps) < pMoverH) {
            a.type = 1;
            a.left = right + lerp(60f, 130f * scale, rnd.nextFloat());
            a.top = clamp(top + lerp(-40f, 60f, rnd.nextFloat()), 110f, 620f);
        } else {
            a.type = 2;
            a.left = right + lerp(55f, 120f * scale, rnd.nextFloat());
            a.top = clamp(top + lerp(35f, RISE_MAX * 0.85f, rnd.nextFloat()), 160f, 620f);
        }

        a.width = lerp(PLATFORM_MIN_W, PLATFORM_MAX_W, rnd.nextFloat());
        if (a.left + a.width > goalLeft - 120f) return null;
        if (a.top > top + 10f && a.left < right + MIN_CLEARANCE) {
            a.left = right + MIN_CLEARANCE;
            if (a.left + a.width > goalLeft - 120f) return null;
        }
        return a;
    }

    private static float place(Level lv, Anchor a, Random rnd, int index, float difficulty) {
        if (a.type == 0) {
            Rect r = new Rect(a.left, a.top - PLATFORM_TH, a.width, PLATFORM_TH);
            lv.platforms.add(r);
            maybeSpike(lv, r, rnd, index, difficulty);
            return r.right();
        }
        if (a.type == 3) {
            float x = a.left;
            float y = a.top;
            for (int i = 0; i < a.steps; i++) {
                float w = lerp(PLATFORM_MIN_W, 180f, rnd.nextFloat());
                Rect r = new Rect(x, y - PLATFORM_TH, w, PLATFORM_TH);
                lv.platforms.add(r);
                x = r.right() + lerp(35f, 70f, rnd.nextFloat());
                y = Math.min(620f, y + 80f);
            }
            return x;
        }
        if (a.type == 1) {
            // 水平摆动：摆幅收窄，使它在任一相位都够得着，不必赌时机
            float amp = lerp(35f, 70f, rnd.nextFloat() * (0.4f + 0.6f * difficulty));
            float period = lerp(2.4f, 4.2f, rnd.nextFloat());
            float phase = rnd.nextFloat() * 6.2832f;
            float w = Math.min(a.width, 130f);
            float baseX = a.left + amp;
            lv.movers.add(new Mover(baseX, a.top - PLATFORM_TH, w, PLATFORM_TH,
                    false, amp, period, phase));
            float right = baseX + w + amp;
            // 摆动平台之后紧跟一块静态中继台，避免出现「长距离只能靠平台」的死段
            Rect relay = new Rect(right + 55f, a.top - PLATFORM_TH, 150f, PLATFORM_TH);
            lv.platforms.add(relay);
            return relay.right();
        }
        // 垂直摆动：最低点到最高点都在包络内
        float amp = lerp(40f, 80f, rnd.nextFloat() * (0.4f + 0.6f * difficulty));
        float period = lerp(2.6f, 4.4f, rnd.nextFloat());
        float phase = rnd.nextFloat() * 6.2832f;
        float w = Math.min(a.width, 130f);
        lv.movers.add(new Mover(a.left, a.top - PLATFORM_TH - amp, w, PLATFORM_TH,
                true, amp, period, phase));
        Rect relay2 = new Rect(a.left + w + 55f, a.top - PLATFORM_TH, 150f, PLATFORM_TH);
        lv.platforms.add(relay2);
        return relay2.right();
    }

    /** 最后一段：逐块平台把当前点接到终点台，间距锁在包络内。 */
    private static void closeGap(Level lv, Random rnd, float x, float y,
                                 float goalLeft, float goalTop) {
        int guard = 0;
        while (goalLeft - x > GAP_FLAT * 0.9f && guard++ < 24) {
            float w = 150f;
            float gap = Math.min(GAP_FLAT * 0.8f, Math.max(40f, goalLeft - x - w - 20f));
            float progress = clamp((x + gap) / Math.max(1f, goalLeft), 0f, 1f);
            float ny = clamp(y + (goalTop - y) * progress * 0.9f, 110f, 600f);
            if (Math.abs(ny - y) > RISE_MAX * 0.8f) {
                ny = y + Math.signum(goalTop - y) * RISE_MAX * 0.8f;
            }
            if (ny > y + 10f) gap = Math.max(gap, MIN_CLEARANCE);
            Rect r = new Rect(x + gap, ny - PLATFORM_TH, w, PLATFORM_TH);
            lv.platforms.add(r);
            x = r.right();
            y = r.top();
        }
        if (goalLeft - x > 30f) {
            lv.platforms.add(new Rect(
                    x + Math.min(GAP_FLAT * 0.8f, goalLeft - x - 15f),
                    goalTop - PLATFORM_TH, 130f, PLATFORM_TH));
        }
    }

    /** 尖刺悬在平台正上方较高处，低跳即可安全通过，不切断通路。 */
    private static void maybeSpike(Level lv, Rect plat, Random rnd, int index, float difficulty) {
        if (!allowSpikes(index)) return;
        if (plat.w < 160f) return;
        if (rnd.nextFloat() > 0.10f + 0.22f * difficulty) return;
        float sw = Math.min(48f, plat.w * 0.35f);
        float sx = plat.x + plat.w * 0.32f;
        float sy = plat.top() + 190f;
        if (sy + 16f > C.LEVEL_H - 30f) return;
        lv.spikes.add(new Rect(sx, sy, sw, 16f));
    }

    /** 兜底：等间距、等高的宽平台阶梯，几何上必然可达。 */
    private static Level conservative(int index) {
        Level lv = new Level(index);
        float startY = 150f;
        lv.solids.add(new Rect(40f, startY - PLATFORM_TH, 220f, PLATFORM_TH));
        lv.startX = 90f;
        lv.startY = startY;
        float goalLeft = lv.width - 320f;
        float goalTop = 230f;
        lv.solids.add(new Rect(goalLeft, goalTop - PLATFORM_TH, lv.width - goalLeft, PLATFORM_TH));
        lv.solids.add(new Rect(lv.width - 24f, goalTop, 24f, 320f));
        lv.goal = new Rect(goalLeft + 40f, 0f, 40f, C.LEVEL_H);
        lv.boundRight = lv.width - 26f;

        float x = 320f;
        float y = startY;
        int i = 0;
        while (x < goalLeft - 200f) {
            lv.platforms.add(new Rect(x, y - PLATFORM_TH, 160f, PLATFORM_TH));
            x += 160f + 100f;
            if (++i % 5 == 0) y = Math.min(560f, y + 80f);
        }
        lv.platforms.add(new Rect(Math.min(x, goalLeft - 170f), y - PLATFORM_TH, 160f, PLATFORM_TH));
        return lv;
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
