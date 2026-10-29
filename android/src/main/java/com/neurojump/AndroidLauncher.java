package com.neurojump;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;

/**
 * Android 入口。
 *
 * 针对 32 位 + 低内存设备（约 200MB 运存）做了几处收敛：
 *  - 不开 GL30、不开抗锯齿、不要深度/模板缓冲：本游戏只有 2D 纯色图元，这些都白占显存
 *  - 关闭加速度计/指南针/键盘监听，省掉对应的传感器与输入线程开销
 *  - 按设备内存分档决定渲染分辨率上限，内存越小越保守，避免大面积帧缓冲
 */
public class AndroidLauncher extends AndroidApplication {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useImmersiveMode = true;
        config.useAccelerometer = false;
        config.useCompass = false;
        config.useGyroscope = false;
        // 2D 纯色绘制不需要这些缓冲
        config.useGL30 = false;
        config.numSamples = 0;
        config.depth = 0;
        config.stencil = 0;
        config.useWakelock = true;

        config.r = 8;
        config.g = 8;
        config.b = 8;
        config.a = 8;

        initialize(new GameRoot(), config);
    }

    /** 设备内存分级：用于选择渲染分辨率与训练线程数。 */
    public static int memoryClassMb(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return 96;
        if (Build.VERSION.SDK_INT >= 11) {
            return am.getLargeMemoryClass();
        }
        return am.getMemoryClass();
    }
}
