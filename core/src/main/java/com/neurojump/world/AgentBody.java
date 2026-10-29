package com.neurojump.world;

/**
 * 角色物理体。训练、试玩、离线验证共用同一实现，保证三者表现一致。
 * 移动平台位置由时间纯函数给出，因此本对象只持有自身状态，可任意复制并行推演。
 */
public final class AgentBody {
    public float x, y;
    public float vx, vy;
    public boolean onGround;
    public int jumpsLeft = C.MAX_JUMPS;
    /** 可用的箱子数量：空中使用可补满跳跃次数并上冲，实现空中连跳。 */
    public int chests = C.CHEST_MAX;
    /** 距离下次发放箱子的累计时间。 */
    private float chestTimer;
    /** 本步是否使用了箱子（供训练奖励与界面提示）。 */
    public boolean usedChestThisStep;
    public int facing = 1;
    /** 跳跃请求的冷却计时，避免一帧内重复触发。 */
    private float jumpCooldown;
    /** 落地后已在地面停留的时间，用于落地缓冲。 */
    private float groundTime;
    /** 当前所站移动平台下标，-1 表示静态面或空中。 */
    public int standingMover = -1;

    private float coyote;
    private float jumpBuffer;
    private float lastT;

    public void reset(float sx, float sy) {
        x = sx;
        y = sy;
        vx = 0;
        vy = 0;
        onGround = true;
        jumpsLeft = C.MAX_JUMPS;
        chests = C.CHEST_MAX;
        chestTimer = 0f;
        usedChestThisStep = false;
        facing = 1;
        jumpCooldown = 0f;
        groundTime = 0f;
        standingMover = -1;
        coyote = C.COYOTE_TIME;
        jumpBuffer = 0f;
        lastT = 0f;
    }

    public void copyTo(AgentBody o) {
        o.x = x; o.y = y; o.vx = vx; o.vy = vy;
        o.onGround = onGround;
        o.jumpsLeft = jumpsLeft;
        o.chests = chests;
        o.chestTimer = chestTimer;
        o.usedChestThisStep = usedChestThisStep;
        o.facing = facing;
        o.jumpCooldown = jumpCooldown;
        o.groundTime = groundTime;
        o.standingMover = standingMover;
        o.coyote = coyote;
        o.jumpBuffer = jumpBuffer;
        o.lastT = lastT;
    }

    public AgentBody copy() {
        AgentBody b = new AgentBody();
        copyTo(b);
        return b;
    }

    public float centerX() { return x + C.AGENT_W * 0.5f; }
    public float bottom() { return y; }
    public float top() { return y + C.AGENT_H; }
    public float centerY() { return y + C.AGENT_H * 0.5f; }

