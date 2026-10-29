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
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.neurojump.store.ModelRecord;
import com.neurojump.store.ModelStore;
import com.neurojump.ui.Button;
import com.neurojump.ui.Draw;
import com.neurojump.ui.Fonts;
import com.neurojump.world.C;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型库：浏览已保存的模型，勾选若干个进入竞速对比，也可单独删除。
 * 每行显示名字、诞生层/代、通关层数与族谱，便于判断训练成果。
 */
public final class ModelPickerScreen extends ScreenAdapter {
    private static final int BTN_MENU = 1;
    private static final int BTN_RACE = 2;
    private static final int BTN_ENDLESS = 5;
    private static final int BTN_CAMPAIGN = 6;

    private static final int ROWS_PER_PAGE = 3;

    private final GameRoot game;
    private final ShapeRenderer sr = new ShapeRenderer();
    private final SpriteBatch batch = new SpriteBatch();
    private final Matrix4 proj = new Matrix4();

    private final ModelStore store = new ModelStore();
    private final List<ModelRecord> models = new ArrayList<>();
    private final boolean[] selected = new boolean[ModelStore.MAX_MODELS];

    private final Button menuBtn;
    private final Button raceBtn;
    private final Button endlessBtn;
    private final Button campaignBtn;
    private final Button prevPageBtn;
    private final Button nextPageBtn;

    private final List<Rectangle> rows = new ArrayList<>();
    private final List<Rectangle> deleteButtons = new ArrayList<>();

    private int page;
    private float hoverX = -999f;
    private float hoverY = -999f;
    private String toast = "";
    private float toastTimer;

    public ModelPickerScreen(GameRoot game) {
        this.game = game;
        menuBtn = new Button(BTN_MENU, "返回", 24f, C.VIEW_H - 68f, 110f, 48f);
        raceBtn = new Button(BTN_RACE, "竞速对比", C.VIEW_W - 168f, 96f, 150f, 52f);
        endlessBtn = new Button(BTN_ENDLESS, "投进无限跑分", C.VIEW_W - 348f, 96f, 168f, 52f);
        campaignBtn = new Button(BTN_CAMPAIGN, "导入100关训练", C.VIEW_W - 528f, 96f, 168f, 52f);
        prevPageBtn = new Button(3, "上一页", C.VIEW_W - 388f, 26f, 110f, 56f);
        nextPageBtn = new Button(4, "下一页", C.VIEW_W - 268f, 26f, 90f, 56f);
        reload();
    }

    private void reload() {
        models.clear();
        models.addAll(store.loadAll());
        models.sort((a, b) -> Integer.compare(b.solvedLevels, a.solvedLevels));
        for (int i = 0; i < selected.length; i++) {
            if (i >= models.size()) selected[i] = false;
        }
        int maxPage = Math.max(0, (models.size() - 1) / ROWS_PER_PAGE);
        page = Math.min(page, maxPage);
    }

    @Override
    public void render(float delta) {
        updateInput();
        toastTimer = Math.max(0f, toastTimer - delta);

        Gdx.gl.glClearColor(0.05f, 0.06f, 0.10f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        proj.setToOrtho2D(0, 0, C.VIEW_W, C.VIEW_H);
        sr.setProjectionMatrix(proj);
        batch.setProjectionMatrix(proj);

        rows.clear();
        deleteButtons.clear();

        sr.begin(ShapeRenderer.ShapeType.Filled);
        menuBtn.drawShape(sr, menuBtn.hit(hoverX, hoverY), true);
        int start = page * ROWS_PER_PAGE;
        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            int idx = start + i;
            if (idx >= models.size()) break;
            float y = C.VIEW_H - 250f - i * 118f;
            Rectangle row = new Rectangle(40f, y - 96f, C.VIEW_W - 260f, 96f);
            rows.add(row);
            boolean on = selected[idx];
            boolean hover = row.contains(hoverX, hoverY);
            Draw.rect(sr, row.x, row.y, row.width, row.height,
                    on ? new Color(0.16f, 0.34f, 0.30f, 0.95f)
                            : hover ? new Color(0.16f, 0.20f, 0.30f, 0.95f)
                            : new Color(0.10f, 0.13f, 0.20f, 0.95f));
            Draw.border(sr, row.x, row.y, row.width, row.height, 2f,
                    on ? new Color(0.40f, 0.95f, 0.70f, 1f)
                            : new Color(0.22f, 0.30f, 0.44f, 1f));

            Rectangle del = new Rectangle(row.x + row.width - 96f, row.y + 26f, 76f, 44f);
            deleteButtons.add(del);
            Draw.rect(sr, del.x, del.y, del.width, del.height, new Color(0.42f, 0.16f, 0.20f, 1f));
        }
        int maxPage = Math.max(0, (models.size() - 1) / ROWS_PER_PAGE);
        if (maxPage > 0) {
            prevPageBtn.drawShape(sr, prevPageBtn.hit(hoverX, hoverY), page > 0);
            nextPageBtn.drawShape(sr, nextPageBtn.hit(hoverX, hoverY), page < maxPage);
        }
        boolean anySelected = selectedCount() > 0;
        raceBtn.drawShape(sr, raceBtn.hit(hoverX, hoverY), anySelected);
        endlessBtn.drawShape(sr, endlessBtn.hit(hoverX, hoverY), anySelected);
        campaignBtn.drawShape(sr, campaignBtn.hit(hoverX, hoverY), anySelected);
        sr.end();

        batch.begin();
        drawTexts(start);
        menuBtn.drawLabel(batch, Fonts.of(24), true);
        if (maxPage > 0) {
            prevPageBtn.drawLabel(batch, Fonts.of(20), page > 0);
            nextPageBtn.drawLabel(batch, Fonts.of(20), page < maxPage);
        }
        raceBtn.drawLabel(batch, Fonts.of(22), anySelected);
        endlessBtn.drawLabel(batch, Fonts.of(21), anySelected);
        campaignBtn.drawLabel(batch, Fonts.of(20), anySelected);
        batch.end();
    }

