package com.neurojump;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.neurojump.nn.NeuralNet;
import com.neurojump.store.ModelRecord;
import com.neurojump.store.ModelStore;
import com.neurojump.train.Actions;
import com.neurojump.train.EndlessTrainer;
import com.neurojump.train.Sensors;
import com.neurojump.train.Trainer;
import com.neurojump.ui.Button;
import com.neurojump.ui.Draw;
import com.neurojump.ui.Fonts;
import com.neurojump.ui.LevelRenderer;
import com.neurojump.world.AgentBody;
import com.neurojump.world.C;
import com.neurojump.world.EndlessLevel;
import com.neurojump.world.Mover;
import com.neurojump.world.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * 无限跑分：一条永远走不到尽头的跑酷，含移动平台与尖刺。
 *
 * 界面同时服务两种用途：种群在这里比谁跑得远、按距离进化；已保存的模型
 * 也可以作为种子投进来，与种群同场继续训练。视角始终跟随最佳个体。
 */
public final class EndlessScreen extends ScreenAdapter {
    private final GameRoot game;
    private final ShapeRenderer sr = new ShapeRenderer();
    private final SpriteBatch batch = new SpriteBatch();
    private final Matrix4 proj = new Matrix4();

    private final boolean training;
    /** 是否卡点训练模式（关卡全是移动平台）。 */
    private final boolean timingMode;
    private final EndlessTrainer trainer;
    private final EndlessLevel level;

    private final Button menuBtn;
    private final Button saveBtn;
    private final Button pauseBtn;
    private final Button restartBtn;
    private final Button speedBtn;

    private final NeuralNet demoNet = new NeuralNet(Trainer.LAYERS);
    private final AgentBody demoBody = new AgentBody();
    private final Sensors demoSensors = new Sensors();
    private float demoTime;
    /** 死亡后的停留计时，与物理时间分开，避免互相污染。 */
    private float deadTimer;
    private float camX;
    /** 低倍率播放时累积的时间，攒够一个物理步才推进。 */
    private float acc;
    /** 连续掉帧计数：用于在高倍速下自动限流，避免画面卡死。 */
    private int slowFrames;
    private boolean demoDead;

    /** 演示播放倍率，从极慢到 4 倍。低倍率用于看清模型的每个动作。 */
    private static final float[] SPEEDS = {0.05f, 0.25f, 0.5f, 1f, 2f, 4f, 25f, 75f, 100f};
    private int speedIndex = 3;

    private boolean paused;
    private float hoverX = -999f;
    private float hoverY = -999f;
    private String toast = "";
    private float toastTimer;

    public EndlessScreen(GameRoot game, boolean training, List<ModelRecord> seeds) {
        this(game, training, seeds, com.neurojump.train.EndlessTrainer.Style.MIXED);
    }

    /** style = TIMING 时进入「卡点训练」：整条跑酷全是移动平台，专练起跳时机。 */
    public EndlessScreen(GameRoot game, boolean training, List<ModelRecord> seeds,
                         com.neurojump.train.EndlessTrainer.Style style) {
        this.game = game;
        this.training = training;
        this.timingMode = style == com.neurojump.train.EndlessTrainer.Style.TIMING;
        List<ModelRecord> start = seeds == null ? new ArrayList<>() : seeds;
        // 种群放大到 120：网络加宽后搜索空间变大，小种群很难搜到好解
        trainer = new EndlessTrainer(training ? 120 : 60, threads(), start, style);
        level = trainer.level();
        trainer.start();

        menuBtn = new Button(1, "返回", 24f, C.VIEW_H - 68f, 110f, 48f);
        restartBtn = new Button(4, "重跑演示", C.VIEW_W - 548f, C.VIEW_H - 68f, 128f, 48f);
        speedBtn = new Button(5, "倍速 ×1", 146f, C.VIEW_H - 68f, 132f, 48f);
        pauseBtn = new Button(3, "暂停", C.VIEW_W - 412f, C.VIEW_H - 68f, 100f, 48f);
        saveBtn = new Button(2, "保存跑分模型", C.VIEW_W - 304f, C.VIEW_H - 68f, 180f, 48f);

        demoBody.reset(level.startX, level.startY);
        camX = demoBody.x;
        // 开局先拿当前最佳（此刻还是随机个体）跑，后续每轮重跑会自动换成新冠军
        copyShowcaseToDemo();
    }

    /** 把训练器当前的最佳网络复制给演示网络。 */
    private void copyShowcaseToDemo() {
        // 用「从起点跑得最远」的网络，因为演示每一轮都从起跑点重新开始
        NeuralNet champ = trainer.demoNet();
        if (champ == null) return;
        System.arraycopy(champ.weights(), 0, demoNet.weights(), 0, champ.weightCount());
        System.arraycopy(champ.biases(), 0, demoNet.biases(), 0, champ.biasCount());
    }

