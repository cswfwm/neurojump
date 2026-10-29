package com.neurojump.train;

/**
 * 可被外部（AI 教练）调整的进化超参数。
 *
 * 全部字段都会被训练线程每代读取，因此用 volatile 保证可见性。
 * 取值范围在 apply 里统一裁剪，避免外部给出离谱数值把进化搞崩。
 */
public final class Tuning {
    /** 基因变异强度：越大越探索，越小越精修。 */
    public volatile float mutation = 0.20f;
    /** 交叉概率：越高越倾向重组已有片段。 */
    public volatile float crossover = 0.55f;
    /** 精英保留个数（按种群比例换算），保证已验证的成绩不丢。 */
    public volatile float eliteRatio = 0.03f;
    /** 每层允许的最大代数：越大越有耐心。 */
    public volatile int maxGenerations = 400;
    /** 停滞多少代后注入新血。 */
    public volatile int diversifyEvery = 12;

    public void apply(float mutation, float crossover, float eliteRatio,
                      int maxGenerations, int diversifyEvery) {
        this.mutation = clamp(mutation, 0.02f, 1.2f);
        this.crossover = clamp(crossover, 0.1f, 1.0f);
        this.eliteRatio = clamp(eliteRatio, 0.01f, 0.2f);
        this.maxGenerations = Math.max(60, Math.min(2000, maxGenerations));
        this.diversifyEvery = Math.max(4, Math.min(60, diversifyEvery));
    }

    /** 复制一份，供界面显示。 */
    public float[] snapshot() {
        return new float[]{mutation, crossover, eliteRatio, maxGenerations, diversifyEvery};
    }

    private static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v)) return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