    /**
     * 推进一个物理步。
     * @param t        本步起始时刻（秒），移动平台位置据此推算
     * @param inputX   水平意图 [-1,1]
     * @param jumpHeld 跳跃键是否按住；仅在按下的那一帧起跳
     */
    public void step(Level lv, float t, float inputX, boolean jumpHeld, float dt) {
        float target = clamp(inputX, -1f, 1f) * C.MOVE_MAX;
        boolean grounded = onGround || coyote > 0f;
        float accel;
        if (target == 0f) accel = grounded ? C.GROUND_FRICTION : C.AIR_FRICTION;
        else accel = grounded ? C.MOVE_ACCEL : C.MOVE_ACCEL * 0.8f;
        vx = approach(vx, target, accel * dt);
        if (vx > 0.01f) facing = 1;
        else if (vx < -0.01f) facing = -1;

        // 落地缓冲：刚落地的一小段时间内不接受起跳请求。
        // 没有这条约束时，模型会「落地帧立刻再跳」，永远不在地面停留，
        // 也就学不会观察地形、等待平台。
        if (onGround) groundTime += dt;
        else groundTime = 0f;

        // 跳跃请求：带冷却，同一个意图不会在相邻帧重复消耗二段跳次数
        jumpCooldown = Math.max(0f, jumpCooldown - dt);
        boolean canJumpNow = jumpCooldown <= 0f && (!onGround || groundTime >= C.LANDING_REST);
        boolean jumpPressed = jumpHeld && canJumpNow;
        if (jumpPressed) {
            jumpBuffer = C.JUMP_BUFFER;
            jumpCooldown = C.JUMP_COOLDOWN;
        } else {
            jumpBuffer = Math.max(0f, jumpBuffer - dt);
        }

        if (jumpBuffer > 0f) {
            if (onGround || coyote > 0f) {
                vy = C.JUMP_V;
                jumpsLeft = C.MAX_JUMPS - 1;
                onGround = false;
                coyote = 0f;
                jumpBuffer = 0f;
                standingMover = -1;
            } else if (jumpsLeft > 0) {
                vy = C.DOUBLE_JUMP_V;
                jumpsLeft--;
                jumpBuffer = 0f;
                standingMover = -1;
            }
        }

        // 箱子补给：按真实时间发放，而不是按帧，保证不同帧率下一致
        chestTimer += dt;
        while (chestTimer >= C.CHEST_PERIOD) {
            chestTimer -= C.CHEST_PERIOD;
            if (chests < C.CHEST_MAX) chests++;
        }

        // 空中使用箱子：补满跳跃次数并给一次上冲，等价于「凭空多一段跳」
        usedChestThisStep = false;
        if (jumpPressed && !onGround && chests > 0 && jumpsLeft <= 0) {
            chests--;
            jumpsLeft = C.MAX_JUMPS - 1;
            vy = C.CHEST_JUMP_V;
            jumpBuffer = 0f;
            jumpCooldown = C.JUMP_COOLDOWN;
            usedChestThisStep = true;
        }

        vy += C.GRAVITY * dt;
        if (vy < C.MAX_FALL_V) vy = C.MAX_FALL_V;

        float dx = vx * dt;
        float dy = vy * dt;
        int subs = 1 + (int) (Math.max(Math.abs(dx), Math.abs(dy)) / 5f);
        if (subs > 8) subs = 8;
        float sdx = dx / subs;
        float sdy = dy / subs;

        boolean landed = false;
        int landedMover = -1;

        for (int i = 0; i < subs; i++) {
            float st = t + dt * i / subs;
            float stNext = t + dt * (i + 1) / subs;

            // 站在移动平台上：随平台漂移
            if (standingMover >= 0 && standingMover < lv.movers.size()) {
                Mover m = lv.movers.get(standingMover);
                x += m.xAt(stNext) - m.xAt(st);
                y += m.yAt(stNext) - m.yAt(st);
            }

            float prevBottom = y;
            float prevTop = y + C.AGENT_H;

            x += sdx;
            resolveX(lv, st, sdx);

            y += sdy;
            int hit = resolveY(lv, st, sdy, prevBottom, prevTop);
            if (hit != -1) {
                landed = true;
                landedMover = hit;
            }
        }

        if (landed) {
            onGround = true;
            jumpsLeft = C.MAX_JUMPS;
            standingMover = landedMover;
            coyote = C.COYOTE_TIME;
        } else if (onGround && supported(lv, t + dt)) {
            // 仍在同一台面上（水平移动平台带走时也不掉）
            jumpsLeft = C.MAX_JUMPS;
            coyote = C.COYOTE_TIME;
        } else {
            onGround = false;
            standingMover = -1;
            coyote = Math.max(0f, coyote - dt);
        }

        // 关卡左右边界与地面都是硬边界：掉出下界才算失败，左右越界直接挡住
        if (x < lv.boundLeft) { x = lv.boundLeft; vx = 0f; }
        if (x + C.AGENT_W > lv.boundRight) { x = lv.boundRight - C.AGENT_W; vx = 0f; }
        lastT = t;
    }

    private void resolveX(Level lv, float t, float dx) {
        if (dx == 0f) return;
        for (int i = 0; i < lv.solids.size(); i++) {
            Rect s = lv.solids.get(i);
            if (!overlapsY(s) || !overlapsX(s)) continue;
            // 容差：只擦到台面顶边时不算撞墙，避免上升途中被吸附在侧壁上
            if (y >= s.top() - 6f && vy > -60f) continue;
            if (dx > 0f) x = s.x - C.AGENT_W;
            else x = s.right();
            vx = 0f;
        }
        for (int i = 0; i < lv.movers.size(); i++) {
            Mover m = lv.movers.get(i);
            float mx = m.xAt(t), my = m.yAt(t);
            if (y + C.AGENT_H <= my || y >= my + m.h) continue;
            if (x + C.AGENT_W <= mx || x >= mx + m.w) continue;
            if (dx > 0f) x = mx - C.AGENT_W;
            else x = mx + m.w;
            vx = 0f;
        }
    }

