package com.neurojump.nn;

import java.util.Random;

/**
 * 全连接多层感知机，权重扁平存放，便于遗传算法直接交叉/变异。
 * 激活函数统一 tanh，输出 [-1,1]。
 */
public final class NeuralNet {
    /** 各层神经元数量，含输入层与输出层。 */
    private final int[] sizes;
    private final float[] weights;
    private final float[] biases;
    private final int weightCount;
    private final int biasCount;

    private final float[][] activations;

    public NeuralNet(int... sizes) {
        if (sizes.length < 2) throw new IllegalArgumentException("至少需要输入层和输出层");
        this.sizes = sizes.clone();
        int w = 0, b = 0;
        for (int i = 1; i < sizes.length; i++) {
            w += sizes[i] * sizes[i - 1];
            b += sizes[i];
        }
        this.weightCount = w;
        this.biasCount = b;
        this.weights = new float[w];
        this.biases = new float[b];
        this.activations = new float[sizes.length][];
        for (int i = 0; i < sizes.length; i++) activations[i] = new float[sizes[i]];
    }

    public int[] sizes() { return sizes.clone(); }
    public int weightCount() { return weightCount; }
    public int biasCount() { return biasCount; }
    public int geneCount() { return weightCount + biasCount; }

    public float[] weights() { return weights; }
    public float[] biases() { return biases; }

    /** 随机初始化权重（Xavier 缩放），偏置置零。 */
    public void randomize(Random rnd) {
        int idx = 0;
        for (int layer = 1; layer < sizes.length; layer++) {
            int in = sizes[layer - 1];
            float scale = (float) Math.sqrt(2.0 / in);
            for (int n = 0; n < sizes[layer]; n++) {
                for (int i = 0; i < in; i++) {
                    weights[idx++] = (float) rnd.nextGaussian() * scale;
                }
            }
        }
        for (int i = 0; i < biasCount; i++) biases[i] = 0f;
    }

    /** 前向传播，返回输出层引用（复用内部数组，调用方须立即读取）。 */
    public float[] forward(float[] input) {
        float[] cur = activations[0];
        System.arraycopy(input, 0, cur, 0, Math.min(input.length, cur.length));
        int widx = 0, bidx = 0;
        for (int layer = 1; layer < sizes.length; layer++) {
            float[] prev = activations[layer - 1];
            float[] out = activations[layer];
            int in = sizes[layer - 1];
            for (int n = 0; n < sizes[layer]; n++) {
                float sum = biases[bidx + n];
                int base = widx + n * in;
                for (int i = 0; i < in; i++) sum += weights[base + i] * prev[i];
                out[n] = (float) Math.tanh(sum);
            }
            widx += sizes[layer] * in;
            bidx += sizes[layer];
        }
        return activations[sizes.length - 1];
    }

    /** 输入层有多少个神经元，供外部构造输入向量。 */
    public int inputCount() { return sizes[0]; }

    public int outputCount() { return sizes[sizes.length - 1]; }

    /** 供网络可视化：某一层神经元的激活值。 */
    public float[] activationsOf(int layer) { return activations[layer]; }
}