    private static int threads() {
        int n = Runtime.getRuntime().availableProcessors();
        // 评估任务彼此独立，尽量吃满可用核心；留一个核给渲染线程
        return Math.max(2, Math.min(8, n - 1));
    }

    @Override
    public void render(float delta) {
        updateInput();
        toastTimer = Math.max(0f, toastTimer - delta);
        if (delta > 0.024f) slowFrames++;
        else slowFrames = Math.max(0, slowFrames - 1);
        if (!paused) stepDemo(Math.min(delta, 0.05f) * SPEEDS[speedIndex]);

        Gdx.gl.glClearColor(0.05f, 0.06f, 0.10f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        Matrix4 world = cameraMatrix();
        sr.setProjectionMatrix(world);
        batch.setProjectionMatrix(world);
        drawWorld();

        proj.setToOrtho2D(0, 0, C.VIEW_W, C.VIEW_H);
        sr.setProjectionMatrix(proj);
        batch.setProjectionMatrix(proj);

        sr.begin(ShapeRenderer.ShapeType.Filled);
        drawHudBars();
        drawChestBoxes();
        menuBtn.drawShape(sr, menuBtn.hit(hoverX, hoverY), true);
        restartBtn.drawShape(sr, restartBtn.hit(hoverX, hoverY), true);
        speedBtn.drawShape(sr, speedBtn.hit(hoverX, hoverY), true);
        pauseBtn.drawShape(sr, pauseBtn.hit(hoverX, hoverY), true);
        saveBtn.drawShape(sr, saveBtn.hit(hoverX, hoverY), true);
        sr.end();

        batch.begin();
        menuBtn.drawLabel(batch, Fonts.of(24), true);
        restartBtn.drawLabel(batch, Fonts.of(21), true);
        speedBtn.drawLabel(batch, Fonts.of(21), true);
        pauseBtn.drawLabel(batch, Fonts.of(22), true);
        saveBtn.drawLabel(batch, Fonts.of(21), true);
        drawTexts();
        batch.end();
    }

    /**
     * 相机横向跟随、纵向固定。视口按像素 1:1 使用 1280x720，
     * 只把横向中心移到 camX，因此地形的像素坐标可以直接绘制。
     */
    private Matrix4 cameraMatrix() {
        Matrix4 m = new Matrix4();
        m.setToOrtho2D(camX - C.VIEW_W * 0.5f, 20f, C.VIEW_W, C.VIEW_H);
        return m;
    }

    private void drawWorld() {
        sr.begin(ShapeRenderer.ShapeType.Filled);
        float left = camX - C.VIEW_W * 0.6f;
        float right = camX + C.VIEW_W * 0.6f;

        for (int i = 0; i < level.solids.size(); i++) {
            Rect r = level.solids.get(i);
            if (r.right() < left || r.x > right) continue;
            Draw.rect(sr, r.x, r.y, r.w, r.h, new Color(0.30f, 0.86f, 0.62f, 1f));
            Draw.rect(sr, r.x, r.top() - 4f, r.w, 4f, new Color(0.55f, 1f, 0.80f, 1f));
        }
        for (int i = 0; i < level.platforms.size(); i++) {
            Rect r = level.platforms.get(i);
            if (r.right() < left || r.x > right) continue;
            Draw.rect(sr, r.x, r.y, r.w, r.h, new Color(0.42f, 0.55f, 0.95f, 1f));
            Draw.rect(sr, r.x, r.top() - 4f, r.w, 4f, new Color(0.68f, 0.78f, 1f, 1f));
        }
        for (int i = 0; i < level.movers.size(); i++) {
            Mover m = level.movers.get(i);
            float x = m.xAt(demoTime);
            float y = m.yAt(demoTime);
            if (x + m.w < left || x > right) continue;
            Draw.rect(sr, x, y, m.w, m.h, new Color(0.96f, 0.62f, 0.30f, 1f));
            Draw.rect(sr, x, y + m.h - 4f, m.w, 4f, new Color(1f, 0.82f, 0.52f, 1f));
        }
        sr.setColor(new Color(0.95f, 0.32f, 0.42f, 1f));
        for (int i = 0; i < level.spikes.size(); i++) {
            Rect r = level.spikes.get(i);
            if (r.right() < left || r.x > right) continue;
            int n = Math.max(1, (int) (r.w / 16f));
            float step = r.w / n;
            for (int k = 0; k < n; k++) {
                sr.triangle(r.x + k * step, r.y, r.x + (k + 1) * step, r.y,
                        r.x + k * step + step * 0.5f, r.top());
            }
        }
        LevelRenderer.drawAgent(sr, demoBody, 0f, 0f, 1f, Color.WHITE);
        sr.end();
    }

    /** 箱子数量：实心表示可用，暗色表示已用掉或未补满。 */
    private void drawChestBoxes() {
        for (int i = 0; i < C.CHEST_MAX; i++) {
            Draw.rect(sr, 96f + i * 26f, C.VIEW_H - 358f, 18f, 18f,
                    i < demoBody.chests ? new Color(0.95f, 0.78f, 0.30f, 1f)
                            : new Color(0.25f, 0.27f, 0.34f, 1f));
        }
    }

    private void drawHudBars() {
        Draw.rect(sr, 20f, C.VIEW_H - 196f, 430f, 116f, new Color(0.07f, 0.09f, 0.14f, 0.90f));
        Draw.border(sr, 20f, C.VIEW_H - 196f, 430f, 116f, 2f,
                new Color(0.20f, 0.28f, 0.42f, 1f));

        float[] scores = trainer.scoreSnapshot;
        if (scores.length == 0) return;
        float best = 1f;
        for (float s : scores) best = Math.max(best, s);
        float baseX = 32f;
        float baseY = C.VIEW_H - 190f;
        float w = 406f;
        float h = 56f;
        float barW = w / scores.length;
        for (int i = 0; i < scores.length; i++) {
            float p = Math.max(0.02f, scores[i] / best);
            Draw.rect(sr, baseX + i * barW, baseY, Math.max(1.5f, barW - 0.8f), h * p,
                    new Color(0.35f, 0.65f, 0.95f, 0.9f));
        }
    }

    private void drawTexts() {
        Draw.text(batch, Fonts.of(26),
                timingMode ? "卡点训练（移动平台）" : (training ? "无限跑分训练" : "无限跑分"),
                40f, C.VIEW_H - 214f, Color.WHITE);
        Draw.text(batch, Fonts.of(22), "本段 " + Math.round(demoBody.x / 20f) + " 米",
                40f, C.VIEW_H - 246f, new Color(0.45f, 1f, 0.70f, 1f));
        Draw.text(batch, Fonts.of(17), "最远 " + Math.round(trainer.bestScore / 20f)
                        + " 米   第 " + trainer.generation + " 代",
                40f, C.VIEW_H - 270f, new Color(0.60f, 0.72f, 0.90f, 1f));
        // 操作级记分：等待次数与连跳次数，能直观看出「会不会停住等平台」
        Draw.text(batch, Fonts.of(17), "等平台 " + trainer.bestWaitRewards
                        + " 次   连跳 " + trainer.bestCombos + " 次",
                40f, C.VIEW_H - 318f, new Color(0.55f, 0.90f, 0.75f, 1f));
        Draw.text(batch, Fonts.of(17), "箱子", 40f, C.VIEW_H - 344f,
                new Color(0.85f, 0.78f, 0.45f, 1f));

        Draw.text(batch, Fonts.of(17), "地形 " + level.segments() + " 段 · 平台 "
                        + level.platforms.size() + " · 移动 " + level.movers.size()
                        + " · 尖刺 " + level.spikes.size(),
                40f, C.VIEW_H - 294f, new Color(0.55f, 0.65f, 0.80f, 1f));

        if (demoDead) {
            Draw.textCentered(batch, Fonts.of(28), "本轮结束，重跑中…",
                    C.VIEW_W * 0.5f, C.VIEW_H * 0.56f, new Color(1f, 0.75f, 0.50f, 1f));
        }
        if (paused) {
            Draw.textCentered(batch, Fonts.of(34), "已暂停",
                    C.VIEW_W * 0.5f, C.VIEW_H * 0.56f, Color.WHITE);
        }
        if (toastTimer > 0f) {
            Draw.textCentered(batch, Fonts.of(21), toast, C.VIEW_W * 0.5f, 140f,
                    new Color(0.45f, 1f, 0.70f, 1f));
        }
        Draw.text(batch, Fonts.of(16), "点「保存跑分模型」把当前最佳存进模型库",
                40f, 46f, new Color(0.45f, 0.55f, 0.70f, 1f));
    }

    private void stepDemo(float dt) {
        if (demoDead) {
            deadTimer += dt;
            if (deadTimer > 0.8f) resetDemo();
            camX += (demoBody.x - camX) * 0.15f;
            return;
        }

        // 低倍率时一帧可能不足一个物理步，用累积器攒够再走，避免动作被吞掉
        // 每隔一段时间同步一次最新冠军，演示内容跟得上训练进度
        if (demoTime <= 0f) copyShowcaseToDemo();

        // 高倍速下每帧要补很多物理步：上限按倍率放大，避免高速时被截断成「假提速」。
        // 但手机上一帧塞上百步会掉帧，这里按实测帧时长动态限流：
        // 单帧耗时超过 16ms 就少补几步，宁可慢一点也不要卡住画面。
        float stepDt = 1f / 60f;
        acc += dt;
        int cap = Math.max(8, (int) (SPEEDS[speedIndex] * 4f) + 8);
        if (slowFrames > 8 && cap > 12) cap = 12;
        if (cap > 900) cap = 900;
        int steps = 0;
        while (acc >= stepDt && steps < cap) {
            acc -= stepDt;
            steps++;
        }
        // 倍率极高时一帧可能要几百步，丢掉积压避免越积越多
        if (acc > stepDt * 4f) acc = stepDt * 4f;
        for (int i = 0; i < steps && !demoDead; i++) {
            level.ensure(demoBody.x + 2000f);
            level.keepFrom(demoBody.x);
            float[] out = demoNet.forward(demoSensors.read(level, demoBody, demoTime));
            demoBody.step(level, demoTime, Actions.move(out), Actions.jump(out), stepDt);
            demoTime += stepDt;
            if (demoBody.touchesSpike(level) || demoBody.fellOut()) demoDead = true;
        }
        camX += (demoBody.x - camX) * 0.12f;
    }

    /**
     * 重开一次演示。
     *
     * 从起点重跑会让画面长期停在出生台附近（模型在起点的表现往往最差），
     * 所以改为从「模型已经能跑到的最远位置往回退一段」开跑：
     * 既能立刻看到它在动，也能看出它当前的能力边界。
     */
    private void resetDemo() {
        copyShowcaseToDemo();
        // 起跑点取「从起点跑能到的最远处」稍微往回退，保证脚下有地形
        float best = Math.max(level.startX, trainer.reachedX);
        float spawnX = Math.max(level.startX, best - 900f);
        if (spawnX > level.startX) {
            level.ensure(spawnX + 2200f);
            demoBody.reset(spawnX, spawnYNear(spawnX));
        } else {
            demoBody.reset(level.startX, level.startY);
        }
        level.keepFrom(demoBody.x);
        demoTime = 0f;
        deadTimer = 0f;
        demoDead = false;
        acc = 0f;
        camX = demoBody.x;
    }

    /** 在 x 附近找一块平台顶面，作为起跑高度。 */
    private float spawnYNear(float x) {
        float bestTop = level.startY;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < level.platforms.size(); i++) {
            Rect r = level.platforms.get(i);
            float cx = r.x + r.w * 0.5f;
            float d = Math.abs(cx - x);
            if (d < bestDist) {
                bestDist = d;
                bestTop = r.top();
            }
        }
        return bestTop;
    }

