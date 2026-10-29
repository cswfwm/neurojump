package com.neurojump.ai;

import com.neurojump.train.Trainer;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 教练：训练过程中周期性把当前状态（层、代数、通关率、是否停滞）交给大模型，
 * 由模型给出进化超参数建议（变异强度、交叉率、精英比例、耐心代数、多样注入间隔），
 * 训练器随后按建议调整。模型只负责「调参」，不接触网络权重本身。
 *
 * 请求在独立线程里发，避免网络延迟卡住训练；同一时间只允许一个请求在飞。
 */
public final class Coach implements Runnable {
    /** 至少间隔这么多秒才请求一次，控制费用与延迟。 */
    private static final long MIN_INTERVAL_MS = 12_000L;
    /** 停滞这么多代就提前触发一次咨询。 */
    private static final int STAGNATION_TRIGGER = 20;

    private static final String SYSTEM_PROMPT =
            "你是一个遗传算法调参助手，负责训练一个 2D 平台跳跃游戏的神经网络控制器。"
                    + "只输出一行 JSON，不要解释，不要代码块。字段："
                    + "mutation(0.02-1.2 实数), crossover(0.1-1.0 实数), "
                    + "eliteRatio(0.01-0.2 实数), maxGenerations(60-2000 整数), "
                    + "diversifyEvery(4-60 整数)。"
                    + "经验规则：进度长期不动时提高 mutation 并减小 diversifyEvery；"
                    + "已能通关但速度慢时降低 mutation、提高 eliteRatio 以精修。";

    private final Trainer trainer;
    private final OpenAiClient client;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 最近一次建议的原始回复，供界面显示。 */
    public volatile String lastReply = "";
    public volatile String lastError = "";
    public volatile long lastRequestAt;
    public volatile int appliedCount;

    public Coach(Trainer trainer, OpenAiClient client) {
        this.trainer = trainer;
        this.client = client;
    }

    public boolean enabled() {
        return client != null && client.isConfigured();
    }

    public void maybeConsult() {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        if (now - lastRequestAt < MIN_INTERVAL_MS) return;
        if (!running.compareAndSet(false, true)) return;
        lastRequestAt = now;
        Thread t = new Thread(this, "ai-coach");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void run() {
        try {
            String user = buildUserPrompt();
            String reply = client.chat(SYSTEM_PROMPT, user, 20_000);
            lastReply = reply;
            lastError = "";
            if (apply(reply)) appliedCount++;
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            running.set(false);
        }
    }

    private String buildUserPrompt() {
        return "当前状态：第 " + (trainer.levelIndex + 1) + " 层，第 " + trainer.generation
                + " 代，本代通关个体 " + trainer.wonThisGeneration + " / " + trainer.populationSize()
                + "，最佳进度 " + Math.round(trainer.bestProgress * 100) + "%，"
                + "已通关 " + trainer.solvedLevels + " 层，未稳定学会 " + trainer.stalledLevels
                + " 层。当前参数：mutation=" + trainer.tuning.mutation
                + ", crossover=" + trainer.tuning.crossover
                + ", eliteRatio=" + trainer.tuning.eliteRatio
                + ", maxGenerations=" + trainer.tuning.maxGenerations
                + ", diversifyEvery=" + trainer.tuning.diversifyEvery
                + "。请给出下一阶段的参数。";
    }

    /** 从回复里解析 JSON 字段并应用。解析不出任何字段则视为失败。 */
    boolean apply(String reply) {
        if (reply == null || reply.isEmpty()) return false;
        Float mutation = number(reply, "mutation");
        Float crossover = number(reply, "crossover");
        Float elite = number(reply, "eliteRatio");
        Float maxGen = number(reply, "maxGenerations");
        Float diversify = number(reply, "diversifyEvery");
        if (mutation == null && crossover == null && elite == null
                && maxGen == null && diversify == null) {
            return false;
        }
        trainer.tuning.apply(
                mutation == null ? trainer.tuning.mutation : mutation,
                crossover == null ? trainer.tuning.crossover : crossover,
                elite == null ? trainer.tuning.eliteRatio : elite,
                maxGen == null ? trainer.tuning.maxGenerations : maxGen.intValue(),
                diversify == null ? trainer.tuning.diversifyEvery : diversify.intValue());
        return true;
    }

    /** 在 JSON 文本里找 "key": 后面的数字，容忍空格与冒号差异。 */
    private static Float number(String text, String key) {
        int i = text.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        int colon = text.indexOf(':', i + key.length() + 2);
        if (colon < 0) return null;
        int j = colon + 1;
        while (j < text.length() && (Character.isWhitespace(text.charAt(j))
                || text.charAt(j) == '"')) {
            j++;
        }
        int start = j;
        while (j < text.length()) {
            char c = text.charAt(j);
            if (Character.isDigit(c) || c == '.' || c == '-' || c == '+'
                    || c == 'e' || c == 'E') {
                j++;
            } else {
                break;
            }
        }
        if (j == start) return null;
        try {
            return Float.parseFloat(text.substring(start, j));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public int stagnationThreshold() {
        return STAGNATION_TRIGGER;
    }
}
