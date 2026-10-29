import com.neurojump.nn.NeuralNet;
import com.neurojump.train.Actions;
import com.neurojump.train.EndlessEpisode;
import com.neurojump.train.EndlessTrainer;
import com.neurojump.train.Sensors;
import com.neurojump.world.AgentBody;
import com.neurojump.world.EndlessLevel;
import com.neurojump.world.Mover;
import com.neurojump.world.Rect;

/**
 * 瓶颈定位：多次跑最佳个体，统计死点分布，并打印死点处的地形组合。
 * 用来判断「是模型学不会」还是「该段本身不可解」。
 */
public final class Bottleneck {
    public static void main(String[] a) throws Exception {
        int secs = a.length > 0 ? Integer.parseInt(a[0]) : 60;
        EndlessTrainer t = new EndlessTrainer(120, 4, new java.util.ArrayList<>(),
                EndlessTrainer.Style.TIMING);
        t.start();
        Thread.sleep(secs * 1000L);
        t.stop();

        EndlessLevel lv = t.level();
        NeuralNet net = t.demoNet();
        System.out.printf("训练 %ds：代 %d，历史最佳 %.0f 米%n",
                secs, t.generation, t.bestEverDistance() / 20f);

        java.util.Map<Integer, Integer> buckets = new java.util.TreeMap<>();
        float worst = 0;
        for (int i = 0; i < 8; i++) {
            AgentBody b = new AgentBody();
            b.reset(lv.startX, lv.startY);
            Sensors s = new Sensors();
            float time = 0, maxX = b.x;
            while (time < 45f) {
                lv.ensure(b.x + 2000f);
                lv.keepFrom(b.x);
                float[] out = net.forward(s.read(lv, b, time));
                b.step(lv, time, Actions.move(out), Actions.jump(out), 1f / 60f);
                time += 1f / 60f;
                maxX = Math.max(maxX, b.x);
                if (b.fellOut() || b.touchesSpike(lv)) break;
            }
            int meter = (int) ((maxX - lv.startX) / 20f);
            buckets.merge(meter / 25 * 25, 1, Integer::sum);
            worst = Math.max(worst, maxX);
        }
        System.out.println("死点分布（米段 → 次数）：");
        buckets.forEach((k, v) -> System.out.printf("  %3d~%3d 米：%d 次%n", k, k + 25, v));

        float d = worst;
        System.out.printf("%n最远处 x=%.0f（%.0f 米）附近地形：%n", d, (d - lv.startX) / 20f);
        for (Rect p : lv.platforms)
            if (p.right() > d - 420 && p.x < d + 320)
                System.out.printf("  平台 [%.0f,%.0f] top=%.0f%n", p.x, p.right(), p.top());
        for (Mover m : lv.movers) {
            float l = m.vertical ? m.baseX : m.baseX - m.amplitude;
            float r = m.vertical ? m.baseX + m.w : m.baseX + m.w + m.amplitude;
            if (r > d - 420 && l < d + 320)
                System.out.printf("  移动 [%.0f,%.0f] top=[%.0f..%.0f] %s 周期%.1f%n",
                        l, r, m.baseY + m.h - m.amplitude, m.baseY + m.h + m.amplitude,
                        m.vertical ? "垂直" : "水平", m.period);
        }
        for (Rect sp : lv.spikes)
            if (sp.right() > d - 420 && sp.x < d + 320)
                System.out.printf("  尖刺 [%.0f,%.0f] y=[%.0f,%.0f]%n", sp.x, sp.right(), sp.y, sp.top());
        System.exit(0);
    }
}
