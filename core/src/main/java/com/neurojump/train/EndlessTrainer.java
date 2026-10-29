package com.neurojump.train;

import com.neurojump.nn.NeuralNet;
import com.neurojump.store.ModelRecord;
import com.neurojump.world.EndlessLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 无限跑分训练：种群在无限关卡里比谁跑得远，按距离择优、交叉、变异。
 *
 * 与课程训练的区别：没有「过关」概念，只有连续的分数；因此适合把已经训练过的
 * 模型投进来继续打磨——它们会作为种子加入种群，与变异体一起竞争。
 */
public final class EndlessTrainer implements Runnable {
    private final int population;
    private final ExecutorService pool;
    private final Random rnd = new Random(0xE4D1E55L);

    private final NeuralNet[] nets;
    private final float[][] genes;
    private final float[] scores;
    /** 本代每个个体的起跑位置，供 UI 标注。 */
    public volatile float[] spawnSnapshot = new float[0];
    private final EndlessEpisode[] episodes;
    private final EndlessLevel level;
    private final Evolver evolver = new Evolver(0x5C0BEL);

    public volatile int generation = 1;
    /** 本代最佳个体的推进距离（相对开局起点）。 */
    public volatile float bestScore = 0f;
    /** 从起点开跑能到达的最远 x，用于决定随机起跑点范围与演示起点。 */
    public volatile float reachedX = 0f;
    /** 本代从起点跑得最远的个体下标，用于生成演示网络。 */
    private volatile int genBestStartIdx = -1;
    private volatile float genBestStartX = -1f;
    /** 历史最佳个体（按从起点跑出的距离）的基因快照，永不被劣化覆盖。 */
    private volatile float[] bestEverGenes;
    private volatile float bestEverDistance = -1f;
    /** 历史最佳跑分（米），仅作展示。 */
    public volatile float bestScoreEver = 0f;
    public volatile float generationBestX = 0f;
    public volatile boolean running = false;
    public volatile int aliveCount = 0;
    /** 供 UI 可视化当前最佳个体。 */
    public volatile NeuralNet showcase;
    /** 每代各个体分数，供 UI 画分布。 */
    public volatile float[] scoreSnapshot = new float[0];
    /** 从起点跑得最远的个体在这一局里拿到的等待奖励次数与连跳次数。 */
    public volatile int bestWaitRewards = 0;
    public volatile int bestCombos = 0;
    /** 历史最佳个体的等待次数（用于展示「学会等平台」的进展）。 */
    public volatile int bestEverWaits = 0;
    /** 最近一次跑分结束时的死亡位置，用于在关卡视图上标出「死点」。 */
    public volatile float[] deathMarks = new float[0];

    private final AtomicBoolean stop = new AtomicBoolean(false);
    public final Style style;

    /** 关卡风格：普通跑酷，或专门练卡点的移动平台阵。 */
    public enum Style { MIXED, TIMING }

    public EndlessTrainer(int population, int threads, List<ModelRecord> seeds) {
        this(population, threads, seeds, Style.MIXED);
    }

    public EndlessTrainer(int population, int threads, List<ModelRecord> seeds, Style style) {
        this.population = Math.max(8, population);
        this.pool = Executors.newFixedThreadPool(Math.max(1, threads));
        this.nets = new NeuralNet[this.population];
        this.genes = new float[this.population][];
        this.scores = new float[this.population];
        this.episodes = new EndlessEpisode[this.population];
        this.style = style;
        this.level = new EndlessLevel(0x1234ABCD,
                style == Style.TIMING ? EndlessLevel.Style.TIMING
                        : EndlessLevel.Style.MIXED);
        this.reachedX = this.level.startX;

        for (int i = 0; i < this.population; i++) {
            nets[i] = new NeuralNet(Trainer.LAYERS);
            genes[i] = new float[nets[i].geneCount()];
            episodes[i] = new EndlessEpisode();
        }
        this.showcase = new NeuralNet(Trainer.LAYERS);

        initPopulation(seeds);
    }

