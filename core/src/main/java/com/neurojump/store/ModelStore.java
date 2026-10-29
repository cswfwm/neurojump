package com.neurojump.store;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型存档仓库。
 *
 * 每个存档单独存一个键，避免整表序列化时因体积过大触发 Preferences 限制。
 * 键名固定为 {@code model.<序号>}，索引表记录所有已用序号。
 */
public final class ModelStore {
    private static final String PREFS = "neurojump-models";
    private static final String KEY_INDEX = "index";
    private static final String KEY_PREFIX = "model.";

    /** 存档上限，超过后拒绝保存并提示，避免无限膨胀。 */
    public static final int MAX_MODELS = 24;

    private final Preferences prefs = Gdx.app.getPreferences(PREFS);

    public ModelStore() {}

    public List<ModelRecord> loadAll() {
        List<ModelRecord> list = new ArrayList<>();
        String idx = prefs.getString(KEY_INDEX, "");
        if (idx.isEmpty()) return list;
        for (String part : idx.split(",")) {
            if (part.isEmpty()) continue;
            ModelRecord r = decode(prefs.getString(KEY_PREFIX + part, ""));
            if (r != null) list.add(r);
        }
        return list;
    }

    /** 保存一个模型，返回是否成功（达到上限时失败）。 */
    public boolean save(ModelRecord record) {
        List<ModelRecord> existing = loadAll();
        if (existing.size() >= MAX_MODELS) return false;
        int id = nextId();
        prefs.putString(KEY_PREFIX + id, encode(record));
        String idx = prefs.getString(KEY_INDEX, "");
        prefs.putString(KEY_INDEX, idx.isEmpty() ? String.valueOf(id) : idx + "," + id);
        prefs.flush();
        return true;
    }

    public void remove(ModelRecord record) {
        String idx = prefs.getString(KEY_INDEX, "");
        if (idx.isEmpty()) return;
        List<String> keep = new ArrayList<>();
        for (String id : idx.split(",")) {
            if (id.isEmpty()) continue;
            ModelRecord r = decode(prefs.getString(KEY_PREFIX + id, ""));
            if (r == null || !r.name.equals(record.name)) keep.add(id);
            else prefs.remove(KEY_PREFIX + id);
        }
        prefs.putString(KEY_INDEX, String.join(",", keep));
        prefs.flush();
    }

    private int nextId() {
        int max = 0;
        String idx = prefs.getString(KEY_INDEX, "");
        if (!idx.isEmpty()) {
            for (String s : idx.split(",")) {
                try {
                    max = Math.max(max, Integer.parseInt(s));
                } catch (NumberFormatException ignored) {
                    // 索引项损坏时跳过
                }
            }
        }
        return max + 1;
    }

    /**
     * 编码格式：字段用 \u0001 分隔，基因用逗号分隔。
     * Preferences 在 Android 上走 XML，控制字符不会破坏结构。
     */
    private static String encode(ModelRecord r) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append(r.name).append('\u0001')
                .append(r.savedAt).append('\u0001')
                .append(r.solvedLevels).append('\u0001')
                .append(r.levelIndex).append('\u0001')
                .append(r.generation).append('\u0001')
                .append(r.bestProgress).append('\u0001')
                .append(r.lineage == null ? "" : r.lineage).append('\u0001');
        if (r.genes != null) {
            for (int i = 0; i < r.genes.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(r.genes[i]);
            }
        }
        return sb.toString();
    }

    private static ModelRecord decode(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String[] parts = raw.split("\u0001", -1);
        if (parts.length < 8) return null;
        ModelRecord r = new ModelRecord();
        try {
            r.name = parts[0];
            r.savedAt = Long.parseLong(parts[1]);
            r.solvedLevels = Integer.parseInt(parts[2]);
            r.levelIndex = Integer.parseInt(parts[3]);
            r.generation = Integer.parseInt(parts[4]);
            r.bestProgress = Float.parseFloat(parts[5]);
            r.lineage = parts[6];
            String[] g = parts[7].isEmpty() ? new String[0] : parts[7].split(",");
            r.genes = new float[g.length];
            for (int i = 0; i < g.length; i++) r.genes[i] = Float.parseFloat(g[i]);
        } catch (NumberFormatException e) {
            return null;
        }
        return r;
    }
}
