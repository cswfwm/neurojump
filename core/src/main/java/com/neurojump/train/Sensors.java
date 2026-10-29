package com.neurojump.train;

import com.neurojump.world.AgentBody;
import com.neurojump.world.C;
import com.neurojump.world.Level;
import com.neurojump.world.Mover;
import com.neurojump.world.Rect;

/**
 * 神经网络可见的世界：一组归一化传感器读数。
 *
 * 设计要点：动作是「一直向右跑 + 该跳的时候跳」，所以最关键的信息不是
 * 「脚下有多深」，而是「前方还有多少落脚空间」与「下一块平台在哪」。
 * 这里把手感上真正有用的量都给出：离边缘多远、边缘外是什么、最近的
 * 落脚点相对位置、移动平台何时会到位、有没有尖刺挡路。
 */
public final class Sensors {
    /**
     * 自身 6 + 前方地形 8 + 落脚点 4 + 移动平台 6 + 跳跃可行性 3 + 危险 2。
     * 移动平台的 6 项里有两项专门描述「还要等多久才到最佳落点」，
     * 这是卡点起跳的核心依据。
     */
    public static final int COUNT = 29;

    private final float[] input = new float[COUNT];

    public float[] read(Level lv, AgentBody b, float t) {
        int i = 0;
        float cx = b.centerX();
        float foot = b.bottom();

        // --- 自身状态 ---
        input[i++] = b.vx / C.MOVE_MAX;
        input[i++] = b.vy / C.JUMP_V;
        input[i++] = b.onGround ? 1f : -1f;
        input[i++] = (b.jumpsLeft - 1);
        // 箱子：剩余数量与「现在用能否补一段跳」，让网络学会空中续跳
        input[i++] = b.chests / (float) C.CHEST_MAX;
        input[i++] = (!b.onGround && b.chests > 0 && b.jumpsLeft <= 0) ? 1f : -1f;

        // --- 前方落脚空间（核心信息）---
        // 当前站立的台面还剩多少水平余量；很短就意味着「马上要跳」
        float edgeAhead = walkableAhead(lv, b, 260f);
        input[i++] = clamp(edgeAhead / 260f, 0f, 1f);
        // 边缘外侧是空还是有地面：有地面则不必冒险起跳
        input[i++] = hasFloorBetween(lv, cx, cx + 40f, foot) ? 1f : -1f;
        input[i++] = hasFloorBetween(lv, cx + 40f, cx + 120f, foot) ? 1f : -1f;
        // 头顶空间：低矮时不能起跳
        input[i++] = clamp(lv.ceilingDistance(cx, b.top()) / 300f, 0f, 1f);
        // 脚下与前方地面深度：判断高低差
        input[i++] = norm(lv.groundDepth(cx, foot - 2f, 400f), 400f);
        input[i++] = norm(lv.groundDepth(cx + 110f, foot - 2f, 400f), 400f);
        // 前方是否有墙（撞墙时要跳过去）
        input[i++] = clamp(lv.wallDistance(cx, foot + 10f, 220f) / 220f, 0f, 1f);

        // --- 最近的落脚点（相对位置 + 高低差）---
        Landing land = nearestLanding(lv, b, t);
        input[i++] = clamp((land.dx) / 300f, -1f, 1f);
        input[i++] = clamp((land.dy) / 200f, -1f, 1f);
        input[i++] = clamp(land.width / 160f, 0f, 1f);
        input[i++] = land.found ? 1f : -1f;

        // --- 移动平台时序 ---
        writeMoverSignals(lv, b, t, i);
        i += 6;

        // --- 跳跃可行性（把「现在跳能不能过去」直接算给网络）---
        writeJumpFeasibility(lv, b, t, i);
        i += 3;

        // --- 危险 ---
        input[i++] = spikeAhead(lv, b) ? 1f : -1f;
        input[i] = nearSpike(lv, b) ? 1f : -1f;
        return input;
    }

