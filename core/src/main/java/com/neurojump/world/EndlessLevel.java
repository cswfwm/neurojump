package com.neurojump.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 无限跑分关卡：一条永远走不到尽头的横向跑酷。
 *
 * 与课程关卡的区别：
 *  - 没有终点，score 由「越过多少段」决定，因此比的是谁活得更久、跑得更远；
 *  - 每隔 SEGMENT 像素生成一段新地形，动态追加，边跑边生成；
 *  - 移动平台与尖刺按比例混入，全部局限在跳跃可达范围内，保证公平。
 *
 * 生成是「按需扩展」的：ensure() 只补齐角色前方一定范围，长期运行也不会无限占内存。
 */
public class EndlessLevel extends Level {

    /** 每段的水平长度。 */
    public static final float SEGMENT = 520f;
    /** 提前生成多远的距离，避免跑出已生成区域。 */
    private static final float LOOKAHEAD = 2600f;
    /** 保留身后多远的几何（再远就裁掉，避免长时间跑分堆积对象）。 */
    private static final float KEEP_BEHIND = 3200f;

    private final Random rnd;
    private int segmentsBuilt;
    /** 已铺地形的右边界与落脚高度。用数值游标推进，避免依赖会被裁掉的对象列表。 */
    private float cursorRight = 300f;
    private float cursorTop = 150f;

    /** 每次扩展地形时回调，供外部提示「又前进了多少」。 */
    public interface Listener {
        void onExtended(EndlessLevel level, int segments, float maxX);
    }

    private final List<Listener> listeners = new ArrayList<>();

    /** 关卡风格：MIXED 混合地形，TIMING 纯移动平台（专门练卡点）。 */
    public enum Style { MIXED, TIMING }

    private final Style style;

    public EndlessLevel(int seed) {
        this(seed, Style.MIXED);
    }

