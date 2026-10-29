package com.neurojump.train;

import com.neurojump.nn.NeuralNet;
import com.neurojump.world.C;
import com.neurojump.world.Level;
import com.neurojump.world.LevelFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 逐层课程训练：从第 1 层开始，达到通关标准后解锁下一层。
 * 上一层冠军作为下一层种群的种子（热启动），学习成果可累积。
 * 全部计算在后台线程池完成；UI 只读 volatile 快照，不阻塞渲染。
 */
public final class Trainer implements Runnable {
    /** 过关标准：连续这么多代都有个体通关，避免偶然一次通关就进入下一层。 */
    private static final int SOLVE_STREAK = 2;
    /** 单层最大代数，超过则带着当前冠军继续下一层，避免永久卡死。 */
    private static final int MAX_GENS = 400;

    public static final int INPUT = Sensors.COUNT;
    public static final int HIDDEN = 40;
    public static final int HIDDEN2 = 20;
    public static final int OUTPUT = 3;
    /**
     * 两层隐藏层。宽度取 40/20：平台跳跃要把「边缘余量、落脚点高低差、
     * 平台接近速度、剩余跳跃次数」这些量组合成判断，太窄表达不出这种非线性组合。
     */
    public static final int[] LAYERS = {INPUT, HIDDEN, HIDDEN2, OUTPUT};

    /**
     * 关卡槽位：按需生成。
     *
     * 用「先造好、再发布引用」的方式写入，读取端完全不加锁：
     * 生成一层要做物理自检（低端机上几百毫秒），若让渲染线程等锁会直接卡顿。
     */
    private final Level[] slots;
    private final java.util.concurrent.atomic.AtomicInteger built = 
            new java.util.concurrent.atomic.AtomicInteger();
    private final int population;
    private final ExecutorService pool;
    private final Random rnd = new Random(20261026L);

    private final NeuralNet[] nets;
    private final float[][] genes;
    private final float[] fitness;
    private final Episode[] episodes;

    private final Evolver evolver = new Evolver(0xC0FFEEL);

    /** 进化超参数，可被 AI 教练在训练过程中调整。 */
    public final Tuning tuning = new Tuning();

    // ---- UI 读取的快照 ----
    public volatile int levelIndex = 0;
    public volatile int generation = 0;
    public volatile int solvedLevels = 0;
    public volatile int solvedInGeneration = 0;
    public volatile float bestProgress = 0f;
    public volatile float bestFitness = 0f;
    public volatile long totalSteps = 0;
    public volatile boolean finished = false;
    public volatile boolean running = false;
    /** 到代数上限仍未被稳定学会、只能带着现有冠军继续的层数。 */
    public volatile int stalledLevels = 0;
    public volatile String status = "";

    /** 供 UI 可视化：每代给出一份独立的网络快照，训练线程不再触碰它。 */
    public volatile NeuralNet showcase;
    /** 每代每个个体的进度（0..1）与适应度快照，供 UI 画分布。 */
    public volatile float[] progressSnapshot = new float[0];
    public volatile float[] fitnessSnapshot = new float[0];
    /** 当前代有多少个体到达终点。 */
    public volatile int wonThisGeneration = 0;

    private final AtomicBoolean stop = new AtomicBoolean(false);

    /** 最近一次「过关」时的冠军基因快照，供存档使用。 */
    public volatile float[] championGenes;
    /** 冠军诞生时的元信息。 */
    public volatile int championLevel;
    public volatile int championGeneration;
    public volatile float championProgress;
    /** 族谱：谁演化成了当前种群主力。 */
    public volatile String lineage = "";

    private void recordChampion(int lv, Level level) {
        int best = bestIndex();
        championGenes = genes[best].clone();
        championLevel = lv;
        championGeneration = generation;
        championProgress = episodes[best].progress;
        lineage = "L" + (lv + 1) + " 第" + generation + "代";
    }

    /** 无限模式：关卡按需生成，层数没有上限。 */
    private final boolean infinite;

    public Trainer(List<Level> levels, int population, int threads) {
        this(levels, population, threads, false);
    }

    public Trainer(List<Level> levels, int population, int threads, boolean infinite) {
        this.infinite = infinite;
        this.slots = new Level[infinite ? 4096 : C.LEVEL_COUNT];
        for (int i = 0; i < levels.size() && i < slots.length; i++) {
            slots[i] = levels.get(i);
        }
        built.set(Math.min(levels.size(), slots.length));
        this.population = population;
        this.pool = Executors.newFixedThreadPool(Math.max(1, threads));
        this.nets = new NeuralNet[population];
        this.genes = new float[population][];
        this.fitness = new float[population];
        this.episodes = new Episode[population];
        for (int i = 0; i < population; i++) {
            nets[i] = new NeuralNet(LAYERS);
            genes[i] = new float[nets[i].geneCount()];
            episodes[i] = new Episode();
        }
        this.showcase = new NeuralNet(LAYERS);
    }