    /** 有存档模型时优先用它们做种子，其余用变异体与随机个体补齐。 */
    private void initPopulation(List<ModelRecord> seeds) {
        int filled = 0;
        if (seeds != null) {
            for (ModelRecord m : seeds) {
                if (filled >= population) break;
                NeuralNet net = m.toNet();
                if (net == null) continue;
                float[] g = new float[net.geneCount()];
                System.arraycopy(net.weights(), 0, g, 0, net.weightCount());
                System.arraycopy(net.biases(), 0, g, net.weightCount(), net.biasCount());
                genes[filled] = g;
                applyGenes(filled);
                filled++;
            }
        }
        if (filled == 0) {
            // 没有任何种子时全随机，让新模型从零开始学跑酷
            for (int i = 0; i < population; i++) {
                nets[i].randomize(rnd);
                syncGenes(i);
            }
            filled = population;
        }
        for (int i = filled; i < population; i++) {
            int src = rnd.nextInt(filled);
            genes[i] = genes[src].clone();
            for (int g = 0; g < genes[i].length; g++) {
                if (rnd.nextFloat() < 0.3f) {
                    genes[i][g] += (float) rnd.nextGaussian() * 0.3f;
                }
            }
            applyGenes(i);
        }
    }

    public EndlessLevel level() { return level; }
    public int populationSize() { return population; }
    public EndlessEpisode episode(int i) { return episodes[i]; }
    public float scoreOf(int i) { return scores[i]; }
    public NeuralNet net(int i) { return nets[i]; }

    public void start() {
        running = true;
        Thread t = new Thread(this, "endless-trainer");
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
        float bestEver = -Float.MAX_VALUE;
        int stagnant = 0;
        while (!stop.get()) {
            evaluateGeneration();
            if (stop.get()) break;
            publishShowcase();

            // 停滞判定用「从起点开跑的最远距离」：这个量只由真实能力决定，
            // 不像本代最佳那样受随机起跑点影响，能避免误判导致多样性注入失效。
            float progressSignal = reachedX - level.startX;
            if (progressSignal > bestEver + 20f) {
                bestEver = progressSignal;
                stagnant = 0;
            } else {
                stagnant++;
            }
            float mut = 0.08f + Math.min(0.9f, stagnant * 0.02f);
            // 精英比例提高：跑分训练里好个体很稀缺，丢掉就再也找不回来
            int elite = Math.max(2, population / 16);
            evolver.evolve(genes, scores, elite, mut, 0.55f);
            // 历史最佳必须留在种群中：进化以「本代分数」排序，而历史最佳的快照
            // 可能来自某一代随机起跑的运气，若不写回，它下一代就会被覆盖掉。
            reinsertBestEver();
            if (stagnant > 0 && stagnant % 15 == 0) injectDiversity();
            generation++;
        }
        running = false;
    }

    /**
     * 把历史最佳个体写回种群（替换当前最差的一个）。
     * 这样既保住已取得的成绩，又让它的基因持续参与后续交叉。
     */
    private void reinsertBestEver() {
        float[] best = bestEverGenes;
        if (best == null || best.length != genes[0].length) return;
        int worst = 0;
        for (int i = 1; i < population; i++) {
            if (scores[i] < scores[worst]) worst = i;
        }
        System.arraycopy(best, 0, genes[worst], 0, best.length);
        applyGenes(worst);
        scores[worst] = bestEverDistance;
    }

    /** 长期停滞：把最差的一部分换成随机个体与冠军的大幅变异体。 */
    private void injectDiversity() {
        Integer[] order = new Integer[population];
        for (int i = 0; i < population; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Float.compare(scores[b], scores[a]));
        int cull = Math.max(3, population / 3);
        int champ = order[0];
        for (int k = 0; k < cull; k++) {
            int idx = order[population - 1 - k];
            if (k % 2 == 0) {
                nets[idx].randomize(rnd);
                syncGenes(idx);
            } else {
                System.arraycopy(genes[champ], 0, genes[idx], 0, genes[idx].length);
                for (int g = 0; g < genes[idx].length; g++) {
                    if (rnd.nextFloat() < 0.45f) {
                        genes[idx][g] += (float) rnd.nextGaussian() * 0.9f;
                    }
                }
                applyGenes(idx);
            }
            scores[idx] = -1f;
        }
    }

