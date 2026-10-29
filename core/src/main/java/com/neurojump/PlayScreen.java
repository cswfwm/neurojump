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
 * 手动试玩：与 AI 完全相同的物理与关卡，玩家自己上手。
 * 左下角虚拟方向键控制左右，右下角按钮跳跃；也支持键盘 A/D 与空格。
 */
public final class PlayScreen extends ScreenAdapter {
    private static final int BTN_MENU = 1;
    private static final int BTN_RESET = 2;
    private static final int BTN_PREV = 3;
    private static final int BTN_NEXT = 4;

    private final GameRoot game;
    private final ShapeRenderer sr = new ShapeRenderer();
    private final SpriteBatch batch = new SpriteBatch();
    private final Matrix4 proj = new Matrix4();

    // 不预生成：100 层一次性生成要做上百次物理自检，在主线程会触发 ANR。
    // 这里只在切到某层时才生成，切层有几百毫秒延迟但不会闪退。
    private final java.util.Map<Integer, Level> levelCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 生成并缓存一层；调用前请确保不在渲染主路径上。 */
    /** 缓存上限：每层约 30KB，留 24 层足够来回切换，又不至于吃掉低配机的内存。 */
    private static final int CACHE_LIMIT = 24;

    private Level buildLevel(int index) {
        Level lv = levelCache.get(index);
        if (lv == null) {
            lv = LevelFactory.buildInfinite(index);
            if (levelCache.size() >= CACHE_LIMIT) {
                // 只清掉较远的层，保留当前层与邻近几层，避免把正在用的关卡丢掉
                java.util.Iterator<java.util.Map.Entry<Integer, Level>> it =
                        levelCache.entrySet().iterator();
                while (it.hasNext()) {
                    int k = it.next().getKey();
                    if (Math.abs(k - index) > 3) it.remove();
                }
            }
            levelCache.put(index, lv);
        }
        return lv;
    }

    /**
     * 后台把邻近的几层先造好。
     * 这样切「上一关/下一关」时基本是瞬时的，不需要玩家等物理自检。
     */
    private volatile boolean prefetching;

    private void prefetchAround(int index) {
        if (prefetching) return;
        prefetching = true;
        new Thread(() -> {
            try {
                for (int d = 1; d <= 3; d++) {
                    int nx = index + d;
                    if (nx < LEVEL_TOTAL) buildLevel(nx);
                }
            } finally {
                prefetching = false;
            }
        }, "level-prefetch").start();
    }

    private static final int LEVEL_TOTAL = C.LEVEL_COUNT;
    private int levelIndex;
    private Level level;
    private final AgentBody body = new AgentBody();

    private final Button menuBtn;
    private final Button resetBtn;
    private final Button prevBtn;
    private final Button nextBtn;

    private final Rectangle leftPad = new Rectangle(40f, 40f, 130f, 130f);
    private final Rectangle rightPad = new Rectangle(190f, 40f, 130f, 130f);
    private final Rectangle jumpPad = new Rectangle(C.VIEW_W - 200f, 40f, 150f, 150f);

    private boolean touchLeft;
    private boolean touchRight;
    private boolean touchJump;

    private float time;
    private int state;          // 0 进行中, 1 通关, 2 失败
    private float resultTimer;
    private float hoverX = -999f;
    private float hoverY = -999f;

    public PlayScreen(GameRoot game) {
        this.game = game;
        menuBtn = new Button(BTN_MENU, "返回", 24f, C.VIEW_H - 68f, 110f, 48f);
        resetBtn = new Button(BTN_RESET, "重来", 148f, C.VIEW_H - 68f, 110f, 48f);
        prevBtn = new Button(BTN_PREV, "上一关", C.VIEW_W - 288f, C.VIEW_H - 68f, 120f, 48f);
        nextBtn = new Button(BTN_NEXT, "下一关", C.VIEW_W - 156f, C.VIEW_H - 68f, 120f, 48f);
        loadLevel(0);
    }