    public int populationSize() { return population; }
    public Episode episode(int i) { return episodes[i]; }
    public float fitnessOf(int i) { return fitness[i]; }

    public Level currentLevel() {
        return levelAt(levelIndex);
    }

    /**
     * 用已存档模型作为初始种群，实现「把训练过的模型导回来继续训练」。
     * 传入的模型按原样保留，其余个体用它们的变异体补齐，保证既有起点又有探索。
     */
    public void seedFromModels(java.util.List<com.neurojump.store.ModelRecord> models) {
        int filled = 0;
        for (int i = 0; i < models.size() && filled < population; i++) {
            com.neurojump.store.ModelRecord m = models.get(i);
            float expected = genes[0].length;
            if (m.genes == null || m.genes.length != expected) continue;
            System.arraycopy(m.genes, 0, genes[filled], 0, genes[filled].length);
            applyGenes(filled);
            filled++;
        }
        if (filled == 0) return;
        for (int i = filled; i < population; i++) {
            int src = rnd.nextInt(filled);
            System.arraycopy(genes[src], 0, genes[i], 0, genes[i].length);
            for (int g = 0; g < genes[i].length; g++) {
                if (rnd.nextFloat() < 0.22f) {
                    genes[i][g] += (float) rnd.nextGaussian() * 0.25f;
                }
            }
            applyGenes(i);
        }
        seeded = true;
    }

    /** 是否用存档模型作为初始种群启动（此时不再随机初始化）。 */
    private volatile boolean seeded;

    private void applyGenes(int idx) {
        NeuralNet n = nets[idx];
        System.arraycopy(genes[idx], 0, n.weights(), 0, n.weightCount());
        System.arraycopy(genes[idx], n.weightCount(), n.biases(), 0, n.biasCount());
    }

