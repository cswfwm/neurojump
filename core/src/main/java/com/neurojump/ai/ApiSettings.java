package com.neurojump.ai;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;

/** OpenAI 兼容接口的配置：地址、密钥、模型名。存在 Preferences 里，跨启动保留。 */
public final class ApiSettings {
    private static final String PREFS = "neurojump-api";
    private static final String KEY_BASE = "baseUrl";
    private static final String KEY_KEY = "apiKey";
    private static final String KEY_MODEL = "model";

    public String baseUrl = "https://api.openai.com/v1";
    public String apiKey = "";
    public String model = "gpt-4o-mini";

    public static ApiSettings load() {
        ApiSettings s = new ApiSettings();
        Preferences p = Gdx.app.getPreferences(PREFS);
        s.baseUrl = p.getString(KEY_BASE, s.baseUrl);
        s.apiKey = p.getString(KEY_KEY, "");
        s.model = p.getString(KEY_MODEL, s.model);
        return s;
    }

    public void save() {
        Preferences p = Gdx.app.getPreferences(PREFS);
        p.putString(KEY_BASE, baseUrl == null ? "" : baseUrl.trim());
        p.putString(KEY_KEY, apiKey == null ? "" : apiKey.trim());
        p.putString(KEY_MODEL, model == null ? "" : model.trim());
        p.flush();
    }

    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.trim().isEmpty()
                && apiKey != null && !apiKey.trim().isEmpty();
    }

    public OpenAiClient client() {
        return new OpenAiClient(baseUrl, apiKey, model);
    }

    /** 密钥脱敏展示，界面上不显示完整内容。 */
    public String maskedKey() {
        if (apiKey == null || apiKey.isEmpty()) return "（未设置）";
        if (apiKey.length() <= 8) return "****";
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