    /** 返回落脚的移动平台下标；-2 表示落在静态面上；-1 表示未落地。 */
    private int resolveY(Level lv, float t, float dy, float prevBottom, float prevTop) {
        if (dy > 0f) {
            for (int i = 0; i < lv.solids.size(); i++) {
                Rect s = lv.solids.get(i);
                if (!overlapsX(s)) continue;
                if (prevTop <= s.y && y + C.AGENT_H > s.y) {
                    y = s.y - C.AGENT_H;
                    vy = 0f;
                }
            }
            return -1;
        }
        if (dy >= 0f) return -1;

        float bestTop = -Float.MAX_VALUE;
        int bestMover = -1;

        for (int i = 0; i < lv.solids.size(); i++) {
            Rect s = lv.solids.get(i);
            if (!overlapsX(s)) continue;
            float top = s.top();
            if (prevBottom >= top - 0.5f && y < top && top > bestTop) {
                bestTop = top;
                bestMover = -2;
            }
        }
        for (int i = 0; i < lv.platforms.size(); i++) {
            Rect p = lv.platforms.get(i);
            if (!overlapsX(p)) continue;
            float top = p.top();
            if (prevBottom >= top - 0.5f && y < top && top > bestTop) {
                bestTop = top;
                bestMover = -2;
            }
        }
        for (int i = 0; i < lv.movers.size(); i++) {
            Mover m = lv.movers.get(i);
            float mx = m.xAt(t), my = m.yAt(t);
            if (x + C.AGENT_W <= mx || x >= mx + m.w) continue;
            float top = my + m.h;
            if (prevBottom >= top - 0.5f && y < top && top > bestTop) {
                bestTop = top;
                bestMover = i;
            }
        }
        if (bestMover != -1) {
            y = bestTop;
            vy = 0f;
            return bestMover;
        }
        return -1;
    }

    private boolean supported(Level lv, float t) {
        for (int i = 0; i < lv.solids.size(); i++) {
            Rect s = lv.solids.get(i);
            if (overlapsX(s) && Math.abs(y - s.top()) < 2.5f) return true;
        }
        for (int i = 0; i < lv.platforms.size(); i++) {
            Rect p = lv.platforms.get(i);
            if (overlapsX(p) && Math.abs(y - p.top()) < 2.5f) return true;
        }
        for (int i = 0; i < lv.movers.size(); i++) {
            Mover m = lv.movers.get(i);
            float mx = m.xAt(t);
            if (x + C.AGENT_W <= mx || x >= mx + m.w) continue;
            if (Math.abs(y - (m.yAt(t) + m.h)) < 2.5f) return true;
        }
        return false;
    }

    private boolean overlapsX(Rect r) {
        return x + C.AGENT_W > r.x && x < r.right();
    }

    private boolean overlapsY(Rect r) {
        return y + C.AGENT_H > r.y && y < r.top();
    }

    private boolean overlaps(Rect r) {
        return overlapsX(r) && overlapsY(r);
    }

    public boolean touchesSpike(Level lv) {
        for (int i = 0; i < lv.spikes.size(); i++) {
            if (overlaps(lv.spikes.get(i))) return true;
        }
        return false;
    }

    /** 到达终点：越过终点竖线即通关（平台游戏惯用的旗杆判定，不会因跳过头而失败）。 */
    public boolean reachedGoal(Level lv) {
        return centerX() >= lv.goal.x;
    }

    public boolean fellOut() {
        return y + C.AGENT_H < -30f;
    }

    private static float approach(float v, float target, float maxDelta) {
        if (v < target) return Math.min(v + maxDelta, target);
        if (v > target) return Math.max(v - maxDelta, target);
        return v;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
