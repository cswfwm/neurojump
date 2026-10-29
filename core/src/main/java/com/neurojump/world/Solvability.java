package com.neurojump.world;

import java.util.ArrayList;
import java.util.List;

/**
 * 独立于神经网络的自动求解器：用与游戏完全相同的物理体逐落点推演。
 *
 * 生成关卡时用它做自检（见 LevelFactory.build），也用于运行期校验：
 * 只要它能走通，就证明存在一条无需神经网络的可行路线，即该层确实可通关。
 *
 * 每段穷举「等待时长 × 水平意图 × 一段/二段跳 × 二段跳时机」，取第一条成功落到
 * 目标台面的轨迹。移动平台按其摆动范围参与评估。
 */
public final class Solvability {
    private static final float DT = 1f / 60f;
    private static final float MAX_TOTAL_TIME = 120f;

    private Solvability() {}

    public static boolean check(Level lv) {
        List<Pad> statics = staticPads(lv);
        if (statics.isEmpty()) return false;
        List<Pad> all = new ArrayList<>(statics);
        for (Mover m : lv.movers) all.add(moverPad(m));
        all.sort((a, b) -> Float.compare(a.left, b.left));

        AgentBody body = new AgentBody();
        body.reset(lv.startX, lv.startY);
        float t = 0f;
        Pad goalPad = statics.get(statics.size() - 1);

        int guard = 0;
        while (guard++ < 90) {
            if (t > MAX_TOTAL_TIME) return false;
            if (body.centerX() >= lv.goal.x - 8f) return true;

            Res found = null;
            for (int i = 0; i < all.size(); i++) {
                Pad p = all.get(i);
                if (p == goalPad) continue;
                if (p.right <= body.centerX() + 10f) continue;
                if (p.left > lv.goal.x) break;
                Res r = reach(lv, body, p, t);
                if (r != null) { found = r; break; }
            }
            if (found == null) {
                found = reach(lv, body, goalPad, t);
                if (found == null) return false;
            }
            body = found.body;
            t = found.t;
        }
        return body.centerX() >= lv.goal.x - 8f;
    }

    private static final class Pad {
        float left, right, top;
        Pad(float l, float r, float tp) { left = l; right = r; top = tp; }
    }

    private static Pad moverPad(Mover m) {
        if (m.vertical) {
            return new Pad(m.baseX, m.baseX + m.w, m.baseY + m.h + m.amplitude);
        }
        return new Pad(m.baseX - m.amplitude, m.baseX + m.w + m.amplitude, m.baseY + m.h);
    }

    private static List<Pad> staticPads(Level lv) {
        List<Pad> pads = new ArrayList<>();
        for (Rect s : lv.solids) pads.add(new Pad(s.x, s.right(), s.top()));
        for (Rect p : lv.platforms) pads.add(new Pad(p.x, p.right(), p.top()));
        pads.add(new Pad(lv.goal.x, lv.goal.x + lv.goal.w, lv.goal.y));
        pads.sort((a, b) -> Float.compare(a.left, b.left));
        return pads;
    }

    private static final class Res {
        AgentBody body;
        float t;
    }

    private static Res reach(Level lv, AgentBody from, Pad target, float t0) {
        float[] waits = {0f, 0.08f, 0.16f, 0.26f, 0.38f, 0.5f, 0.65f, 0.85f, 1.1f, 1.4f};
        float[] moves = {1f, 0.85f, 0.65f, 0.45f};
        for (float wait : waits) {
            for (float mv : moves) {
                for (int jumps = 1; jumps <= 2; jumps++) {
                    for (int k = 0; k < 3; k++) {
                        float djAt = 0.20f + 0.11f * k;
                        Res r = attempt(lv, from, target, t0, wait, mv, jumps, djAt);
                        if (r != null) return r;
                    }
                }
            }
        }
        return null;
    }

    private static Res attempt(Level lv, AgentBody from, Pad target, float t0,
                               float wait, float mv, int jumps, float djAt) {
        AgentBody b = from.copy();
        float t = t0;
        for (float e = 0f; e < wait; e += DT) {
            b.step(lv, t, 0f, false, DT);
            t += DT;
            if (b.touchesSpike(lv) || b.fellOut()) return null;
        }
        boolean jumped1 = false;
        boolean jumped2 = false;
        float air = 0f;
        while (air < 2.8f) {
            boolean press = false;
            if (!jumped1) { press = true; jumped1 = true; }
            else if (jumps == 2 && !jumped2 && air >= djAt) { press = true; jumped2 = true; }

            b.step(lv, t, mv, press, DT);
            t += DT;
            air += DT;

            if (b.touchesSpike(lv) || b.fellOut()) return null;
            if (b.onGround && air > 0.05f) {
                boolean onTarget = b.centerX() >= target.left - 14f
                        && b.centerX() <= target.right + 14f
                        && Math.abs(b.y - target.top) < 8f;
                if (!onTarget) return null;   // 落到别处，本次策略失败
                Res r = new Res();
                r.body = b;
                r.t = t;
                return r;
            }
        }
        return null;
    }
}
