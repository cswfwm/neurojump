package com.neurojump.train;

import java.util.Random;

/**
 * 进化算法：锦标赛选择 + 分段交叉 + 自适应变异 + 精英保留。
 *
 * 针对神经网络基因的两个调整：
 *  - 交叉按「连续块」切分而不是逐个基因随机挑选。相邻权重属于同一个神经元，
 *    逐基因打散会把它们拆开，子代往往两边都不像，反而破坏已学到的局部功能。
 *  - 变异强度自适应：对排名前列的个体用小步长精修，对排名靠后的用大步长探索，
 *    在搜索空间变大（网络加宽）后仍能持续收敛。
 */
public final class Evolver {
    private final Random rnd;

    public Evolver(long seed) {
        this.rnd = new Random(seed);
    }

    /**
     * 由当前种群产生下一代。
     *
     * @param genes         每个个体的基因为全网络权重
     * @param fitness       对应适应度
     * @param elitism       直接保留的精英个数
     * @param mutation      基础变异强度（标准差）
     * @param crossoverRate 交叉概率
     */
    public void evolve(float[][] genes, float[] fitness, int elitism,
                       float mutation, float crossoverRate) {
        int n = genes.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Float.compare(fitness[b], fitness[a]));

        float[][] next = new float[n][];
        int k = 0;
        for (; k < elitism && k < n; k++) {
            next[k] = genes[order[k]].clone();
        }
        while (k < n) {
            int i1 = tournament(fitness);
            int i2 = tournament(fitness);
            float[] p1 = genes[i1];
            float[] p2 = genes[i2];
            float[] child;
            if (rnd.nextFloat() < crossoverRate) child = blockCrossover(p1, p2);
            else child = p1.clone();

            // 排名越靠前，变异越小：好个体只做微调，差个体大胆探索
            float rankRatio = k / (float) n;
            float sigma = mutation * (0.45f + 0.9f * rankRatio);
            mutate(child, sigma);
            next[k++] = child;
        }
        for (int i = 0; i < n; i++) genes[i] = next[i];
    }

    private int tournament(float[] fitness) {
        int best = rnd.nextInt(fitness.length);
        for (int i = 0; i < 3; i++) {
            int c = rnd.nextInt(fitness.length);
            if (fitness[c] > fitness[best]) best = c;
        }
        return best;
    }

    /** 按连续块交换：把基因切成若干小段，整段取某个父代。 */
    private float[] blockCrossover(float[] a, float[] b) {
        float[] out = new float[a.length];
        int pos = 0;
        boolean takeA = rnd.nextBoolean();
        while (pos < a.length) {
            // 块长度 4~20 个基因：太大会整段破坏已学好的功能组合
            int len = 4 + rnd.nextInt(17);
            int end = Math.min(a.length, pos + len);
            float[] src = takeA ? a : b;
            System.arraycopy(src, pos, out, pos, end - pos);
            pos = end;
            takeA = !takeA;
        }
        return out;
    }

    /** 按概率叠加高斯噪声；偶发大跳变帮助跳出局部最优。 */
    private void mutate(float[] g, float sigma) {
        float rate = 0.10f;
        for (int i = 0; i < g.length; i++) {
            if (rnd.nextFloat() < rate) {
                g[i] += (float) rnd.nextGaussian() * sigma;
                if (rnd.nextFloat() < 0.008f) g[i] += (float) rnd.nextGaussian() * 1.0f;
                if (g[i] > 6f) g[i] = 6f;
                else if (g[i] < -6f) g[i] = -6f;
            }
        }
    }
}
