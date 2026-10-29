package com.neurojump.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.neurojump.nn.NeuralNet;

/**
 * 把神经网络画成连线图：节点亮度表示激活值，连线粗细/颜色表示权重正负与大小。
 * 只画权重绝对值最大的一部分连线，避免 17x16 全连接糊成一片。
 */
public final class NetRenderer {

    private static final int MAX_LINKS_PER_NODE = 3;

    private NetRenderer() {}

    public static void draw(ShapeRenderer sr, NeuralNet net,
                            float x, float y, float w, float h) {
        int[] sizes = net.sizes();
        int layers = sizes.length;
        float colGap = w / Math.max(1, layers - 1);
        float nodeR = Math.min(9f, Math.min(colGap * 0.22f, h / (maxNodes(sizes) * 2.6f)));

        float[][] px = new float[layers][];
        float[][] py = new float[layers][];
        for (int l = 0; l < layers; l++) {
            px[l] = new float[sizes[l]];
            py[l] = new float[sizes[l]];
            float cx = x + l * colGap;
            float total = (sizes[l] - 1) * (nodeR * 2.8f);
            float startY = y + h * 0.5f + total * 0.5f;
            for (int n = 0; n < sizes[l]; n++) {
                px[l][n] = cx;
                py[l][n] = startY - n * nodeR * 2.8f;
            }
        }

        // 连线：每个节点取权重绝对值最大的若干条（不修改原权重，用下标去重）
        float[] weights = net.weights();
        int widx = 0;
        int[] picked = new int[MAX_LINKS_PER_NODE];
        for (int l = 1; l < layers; l++) {
            int in = sizes[l - 1];
            for (int n = 0; n < sizes[l]; n++) {
                int base = widx + n * in;
                int found = 0;
                for (int k = 0; k < MAX_LINKS_PER_NODE; k++) {
                    int bestI = -1;
                    float bestAbs = -1f;
                    for (int i = 0; i < in; i++) {
                        if (contains(picked, found, i)) continue;
                        float v = Math.abs(weights[base + i]);
                        if (v > bestAbs) { bestAbs = v; bestI = i; }
                    }
                    if (bestI < 0) break;
                    picked[found++] = bestI;
                    float wv = weights[base + bestI];
                    float mag = Math.min(1f, Math.abs(wv) / 2.2f);
                    Color c = wv >= 0
                            ? new Color(0.35f, 0.85f, 1f, 0.10f + 0.55f * mag)
                            : new Color(1f, 0.45f, 0.5f, 0.10f + 0.55f * mag);
                    Draw.line(sr, px[l - 1][bestI], py[l - 1][bestI], px[l][n], py[l][n],
                            0.6f + 2.6f * mag, c);
                }
            }
        }

        // 节点：颜色随激活值从暗蓝到亮青
        for (int l = 0; l < layers; l++) {
            float[] act = net.activationsOf(l);
            for (int n = 0; n < sizes[l]; n++) {
                float a = Math.min(1f, Math.abs(act[n]));
                Color c = new Color(0.15f + 0.35f * a, 0.45f + 0.45f * a, 0.75f + 0.25f * a, 1f);
                sr.setColor(c);
                sr.circle(px[l][n], py[l][n], nodeR, 14);
            }
        }
    }

    private static boolean contains(int[] arr, int count, int v) {
        for (int i = 0; i < count; i++) if (arr[i] == v) return true;
        return false;
    }

    private static int maxNodes(int[] sizes) {
        int m = 1;
        for (int s : sizes) m = Math.max(m, s);
        return m;
    }
}