    private void saveChampion() {
        NeuralNet champ = trainer.championNet();
        if (champ == null) {
            showToast("还没有可保存的模型");
            return;
        }
        int meters = (int) (trainer.bestScoreEver / 20f);
        ModelRecord rec = ModelRecord.of("跑分-" + meters + "米", champ, meters, 0,
                trainer.generation, meters, "无限跑分第" + trainer.generation + "代");
        if (new ModelStore().save(rec)) {
            showToast("已保存：跑分-" + meters + "米");
        } else {
            showToast("存档已满，请到模型库删除");
        }
    }

    private static String fmtSpeed(float v) {
        if (v == Math.round(v)) return String.valueOf((int) v);
        return String.valueOf(v);
    }

    private void showToast(String msg) {
        toast = msg;
        toastTimer = 2.5f;
    }

    private void updateInput() {
        float sx = C.VIEW_W / Gdx.graphics.getWidth();
        float sy = C.VIEW_H / Gdx.graphics.getHeight();
        Vector3 p = new Vector3(Gdx.input.getX(), Gdx.input.getY(), 0f);
        hoverX = p.x * sx;
        hoverY = C.VIEW_H - p.y * sy;

        if (Gdx.input.isKeyJustPressed(Input.Keys.BACK)) {
            trainer.stop();
            game.setScreen(new MenuScreen(game));
            return;
        }
        if (!Gdx.input.justTouched()) return;

        if (menuBtn.hit(hoverX, hoverY)) {
            trainer.stop();
            game.setScreen(new MenuScreen(game));
        } else if (saveBtn.hit(hoverX, hoverY)) {
            saveChampion();
        } else if (pauseBtn.hit(hoverX, hoverY)) {
            paused = !paused;
            pauseBtn.label = paused ? "继续" : "暂停";
        } else if (restartBtn.hit(hoverX, hoverY)) {
            demoDead = true;
        } else if (speedBtn.hit(hoverX, hoverY)) {
            speedIndex = (speedIndex + 1) % SPEEDS.length;
            speedBtn.label = "倍速 ×" + fmtSpeed(SPEEDS[speedIndex]);
        }
    }

    @Override
    public void dispose() {
        trainer.stop();
        sr.dispose();
        batch.dispose();
    }
}
