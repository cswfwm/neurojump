package com.neurojump.train;

/**
 * 网络输出 → 动作的解码规则。训练、演示、回放共用同一套，避免表现不一致。
 *
 * 三个输出：
 *   0 水平意图 —— 正数向右、负数向左、接近 0 站定。
 *     用单输出而非「左/右两路相减」：两路 tanh 很容易一路饱和到 -1、
 *     另一路也饱和到 -1，相减后方向被噪声主导，表现为一直往左撞墙。
 *   1 跳跃 ——— 必须明显为正才起跳。跳跃是稀缺动作，阈值偏高，
 *              否则随机初始化时输出噪声会让人看起来「一直在跳」。
 *   2 备用位（暂未使用），保留以兼容旧存档的网络维度。
 */
public final class Actions {
    /** 水平死区：差值落在此范围内视为站定。 */
    private static final float MOVE_DEADZONE = 0.20f;
    /** 起跳阈值：偏低会退化成不停跳跃。 */
    public static final float JUMP_THRESHOLD = 0.35f;

    private Actions() {}

    public static float move(float[] out) {
        float v = out[0];
        if (v > -MOVE_DEADZONE && v < MOVE_DEADZONE) return 0f;
        return v > 0f
                ? (v - MOVE_DEADZONE) / (1f - MOVE_DEADZONE)
                : (v + MOVE_DEADZONE) / (1f - MOVE_DEADZONE);
    }

    public static boolean jump(float[] out) {
        return out[1] > JUMP_THRESHOLD;
    }
}