    private void drawTexts(int start) {
        Draw.text(batch, Fonts.of(28), "模型库", 150f, C.VIEW_H - 30f, Color.WHITE);
        Draw.text(batch, Fonts.of(20), "共 " + models.size() + " 个，已选 " + selectedCount()
                        + " 个（点行选中，选 2 个以上可对比）",
                300f, C.VIEW_H - 32f, new Color(0.60f, 0.72f, 0.90f, 1f));

        if (models.isEmpty()) {
            Draw.textCentered(batch, Fonts.of(26), "还没有存档：训练界面点「保存模型」即可",
                    C.VIEW_W * 0.5f, C.VIEW_H * 0.5f, new Color(0.60f, 0.68f, 0.82f, 1f));
            return;
        }

        BitmapFont title = Fonts.of(24);
        BitmapFont small = Fonts.of(17);
        BitmapFont btn = Fonts.of(18);
        for (int i = 0; i < rows.size(); i++) {
            int idx = start + i;
            if (idx >= models.size()) break;
            ModelRecord m = models.get(idx);
            Rectangle row = rows.get(i);
            String mark = selected[idx] ? "[已选] " : "";
            Draw.text(batch, title, mark + m.name, row.x + 18f, row.y + row.height - 14f,
                    Color.WHITE);
            Draw.text(batch, small, "诞生于第 " + (m.levelIndex + 1) + " 层 · 第 "
                            + m.generation + " 代 · 已通关 " + m.solvedLevels + " 层",
                    row.x + 18f, row.y + 44f, new Color(0.62f, 0.74f, 0.90f, 1f));
            String lineageText = (m.lineage == null || m.lineage.isEmpty())
                    ? "初始种群" : m.lineage;
            if (m.isOutdated()) lineageText = "旧版存档，网络结构已升级，无法加载";
            Draw.text(batch, small, "族谱 " + lineageText,
                    row.x + 18f, row.y + 20f, m.isOutdated()
                            ? new Color(1f, 0.65f, 0.55f, 1f)
                            : new Color(0.50f, 0.60f, 0.74f, 1f));

            Rectangle del = deleteButtons.get(i);
            Draw.textCentered(batch, btn, "删除", del.x + del.width * 0.5f,
                    del.y + del.height * 0.5f + btn.getCapHeight() * 0.5f, Color.WHITE);
        }

        if (toastTimer > 0f) {
            Draw.textCentered(batch, Fonts.of(22), toast, C.VIEW_W * 0.5f, 96f,
                    new Color(1f, 0.80f, 0.45f, 1f));
        }
    }

    private int selectedCount() {
        int n = 0;
        for (int i = 0; i < models.size() && i < selected.length; i++) {
            if (selected[i]) n++;
        }
        return n;
    }

    private List<ModelRecord> selectedModels() {
        List<ModelRecord> out = new ArrayList<>();
        for (int i = 0; i < models.size() && i < selected.length; i++) {
            if (selected[i]) out.add(models.get(i));
        }
        return out;
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
            return;
        }
        int maxPage = Math.max(0, (models.size() - 1) / ROWS_PER_PAGE);
        if (maxPage > 0 && prevPageBtn.hit(hoverX, hoverY)) {
            if (page > 0) page--;
            return;
        }
        if (maxPage > 0 && nextPageBtn.hit(hoverX, hoverY)) {
            if (page < maxPage) page++;
            return;
        }
        List<ModelRecord> picked = selectedModels();
        if (raceBtn.hit(hoverX, hoverY)) {
            if (picked.isEmpty()) {
                showToast("至少选一个模型");
            } else {
                game.setScreen(new RaceScreen(game, picked, picked.get(0).levelIndex));
            }
            return;
        }
        if (endlessBtn.hit(hoverX, hoverY)) {
            if (picked.isEmpty()) {
                showToast("至少选一个模型");
            } else {
                game.setScreen(new EndlessScreen(game, true, picked));
            }
            return;
        }
        if (campaignBtn.hit(hoverX, hoverY)) {
            if (picked.isEmpty()) {
                showToast("至少选一个模型");
            } else {
                game.setScreen(new TrainScreen(game, false, picked));
            }
            return;
        }

        int start = page * ROWS_PER_PAGE;
        for (int i = 0; i < rows.size(); i++) {
            int idx = start + i;
            if (idx >= models.size()) break;
            if (deleteButtons.get(i).contains(hoverX, hoverY)) {
                store.remove(models.get(idx));
                selected[idx] = false;
                reload();
                showToast("已删除");
                return;
            }
            if (rows.get(i).contains(hoverX, hoverY)) {
                if (models.get(idx).isOutdated()) {
                    showToast("该存档来自旧版网络，无法加载");
                    return;
                }
                selected[idx] = !selected[idx];
                return;
            }
        }
    }

    private void showToast(String msg) {
        toast = msg;
        toastTimer = 2.0f;
    }

    @Override
    public void dispose() {
        sr.dispose();
        batch.dispose();
    }
}
