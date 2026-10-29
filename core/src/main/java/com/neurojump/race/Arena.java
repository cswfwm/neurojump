package com.neurojump.race;

import com.neurojump.nn.NeuralNet;
import com.neurojump.store.ModelRecord;
import com.neurojump.train.Trainer;
import com.neurojump.world.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 竞技场：把多个族群的模型放进同一关卡竞速，跑完按名次择优，
 * 让优胜者互相交叉、变异，产生下一代新模型继续在更难的一关训练。
 *
 * 与课程训练的区别：这里没有「单一血脉」，每个个体都带着自己的族谱，
 * 名次决定谁把自己的基因传下去，因此可以直观看到哪些祖先的后代更有优势。
 */
public final class Arena {
    /** 每个个体：基因 + 身份 + 族谱。 */
    public static final class Individual {
        public String name;
        public String lineage;
        public final float[] genes;
        public NeuralNet net;

        Individual(String name, String lineage, float[] genes) {
            this.name = name;
            this.lineage = lineage;
            this.genes = genes;
            this.net = toNet(genes);
        }

        static NeuralNet toNet(float[] genes) {
            NeuralNet n = new NeuralNet(Trainer.LAYERS);
            if (genes == null || genes.length != n.geneCount()) return n;
            System.arraycopy(genes, 0, n.weights(), 0, n.weightCount());
            System.arraycopy(genes, n.weightCount(), n.biases(), 0, n.biasCount());
            return n;
        }

        static float[] genesOf(NeuralNet net) {
            float[] g = new float[net.geneCount()];
            System.arraycopy(net.weights(), 0, g, 0, net.weightCount());
            System.arraycopy(net.biases(), 0, g, net.weightCount(), net.biasCount());
            return g;
        }
    }

    private final Random rnd = new Random(0xA7A1A5EEDL);
    private final List<Individual> population = new ArrayList<>();
    private final List<Racer> racers = new ArrayList<>();
    private final int size;

    public int generation = 1;
    private int levelIndex;
    private boolean evaluated;

    public Arena(List<ModelRecord> ancestors, int levelIndex, int size) {
        this.size = Math.max(2, size);
        this.levelIndex = Math.max(0, levelIndex);
        for (ModelRecord m : ancestors) {
            NeuralNet net = m.toNet();
            if (net == null) continue;
            population.add(new Individual(m.name, m.lineage, Individual.genesOf(net)));
        }
        if (population.isEmpty()) {
            for (int i = 0; i < this.size; i++) {
                NeuralNet net = new NeuralNet(Trainer.LAYERS);
                net.randomize(rnd);
                population.add(new Individual("随机" + (i + 1), "初始种群",
                        Individual.genesOf(net)));
            }
        }
        // 祖先不足时用变异体补齐，保证竞速有足够对手
        while (population.size() < this.size) {
            Individual base = population.get(rnd.nextInt(population.size()));
            float[] g = base.genes.clone();
            mutate(g, 0.35f);
            population.add(new Individual(base.name + "变体", base.lineage, g));
        }
    }

    public int levelIndex() { return levelIndex; }
    public void setLevelIndex(int i) { this.levelIndex = Math.max(0, i); }
    public int size() { return population.size(); }
    public List<Racer> racers() { return racers; }
    public Individual individual(int i) { return population.get(i); }

    /** 开始新一代的比赛：每个个体拿到同一关卡的一个独立跑动实例。 */
    public void startRace(Level level) {
        racers.clear();
        for (Individual ind : population) {
            Racer r = new Racer(ind.name, ind.net);
            r.reset(level);
            racers.add(r);
        }
        evaluated = false;
    }

    /** 推进所有选手一帧。 */
    public void step(float dt) {
        for (Racer r : racers) r.step(level, dt);
    }

    /** 是否全部分出结果。 */
    public boolean allDone() {
        for (Racer r : racers) if (!r.done()) return false;
        return !racers.isEmpty();
    }

    private Level level;
    public void setLevel(Level level) { this.level = level; }

    public boolean evaluated() { return evaluated; }

