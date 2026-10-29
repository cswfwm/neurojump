import com.neurojump.nn.NeuralNet;
import com.neurojump.train.EndlessEpisode;
import com.neurojump.train.Sensors;
import com.neurojump.train.Trainer;
import com.neurojump.world.AgentBody;
import com.neurojump.world.EndlessLevel;

import java.util.Random;

/** 验证无限关卡：地形能持续生成、裁切正常，且模型能跑出距离。 */
public final class EndlessCheck {
    public static void main(String[] a) {
        EndlessLevel lv = new EndlessLevel(7);
        System.out.printf("起点 x=%.0f y=%.0f width=%.0f%n", lv.startX, lv.startY, lv.width);

        lv.ensure(200000f);
        System.out.printf("生成到 20 万像素后：平台 %d 移动 %d 尖刺 %d 宽 %.0f%n",
                lv.platforms.size(), lv.movers.size(), lv.spikes.size(), lv.width);

        Random r = new Random(1);
        int died = 0;
        float best = 0;
        for (int i = 0; i < 40; i++) {
            NeuralNet net = new NeuralNet(Trainer.LAYERS);
            net.randomize(r);
            EndlessEpisode ep = new EndlessEpisode();
            ep.reset(lv);
            int st;
            do { st = ep.step(lv, net); } while (st == EndlessEpisode.RUNNING);
            if (st == EndlessEpisode.DIED) died++;
            best = Math.max(best, ep.score);
        }
        System.out.printf("40 个随机模型：死亡 %d，最远跑分 %.0f px%n", died, best);

        AgentBody body = new AgentBody();
        body.reset(lv.startX, lv.startY);
        float t = 0f;
        float maxX = body.x;
        for (int i = 0; i < 60 * 40; i++) {
            lv.ensure(body.x + 1800f);
            body.step(lv, t, 1f, body.onGround, 1f / 60f);
            t += 1f / 60f;
            maxX = Math.max(maxX, body.x);
            if (body.fellOut() || body.touchesSpike(lv)) break;
        }
        System.out.printf("基线脚本：跑到 x=%.0f（距离 %.0f），存活 %.1fs%n",
                maxX, maxX - lv.startX, t);
    }
}
