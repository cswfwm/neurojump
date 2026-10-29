package com.neurojump.ai;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * OpenAI 兼容接口的最小客户端：只要服务端实现 /chat/completions 就能用
 * （官方 OpenAI、各类中转站、本地兼容服务）。
 *
 * 只用 JDK 自带的 HttpURLConnection，避免为一次请求引入额外依赖。
 * 注意：这里不发流式请求，也不做重试——训练循环里由调用方控制频率。
 */
public final class OpenAiClient {

    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public OpenAiClient(String baseUrl, String apiKey, String model) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.baseUrl = b;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.trim().isEmpty() ? "gpt-4o-mini" : model.trim();
    }

    public boolean isConfigured() {
        return !baseUrl.isEmpty() && !apiKey.isEmpty();
    }

    /** 一次对话请求，返回助手回复正文；失败抛异常，由调用方记录。 */
    public String chat(String systemPrompt, String userPrompt, int timeoutMs) throws Exception {
        String body = "{\"model\":" + quote(model)
                + ",\"temperature\":0.3"
                + ",\"max_tokens\":320"
                + ",\"messages\":["
                + "{\"role\":\"system\",\"content\":" + quote(systemPrompt) + "},"
                + "{\"role\":\"user\",\"content\":" + quote(userPrompt) + "}]}";

        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/chat/completions")
                .openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? conn.getInputStream()
                : conn.getErrorStream();
        String text = readAll(is);
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": " + trim(text, 200));
        }
        return extractContent(text);
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 从响应里取出 choices[0].message.content。
     * 不引入 JSON 库，按字段定位并做反转义即可；拿不到时返回空串。
     */
    static String extractContent(String json) {
        int c = json.indexOf("\"content\"");
        while (c >= 0) {
            int colon = json.indexOf(':', c + 9);
            if (colon < 0) return "";
            int i = colon + 1;
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i < json.length() && json.charAt(i) == '"') {
                return unescape(json, i + 1);
            }
            c = json.indexOf("\"content\"", c + 9);
        }
        return "";
    }

    private static String unescape(String s, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"') break;
            if (ch != '\\') {
                sb.append(ch);
                continue;
            }
            if (++i >= s.length()) break;
            char e = s.charAt(i);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                    break;
                default: sb.append(e);
            }
        }
        return sb.toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