    private void evaluateGeneration() {
        // 本代最小的起跑点作为观察点：所有个体共用一个关卡，裁切不能越过最靠后的那位
        // 每代重置保留点：本代所有个体都从起点或起点附近出发，
        // 因此只需保证起点往右的地形不被裁掉。
        level.resetKeep();
        level.keepFrom(level.startX);
        // 每代重新评选「本代从起点跑得最远」的个体，供演示网络使用
        genBestStartIdx = -1;
        genBestStartX = -1f;

        final List<Callable<Void>> tasks = new ArrayList<>(population);
        // 一部分个体从起点开跑（考核完整表现），其余从前方的不同路段开跑，
        // 这样整代不会全部卡在同一个难点上，覆盖面更广。
        // 只在「历史最佳能跑到的最远处附近」撒点：太靠前没有训练价值，
        // 太靠后个体一出生就掉下去，反而把种群的分数拖低。
        // 撒点范围集中在「已能到达处」附近：卡点训练的关键是把当前瓶颈前后
        // 的路段反复练熟，而不是撒到没人去过的地方浪费算力。
        float spread = style == Style.TIMING
                ? 260f + generation * 20f
                : 120f + generation * 90f;
        final float maxSpawn = Math.max(400f, Math.min(reachedX - level.startX,
                300f + spread * (style == Style.TIMING ? 6f : 12f)));
        final float[] spawnPoints = new float[population];
        for (int i = 0; i < population; i++) {
            // 卡点训练全部从起点起跑：分数完全可比，避免中途起跑带来的评估噪声。
            // 混合模式仍保留一部分中途起跑，用于探索长路段。
            spawnPoints[i] = (style == Style.TIMING) ? 0f
                    : ((i % 4 == 0) ? 0f
                    : level.startX + 300f + rnd.nextFloat() * maxSpawn);
        }
        for (int i = 0; i < population; i++) {
            final int idx = i;
            final boolean fromStart = spawnPoints[i] == 0f;
            final float spawnX = spawnPoints[i];
            final float spawnY = fromStart ? 0f : spawnYAt(spawnX);
            tasks.add(() -> {
                EndlessEpisode ep = episodes[idx];
                if (fromStart) {
                    ep.reset(level);
                } else {
                    level.ensure(spawnX + 2200f);
                    ep.resetAt(level, spawnX, spawnY);
                }
                int state = EndlessEpisode.RUNNING;
                while (state == EndlessEpisode.RUNNING && !stop.get()) {
                    state = ep.step(level, nets[idx]);
                }
                // 分数一律用「相对自己起跑点的推进距离」。
                // 若用绝对位置，从中途起跑的个体天生分数更高，会挤掉真正会跑的个体，
                // 导致进化方向被带偏、整体能力停滞。
                // 选拔分数：从起点起跑者用它自己的实际距离；中途起跑者折算成
                // 「起点等价距离」，让两类个体在同一尺度上比较。
                scores[idx] = fromStart ? ep.score
                        : Math.max(0f, (spawnX - level.startX) * 0.55f + ep.score * 0.45f);
                if (fromStart) {
                    synchronized (EndlessTrainer.this) {
                        if (ep.maxX > reachedX) reachedX = ep.maxX;
                        // 每代都重新挑选「本代从起点跑得最远」的个体，供演示使用。
                        // 只在破历史记录时更新会让演示长期停留在同一个旧网络。
                        if (ep.maxX > genBestStartX) {
                            genBestStartX = ep.maxX;
                            genBestStartIdx = idx;
                        }
                        // 历史最佳只增不减：训练后期偶发的劣化不该让演示退回差网络
                        float dist = ep.maxX - level.startX;
                        if (dist > bestEverDistance) {
                            bestEverDistance = dist;
                            bestEverGenes = genes[idx].clone();
                            bestEverWaits = ep.waitRewards;
                        }
                        bestWaitRewards = ep.waitRewards;
                        bestCombos = ep.combos;
                    }
                }
                return null;
            });
        }
        try {
            for (Future<Void> f : pool.invokeAll(tasks)) f.get();
        } catch (Exception e) {
            return;
        }

        float best = 0f;
        int bestIdx = 0;
        int alive = 0;
        float[] marks = new float[population];
        float[] snap = new float[population];
        this.spawnSnapshot = spawnPoints.clone();
        for (int i = 0; i < population; i++) {
            // 展示用分数：相对起点的推进距离，便于直观看出这一段跑了多远
            snap[i] = Math.max(0f, episodes[i].maxX - episodes[i].startX());
            marks[i] = episodes[i].maxX;
            if (scores[i] > best) {
                best = scores[i];
                bestIdx = i;
            }
            if (episodes[i].state == EndlessEpisode.TIMEOUT) alive++;
        }
        generationBestX = episodes[bestIdx].maxX;
        bestScore = Math.max(0f, reachedX - level.startX);
        if (bestScore > bestScoreEver) bestScoreEver = bestScore;
        aliveCount = alive;
        scoreSnapshot = snap;
        deathMarks = marks;
    }

