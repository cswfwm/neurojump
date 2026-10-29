package com.neurojump;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.neurojump.ai.ApiSettings;
import com.neurojump.ai.Coach;
import com.neurojump.nn.NeuralNet;
import com.neurojump.store.ModelRecord;
import com.neurojump.store.ModelStore;
import com.neurojump.train.Actions;
import com.neurojump.train.Episode;
import com.neurojump.train.Sensors;
import com.neurojump.train.Trainer;
import com.neurojump.ui.Button;
import com.neurojump.ui.Draw;
import com.neurojump.ui.Fonts;
import com.neurojump.ui.LevelRenderer;
import com.neurojump.ui.NetRenderer;
import com.neurojump.world.AgentBody;
import com.neurojump.world.C;
import com.neurojump.world.Level;
import com.neurojump.world.LevelFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动训练主界面。
 *
 * 训练在后台线程池里跑，这里只做两件事：
 *  1. 用独立副本的「演示网络」实时跑当前关卡，让人看到 AI 真的在行动；
 *  2. 展示种群进度分布、代数、最佳进度与神经网络连线的激活状态。
 * 演示网络是独立副本，因此不会与训练线程争抢权重数组。
 */
public final class TrainScreen extends ScreenAdapter {
    private static final int BTN_MENU = 1;
    private static final int BTN_SPEED = 2;
    private static final int BTN_SAVE = 3;
    private static final int BTN_LIBRARY = 4;
    private static final int BTN_COACH = 5;

    private static final float[] SPEEDS = {1f, 2f, 4f, 8f, 25f, 75f, 100f};
    /** 种群上限；实际值按可用堆下调，低内存设备上不会撑爆。 */
    private static final int POPULATION = 110;

    private final GameRoot game;
    private final ShapeRenderer sr = new ShapeRenderer();
    private final SpriteBatch batch = new SpriteBatch();
    private final Matrix4 proj = new Matrix4();

    private final Trainer trainer;
    private final ModelStore store = new ModelStore();
    private final boolean endless;
    private final Button menuBtn;
    private final Button speedBtn;
    private final Button saveBtn;
    private final Button libraryBtn;
    private final Button coachBtn;
    private final Coach coach;
    private boolean coachOn;
    private String toast = "";
    private float toastTimer;
    /** 已保存过多少个本局模型，用于自动命名。 */
    private int savedCount;

    private final NeuralNet demoNet = new NeuralNet(Trainer.LAYERS);
    private final AgentBody demoBody = new AgentBody();
    private final Sensors demoSensors = new Sensors();
    private float demoTime;
    /** 低/高倍速播放的步长累积器。 */
    private float acc;
    private int demoState = Episode.RUNNING;
    private int demoLevel = -1;

    private int speedIndex;

    private float hoverX = -999f;
    private float hoverY = -999f;

    public TrainScreen(GameRoot game, boolean endless) {
        this(game, endless, null);
    }

    /** seeds 非空时，把它们作为初始种群继续训练（模型复用 / 反复训练）。 */
    public TrainScreen(GameRoot game, boolean endless, List<ModelRecord> seeds) {
        this.game = game;
        this.endless = endless;
        menuBtn = new Button(BTN_MENU, "返回", 24f, C.VIEW_H - 68f, 110f, 48f);
        speedBtn = new Button(BTN_SPEED, "速度 ×1", C.VIEW_W - 158f, C.VIEW_H - 68f, 134f, 48f);
        saveBtn = new Button(BTN_SAVE, "保存模型", C.VIEW_W - 318f, C.VIEW_H - 68f, 140f, 48f);
        libraryBtn = new Button(BTN_LIBRARY, "模型库", C.VIEW_W - 468f, C.VIEW_H - 68f, 130f, 48f);
        coachBtn = new Button(BTN_COACH, "AI 教练：关", C.VIEW_W - 630f, C.VIEW_H - 68f, 158f, 48f);

        // 不预建关卡：进界面只生成第 1 层，之后的层随训练进度按需生成
        List<Level> levels = new ArrayList<>();
        trainer = new Trainer(levels, population(), threads(), endless);
        if (seeds != null && !seeds.isEmpty()) {
            trainer.seedFromModels(seeds);
        }
        ApiSettings api = ApiSettings.load();
        coach = api.isConfigured() ? new Coach(trainer, api.client()) : null;
        trainer.start();
    }