    /**
     * 起跳可行性的三个读数，比原始几何更好用：
     *  0 以当前速度在边缘起跳、用现有跳跃次数，能否落到前方落脚点（1 能 / -1 不能）
     *  1 目标平台的当前水平速度（正=远离，负=靠近），归一化
     *  2 到边缘的距离占「起跳可跨距离」的比例，越接近 1 越该跳
     */
    private void writeJumpFeasibility(Level lv, AgentBody b, float t, int base) {
        Landing land = nearestLanding(lv, b, t);
        float room = walkableAhead(lv, b, 260f);

        // 跳跃能力：起跳后水平能覆盖的距离（按二段跳保守估计）
        float reach = 300f;
        if (b.jumpsLeft <= 0) reach = 90f;

        boolean canMakeIt = land.found && land.dx <= reach + land.width * 0.5f
                && land.dy < 210f && land.dy > -600f;
        // 站在地面且目标在可及范围内，才算「可以跳过去」
        input[base] = (canMakeIt && b.onGround) ? 1f : -1f;

        // 目标平台的移动速度
        float speed = 0f;
        for (int i = 0; i < lv.movers.size(); i++) {
            Mover m = lv.movers.get(i);
            float dx = Math.abs(m.xAt(t) - b.centerX());
            if (dx > 300f) continue;
            speed = (m.xAt(t + 0.05f) - m.xAt(t)) / 0.05f;
            break;
        }
        input[base + 1] = clamp(speed / 200f, -1f, 1f);

        input[base + 2] = clamp(room / 260f, 0f, 1f);
    }

    /** 从当前位置向前，还能在多少距离内保持有地面（到边缘的距离）。 */
    private static float walkableAhead(Level lv, AgentBody b, float max) {
        float foot = b.bottom();
        for (float d = 0; d < max; d += 12f) {
            if (!hasFloorBetween(lv, b.centerX() + d, b.centerX() + d + 12f, foot)) return d;
        }
        return max;
    }

    /** [x1,x2] 区间内、脚下方不远处是否有可站立面。 */
    private static boolean hasFloorBetween(Level lv, float x1, float x2, float foot) {
        for (Rect r : lv.solids) {
            if (r.right() <= x1 || r.x >= x2) continue;
            if (Math.abs(r.top() - foot) < 6f) return true;
        }
        for (Rect r : lv.platforms) {
            if (r.right() <= x1 || r.x >= x2) continue;
            if (Math.abs(r.top() - foot) < 6f) return true;
        }
        return false;
    }

    /** 最近的前方落脚点：平台或移动平台的顶面。 */
    private static final class Landing {
        float dx, dy, width;
        boolean found;
    }

    private static Landing nearestLanding(Level lv, AgentBody b, float t) {
        Landing best = new Landing();
        float cx = b.centerX();
        float foot = b.bottom();
        float bestDx = Float.MAX_VALUE;

        for (Rect r : lv.platforms) {
            float rx = Math.max(r.x, Math.min(cx + 60f, r.right()));
            float dx = rx - cx;
            if (dx < -10f) continue;
            if (dx < bestDx) {
                bestDx = dx;
                best.dx = dx;
                best.dy = r.top() - foot;
                best.width = r.w;
                best.found = true;
            }
        }
        for (Rect r : lv.solids) {
            float rx = Math.max(r.x, Math.min(cx + 60f, r.right()));
            float dx = rx - cx;
            if (dx < -10f) continue;
            if (dx < bestDx) {
                bestDx = dx;
                best.dx = dx;
                best.dy = r.top() - foot;
                best.width = r.w;
                best.found = true;
            }
        }
        for (Mover m : lv.movers) {
            float mx = m.xAt(t);
            float top = m.yAt(t) + m.h;
            float dx = mx - cx;
            if (dx < -m.w) continue;
            if (dx < bestDx) {
                bestDx = dx;
                best.dx = dx;
                best.dy = top - foot;
                best.width = m.w;
                best.found = true;
            }
        }
        return best;
    }