    public EndlessLevel(int seed, Style style) {
        super(0);
        this.style = style;
        this.endless = true;
        this.rnd = new Random(seed);
        // 无限关卡没有终点，但父类的 reachedGoal / 里程碑逻辑仍会读 goal，
        // 这里把它放到极远处，保证不会被误判为通关。
        this.goal = new Rect(1e9f, 0f, 10f, C.LEVEL_H);
        buildStart();
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    /** 已生成的段数，也等于当前「跑分段」的进度。 */
    public int segments() {
        return segmentsBuilt;
    }

    private void buildStart() {
        // 起点台：足够宽，给模型一点起跑距离
        solids.add(new Rect(40f, 132f, 260f, 18f));
        startX = 90f;
        startY = 150f;
        cursorRight = 300f;
        cursorTop = 150f;
        buildUntil(500f);
    }

    /**
     * 保证 (x + LOOKAHEAD) 之前的地形都已生成；同时裁掉身后过远的几何。
     * 训练线程与渲染线程都会调用，这里用自身做锁保护。
     */
    /**
     * 保证 x 前方地形已生成，并回收远在身后的部分。
     *
     * 裁切边界不会越过起点保护区：训练时不同个体从不同位置起跑、演示角色又可能
     * 回到起点重跑，若单纯按当前 x 裁切，起点附近的平台会被删光，画面只剩出生台。
     */
    public synchronized void ensure(float x) {
        buildUntil(x + LOOKAHEAD);
        // 裁切边界取「调用点身后」与「保留点身后」的较小者。
        float boundary = x - KEEP_BEHIND;
        if (keepFromX >= 0f) boundary = Math.min(boundary, keepFromX - KEEP_BEHIND);
        trimBehind(boundary);
    }

    /**
     * 指定「从这里往右的地形必须保留」。
     *
     * 训练时上百个个体并发推进，各自关心不同位置。如果让每个线程都来改一个共享值，
     * 裁切边界就会随线程调度乱跳，同一个网络在不同负载下跑出不同结果。
     * 因此这里只取最小值：所有调用方共同决定「谁最靠后就保护到谁」。
     */
    public synchronized void keepFrom(float x) {
        if (keepFromX < 0f || x < keepFromX) keepFromX = x;
    }

    /** 每代开始重置，避免旧的最小值永久阻止裁切。 */
    public synchronized void resetKeep() {
        keepFromX = -1f;
    }

    /** 需要保留地形的最左位置，-1 表示不限制。 */
    private float keepFromX = -1f;

    /**
     * 一直铺到「已铺地形的右边界」超过 targetX。
     * 判据用 cursorRight 而不是段号×段长：每段地形实际宽度与理论值不完全一致，
     * 用段号推算会让游标追不上目标，平台被生成到身后再被裁掉。
     */
    private void buildUntil(float targetX) {
        int guard = 0;
        while (cursorRight < targetX && guard++ < 4096) {
            buildSegment(segmentsBuilt);
            segmentsBuilt++;
            float maxX = cursorRight;
            width = Math.max(width, maxX + 1200f);
            boundRight = width - 26f;
            for (int i = 0; i < listeners.size(); i++) {
                listeners.get(i).onExtended(this, segmentsBuilt, maxX);
            }
        }
    }

    /** 从上一段的落点继续往前铺一段地形。 */
    private void buildSegment(int index) {
        if (style == Style.TIMING) {
            buildTimingSegment(index);
            return;
        }
        float left = cursorRight;
        float top = cursorTop;

        // 开局前几段用最简单的等高水平平台，先保证「能跑起来」
        if (index < 4) {
            float w = 170f + rnd.nextFloat() * 60f;
            addPlatform(left + 70f + rnd.nextFloat() * 40f, top, w);
            return;
        }

        int roll = rnd.nextInt(100);
        // 难度随段数缓慢上升，但受物理包络约束，最多到 1
        float difficulty = Math.min(1f, (index - 4) / 40f);

        if (roll < 34) {
            addPlatform(left + gap(difficulty, 60f, 130f), top, width());
        } else if (roll < 52) {
            // 上跳台阶：抬升不超过 85px，单跳即可够到
            addPlatform(left + gap(difficulty, 60f, 110f),
                    clampTop(top + rise(45f, 85f)), width());
        } else if (roll < 66) {
            // 下坡：下降不超过 130px
            addPlatform(left + gap(difficulty, 60f, 150f),
                    clampTop(top - rise(50f, 130f)), width());
        } else if (roll < 80) {
            addMoverH(left, top, difficulty);
        } else if (roll < 92) {
            addMoverV(left, top, difficulty);
        } else {
            Rect plat = addPlatform(left + gap(difficulty, 60f, 120f), top,
                    width() + 60f);
            addSpikeAbove(plat);
        }

        maybeAddAirSpike(difficulty);
        // 游标已由 addPlatform / addMover* 按真实落脚点推进；
        // 这里不再按段号强行推进，否则会把平台甩在身后造成超大间隙。
    }

    // ===== 卡点风格：整条跑酷由移动平台构成，专门训练起跳时机 =====

    private static final int TIMING_EASY = 3;
    /** 开局静态平台段数，用来建立「向前跑」的基本行为。 */
    private static final int STATIC_LEAD = 3;

    /**
     * 一段 = 一块移动平台 + 一块静态中继台。
     * 移动平台是「卡点」主体，中继台保证踩空也有缓冲，避免整关不可通过。
     */
    private void buildTimingSegment(int index) {
        // 开局铺几段静态宽台：先让模型学会「一直向右跑 + 该跳就跳」，
        // 再逐步引入移动平台。否则随机初始化时几乎全员在第一步就摔死，
        // 进化拿不到任何有效梯度。
        if (index < STATIC_LEAD) {
            float w = 200f + rnd.nextFloat() * 80f;
            Rect p = new Rect(cursorRight + 45f, cursorTop - 18f, w, 18f);
            platforms.add(p);
            cursorRight = p.right();
            return;
        }

        // 难度随段数平滑上升：前几段摆幅小、周期慢，之后逐步加快加宽。
        float d = Math.min(1f, Math.max(0f, (index - STATIC_LEAD) / 30f));
        int roll = rnd.nextInt(100);
        if (roll < 45) {
            timingHorizontal(false, d);
        } else if (roll < 80) {
            timingVertical(false, d);
        } else {
            timingPair(false, d);
        }
    }

    /** 水平摆动平台：必须等它靠近才能上。 */
    private void timingHorizontal(boolean easy, float d) {
        float w = 130f + rnd.nextFloat() * 40f;
        float amp = easy ? 45f : 45f + 40f * d + rnd.nextFloat() * 18f;
        float period = easy ? 3.6f : 3.6f - 0.9f * d + rnd.nextFloat() * 0.7f;
        float phase = rnd.nextFloat() * 6.2832f;
        float gap = easy ? 50f + rnd.nextFloat() * 22f
                : 50f + 70f * d + rnd.nextFloat() * 35f;

        float baseX = cursorRight + gap + amp;
        float y = clampTop(cursorTop) - 18f;
        movers.add(new Mover(baseX, y, w, 18f, false, amp, period, phase));
        timingSpike(baseX, y, amp, easy);

        Rect relay = new Rect(baseX + w + amp + 40f, y, 130f, 18f);
        platforms.add(relay);
        cursorRight = relay.right();
        cursorTop = y + 18f;
    }

    /** 垂直摆动平台：必须等它升到够得着的高度。 */
    private void timingVertical(boolean easy, float d) {
        float w = 130f + rnd.nextFloat() * 40f;
        float amp = easy ? 40f : 40f + 36f * d + rnd.nextFloat() * 16f;
        float period = easy ? 3.8f : 3.8f - 1.3f * d + rnd.nextFloat() * 0.8f;
        float phase = rnd.nextFloat() * 6.2832f;
        float gap = easy ? 50f + rnd.nextFloat() * 22f
                : 50f + 70f * d + rnd.nextFloat() * 35f;

        float baseX = cursorRight + gap;
        float top = clampTop(cursorTop);
        movers.add(new Mover(baseX, top - 18f - amp, w, 18f, true, amp, period, phase));

        Rect relay = new Rect(baseX + w + 40f, top - 18f, 130f, 18f);
        platforms.add(relay);
        cursorRight = relay.right();
        cursorTop = top;
    }

    /** 一对反向摆动的平台：要先等 A 靠近，跳上去后再等 B 靠近。 */
    private void timingPair(boolean easy, float d) {
        float w = 120f;
        float period = easy ? 4.0f : 4.0f - 1.4f * d + rnd.nextFloat() * 0.8f;
        float amp = easy ? 45f : 45f + 38f * d + rnd.nextFloat() * 16f;
        float y = clampTop(cursorTop) - 18f;

        float baseA = cursorRight + 60f + amp;
        movers.add(new Mover(baseA, y, w, 18f, false, amp, period, 0f));

        // 两块对摆平台之间的空档随难度拉大：这是最需要「空中连跳」的地方
        float between = 60f + 90f * d + rnd.nextFloat() * 30f;
        float baseB = baseA + w + amp + between + amp;
        movers.add(new Mover(baseB, y, w, 18f, false, amp, period, (float) Math.PI));

        Rect relay = new Rect(baseB + w + amp + 40f, y, 130f, 18f);
        platforms.add(relay);
        cursorRight = relay.right();
        cursorTop = y + 18f;
    }

    /**
     * 在摆动路径上方放尖刺，逼模型挑正确相位。
     * 高度抬到 +190 以上：低于这个值时会正好卡在跳跃弧线中段，
     * 与移动平台叠加后变成「无论何时跳都撞」，那就不再是练时机而是不可解。
     */
    private void timingSpike(float baseX, float y, float amp, boolean easy) {
        if (easy || rnd.nextFloat() > 0.16f) return;
        float sx = baseX + amp * 0.6f + rnd.nextFloat() * 40f;
        float sy = y + 195f + rnd.nextFloat() * 80f;
        if (sy + 16f > C.LEVEL_H - 20f) return;
        spikes.add(new Rect(sx, sy, 44f, 16f));
    }

    private float gap(float difficulty, float min, float max) {
        // 水平间隙上限锁在 165px，二段跳水平跨度约 328px，留足容错
        return Math.min(165f, min + (max - min) * (0.5f + 0.5f * rnd.nextFloat()));
    }

    /** 让落脚高度始终落在 [140, 520] 之间，避免贴地或超出跳跃能力。 */
    private float clampTop(float top) {
        return clamp(top, 150f, 520f);
    }

    private float rise(float min, float max) {
        return min + (max - min) * rnd.nextFloat();
    }

    private float width() {
        return 130f + rnd.nextFloat() * 90f;
    }

    private Rect addPlatform(float x, float top, float w) {
        float t = clampTop(top);
        Rect r = new Rect(x, t - 18f, w, 18f);
        platforms.add(r);
        cursorRight = Math.max(cursorRight, r.right());
        cursorTop = t;
        return r;
    }

    /**
     * 水平摆动平台 + 后方中继台。
     * 平台在最左端时与出发台的距离必须落在包络内，否则玩家没有起跳机会。
     */
    private void addMoverH(float left, float top, float difficulty) {
        float amp = 30f + rnd.nextFloat() * 35f * (0.5f + 0.5f * difficulty);
        float period = 2.2f + rnd.nextFloat() * 2.0f;
        float phase = rnd.nextFloat() * 6.2832f;
        float w = Math.min(width(), 130f);
        // baseX 是摆动中心，最左端 = baseX - amp，控制它距上一块的间隙
        float baseX = left + gap(difficulty, 55f, 90f) + amp + w * 0.3f;
        float y = clampTop(top) - 18f;
        movers.add(new Mover(baseX, y, w, 18f, false, amp, period, phase));
        Rect relay = new Rect(baseX + w + amp + 55f, y, 155f, 18f);
        platforms.add(relay);
        cursorRight = Math.max(cursorRight, relay.right());
        cursorTop = y + 18f;
    }

    /**
     * 垂直摆动平台 + 后方中继台。
     * 摆动幅度限制在二段跳能应付的高度内，且最低点也不能低于出发台太多。
     */
    private void addMoverV(float left, float top, float difficulty) {
        float amp = 35f + rnd.nextFloat() * 30f * (0.5f + 0.5f * difficulty);
        float period = 2.4f + rnd.nextFloat() * 2.0f;
        float phase = rnd.nextFloat() * 6.2832f;
        float w = Math.min(width(), 130f);
        float platformTop = clampTop(top);
        float baseX = left + gap(difficulty, 55f, 95f);
        // baseY 是摆动中心，让最低点仍在地形高度附近，避免出现够不着的空洞
        movers.add(new Mover(baseX, platformTop - 18f - amp, w, 18f,
                true, amp, period, phase));
        Rect relay = new Rect(baseX + w + 55f, platformTop - 18f, 155f, 18f);
        platforms.add(relay);
        cursorRight = Math.max(cursorRight, relay.right());
        cursorTop = platformTop;
    }

    /** 平台正上方较高处悬一枚尖刺：低跳可过，高跳会撞。 */
    private void addSpikeAbove(Rect plat) {
        float sw = Math.min(48f, plat.w * 0.35f);
        float sx = plat.x + plat.w * 0.32f;
        float sy = plat.top() + 190f;
        if (sy + 16f < C.LEVEL_H - 30f) {
            spikes.add(new Rect(sx, sy, sw, 16f));
        }
    }

    /** 空中尖刺：放在两块平台之间的空档上方，促使模型压低跳跃弧线。 */
    private void maybeAddAirSpike(float difficulty) {
        if (rnd.nextFloat() > 0.10f + 0.25f * difficulty) return;
        float sx = cursorRight + 120f + rnd.nextFloat() * 120f;
        float sy = 380f + rnd.nextFloat() * 200f;
        spikes.add(new Rect(sx, sy, 44f, 16f));
    }

    /** 裁掉身后过远的几何。移动平台按静态基准位置判断，避免摆动时被误删。 */
    private void trimBehind(float minX) {
        for (int i = platforms.size() - 1; i >= 0; i--) {
            if (platforms.get(i).right() < minX) platforms.remove(i);
        }
        for (int i = solids.size() - 1; i >= 0; i--) {
            Rect r = solids.get(i);
            if (r.x < 300f) continue;   // 起点台永久保留
            if (r.right() < minX) solids.remove(i);
        }
        for (int i = movers.size() - 1; i >= 0; i--) {
            Mover m = movers.get(i);
            float left = m.vertical ? m.baseX : m.baseX - m.amplitude;
            float right = m.vertical ? m.baseX + m.w : m.baseX + m.w + m.amplitude;
            if (right < minX) movers.remove(i);
        }
        for (int i = spikes.size() - 1; i >= 0; i--) {
            if (spikes.get(i).right() < minX) spikes.remove(i);
        }
    }

    /** 跑分：以越过的段数与最远距离计分。 */
    public float score(float x) {
        return Math.max(0f, x - startX);
    }

    /** 无限模式没有终点，这个判定永远为 false，仅用于兼容父类接口。 */
    public boolean reachedGoal() {
        return false;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