    /** 按「是否完赛 → 用时 → 进度」给出名次。 */
    public void rankRacers() {
        List<Racer> sorted = new ArrayList<>(racers);
        sorted.sort(Comparator
                .comparingInt((Racer r) -> r.state == Racer.FINISHED ? 0 : 1)
                .thenComparingDouble(r -> r.state == Racer.FINISHED ? r.finishTime : -r.progress));
        for (int i = 0; i < sorted.size(); i++) sorted.get(i).rank = i + 1;
    }

    /** 完赛越快分值越高；未完赛按最远进度给分。 */
    public float fitnessOf(Racer r) {
        if (r.state == Racer.FINISHED) return 10000f / Math.max(0.1f, r.finishTime);
        return r.progress * 100f;
    }

    /**
     * 繁殖出下一代：排名前两名直接保留，其余由「名次越靠前越容易被选中」的父母
     * 交叉 + 变异产生。子代名字记录双亲，方便看出血脉来源。
     */
    public void evolve() {
        if (!evaluated) {
            rankRacers();
            evaluated = true;
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < population.size(); i++) order.add(i);
        final float[] fit = new float[population.size()];
        for (int i = 0; i < population.size() && i < racers.size(); i++) {
            fit[i] = fitnessOf(racers.get(i));
        }
        order.sort((a, b) -> Float.compare(fit[b], fit[a]));

        List<Individual> next = new ArrayList<>();
        // 精英：前两名原样保留，保证已获得的成绩不丢失
        int elite = Math.min(2, population.size());
        for (int i = 0; i < elite; i++) {
            Individual src = population.get(order.get(i));
            next.add(new Individual(src.name, src.lineage, src.genes.clone()));
        }
        while (next.size() < population.size()) {
            int p1 = tournament(order, fit);
            int p2 = tournament(order, fit);
            float[] child = crossover(population.get(p1).genes, population.get(p2).genes);
            mutate(child, 0.22f);
            Individual a = population.get(p1);
            Individual b = population.get(p2);
            String name = shortName(a.name) + "×" + shortName(b.name);
            String lineage = a.name + "×" + b.name;
            next.add(new Individual(name, lineage, child));
        }

        population.clear();
        population.addAll(next);
        generation++;
        levelIndex++;
        racers.clear();
        evaluated = false;
    }

    /** 名字过长会让界面拥挤，这里只保留前缀。 */
    private static String shortName(String s) {
        int cut = s.indexOf('×');
        String base = cut > 0 ? s.substring(0, cut) : s;
        return base.length() > 8 ? base.substring(0, 8) : base;
    }

    private int tournament(List<Integer> order, float[] fit) {
        // 在排名前一半里随机挑，名次好的更容易被选中
        int half = Math.max(1, order.size() / 2);
        int a = order.get(rnd.nextInt(half));
        int b = order.get(rnd.nextInt(half));
        return fit[a] >= fit[b] ? a : b;
    }

    private float[] crossover(float[] a, float[] b) {
        float[] out = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = rnd.nextBoolean() ? a[i] : b[i];
            // 小概率取两者平均，帮助保留中间表型
            if (rnd.nextFloat() < 0.1f) out[i] = (a[i] + b[i]) * 0.5f;
        }
        return out;
    }

    private void mutate(float[] g, float sigma) {
        for (int i = 0; i < g.length; i++) {
            if (rnd.nextFloat() < 0.18f) {
                g[i] += (float) rnd.nextGaussian() * sigma;
                if (g[i] > 6f) g[i] = 6f;
                else if (g[i] < -6f) g[i] = -6f;
            }
        }
    }

    /** 当前代表现最好的个体，供存档。 */
    public Individual champion() {
        if (racers.isEmpty()) return population.isEmpty() ? null : population.get(0);
        int best = 0;
        float bestFit = -Float.MAX_VALUE;
        for (int i = 0; i < racers.size() && i < population.size(); i++) {
            float f = fitnessOf(racers.get(i));
            if (f > bestFit) {
                bestFit = f;
                best = i;
            }
        }
        return population.get(best);
    }

    public float championFitness() {
        Racer best = null;
        for (Racer r : racers) {
            if (best == null || fitnessOf(r) > fitnessOf(best)) best = r;
        }
        return best == null ? 0f : fitnessOf(best);
    }
}
