package com.neurojump.train;

import com.neurojump.nn.NeuralNet;
import com.neurojump.world.AgentBody;
import com.neurojump.world.C;
import com.neurojump.world.EndlessLevel;
import com.neurojump.world.Mover;

/**
 * 无限跑分中的一次「生命」：从起点出发一直往前跑，直到坠亡或撞刺。
 *
 * 分数按「每个操作」逐步累计，而不是只看最后跑了多远——这样奖励能精确地
 * 落到每一个决策上：
 *  - 前进：按新推进的水平距离给分（主目标）
 *  - 停留：原地不动按时间扣分，压制「站着不动最安全」的惰性解
 *  - 死亡：一次性重罚，让冒险失败明显劣于保守前进
 *  - 连跳：短时间内连续起跳额外加分，鼓励果断的节奏
 *  - 卡点等待：在移动平台靠过来之前减速/站定再起跳，给一次性奖励，
 *    这是「会停下来等时机」的关键引导，否则模型只会一路狂奔然后摔死。
 */
public final class EndlessEpisode {
    public static final float DT = 1f / 60f;
    /** 单次跑分的时间上限，防止某个模型卡在原地耗死整代。 */
    public static final float MAX_TIME = 32f;

    public static final int RUNNING = 0;
    public static final int DIED = 1;
    public static final int TIMEOUT = 2;

    // 各项权重：距离是主目标，其余为塑形项
    private static final float W_PROGRESS = 1.0f;
    private static final float W_IDLE = 0.55f;
    private static final float W_DEATH = 260f;
    private static final float W_COMBO = 6f;
    private static final float W_WAIT = 130f;
    /** 单局最多能拿到多少次等待奖励，防止「站着不动刷分」。 */
    private static final int MAX_WAIT_REWARDS = 6;

    /** 多少秒没前进算「原地停留」。 */
    private static final float IDLE_TIME = 0.45f;
    /** 两次起跳间隔小于这个值算「连跳」。 */
    private static final float COMBO_WINDOW = 0.55f;

    public int state = RUNNING;
    public float score;
    public float time;
    public float maxX;
    /** 着地帧数，用于统计有效前进时间。 */
    public int groundFrames;
    /** 本次跑分中通过「停住等待」拿到的奖励次数，供界面展示。 */
    public int waitRewards;
    /** 连跳次数。 */
    public int combos;
    /** 空中使用箱子的次数。 */
    public int chestUses;

    private final AgentBody body = new AgentBody();
    private final Sensors sensors = new Sensors();
    private float startX;
    private float lastX;
    private float lastProgressX;
    private float idleTimer;
    private float lastJumpTime = -99f;
    private boolean prevJumpHeld;
    /** 上一次操作前的「前方落脚点距离」，用来判断平台是否在靠近。 */
    private float prevLandingDx = Float.NaN;
    /** 已经奖励过的等待时机时刻，避免同一窗口反复加分。 */
    private float lastWaitRewardAt = -99f;
    /** 当前是否处于「减速等平台」的意图中。 */
    private boolean waiting;
    /** 连续处于等待状态的帧数。 */
    private int waitFrames;
    /** 连续滞空帧数。 */
    private int airFrames;

    public AgentBody body() { return body; }

    public void reset(EndlessLevel level) {
        resetAt(level, level.startX, level.startY);
    }

    public void resetAt(EndlessLevel level, float x, float y) {
        body.reset(x, y);
        startX = x;
        lastX = x;
        maxX = x;
        lastProgressX = x;
        state = RUNNING;
        score = 0f;
        time = 0f;
        groundFrames = 0;
        idleTimer = 0f;
        lastJumpTime = -99f;
        prevJumpHeld = false;
        prevLandingDx = Float.NaN;
        lastWaitRewardAt = -99f;
        waiting = false;
        waitFrames = 0;
        airFrames = 0;
        waitRewards = 0;
        combos = 0;
        chestUses = 0;
    }

    /** 本次跑分的绝对起点，配合距离即可还原绝对位置。 */
    public float startX() { return startX; }