    private void loadLevel(int index) {
        levelIndex = Math.max(0, Math.min(LEVEL_TOTAL - 1, index));
        level = buildLevel(levelIndex);
        prefetchAround(levelIndex);
        body.reset(level.startX, level.startY);
        time = 0f;
        state = 0;
        resultTimer = 0f;
    }

    @Override
    public void render(float delta) {
        updateInput();
        float dt = Math.min(delta, 1f / 30f);
        if (state == 0) {
            float move = (touchRight ? 1f : 0f) - (touchLeft ? 1f : 0f);
            body.step(level, time, move, touchJump, dt);
            time += dt;
            if (body.touchesSpike(level) || body.fellOut()) state = 2;
            else if (body.reachedGoal(level)) state = 1;
        } else {
            resultTimer += dt;
            if (state == 1 && resultTimer > 1.0f && levelIndex + 1 < LEVEL_TOTAL) {
                loadLevel(levelIndex + 1);
            }
        }

        Gdx.gl.glClearColor(0.05f, 0.06f, 0.10f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        proj.setToOrtho2D(0, 0, C.VIEW_W, C.VIEW_H);
        sr.setProjectionMatrix(proj);
        batch.setProjectionMatrix(proj);

        // 关卡整体缩到触摸区之上（触摸区高 130 起于 y=40），避免遮挡
        float viewH = C.VIEW_H - 240f;
        float scale = Math.min((C.VIEW_W - 40f) / level.width, viewH / level.height);
        float ox = (C.VIEW_W - level.width * scale) * 0.5f;
        float oy = 190f;

        sr.begin(ShapeRenderer.ShapeType.Filled);
        LevelRenderer.drawWorld(sr, level, time, ox, oy, scale);
        LevelRenderer.drawAgent(sr, body, ox, oy, scale, Color.WHITE);
        drawTouchPads();
        menuBtn.drawShape(sr, menuBtn.hit(hoverX, hoverY), true);
        resetBtn.drawShape(sr, resetBtn.hit(hoverX, hoverY), true);
        prevBtn.drawShape(sr, prevBtn.hit(hoverX, hoverY), levelIndex > 0);
        nextBtn.drawShape(sr, nextBtn.hit(hoverX, hoverY), levelIndex + 1 < LEVEL_TOTAL);
        // 箱子数量：空中按住跳可以消耗一个续跳
        for (int i = 0; i < C.CHEST_MAX; i++) {
            Draw.rect(sr, 120f + i * 26f, C.VIEW_H - 152f, 18f, 18f,
                    i < body.chests ? new Color(0.95f, 0.78f, 0.30f, 1f)
                            : new Color(0.25f, 0.27f, 0.34f, 1f));
        }
        sr.end();

        batch.begin();
        menuBtn.drawLabel(batch, Fonts.of(24), true);
        resetBtn.drawLabel(batch, Fonts.of(24), true);
        prevBtn.drawLabel(batch, Fonts.of(24), levelIndex > 0);
        nextBtn.drawLabel(batch, Fonts.of(24), levelIndex + 1 < LEVEL_TOTAL);
        Draw.text(batch, Fonts.of(28), "第 " + (levelIndex + 1) + " / " + LEVEL_TOTAL + " 层",
                120f, C.VIEW_H - 92f, Color.WHITE);
        Draw.text(batch, Fonts.of(18), "A/D 移动，空格跳跃；空中再按一次跳可消耗箱子续跳",
                300f, C.VIEW_H - 88f, new Color(0.55f, 0.65f, 0.82f, 1f));
        Draw.text(batch, Fonts.of(17), "箱子", 60f, C.VIEW_H - 136f,
                new Color(0.85f, 0.78f, 0.45f, 1f));
        if (state == 1) {
            Draw.textCentered(batch, Fonts.of(58), "通关！", C.VIEW_W * 0.5f, C.VIEW_H * 0.55f,
                    new Color(0.45f, 1f, 0.65f, 1f));
        } else if (state == 2) {
            Draw.textCentered(batch, Fonts.of(58), "失败", C.VIEW_W * 0.5f, C.VIEW_H * 0.55f,
                    new Color(1f, 0.5f, 0.55f, 1f));
        }
        batch.end();
    }

    private void drawTouchPads() {
        Color base = new Color(1f, 1f, 1f, 0.10f);
        Color active = new Color(0.45f, 0.75f, 1f, 0.35f);
        Draw.rect(sr, leftPad.x, leftPad.y, leftPad.width, leftPad.height,
                touchLeft ? active : base);
        Draw.rect(sr, rightPad.x, rightPad.y, rightPad.width, rightPad.height,
                touchRight ? active : base);
        Draw.rect(sr, jumpPad.x, jumpPad.y, jumpPad.width, jumpPad.height,
                touchJump ? new Color(0.5f, 1f, 0.7f, 0.35f) : base);

        // 方向符号
        sr.setColor(new Color(1f, 1f, 1f, 0.75f));
        float cx = leftPad.x + leftPad.width * 0.5f;
        float cy = leftPad.y + leftPad.height * 0.5f;
        sr.triangle(cx + 18f, cy + 26f, cx + 18f, cy - 26f, cx - 24f, cy);
        cx = rightPad.x + rightPad.width * 0.5f;
        sr.triangle(cx - 18f, cy + 26f, cx - 18f, cy - 26f, cx + 24f, cy);
        cx = jumpPad.x + jumpPad.width * 0.5f;
        sr.triangle(cx - 26f, cy - 12f, cx + 26f, cy - 12f, cx, cy + 28f);
    }

    private void updateInput() {
        float sx = C.VIEW_W / Gdx.graphics.getWidth();
        float sy = C.VIEW_H / Gdx.graphics.getHeight();
        Vector3 p = new Vector3(Gdx.input.getX(), Gdx.input.getY(), 0f);
        hoverX = p.x * sx;
        hoverY = C.VIEW_H - p.y * sy;

        // 触摸：按下期间持续生效，支持多点触控
        touchLeft = false;
        touchRight = false;
        touchJump = false;
        for (int i = 0; i < 4; i++) {
            if (!Gdx.input.isTouched(i)) continue;
            float tx = Gdx.input.getX(i) * sx;
            float ty = C.VIEW_H - Gdx.input.getY(i) * sy;
            if (leftPad.contains(tx, ty)) touchLeft = true;
            if (rightPad.contains(tx, ty)) touchRight = true;
            if (jumpPad.contains(tx, ty)) touchJump = true;
        }
        // 键盘
        if (Gdx.input.isKeyPressed(Input.Keys.A) || Gdx.input.isKeyPressed(Input.Keys.LEFT)) {
            touchLeft = true;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.D) || Gdx.input.isKeyPressed(Input.Keys.RIGHT)) {
            touchRight = true;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.SPACE) || Gdx.input.isKeyPressed(Input.Keys.W)) {
            touchJump = true;
        }

        if (Gdx.input.isKeyJustPressed(Input.Keys.BACK)) {
            game.setScreen(new MenuScreen(game));
            return;
        }
        if (Gdx.input.justTouched()) {
            if (menuBtn.hit(hoverX, hoverY)) {
                game.setScreen(new MenuScreen(game));
            } else if (resetBtn.hit(hoverX, hoverY)) {
                loadLevel(levelIndex);
            } else if (prevBtn.hit(hoverX, hoverY)) {
                loadLevel(levelIndex - 1);
            } else if (nextBtn.hit(hoverX, hoverY)) {
                loadLevel(levelIndex + 1);
            }
        }
    }

    @Override
    public void dispose() {
        sr.dispose();
        batch.dispose();
    }
}
