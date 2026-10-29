package com.neurojump.train;

import com.neurojump.nn.NeuralNet;
import com.neurojump.world.AgentBody;
import com.neurojump.world.C;
import com.neurojump.world.Level;
import com.neurojump.world.Mover;
import com.neurojump.world.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次完整试验：把网络放进关卡跑到通关/死亡/超时。
 * 不依赖渲染，可在线程池里并行评估。
 *
 * 适应度按「里程碑」计分：网络每越过一个参考落点就得分。这样「在移动平台前
 * 等正确时机」也是实打实的进展，而不会因最远水平距离没变而被当成停滞。
 */
public final class Episode {
    public static final float DT = 1f / 60f;
    public static final float MAX_TIME = 40f;

    public static final int RUNNING = 0;
    public static final int WON = 1;
    public static final int DIED = 2;
    public static final int TIMEOUT = 3;

    public int state = RUNNING;
    public float fitness;
    public float progress;
    public float time;
    public int frames;

    private final AgentBody body = new AgentBody();
    private final Sensors sensors = new Sensors();

    private float[] milestones = new float[0];
    private int reached;
    private float maxX;
    private float startX;
    private float lastX;

    /** 该关的参考落点（按 x 升序），供所有个体复用。 */
    public static float[] milestonesOf(Level lv) {
        List<Float> xs = new ArrayList<>();
        for (Rect r : lv.platforms) xs.add(r.centerX());
        for (Rect r : lv.solids) if (r.x > 300f) xs.add(r.centerX());
        for (Mover m : lv.movers) {
            xs.add(m.vertical ? m.baseX + m.w * 0.5f : m.baseX + m.w * 0.5f);
        }
        xs.add(lv.goal.x + 20f);
        xs.sort(Float::compare);
        float[] out = new float[xs.size()];
        for (int i = 0; i < out.length; i++) out[i] = xs.get(i);
        return out;
    }

    public AgentBody body() { return body; }

    public void reset(Level lv, float[] milestoneXs) {
        body.reset(lv.startX, lv.startY);
        milestones = milestoneXs;
        reached = 0;
        maxX = lv.startX;
        startX = lv.startX;
        lastX = lv.startX;
        state = RUNNING;
        fitness = 0f;
        progress = 0f;
        time = 0f;
        frames = 0;
    }

    public int step(Level lv, NeuralNet net) {
        if (state != RUNNING) return state;

        float[] out = net.forward(sensors.read(lv, body, time));
        float move = Actions.move(out);
        boolean jump = Actions.jump(out);

        body.step(lv, time, move, jump, DT);
        time += DT;
        frames++;

        if (body.centerX() > maxX) maxX = body.centerX();
        while (reached < milestones.length && maxX >= milestones[reached]) reached++;

        if (body.touchesSpike(lv)) state = DIED;
        else if (body.reachedGoal(lv)) state = WON;
        else if (body.fellOut()) state = DIED;
        else if (time >= MAX_TIME) state = TIMEOUT;
        else if (body.x < lastX - 320f) state = DIED;
        lastX = body.x;

        progress = clamp((maxX - startX) / Math.max(1f, lv.goal.x - startX), 0f, 1f);
        fitness = score();
        return state;
    }

    private float score() {
        float f = reached * 12f;
        if (milestones.length > 0 && reached >= milestones.length) f += 60f;
        f += progress * 40f;
        if (state == WON) f += 200f + (1f - time / MAX_TIME) * 80f;
        else if (state == DIED) f -= 25f;
        return f;
    }

    public int reachedMilestones() { return reached; }
    public int milestoneCount() { return milestones.length; }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
