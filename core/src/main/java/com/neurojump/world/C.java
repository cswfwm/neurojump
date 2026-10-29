package com.neurojump.world;

/** 全局常量：视口、物理参数、瓦片类型。 */
public final class C {
    public static final float VIEW_W = 1280f;
    public static final float VIEW_H = 720f;

    // 一层关卡的世界尺寸（像素）
    public static final float LEVEL_W = 2600f;
    public static final float LEVEL_H = 900f;

    // 角色物理：加速度式移动，手感与可学习性兼顾
    public static final float GRAVITY = -2100f;
    public static final float MOVE_ACCEL = 3800f;
    public static final float MOVE_MAX = 430f;
    public static final float GROUND_FRICTION = 3400f;
    public static final float AIR_FRICTION = 800f;
    public static final float JUMP_V = 830f;
    public static final float DOUBLE_JUMP_V = 760f;
    public static final int MAX_JUMPS = 2;

    // 箱子：每分钟补 3 个，可在空中使用并把二段跳次数补满，实现空中连跳
    public static final int CHEST_MAX = 3;
    public static final float CHEST_PERIOD = 60f;
    /** 使用箱子的上冲速度，略低于普通起跳，避免比地面跳更划算。 */
    public static final float CHEST_JUMP_V = 700f;
    public static final float MAX_FALL_V = -1500f;
    public static final float COYOTE_TIME = 0.08f;
    public static final float JUMP_BUFFER = 0.1f;
    /** 两次跳跃请求的最小间隔，避免一帧内重复触发。 */
    public static final float JUMP_COOLDOWN = 0.13f;
    /** 落地后必须在地面停留的最短时间，之后才允许起跳（强制「落地观察再跳」）。 */
    public static final float LANDING_REST = 0.10f;

    public static final float AGENT_W = 30f;
    public static final float AGENT_H = 42f;

    // 瓦片类型
    public static final byte AIR = 0;
    public static final byte SOLID = 1;
    public static final byte PLATFORM = 2;   // 单向平台：仅从上方站立
    public static final byte SPIKE = 3;      // 危险：触碰即失败

    // 训练与关卡
    public static final int LEVEL_COUNT = 100;
    public static final int TOTAL_LAYERS = 100;

    private C() {}
}
