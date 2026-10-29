package com.neurojump.store;

import com.neurojump.nn.NeuralNet;
import com.neurojump.train.Trainer;

/**
 * 一个已存档的个体：网络基因 + 训练信息 + 族谱。
 *
 * 族谱用「父代名字 + 诞生代数 + 诞生层」三元组描述，保存时扁平化成一行文本，
 * 便于在界面上直接展示某个模型是从哪个祖先、在哪一代变异出来的。
 */
public final class ModelRecord {
    /** 存档名（用户可见，唯一）。 */
    public String name;
    /** 保存时间戳（毫秒）。 */
    public long savedAt;
    /** 保存时已通关的层数。 */
    public int solvedLevels;
    /** 保存时所处的层号（从 0 开始）。 */
    public int levelIndex;
    /** 保存时的代数。 */
    public int generation;
    /** 该模型在诞生代的最佳进度，用于横向对比。 */
    public float bestProgress;
    /** 祖先链，从最近到最远。 */
    public String lineage;
    /** 扁平化的权重与偏置。 */
    public float[] genes;

    public ModelRecord() {}

    /** 从网络快照构造存档。 */
    public static ModelRecord of(String name, NeuralNet net, int solvedLevels,
                                 int levelIndex, int generation, float bestProgress,
                                 String lineage) {
        ModelRecord r = new ModelRecord();
        r.name = name;
        r.savedAt = System.currentTimeMillis();
        r.solvedLevels = solvedLevels;
        r.levelIndex = levelIndex;
        r.generation = generation;
        r.bestProgress = bestProgress;
        r.lineage = lineage;
        r.genes = new float[net.geneCount()];
        System.arraycopy(net.weights(), 0, r.genes, 0, net.weightCount());
        System.arraycopy(net.biases(), 0, r.genes, net.weightCount(), net.biasCount());
        return r;
    }

    /** 还原成可运行的网络。基因长度不匹配时返回 null。 */
    public NeuralNet toNet() {
        int expected = expectedGeneCount();
        if (genes == null || genes.length != expected) return null;
        NeuralNet net = new NeuralNet(Trainer.LAYERS);
        System.arraycopy(genes, 0, net.weights(), 0, net.weightCount());
        System.arraycopy(genes, net.weightCount(), net.biases(), 0, net.biasCount());
        return net;
    }

    public static int expectedGeneCount() {
        return new NeuralNet(Trainer.LAYERS).geneCount();
    }

    /** 是否是旧版本存档（网络结构已变化，无法直接加载）。 */
    public boolean isOutdated() {
        return genes == null || genes.length != expectedGeneCount();
    }

    /** 供界面显示的一行摘要。 */
    public String summary() {
        return name + "  第" + (levelIndex + 1) + "层  代" + generation
                + "  通关" + solvedLevels + "层";
    }
}