    /**
     * 最近移动平台的时序信息：
     *  0 相对水平位置（正 = 在前方）
     *  1 相对高度
     *  2 未来 1.2s 内它能否靠到我够得着的位置
     *  3 它正在靠近还是远离
     */
    private void writeMoverSignals(Level lv, AgentBody b, float t, int base) {
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < lv.movers.size(); i++) {
            Mover m = lv.movers.get(i);
            float mx = m.xAt(t) + m.w * 0.5f;
            float my = m.yAt(t) + m.h;
            float d = Math.abs(mx - b.centerX()) + Math.abs(my - b.bottom()) * 1.4f;
            if (d < bestScore) {
                bestScore = d;
                best = i;
            }
        }
        if (best < 0) {
            input[base] = 0f;
            input[base + 1] = 0f;
            input[base + 2] = -1f;
            input[base + 3] = -1f;
            input[base + 4] = 1f;
            input[base + 5] = 0f;
            return;
        }

        Mover m = lv.movers.get(best);
        float cx = b.centerX();
        float cy = b.bottom();
        input[base] = clamp((m.xAt(t) + m.w * 0.5f - cx) / 260f, -1f, 1f);
        input[base + 1] = clamp((m.yAt(t) + m.h - cy) / 200f, -1f, 1f);

        float minDist = Float.MAX_VALUE;
        boolean approaching = false;
        for (int s = 1; s <= 24; s++) {
            float tt = t + 0.05f * s;
            float dist = Math.abs(m.xAt(tt) - cx) + Math.abs(m.yAt(tt) + m.h - cy) * 1.2f;
            if (dist < minDist) {
                minDist = dist;
                float prev = Math.abs(m.xAt(tt - 0.05f) - cx)
                        + Math.abs(m.yAt(tt - 0.05f) + m.h - cy) * 1.2f;
                approaching = dist < prev;
            }
        }
        input[base + 2] = clamp(1f - minDist / 260f, -1f, 1f);
        input[base + 3] = approaching ? 1f : -1f;

        // 时间窗口：还要等多久平台才到最佳落点（秒）。
        // 这是「卡点」真正需要的量——网络得知道现在跳还是再等两拍。
        float waitTime = 0f;
        float bestDist = Float.MAX_VALUE;
        for (int s = 0; s <= 40; s++) {
            float tt = t + 0.05f * s;
            float dist = Math.abs(m.xAt(tt) - cx) + Math.abs(m.yAt(tt) + m.h - cy) * 1.2f;
            if (dist < bestDist) {
                bestDist = dist;
                waitTime = 0.05f * s;
            }
        }
        input[base + 4] = clamp(waitTime / 1.5f, 0f, 1f);
        // 最佳落点相对起跳点的水平距离：判断「跳过去够不够得着」
        input[base + 5] = clamp((m.xAt(t + waitTime) - cx) / 260f, -1f, 1f);
    }

    private static boolean spikeAhead(Level lv, AgentBody b) {
        float cx = b.centerX();
        float foot = b.bottom();
        for (Rect s : lv.spikes) {
            if (s.x > cx && s.x - cx < 200f
                    && s.y > foot - 40f && s.y < foot + 200f) {
                return true;
            }
        }
        return false;
    }

    private static boolean nearSpike(Level lv, AgentBody b) {
        float cx = b.centerX();
        float cy = b.bottom();
        for (Rect s : lv.spikes) {
            float sx = s.x + s.w * 0.5f;
            float sy = s.y + s.h * 0.5f;
            if (Math.abs(sx - cx) < 160f && Math.abs(sy - cy) < 170f) return true;
        }
        return false;
    }

    private static float norm(float depth, float max) {
        if (depth < 0f) return 0f;
        return clamp(1f - depth / max, 0f, 1f);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
