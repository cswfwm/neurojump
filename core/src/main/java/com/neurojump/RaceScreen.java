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
import com.neurojump.race.Arena;
import com.neurojump.race.Racer;
import com.neurojump.store.ModelRecord;
import com.neurojump.store.ModelStore;
import com.neurojump.ui.Button;
import com.neurojump.ui.Draw;
import com.neurojump.ui.Fonts;
import com.neurojump.ui.LevelRenderer;
import com.neurojump.world.AgentBody;
import com.neurojump.world.C;
import com.neurojump.world.Level;
import com.neurojump.world.LevelFactory;

import java.util.List;

/**
 * 竞速演进：把多个族群的模型放进同一关卡同时跑，按名次择优；
 * 一代结束后自动交叉繁殖产生新模型，进入更难的一关继续竞争。
 */
public final class RaceScreen extends ScreenAdapter {
    private static final Color[] PALETTE = {
            new Color(0.40f, 0.90f, 1.00f, 1f),
            new Color(1.00f, 0.72f, 0.35f, 1f),
            new Color(0.62f, 1.00f, 0.55f, 1f),
            new Color(1.00f, 0.55f, 0.72f, 1f),
            new Color(0.78f, 0.68f, 1.00f, 1f),
            new Color(0.55f, 0.95f, 0.85f, 1f),
    };

    private static final float MAX_RACE_TIME = 45f;
    /** 一代跑完后的展示停顿，让人看清名次再繁殖。 */
    private static final float HOLD_TIME = 2.0f;

    private final GameRoot game;
    private final ShapeRenderer sr = new ShapeRenderer();
    private final SpriteBatch batch = new SpriteBatch();
    private final Matrix4 proj = new Matrix4();

    private final Button menuBtn;
    private final Button saveBtn;
    private final Button fightBtn;

    private final Arena arena;
    private Level level;
    private float raceTime;
    private float holdTimer;
    private boolean ranked;

    private float hoverX = -999f;
    private float hoverY = -999f;
    private String toast = "";
    private float toastTimer;

    public RaceScreen(GameRoot game, List<ModelRecord> ancestors, int levelIndex) {
        this.game = game;
        this.arena = new Arena(ancestors, levelIndex, ancestors.isEmpty() ? 6 : Math.max(4,
                Math.min(6, ancestors.size())));
        menuBtn = new Button(1, "返回", 24f, C.VIEW_H - 68f, 110f, 48f);
        saveBtn = new Button(2, "保存冠军", 148f, C.VIEW_H - 68f, 150f, 48f);
        fightBtn = new Button(3, "立即繁殖", C.VIEW_W - 176f, C.VIEW_H - 68f, 150f, 48f);

        level = LevelFactory.build(arena.levelIndex());
        arena.setLevel(level);
        arena.startRace(level);
        raceTime = 0f;
        ranked = false;
        holdTimer = 0f;
    }