    public int step(EndlessLevel level, NeuralNet net) {
        if (state != RUNNING) return state;

        level.ensure(body.x + 1800f);

        float[] in = sensors.read(level, body, time);
        float[] out = net.forward(in);
        float move = Actions.move(out);
        boolean jump = Actions.jump(out);
        boolean jumpPressed = jump && !prevJumpHeld;

        body.step(level, time, move, jump, DT);
        time += DT;

        // ---- 逐操作打分 ----
        if (body.centerX() > maxX) maxX = body.centerX();
        if (body.onGround) groundFrames++;

        // 1) 前进分：只按新推进的距离给分
        float advance = Math.max(0f, body.centerX() - lastProgressX);
        if (advance > 0.5f) {
            score += advance * W_PROGRESS;
            lastProgressX = body.centerX();
            idleTimer = 0f;
        } else {
            idleTimer += DT;
            // 2) 停留罚：明显站着不动要付代价（落地磨蹭、空中悬停同样算）
            if (idleTimer > IDLE_TIME) score -= W_IDLE * DT * 60f;
        }

        // 3) 连跳奖：短时间内连续起跳说明动作果断。
        //    不惩罚「空跳」——惩罚会把它推向两种极端（要么乱跳、要么不敢跳），
        //    「何时该跳」交给卡点等待奖励去引导。
        if (jumpPressed) {
            if (time - lastJumpTime < COMBO_WINDOW) {
                combos++;
                score += W_COMBO;
            }
            lastJumpTime = time;
        }
        prevJumpHeld = jump;

        // 4) 卡点等待奖：向平台靠近时先站住，等它到位再起跳
        maybeRewardWait(level);

        // 4.1) 箱子使用：在空中用它续跳是有价值的动作，
        //      但也别鼓励一拿到就乱扔——只在「跳不出去」的场合给分。
        if (body.usedChestThisStep) {
            score += 40f;
            chestUses++;
        }

        // 4.2) 起跳时机塑形：在边缘处起跳给奖励，边缘很远就起跳不给分。
        //      这条密集信号让「看到边缘再跳」比「随机起跳」更快被学会。
        if (jumpPressed && body.onGround) {
            float room = roomAhead(level);
            if (room < 60f) score += 18f;
            else if (room < 120f) score += 6f;
        }

        // 4.5) 长滞空罚：一直在空中连跳说明没在观察地形，会被推向「乱跳-摔死」循环
        if (!body.onGround) airFrames++;
        else airFrames = 0;
        if (airFrames > 90) score -= 0.8f * DT * 60f;

        // 5) 死亡重罚
        if (body.touchesSpike(level) || body.fellOut()) {
            state = DIED;
            score -= W_DEATH;
        } else if (time >= MAX_TIME) {
            state = TIMEOUT;
        } else if (body.x < lastX - 400f) {
            state = DIED;
            score -= W_DEATH;
        }
        lastX = body.x;
        return state;
    }

    /**
     * 「停住等平台」的奖励条件：前方最近落脚点在移动平台上、它正在靠过来，
     * 且此刻身体已明显减速。满足时给一次性奖励，并对同一段等待加冷却。
     */
    private void maybeRewardWait(EndlessLevel level) {
        float landingDx = nextMoverDx(level);
        if (Float.isNaN(landingDx)) {
            prevLandingDx = Float.NaN;
            waitFrames = 0;
            return;
        }

        // 「该等一等」的窗口：站在地面上、平台在前方且正在靠近。
        // 不强制要求明显减速——模型一开始很难主动刹住，
        // 这里奖励的是「在边缘处按兵不动等平台到位」这个整体行为。
        boolean approaching = !Float.isNaN(prevLandingDx) && landingDx <= prevLandingDx;
        boolean inWindow = landingDx > 0f && landingDx < 320f;
        boolean nearEdge = roomAhead(level) < 110f;

        waiting = body.onGround && approaching && inWindow && nearEdge;
        if (waiting) waitFrames++;
        else waitFrames = 0;

        // 等待奖励设上限：单局总额封顶，否则模型会发现「一直站着等」收益最高
        if (waitFrames >= 9 && time - lastWaitRewardAt > 1.6f
                && waitRewards < MAX_WAIT_REWARDS) {
            score += W_WAIT;
            lastWaitRewardAt = time;
            waitRewards++;
        }
        if (waitFrames > 9) waitFrames = 9;   // 防止一次等待被反复触发
        prevLandingDx = landingDx;
    }

    /** 当前脚下方还有多少水平余量可走；离边缘越近越该停下来等。 */
    private float roomAhead(EndlessLevel level) {
        float foot = body.bottom();
        float cx = body.centerX();
        for (float d = 0f; d < 200f; d += 10f) {
            if (!floorAt(level, cx + d, cx + d + 10f, foot)) return d;
        }
        return 200f;
    }

    private static boolean floorAt(EndlessLevel level, float x1, float x2, float foot) {
        for (int i = 0; i < level.platforms.size(); i++) {
            com.neurojump.world.Rect r = level.platforms.get(i);
            if (r.right() <= x1 || r.x >= x2) continue;
            if (Math.abs(r.top() - foot) < 8f) return true;
        }
        for (int i = 0; i < level.solids.size(); i++) {
            com.neurojump.world.Rect r = level.solids.get(i);
            if (r.right() <= x1 || r.x >= x2) continue;
            if (Math.abs(r.top() - foot) < 8f) return true;
        }
        return false;
    }

    /** 前方最近移动平台的水平距离；没有则 NaN。 */
    private float nextMoverDx(EndlessLevel level) {
        float best = Float.NaN;
        float cx = body.centerX();
        for (int i = 0; i < level.movers.size(); i++) {
            Mover m = level.movers.get(i);
            float dx = m.xAt(time) - cx;
            if (dx < -40f || dx > 320f) continue;
            if (Float.isNaN(best) || dx < best) best = dx;
        }
        return best;
    }

    public boolean done() {
        return state != RUNNING;
    }
}