    public void start() {
        running = true;
        Thread t = new Thread(this, "neuro-trainer");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    public void stop() {
        stop.set(true);
        pool.shutdownNow();
    }

    @Override
    public void run() {
        // 先把第 1 层生成好，UI 首帧就有内容可画
        levelAt(0);
        if (!seeded) {
            for (int i = 0; i < population; i++) {
                nets[i].randomize(rnd);
                syncGenesFromNet(i);
            }
        }
        for (int lv = 0; lv < totalLevels() && !stop.get(); lv++) {
            Level level = levelAt(lv);
            levelIndex = lv;
            generation = 0;
            int streak = 0;

            float lastBest = -Float.MAX_VALUE;
            int stagnant = 0;
            for (int gen = 0; gen < tuning.maxGenerations && !stop.get(); gen++) {
                generation = gen;
                evaluateGeneration(level);
                updateShowcase();
                if (stop.get()) break;

                if (solvedInGeneration > 0) streak++;
                else streak = 0;
                if (streak >= SOLVE_STREAK) break;

                if (bestFitness > lastBest + 0.01f) {
                    lastBest = bestFitness;
                    stagnant = 0;
                } else {
                    stagnant++;
                }
                // 基础策略：停滞越久变异越猛；AI 教练给出的强度作为下限/上限约束
                float auto = 0.12f + Math.min(1.6f, stagnant * 0.05f);
                float mut = tuning.mutation * 0.5f + auto * 0.5f;
                int elite = Math.max(2, (int) (population * tuning.eliteRatio));
                evolver.evolve(genes, fitness, elite, mut, tuning.crossover);
                if (stagnant > 0 && stagnant % tuning.diversifyEvery == 0) injectDiversity(level);
            }

            if (stop.get()) break;
            solvedLevels = lv + 1;
            // 到代数上限仍未连续通关，记为「勉强推进」，UI 上如实区分
            if (streak < SOLVE_STREAK) stalledLevels++;

            seedNextLevel();
            recordChampion(lv, level);
        }
        finished = !stop.get() && !infinite;
        running = false;
        status = finished ? "全部关卡完成" : "已停止";
    }

    /** 用本层最优个体填充下一层种群的大部分，保留少量随机个体维持多样性。 */
    private void seedNextLevel() {
        int champ = bestIndex();
        int keep = population * 3 / 4;
        for (int i = 0; i < keep; i++) {
            System.arraycopy(genes[champ], 0, genes[i], 0, genes[champ].length);
            float sigma = 0.12f + 0.3f * (i / (float) keep);
            for (int g = 0; g < genes[i].length; g++) {
                if (rnd.nextFloat() < 0.15f) {
                    genes[i][g] += (float) rnd.nextGaussian() * sigma;
                }
            }
        }
        for (int i = keep; i < population; i++) {
            nets[i].randomize(rnd);
            syncGenesFromNet(i);
        }
    }

    /** 长期停滞时注入新血：淘汰最差的若干个体，换成随机个体与大幅变异体。 */
    private void injectDiversity(Level level) {
        Integer[] order = new Integer[population];
        for (int i = 0; i < population; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Float.compare(fitness[b], fitness[a]));
        int cull = Math.max(3, population / 3);
        int champ = order[0];
        for (int k = 0; k < cull; k++) {
            int idx = order[population - 1 - k];
            if (k % 2 == 0) {
                nets[idx].randomize(rnd);
                syncGenesFromNet(idx);
            } else {
                System.arraycopy(genes[champ], 0, genes[idx], 0, genes[champ].length);
                for (int g = 0; g < genes[idx].length; g++) {
                    if (rnd.nextFloat() < 0.4f) {
                        genes[idx][g] += (float) rnd.nextGaussian() * 0.8f;
                    }
                }
            }
            fitness[idx] = -Float.MAX_VALUE / 2f;
        }
    }

    private int bestIndex() {
        int best = 0;
        for (int i = 1; i < population; i++) if (fitness[i] > fitness[best]) best = i;
        return best;
    }

    /** 复制最佳个体为独立快照，并发布本代分布。 */
    private void updateShowcase() {
        int best = bestIndex();
        NeuralNet src = nets[best];
        NeuralNet snap = new NeuralNet(LAYERS);
        System.arraycopy(src.weights(), 0, snap.weights(), 0, src.weightCount());
        System.arraycopy(src.biases(), 0, snap.biases(), 0, src.biasCount());
        showcase = snap;

        float[] prog = new float[population];
        float[] fit = new float[population];
        for (int i = 0; i < population; i++) {
            prog[i] = episodes[i].progress;
            fit[i] = fitness[i];
        }
        progressSnapshot = prog;
        fitnessSnapshot = fit;
        wonThisGeneration = solvedInGeneration;
    }

    private void evaluateGeneration(final Level level) {
        final float[] milestones = milestonesFor(level);
        List<Callable<Void>> tasks = new ArrayList<>(population);
        for (int i = 0; i < population; i++) {
            final int idx = i;
            tasks.add(() -> {
                Episode ep = episodes[idx];
                ep.reset(level, milestones);
                int state = Episode.RUNNING;
                while (state == Episode.RUNNING && !stop.get()) {
                    state = ep.step(level, nets[idx]);
                }
                fitness[idx] = ep.fitness;
                return null;
            });
        }
        try {
            for (Future<Void> f : pool.invokeAll(tasks)) f.get();
        } catch (Exception e) {
            return;
        }

        int solved = 0;
        float bp = 0f, bf = -Float.MAX_VALUE;
        long steps = 0;
        for (int i = 0; i < population; i++) {
            if (episodes[i].state == Episode.WON) solved++;
            if (episodes[i].progress > bp) bp = episodes[i].progress;
            if (fitness[i] > bf) bf = fitness[i];
            steps += episodes[i].frames;
        }
        solvedInGeneration = solved;
        bestProgress = bp;
        bestFitness = bf;
        totalSteps += steps;
    }

    private float[] cachedMilestones;
    private int cachedMilestoneLevel = -1;

    private float[] milestonesFor(Level level) {
        if (cachedMilestoneLevel != level.index) {
            cachedMilestones = Episode.milestonesOf(level);
            cachedMilestoneLevel = level.index;
        }
        return cachedMilestones;
    }

    private void syncGenesFromNet(int idx) {
        NeuralNet n = nets[idx];
        System.arraycopy(n.weights(), 0, genes[idx], 0, n.weightCount());
        System.arraycopy(n.biases(), 0, genes[idx], n.weightCount(), n.biasCount());
    }

    public int levelCount() { return built.get(); }

    /**
     * 取第 i 层。列表里已有的直接复用；无限模式下超出列表范围时按需生成并缓存，
     * 因此层号可以一直增长。
     */
    /**
     * 取第 i 层。
     *
     * 达到所需层数时才生成，而不是进界面就预建全部 100 层：
     * 一次性生成 100 层要做上百次物理可达性自检，在低端机上要花数秒，
     * 放在 UI 线程会直接触发 ANR 闪退。
     */
    public Level levelAt(int i) {
        if (i < 0 || i >= slots.length) return null;
        Level ready = slots[i];
        if (ready != null) return ready;
        if (!infinite && i >= C.LEVEL_COUNT) return null;
        synchronized (slots) {
            // 逐层补齐：只生成到需要的那一层，不预建全部
            int want = i + 1;
            for (int k = built.get(); k < want; k++) {
                if (slots[k] == null) {
                    slots[k] = LevelFactory.buildInfinite(k);
                    built.set(k + 1);
                }
            }
            return slots[i];
        }
    }

    public int totalLevels() {
        return infinite ? Integer.MAX_VALUE : C.LEVEL_COUNT;
    }

    /**
     * 只读已生成的层，绝不触发生成。
     * 供渲染线程使用：UI 绝不能因为「关卡还没生成」而卡住几百毫秒。
     */
    public Level readyLevelAt(int i) {
        // 完全无锁：槽位引用一旦写入就不再改变，读不到就返回 null 让 UI 下帧再画。
        // 加锁会让渲染线程等在「几百毫秒的物理自检」后面。
        if (i < 0 || i >= slots.length) return null;
        return slots[i];
    }

    public boolean isInfinite() { return infinite; }
}
