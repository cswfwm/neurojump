import com.neurojump.nn.NeuralNet;
import com.neurojump.train.EndlessTrainer;
import com.neurojump.train.Trainer;

/**
 * 内存基准：测运行期的堆占用与每代分配量。
 * 目标设备只有 200MB 运存，32 位进程的堆上限通常更小。
 */
public final class MemBench {
    private static long allocated() {
        java.lang.management.ThreadMXBean bean =
                java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean)) return -1;
        long total = 0;
        for (long id : bean.getAllThreadIds()) {
            long v = ((com.sun.management.ThreadMXBean) bean).getThreadAllocatedBytes(id);
            if (v > 0) total += v;
        }
        return total;
    }

    private static void gc() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try { Thread.sleep(120); } catch (InterruptedException ignored) { }
        }
    }

    public static void main(String[] a) throws Exception {
        Runtime rt = Runtime.getRuntime();
        System.out.printf("JVM 最大堆: %.0f MB%n", rt.maxMemory() / 1048576.0);
        gc();
        long base = rt.totalMemory() - rt.freeMemory();
        System.out.printf("空载已用: %.1f MB%n", base / 1048576.0);

        int pop = 120;
        EndlessTrainer t = new EndlessTrainer(pop, 4, new java.util.ArrayList<>(),
                EndlessTrainer.Style.TIMING);
        gc();
        long afterInit = rt.totalMemory() - rt.freeMemory();
        System.out.printf("建训练器后: %.1f MB（+%.1f）%n",
                afterInit / 1048576.0, (afterInit - base) / 1048576.0);

        t.start();
        Thread.sleep(3000);
        gc();
        long running = rt.totalMemory() - rt.freeMemory();
        System.out.printf("训练 3s 后: %.1f MB（+%.1f）%n",
                running / 1048576.0, (running - afterInit) / 1048576.0);

        int g0 = t.generation;
        long a0 = allocated();
        Thread.sleep(4000);
        int g1 = t.generation;
        long a1 = allocated();
        if (a0 > 0 && a1 > 0) {
            double mb = (a1 - a0) / 1048576.0;
            int gens = Math.max(1, g1 - g0);
            System.out.printf("4 秒跑 %d 代，分配 %.0f MB，每代约 %.0f KB%n",
                    gens, mb, mb * 1024 / gens);
        }

        NeuralNet n = new NeuralNet(Trainer.LAYERS);
        int genes = n.geneCount();
        System.out.printf("%n单网络 %d 基因 = %.1f KB%n", genes, genes * 4 / 1024.0);
        System.out.printf("种群 %d 个（网络+基因副本）≈ %.1f MB%n",
                pop, pop * genes * 4 * 2 / 1048576.0);

        t.stop();
        System.exit(0);
    }
}
