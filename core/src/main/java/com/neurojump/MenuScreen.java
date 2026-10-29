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
import com.neurojump.ui.Button;
import com.neurojump.ui.Draw;
import com.neurojump.ui.Fonts;
import com.neurojump.world.C;

/** 主菜单：课程训练（100 层）/ 无限训练 / 竞速对比 / 手动试玩。 */
public final class MenuScreen extends ScreenAdapter {
    private static final int BTN_CAMPAIGN = 1;
    private static final int BTN_ENDLESS = 2;
    private static final int BTN_RACE = 3;
    private static final int BTN_PLAY = 4;
    private static final int BTN_API = 5;
    private static final int BTN_ENDLESS_RUN = 6;
    private static final int BTN_TIMING = 7;

    private final GameRoot game;
    private final ShapeRenderer sr = new ShapeRenderer();
    private final SpriteBatch batch = new SpriteBatch();
    private final Matrix4 proj = new Matrix4();

    private final Button campaignBtn;
    private final Button endlessBtn;
    private final Button raceBtn;
    private final Button playBtn;
    private final Button apiBtn;
    private final Button endlessRunBtn;
    private final Button timingBtn;

    private float hoverX = -999f;
    private float hoverY = -999f;

    public MenuScreen(GameRoot game) {
        this.game = game;
        float cx = C.VIEW_W * 0.5f;
        campaignBtn = new Button(BTN_CAMPAIGN, "课程训练（100 层）", cx - 250f, 436f, 500f, 66f);
        endlessBtn = new Button(BTN_ENDLESS, "无限训练（层数无上限）", cx - 250f, 362f, 500f, 66f);
        endlessRunBtn = new Button(BTN_ENDLESS_RUN, "无限跑分（跑酷）", cx - 250f, 288f, 500f, 66f);
        timingBtn = new Button(BTN_TIMING, "卡点训练（全是移动平台）", cx - 250f, 214f, 500f, 66f);
        raceBtn = new Button(BTN_RACE, "竞速对比（读存档模型）", cx - 250f, 140f, 500f, 66f);
        playBtn = new Button(BTN_PLAY, "手动试玩", cx - 250f, 56f, 300f, 66f);
        apiBtn = new Button(BTN_API, "AI 接口设置", cx + 66f, 56f, 184f, 66f);
    }

    @Override
    public void render(float delta) {
        updateInput();

        Gdx.gl.glClearColor(0.05f, 0.06f, 0.10f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        proj.setToOrtho2D(0, 0, C.VIEW_W, C.VIEW_H);
        sr.setProjectionMatrix(proj);
        batch.setProjectionMatrix(proj);

        sr.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < 9; i++) {
            float x = 60f + i * 140f;
            float y = 60f + (i % 3) * 30f;
            Draw.rect(sr, x, y, 96f, 10f,
                    new Color(0.16f, 0.26f, 0.46f, 1f - i * 0.06f));
        }
        campaignBtn.drawShape(sr, campaignBtn.hit(hoverX, hoverY), true);
        endlessBtn.drawShape(sr, endlessBtn.hit(hoverX, hoverY), true);
        raceBtn.drawShape(sr, raceBtn.hit(hoverX, hoverY), true);
        endlessRunBtn.drawShape(sr, endlessRunBtn.hit(hoverX, hoverY), true);
        timingBtn.drawShape(sr, timingBtn.hit(hoverX, hoverY), true);
        playBtn.drawShape(sr, playBtn.hit(hoverX, hoverY), true);
        apiBtn.drawShape(sr, apiBtn.hit(hoverX, hoverY), true);
        sr.end();

        batch.begin();
        BitmapFont btnFont = Fonts.of(32);
        campaignBtn.drawLabel(batch, btnFont, true);
        endlessBtn.drawLabel(batch, btnFont, true);
        raceBtn.drawLabel(batch, btnFont, true);
        endlessRunBtn.drawLabel(batch, Fonts.of(28), true);
        timingBtn.drawLabel(batch, Fonts.of(28), true);
        playBtn.drawLabel(batch, btnFont, true);
        apiBtn.drawLabel(batch, Fonts.of(20), true);

        Draw.textCentered(batch, Fonts.of(72), "神经网络寻路", C.VIEW_W * 0.5f, 660f, Color.WHITE);
        Draw.textCentered(batch, Fonts.of(28), "遗传算法　逐层课程　模型存档对比",
                C.VIEW_W * 0.5f, 578f, new Color(0.55f, 0.72f, 0.95f, 1f));
        Draw.textCentered(batch, Fonts.of(20),
                "AI 从不会走路开始，逐层学会跳跃、等待移动平台、躲避尖刺",
                C.VIEW_W * 0.5f, 40f, new Color(0.45f, 0.52f, 0.66f, 1f));
        batch.end();
    }

    private void updateInput() {
        float sx = C.VIEW_W / Gdx.graphics.getWidth();
        float sy = C.VIEW_H / Gdx.graphics.getHeight();
        Vector3 p = new Vector3(Gdx.input.getX(), Gdx.input.getY(), 0f);
        hoverX = p.x * sx;
        hoverY = C.VIEW_H - p.y * sy;

        if (Gdx.input.isKeyJustPressed(Input.Keys.BACK)) {
            Gdx.app.exit();
            return;
        }
        if (!Gdx.input.justTouched()) return;

        if (campaignBtn.hit(hoverX, hoverY)) {
            game.setScreen(new TrainScreen(game, false));
        } else if (endlessBtn.hit(hoverX, hoverY)) {
            game.setScreen(new TrainScreen(game, true));
        } else if (raceBtn.hit(hoverX, hoverY)) {
            game.setScreen(new ModelPickerScreen(game));
        } else if (playBtn.hit(hoverX, hoverY)) {
            game.setScreen(new PlayScreen(game));
        } else if (endlessRunBtn.hit(hoverX, hoverY)) {
            game.setScreen(new EndlessScreen(game, false, null));
        } else if (timingBtn.hit(hoverX, hoverY)) {
            game.setScreen(new EndlessScreen(game, true, null,
                    com.neurojump.train.EndlessTrainer.Style.TIMING));
        } else if (apiBtn.hit(hoverX, hoverY)) {
            game.setScreen(new ApiScreen(game));
        }
    }

    @Override
    public void dispose() {
        sr.dispose();
        batch.dispose();
    }
}
