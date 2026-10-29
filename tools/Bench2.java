import com.neurojump.nn.NeuralNet;
import com.neurojump.train.EndlessEpisode;
import com.neurojump.train.EndlessTrainer;
import com.neurojump.world.EndlessLevel;

/**
 * 统一基准：固定训练时长，测「从起点起跑」的最远距离。
 * 改动网络结构/训练参数后都跑这个，用数字判断好坏。
 */
public final class Bench2 {
    public static void main(String[] a) throws Exception {
        int secs = a.length > 0 ? Integer.parseInt(a[0]) : 45;
        int runs = a.length > 1 ? Integer.parseInt(a[1]) : 2;
        int pop = a.length > 2 ? Integer.parseInt(a[2]) : 60;

        System.out.println("网络结构 " + java.util.Arrays.toString(
                new NeuralNet(com.neurojump.train.Trainer.LAYERS).sizes())
                + " 基因数 " + new NeuralNet(com.neurojump.train.Trainer.LAYERS).geneCount());

        float sum = 0;
        int sumWait = 0;
        for (int r = 0; r < runs; r++) {
            EndlessTrainer t = new EndlessTrainer(pop, 4, new java.util.ArrayList<>(),
                    EndlessTrainer.Style.TIMING);
            t.start();
            Thread.sleep(secs * 1000L);
            t.stop();

            EndlessLevel lv = t.level();
            float d1 = run(t, lv);
            float d2 = run(t, lv);
            float d = Math.max(d1, d2);
            sum += d;
            sumWait += t.bestEverWaits;
            System.out.printf("  第 %d 次：%.0f 米（两次取优 %.0f / %.0f） 代 %d 等待 %d%n",
                    r + 1, d, d1, d2, t.generation, t.bestEverWaits);
        }
        System.out.printf("平均 %.0f 米%n", sum / runs);
        System.exit(0);
    }

    private static float run(EndlessTrainer t, EndlessLevel lv) {
        NeuralNet net = t.demoNet();
        EndlessEpisode ep = new EndlessEpisode();
        ep.reset(lv);
        int st;
        do { st = ep.step(lv, net); } while (st == EndlessEpisode.RUNNING);
        return (ep.maxX - lv.startX) / 20f;
    }
}
