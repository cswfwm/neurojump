package com.neurojump;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Input.TextInputListener;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.neurojump.ai.ApiSettings;
import com.neurojump.ai.OpenAiClient;
import com.neurojump.ui.Button;
import com.neurojump.ui.Draw;
import com.neurojump.ui.Fonts;
import com.neurojump.world.C;

/**
 * API 设置：配置 OpenAI 兼容接口的地址、密钥与模型，并可当场测试连通性。
 * 输入走系统文本对话框，避免在 GL 界面里自绘键盘。
 */
public final class ApiScreen extends ScreenAdapter {
    private final GameRoot game;
    private final ShapeRenderer sr = new ShapeRenderer();
    private final SpriteBatch batch = new SpriteBatch();
    private final Matrix4 proj = new Matrix4();

    private final ApiSettings settings = ApiSettings.load();

    private final Button menuBtn;
    private final Button baseBtn;
    private final Button keyBtn;
    private final Button modelBtn;
    private final Button testBtn;

    private float hoverX = -999f;
    private float hoverY = -999f;
    private String status = "";
    private boolean testing;

    public ApiScreen(GameRoot game) {
        this.game = game;
        menuBtn = new Button(1, "返回", 24f, C.VIEW_H - 68f, 110f, 48f);
        baseBtn = new Button(2, "修改接口地址", 120f, 400f, 300f, 56f);
        keyBtn = new Button(3, "修改密钥", 120f, 320f, 300f, 56f);
        modelBtn = new Button(4, "修改模型名", 120f, 240f, 300f, 56f);
        testBtn = new Button(5, "测试连接", 120f, 150f, 300f, 56f);
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
        menuBtn.drawShape(sr, menuBtn.hit(hoverX, hoverY), true);
        baseBtn.drawShape(sr, baseBtn.hit(hoverX, hoverY), true);
        keyBtn.drawShape(sr, keyBtn.hit(hoverX, hoverY), true);
        modelBtn.drawShape(sr, modelBtn.hit(hoverX, hoverY), true);
        testBtn.drawShape(sr, testBtn.hit(hoverX, hoverY), !testing);
        sr.end();

        batch.begin();
        BitmapFont title = Fonts.of(28);
        BitmapFont label = Fonts.of(22);
        BitmapFont value = Fonts.of(22);
        BitmapFont small = Fonts.of(18);

        Draw.text(batch, title, "AI 教练 · 接口设置", 140f, C.VIEW_H - 30f, Color.WHITE);
        Draw.text(batch, small, "支持任何 OpenAI 格式的 /chat/completions 服务",
                140f, C.VIEW_H - 64f, new Color(0.55f, 0.70f, 0.90f, 1f));

        Draw.text(batch, label, "接口地址", 140f, 500f, new Color(0.70f, 0.80f, 0.95f, 1f));
        Draw.text(batch, value, settings.baseUrl, 150f, 470f, Color.WHITE);

        Draw.text(batch, label, "密钥", 140f, 435f, new Color(0.70f, 0.80f, 0.95f, 1f));
        Draw.text(batch, value, settings.maskedKey(), 150f, 405f, Color.WHITE);

        Draw.text(batch, label, "模型名", 140f, 355f, new Color(0.70f, 0.80f, 0.95f, 1f));
        Draw.text(batch, value, settings.model, 150f, 325f, Color.WHITE);

        menuBtn.drawLabel(batch, Fonts.of(24), true);
        baseBtn.drawLabel(batch, Fonts.of(22), true);
        keyBtn.drawLabel(batch, Fonts.of(22), true);
        modelBtn.drawLabel(batch, Fonts.of(22), true);
        testBtn.drawLabel(batch, Fonts.of(22), !testing);

        if (!status.isEmpty()) {
            Draw.text(batch, small, status, 140f, 110f, new Color(0.95f, 0.82f, 0.45f, 1f));
        }
        Draw.text(batch, small, "训练界面勾选「AI 教练」后，会周期性根据训练状态调整进化参数",
                140f, 70f, new Color(0.50f, 0.60f, 0.75f, 1f));
        batch.end();
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
        } else if (baseBtn.hit(hoverX, hoverY)) {
            prompt("接口地址", settings.baseUrl, v -> {
                settings.baseUrl = v;
                settings.save();
            });
        } else if (keyBtn.hit(hoverX, hoverY)) {
            prompt("API 密钥", "", v -> {
                settings.apiKey = v;
                settings.save();
            });
        } else if (modelBtn.hit(hoverX, hoverY)) {
            prompt("模型名", settings.model, v -> {
                settings.model = v;
                settings.save();
            });
        } else if (testBtn.hit(hoverX, hoverY) && !testing) {
            test();
        }
    }

    private void prompt(String title, String initial, java.util.function.Consumer<String> onOk) {
        Gdx.input.getTextInput(new TextInputListener() {
            @Override
            public void input(String text) {
                onOk.accept(text.trim());
                status = "已保存";
            }

            @Override
            public void canceled() {
                status = "已取消";
            }
        }, title, initial, "");
    }

    /** 发一次最小请求验证配置是否可用，结果回写到状态栏。 */
    private void test() {
        if (!settings.isConfigured()) {
            status = "请先填写接口地址与密钥";
            return;
        }
        testing = true;
        status = "正在测试…";
        new Thread(() -> {
            try {
                OpenAiClient c = settings.client();
                String reply = c.chat("只回复两个字母：OK", "连通性测试", 15000);
                status = "连接成功，模型回复：" + (reply.length() > 40
                        ? reply.substring(0, 40) + "…" : reply);
            } catch (Exception e) {
                status = "连接失败：" + (e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage());
            } finally {
                testing = false;
            }
        }, "api-test").start();
    }

    @Override
    public void dispose() {
        sr.dispose();
        batch.dispose();
    }
}
