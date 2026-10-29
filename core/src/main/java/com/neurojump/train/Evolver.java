package com.neurojump.train;

import java.util.Random;

/**
 * 进化算法：锦标赛选择 + 分段交叉 + 自适应变异 + 精英保留。
 *
 * 针对神经网络基因的两个调整：
 *  - 交叉按「连续块」切分而不是逐个基因随机挑选。相邻权重属于同一个神经元，
 *    逐基因打散会把它们拆开，子代往往两边都不像，反而破坏已学到的局部功能。
 *  - 变异强度自适应：对排名前列的个体用小步长精修，对靠后的用大步长探索。
 *
 * 内存：所有缓冲区都在构造后复用，每代不再分配新数组。
 * 在 200MB 运存的设备上，每代 1MB 的临时垃圾会显著抬高 GC 压力。
 */
public final class Evolver {
    private final Random rnd;

    /** 排序索引缓冲，避免每代新建 Integer[]。 */
    private int[] order;
    /** 上一代基因的引用副本，用于「读旧写新」而不整体换数组。 */
    private float[][] prev;
    /** 子代暂存：先算到 next，再整体拷回 genes。 */
    private float[][] next;
    private boolean bufferReady;

    public Evolver(long seed) {
        this.rnd = new Random(seed);
    }

    /**
     * 由当前种群产生下一代。
     *
     * @param genes         每个个体的基因为全网络权重（原地更新）
     * @param fitness       对应适应度
     * @param elitism       直接保留的精英个数
     * @param mutation      基础变异强度（标准差）
     * @param crossoverRate 交叉概率
     */
    public void evolve(float[][] genes, float[] fitness, int elitism,
                       float mutation, float crossoverRate) {
        int n = genes.length;

        if (!bufferReady || order.length != n
                || next.length != n || next[0].length != genes[0].length) {
            order = new int[n];
            prev = new float[n][];
            next = new float[n][];
            for (int i = 0; i < n; i++) next[i] = new float[genes[0].length];
            bufferReady = true;
        }
        for (int i = 0; i < n; i++) {
            order[i] = i;
            prev[i] = genes[i];
        }

        // 用插入排序的等价物：按适应度降序排索引（种群规模小，开销可忽略）
        sortByFitness(order, fitness, n);

        int k = 0;
        for (; k < elitism && k < n; k++) {
            System.arraycopy(prev[order[k]], 0, next[k], 0, next[k].length);
        }
        while (k < n) {
            int i1 = tournament(fitness);
            int i2 = tournament(fitness);
            if (rnd.nextFloat() < crossoverRate) {
                blockCrossoverInto(prev[i1], prev[i2], next[k]);
            } else {
                System.arraycopy(prev[i1], 0, next[k], 0, next[k].length);
            }

            // 排名越靠前，变异越小：好个体只做微调，差个体大胆探索
            float rankRatio = k / (float) n;
            mutate(next[k], mutation * (0.45f + 0.9f * rankRatio));
            k++;
        }

        // 把子代写回个体数组（复用原数组，不产生新对象）
        for (int i = 0; i < n; i++) {
            System.arraycopy(next[i], 0, genes[i], 0, genes[i].length);
        }
    }

    /** 按适应度降序排列索引；种群规模小，插入排序足够且无分配。 */
    private void sortByFitness(int[] idx, float[] fitness, int n) {
        for (int i = 1; i < n; i++) {
            int v = idx[i];
            float fv = fitness[v];
            int j = i - 1;
            while (j >= 0 && fitness[idx[j]] < fv) {
                idx[j + 1] = idx[j];
                j--;
            }
            idx[j + 1] = v;
        }
    }

    private int tournament(float[] fitness) {
        int best = rnd.nextInt(fitness.length);
        for (int i = 0; i < 3; i++) {
            int c = rnd.nextInt(fitness.length);
            if (fitness[c] > fitness[best]) best = c;
        }
        return best;
    }

    /** 按连续小段把两个父代拼进目标数组。 */
    private void blockCrossoverInto(float[] a, float[] b, float[] out) {
        int pos = 0;
        boolean takeA = rnd.nextBoolean();
        while (pos < a.length) {
            int len = 4 + rnd.nextInt(17);
            int end = Math.min(a.length, pos + len);
            System.arraycopy(takeA ? a : b, pos, out, pos, end - pos);
            pos = end;
            takeA = !takeA;
        }
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