    /** 在 x 附近找一块可站立的平台顶面，作为随机起跑高度。 */
    private float spawnYAt(float x) {
        float bestTop = level.startY;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < level.platforms.size(); i++) {
            com.neurojump.world.Rect r = level.platforms.get(i);
            float cx = r.x + r.w * 0.5f;
            float d = Math.abs(cx - x);
            if (d < bestDist) {
                bestDist = d;
                bestTop = r.top();
            }
        }
        for (int i = 0; i < level.solids.size(); i++) {
            com.neurojump.world.Rect r = level.solids.get(i);
            float cx = r.x + r.w * 0.5f;
            float d = Math.abs(cx - x);
            if (d < bestDist) {
                bestDist = d;
                bestTop = r.top();
            }
        }
        return bestTop;
    }

    private void publishShowcase() {
        int best = 0;
        for (int i = 1; i < population; i++) if (scores[i] > scores[best]) best = i;
        NeuralNet src = nets[best];
        System.arraycopy(src.weights(), 0, showcase.weights(), 0, src.weightCount());
        System.arraycopy(src.biases(), 0, showcase.biases(), 0, src.biasCount());
    }

    /** 当前最佳个体，供存档与竞速。 */
    public float[] championGenes() {
        int best = 0;
        for (int i = 1; i < population; i++) if (scores[i] > scores[best]) best = i;
        return genes[best].clone();
    }

    public NeuralNet championNet() {
        int best = 0;
        for (int i = 1; i < population; i++) if (scores[i] > scores[best]) best = i;
        return nets[best];
    }

    /** 演示专用：从起点表现最好的网络（真正会「从出生点起跑」的那位）。 */
    /**
     * 演示用网络：优先「本代从起点跑得最远」的个体；若它其实跑得很差
     * （例如整代都早早摔死），就退回综合分最高的冠军，避免演示变成原地摔。
     */
    /** 历史最佳个体从起点跑出的距离（像素）。 */
    public float bestEverDistance() { return bestEverDistance; }

    public NeuralNet demoNet() {
        NeuralNet n = new NeuralNet(Trainer.LAYERS);
        float[] g = bestEverGenes;
        if (g == null || g.length != n.geneCount()) return championNet();
        System.arraycopy(g, 0, n.weights(), 0, n.weightCount());
        System.arraycopy(g, n.weightCount(), n.biases(), 0, n.biasCount());
        return n;
    }

    private void applyGenes(int idx) {
        NeuralNet n = nets[idx];
        System.arraycopy(genes[idx], 0, n.weights(), 0, n.weightCount());
        System.arraycopy(genes[idx], n.weightCount(), n.biases(), 0, n.biasCount());
    }

    private void syncGenes(int idx) {
        NeuralNet n = nets[idx];
        System.arraycopy(n.weights(), 0, genes[idx], 0, n.weightCount());
        System.arraycopy(n.biases(), 0, genes[idx], n.weightCount(), n.biasCount());
    }
}