    /** 把当前冠军存成一份模型：基因 + 训练元信息 + 族谱。 */
    private void saveChampion() {
        float[] genes = trainer.championGenes;
        NeuralNet net = trainer.showcase;
        if (genes == null || net == null) {
            showToast("还没有可保存的冠军");
            return;
        }
        savedCount++;
        String name = (endless ? "无限" : "课程") + "-" + (trainer.solvedLevels) + "层-"
                + savedCount;
        ModelRecord rec = ModelRecord.of(name, net, trainer.solvedLevels,
                trainer.levelIndex, trainer.generation, trainer.bestProgress,
                trainer.lineage);
        if (store.save(rec)) {
            showToast("已保存：" + name);
        } else {
            showToast("存档已满（" + ModelStore.MAX_MODELS + " 个），请到模型库删除");
        }
    }

    private static String fmtSpeed(float v) {
        return v == Math.round(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private void showToast(String msg) {
        toast = msg;
        toastTimer = 2.5f;
    }

    /** 按可用堆决定种群规模。 */
    private static int population() {
        long heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        if (heapMb < 96) return Math.max(20, POPULATION / 3);
        if (heapMb < 160) return Math.max(30, POPULATION / 2);
        return POPULATION;
    }

    private static int threads() {
        int n = Runtime.getRuntime().availableProcessors();
        return Math.max(2, Math.min(6, n - 1));
    }

    @Override
    public void render(float delta) {
        updateInput();
        stepDemo(Math.min(delta, 0.05f) * SPEEDS[speedIndex]);
        toastTimer = Math.max(0f, toastTimer - delta);
        if (coachOn && coach != null && coach.enabled()) coach.maybeConsult();

        Gdx.gl.glClearColor(0.05f, 0.06f, 0.10f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        proj.setToOrtho2D(0, 0, C.VIEW_W, C.VIEW_H);
        sr.setProjectionMatrix(proj);
        batch.setProjectionMatrix(proj);

        Level level = cachedLevel();

        sr.begin(ShapeRenderer.ShapeType.Filled);
        drawPanels();
        if (level != null) drawLevelView(level);
        drawPopulationChart();
        if (trainer.showcase != null) {
            NetRenderer.draw(sr, trainer.showcase, 34f, 34f, 330f, 250f);
        }
        menuBtn.drawShape(sr, menuBtn.hit(hoverX, hoverY), true);
        speedBtn.drawShape(sr, speedBtn.hit(hoverX, hoverY), true);
        libraryBtn.drawShape(sr, libraryBtn.hit(hoverX, hoverY), true);
        coachBtn.drawShape(sr, coachBtn.hit(hoverX, hoverY), coach != null);
        saveBtn.drawShape(sr, saveBtn.hit(hoverX, hoverY),
                trainer.championGenes != null);
        sr.end();

        batch.begin();
        menuBtn.drawLabel(batch, Fonts.of(24), true);
        speedBtn.drawLabel(batch, Fonts.of(24), true);
        libraryBtn.drawLabel(batch, Fonts.of(22), true);
        coachBtn.drawLabel(batch, Fonts.of(22), coach != null);
        saveBtn.drawLabel(batch, Fonts.of(22), trainer.championGenes != null);
        if (toastTimer > 0f) {
            Draw.textCentered(batch, Fonts.of(22), toast, C.VIEW_W * 0.5f, 330f,
                    new Color(1f, 0.85f, 0.45f, 1f));
        }
        drawTexts(level);
        batch.end();
    }

    private void drawPanels() {
        Draw.rect(sr, 20f, 20f, 360f, 300f, new Color(0.08f, 0.10f, 0.16f, 0.92f));
        Draw.border(sr, 20f, 20f, 360f, 300f, 2f, new Color(0.20f, 0.28f, 0.42f, 1f));
        Draw.rect(sr, C.VIEW_W - 400f, 20f, 380f, 340f, new Color(0.08f, 0.10f, 0.16f, 0.92f));
        Draw.border(sr, C.VIEW_W - 400f, 20f, 380f, 340f, 2f, new Color(0.20f, 0.28f, 0.42f, 1f));
    }

    /** 关卡视图：等比缩放到中上部区域，展示当前关卡与演示个体。 */
    private void drawLevelView(Level level) {
        // 左侧网络面板右缘 380，右侧信息面板左缘 VIEW_W-400，中间全部留给关卡
        float viewX = 396f;
        float viewW = C.VIEW_W - 400f - viewX - 16f;
        float viewH = C.VIEW_H - 240f;
        float scale = Math.min(viewW / level.width, viewH / level.height);
        float ox = viewX + (viewW - level.width * scale) * 0.5f;
        float oy = 96f;

        Draw.rect(sr, viewX, 88f, viewW, viewH + 12f,
                new Color(0.07f, 0.09f, 0.14f, 0.92f));
        Draw.border(sr, viewX, 88f, viewW, viewH + 12f, 2f,
                new Color(0.20f, 0.28f, 0.42f, 1f));

        LevelRenderer.drawWorld(sr, level, demoTime, ox, oy, scale);
        LevelRenderer.drawAgent(sr, demoBody, ox, oy, scale, Color.WHITE);
    }

    /** 种群进度直方图：一条竖线一个个体，高度表示该个体的进度。 */
    private void drawPopulationChart() {
        float[] prog = trainer.progressSnapshot;
        if (prog.length == 0) return;
        float baseX = C.VIEW_W - 390f;
        float baseY = 250f;
        float w = 350f;
        float h = 58f;
        float barW = w / prog.length;
        for (int i = 0; i < prog.length; i++) {
            float p = Math.max(0.02f, prog[i]);
            Color c = prog[i] >= 0.999f
                    ? new Color(0.35f, 1f, 0.55f, 1f)
                    : new Color(0.30f + 0.5f * p, 0.55f, 0.95f - 0.4f * p, 1f);
            Draw.rect(sr, baseX + i * barW, baseY, Math.max(1.5f, barW - 0.8f), h * p, c);
        }
        Draw.rect(sr, baseX, baseY - 2f, w, 2f, new Color(0.25f, 0.32f, 0.45f, 1f));
    }

    private void drawTexts(Level level) {
        BitmapFont h1 = Fonts.of(28);
        BitmapFont normal = Fonts.of(20);
        BitmapFont small = Fonts.of(17);

        Draw.text(batch, h1, "自动训练中", 150f, C.VIEW_H - 88f, Color.WHITE);

        int lvNo = trainer.levelIndex + 1;
        String levelText = endless ? ("第 " + lvNo + " 层 · 无限模式")
                : ("第 " + lvNo + " / " + C.LEVEL_COUNT + " 层");
        Draw.text(batch, normal, levelText,
                410f, C.VIEW_H - 88f, new Color(0.80f, 0.88f, 1f, 1f));
        Draw.text(batch, small, "代数 " + trainer.generation
                        + "    本代通关 " + trainer.wonThisGeneration + " / " + trainer.populationSize()
                        + "    已通关 " + trainer.solvedLevels + " 层",
                410f, C.VIEW_H - 114f, new Color(0.60f, 0.70f, 0.85f, 1f));

        Draw.text(batch, small, "神经网络 17 → 16 → 3",
                34f, 284f, new Color(0.75f, 0.85f, 1f, 1f));

        float x = C.VIEW_W - 390f;
        Draw.text(batch, normal, "最佳进度", x, 334f, new Color(0.75f, 0.85f, 1f, 1f));
        Draw.text(batch, normal, String.format("%.0f%%", trainer.bestProgress * 100f),
                x + 260f, 334f, new Color(0.45f, 1f, 0.65f, 1f));

        Draw.text(batch, small, "适应度 " + String.format("%.0f", trainer.bestFitness),
                x, 222f, new Color(0.60f, 0.72f, 0.88f, 1f));
        Draw.text(batch, small, "并行线程 " + threads() + "    种群 " + trainer.populationSize(),
                x, 196f, new Color(0.60f, 0.72f, 0.88f, 1f));
        Draw.text(batch, small, "演示：" + (demoState == Episode.WON ? "到达终点"
                        : demoState == Episode.DIED ? "失败，重试中" : "进行中"),
                x, 170f, demoState == Episode.WON
                        ? new Color(0.45f, 1f, 0.65f, 1f)
                        : new Color(0.85f, 0.70f, 0.55f, 1f));

        if (level != null) {
            Draw.text(batch, small, "本层：平台 " + level.platforms.size()
                            + " · 移动 " + level.movers.size() + " · 尖刺 " + level.spikes.size(),
                    x, 144f, new Color(0.55f, 0.65f, 0.80f, 1f));
        }
        if (coachOn && coach != null) {
            String coachLine = coach.lastError.isEmpty()
                    ? (coach.appliedCount > 0 ? ("AI 教练已调参 " + coach.appliedCount + " 次")
                    : "AI 教练等待首次建议…")
                    : ("AI 教练失败：" + trim(coach.lastError, 28));
            Draw.text(batch, small, coachLine, x, 118f,
                    coach.lastError.isEmpty() ? new Color(0.55f, 0.90f, 0.75f, 1f)
                            : new Color(1f, 0.65f, 0.55f, 1f));
        }
        if (trainer.stalledLevels > 0) {
            Draw.text(batch, small, "未稳定学会的层 " + trainer.stalledLevels,
                    x, 118f, new Color(0.95f, 0.78f, 0.45f, 1f));
        }
        if (trainer.finished) {
            Draw.text(batch, small, "全部 100 层训练完成", x, 94f,
                    new Color(0.45f, 1f, 0.65f, 1f));
        }
    }

    /** 演示播放：复制当前最佳网络，逐帧推进；通关或失败后短暂停留再重开。 */
    /**
     * 缓存的当前层引用。
     *
     * 渲染每帧都会用到关卡，若直接调 levelAt() 会走同步块并在层尚未生成时
     * 触发一次物理自检（低端机上要几百毫秒）。这里只在层号变化时取一次。
     */
    private Level cachedLevel() {
        if (cachedLevelIndex != trainer.levelIndex) {
            cachedLevelIndex = trainer.levelIndex;
            // 只取已生成的层；未就绪时先不画关卡，等训练线程铺好
            cached = trainer.readyLevelAt(cachedLevelIndex);
        }
        return cached;
    }

    private Level cached;
    private int cachedLevelIndex = -1;

    private void stepDemo(float dt) {
        Level level = cachedLevel();
        NeuralNet snap = trainer.showcase;
        if (level == null || snap == null) return;

        if (demoLevel != trainer.levelIndex) {
            demoLevel = trainer.levelIndex;
            copyNet(snap, demoNet);
            resetDemo(level);
        }
        if (demoState != Episode.RUNNING) {
            demoTime += dt;
            if (demoTime > 1.2f) resetDemo(level);
            return;
        }

        // 累积器：低倍速时一帧不足一个物理步也不会丢，高倍速时按上限批量补步
        float stepDt = 1f / 60f;
        int cap = Math.max(8, (int) (SPEEDS[speedIndex] * 4f) + 8);
        if (cap > 900) cap = 900;
        acc += dt;
        int steps = 0;
        while (acc >= stepDt && steps < cap) {
            acc -= stepDt;
            steps++;
        }
        if (acc > stepDt * 4f) acc = stepDt * 4f;
        for (int i = 0; i < steps && demoState == Episode.RUNNING; i++) {
            float[] out = demoNet.forward(demoSensors.read(level, demoBody, demoTime));
            float move = Actions.move(out);
            demoBody.step(level, demoTime, move, Actions.jump(out), stepDt);
            demoTime += stepDt;
            if (demoBody.touchesSpike(level) || demoBody.fellOut()) demoState = Episode.DIED;
            else if (demoBody.reachedGoal(level)) demoState = Episode.WON;
        }
    }

    private void resetDemo(Level level) {
        demoBody.reset(level.startX, level.startY);
        demoTime = 0f;
        acc = 0f;
        demoState = Episode.RUNNING;
    }

    private static void copyNet(NeuralNet src, NeuralNet dst) {
        System.arraycopy(src.weights(), 0, dst.weights(), 0, src.weightCount());
        System.arraycopy(src.biases(), 0, dst.biases(), 0, src.biasCount());
    }

    private void updateInput() {
        float sx = C.VIEW_W / Gdx.graphics.getWidth();
        float sy = C.VIEW_H / Gdx.graphics.getHeight();
        Vector3 p = new Vector3(Gdx.input.getX(), Gdx.input.getY(), 0f);
        hoverX = p.x * sx;
        hoverY = C.VIEW_H - p.y * sy;

        if (Gdx.input.isKeyJustPressed(com.badlogic.gdx.Input.Keys.BACK)) {
            trainer.stop();
            game.setScreen(new MenuScreen(game));
            return;
        }
        if (Gdx.input.justTouched()) {
            if (menuBtn.hit(hoverX, hoverY)) {
                trainer.stop();
                game.setScreen(new MenuScreen(game));
            } else if (speedBtn.hit(hoverX, hoverY)) {
                speedIndex = (speedIndex + 1) % SPEEDS.length;
                speedBtn.label = "速度 ×" + fmtSpeed(SPEEDS[speedIndex]);
            } else if (saveBtn.hit(hoverX, hoverY)) {
                saveChampion();
            } else if (coachBtn.hit(hoverX, hoverY)) {
                if (coach == null) {
                    showToast("请先在主菜单设置 API 地址与密钥");
                } else {
                    coachOn = !coachOn;
                    coachBtn.label = coachOn ? "AI 教练：开" : "AI 教练：关";
                    showToast(coachOn ? "AI 教练已启用" : "AI 教练已关闭");
                }
            } else if (libraryBtn.hit(hoverX, hoverY)) {
                trainer.stop();
                game.setScreen(new ModelPickerScreen(game));
            }
        }
    }

    @Override
    public void dispose() {
        trainer.stop();
        sr.dispose();
        batch.dispose();
    }
}