    @Override
    public void render(float delta) {
        updateInput();
        toastTimer = Math.max(0f, toastTimer - delta);

        if (!ranked) {
            float dt = 1f / 60f;
            int steps = Math.max(1, Math.min(4, (int) (delta / dt)));
            for (int s = 0; s < steps; s++) {
                arena.step(dt);
                raceTime += dt;
                if (arena.allDone() || raceTime >= MAX_RACE_TIME) {
                    arena.rankRacers();
                    ranked = true;
                    holdTimer = 0f;
                    break;
                }
            }
        } else {
            holdTimer += delta;
            if (holdTimer >= HOLD_TIME) nextGeneration();
        }

        Gdx.gl.glClearColor(0.05f, 0.06f, 0.10f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        proj.setToOrtho2D(0, 0, C.VIEW_W, C.VIEW_H);
        sr.setProjectionMatrix(proj);
        batch.setProjectionMatrix(proj);

        float viewH = C.VIEW_H - 330f;
        float scale = Math.min((C.VIEW_W - 60f) / level.width, viewH / level.height);
        float ox = (C.VIEW_W - level.width * scale) * 0.5f;
        float oy = 300f;

        sr.begin(ShapeRenderer.ShapeType.Filled);
        Draw.rect(sr, 24f, 286f, C.VIEW_W - 48f, viewH + 16f,
                new Color(0.07f, 0.09f, 0.14f, 0.94f));
        LevelRenderer.drawWorld(sr, level, raceTime, ox, oy, scale);
        for (int i = 0; i < arena.racers().size(); i++) {
            AgentBody b = arena.racers().get(i).body();
            LevelRenderer.drawAgent(sr, b, ox, oy, scale, PALETTE[i % PALETTE.length]);
        }
        drawBoard();
        menuBtn.drawShape(sr, menuBtn.hit(hoverX, hoverY), true);
        saveBtn.drawShape(sr, saveBtn.hit(hoverX, hoverY), ranked);
        fightBtn.drawShape(sr, fightBtn.hit(hoverX, hoverY), true);
        sr.end();

        batch.begin();
        menuBtn.drawLabel(batch, Fonts.of(24), true);
        saveBtn.drawLabel(batch, Fonts.of(22), ranked);
        fightBtn.drawLabel(batch, Fonts.of(22), true);
        drawTexts();
        batch.end();
    }

    /** 榜单底：每行一条进度条，颜色对应选手。 */
    private void drawBoard() {
        float top = 258f;
        float rowH = 34f;
        for (int i = 0; i < arena.racers().size(); i++) {
            Racer r = arena.racers().get(i);
            Color c = PALETTE[i % PALETTE.length];
            float y = top - i * rowH;
            Draw.rect(sr, 40f, y, 12f, 12f, c);
            Draw.rect(sr, 250f, y + 2f, 320f * r.progress, 8f, c);
            Draw.rect(sr, 250f, y + 2f, 320f, 8f, new Color(1f, 1f, 1f, 0.08f));
        }
    }

    private void drawTexts() {
        BitmapFont h1 = Fonts.of(26);
        BitmapFont small = Fonts.of(18);
        BitmapFont row = Fonts.of(17);

        Draw.text(batch, h1, "竞速演进 · 第 " + (arena.generation) + " 代 · 第 "
                        + (arena.levelIndex() + 1) + " 层",
                320f, C.VIEW_H - 30f, Color.WHITE);
        Draw.text(batch, small, String.format("用时 %.1fs", raceTime), 760f,
                C.VIEW_H - 30f, new Color(0.60f, 0.72f, 0.90f, 1f));

        float top = 258f;
        float rowH = 34f;
        for (int i = 0; i < arena.racers().size(); i++) {
            Racer r = arena.racers().get(i);
            float y = top - i * rowH;
            String rank = r.rank > 0 ? ("#" + r.rank) : "·";
            Draw.text(batch, row, rank, 60f, y + 10f, new Color(0.85f, 0.90f, 1f, 1f));
            Draw.text(batch, row, r.name, 96f, y + 10f, Color.WHITE);
            Draw.text(batch, row, r.stateText(), 600f, y + 10f,
                    r.state == Racer.FINISHED ? new Color(0.45f, 1f, 0.65f, 1f)
                            : r.state == Racer.FAILED ? new Color(1f, 0.55f, 0.55f, 1f)
                            : new Color(0.70f, 0.80f, 0.95f, 1f));
        }

        if (ranked) {
            Draw.text(batch, small, "本代结束，正在繁殖下一代…", 40f, 40f,
                    new Color(0.95f, 0.82f, 0.45f, 1f));
        }
        if (toastTimer > 0f) {
            Draw.textCentered(batch, Fonts.of(21), toast, C.VIEW_W * 0.5f, 76f,
                    new Color(0.45f, 1f, 0.70f, 1f));
        }
    }

    private void nextGeneration() {
        arena.evolve();
        Level newLevel = LevelFactory.build(arena.levelIndex());
        arena.setLevel(newLevel);
        arena.startRace(newLevel);
        raceTime = 0f;
        ranked = false;
        holdTimer = 0f;
        level = newLevel;
    }

    private void saveChampion() {
        Arena.Individual champ = arena.champion();
        if (champ == null) {
            showToast("还没有可保存的冠军");
            return;
        }
        ModelRecord rec = ModelRecord.of("竞速-" + arena.generation + "代-" + shortName(champ.name),
                champ.net, arena.levelIndex(), arena.levelIndex(), arena.generation,
                arena.championFitness(), champ.lineage);
        ModelStore store = new ModelStore();
        showToast(store.save(rec) ? "已保存冠军：" + rec.name : "存档已满，请到模型库删除");
    }

    private static String shortName(String s) {
        return s.length() > 12 ? s.substring(0, 12) : s;
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
            game.setScreen(new MenuScreen(game));
            return;
        }
        if (!Gdx.input.justTouched()) return;
        if (menuBtn.hit(hoverX, hoverY)) {
            game.setScreen(new MenuScreen(game));
        } else if (saveBtn.hit(hoverX, hoverY) && ranked) {
            saveChampion();
        } else if (fightBtn.hit(hoverX, hoverY)) {
            arena.rankRacers();
            nextGeneration();
        }
    }

    @Override
    public void dispose() {
        sr.dispose();
        batch.dispose();
    }
}
