package com.neurojump.race;

import com.neurojump.nn.NeuralNet;
import com.neurojump.train.Actions;
import com.neurojump.train.Episode;
import com.neurojump.train.Sensors;
import com.neurojump.world.AgentBody;
import com.neurojump.world.Level;

/**
 * 竞速参赛者：一个模型在同一关卡上的一次完整跑动。
 * 每帧推进一次，状态由外部驱动，便于多个选手同步推进并公平比较用时。
 */
public final class Racer {
    public static final int RUNNING = 0;
    public static final int FINISHED = 1;
    public static final int FAILED = 2;
    public static final int TIMEOUT = 3;

    public static final float MAX_TIME = 45f;

    public final String name;
    private final NeuralNet net;
    private final AgentBody body = new AgentBody();
    private final Sensors sensors = new Sensors();

    public int state = RUNNING;
    public float time;
    public float progress;
    public float finishTime = -1f;
    /** 名次，1 表示第一；未完赛为 0。 */
    public int rank;

    private float startX;
    private float maxX;

    public Racer(String name, NeuralNet net) {
        this.name = name;
        this.net = net;
    }

    public AgentBody body() { return body; }

    public void reset(Level level) {
        body.reset(level.startX, level.startY);
        state = RUNNING;
        time = 0f;
        progress = 0f;
        finishTime = -1f;
        rank = 0;
        startX = level.startX;
        maxX = level.startX;
    }

    /** 推进一帧。dt 固定为 1/60 以保证比较公平。 */
    public void step(Level level, float dt) {
        if (state != RUNNING) return;

        float[] out = net.forward(sensors.read(level, body, time));
        body.step(level, time, Actions.move(out), Actions.jump(out), dt);
        time += dt;

        if (body.centerX() > maxX) maxX = body.centerX();
        progress = Math.min(1f, (maxX - startX) / Math.max(1f, level.goal.x - startX));

        if (body.touchesSpike(level) || body.fellOut()) {
            state = FAILED;
        } else if (body.reachedGoal(level)) {
            state = FINISHED;
            finishTime = time;
        } else if (time >= MAX_TIME) {
            state = TIMEOUT;
        }
    }

    public boolean done() {
        return state != RUNNING;
    }

    public String stateText() {
        switch (state) {
            case FINISHED: return String.format("%.2fs", finishTime);
            case FAILED: return "失败";
            case TIMEOUT: return "超时";
            default: return String.format("%.0f%%", progress * 100f);
        }
    }
}
