import com.lampe.tuya.BulbClient;
import com.lampe.tuya.EffectEngine;
import org.json.JSONObject;

import java.io.FileReader;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Off-device check of the app's own BulbClient + EffectEngine against the real bulb.
 * Streams every effect mode over DP 28 for a while, then writes DP 24 and waits for its echo
 * to prove the bulb is still responsive. Run with ./run.sh [seconds per mode].
 */
public class BulbTest {
    public static void main(String[] a) throws Exception {
        Properties p = new Properties();
        try (FileReader r = new FileReader("../../bulb.properties")) { p.load(r); }
        int seconds = a.length > 0 ? Integer.parseInt(a[0]) : 12;

        AtomicReference<String> echo = new AtomicReference<>("");
        BulbClient c = new BulbClient(p.getProperty("bulb.id"), p.getProperty("bulb.key"), p.getProperty("bulb.ip"),
                new BulbClient.Listener() {
                    public void onStatus(JSONObject d) { if (d.has("24")) echo.set(d.optString("24")); }
                    public void onConnection(boolean ok) { System.out.println("  [connexion " + ok + "]"); }
                });
        c.start();
        Thread.sleep(1500);
        c.set("20", true);
        c.set("21", "colour");
        Thread.sleep(300);

        for (int m = 0; m < EffectEngine.MODES.length; m++) {
            EffectEngine f = new EffectEngine();
            f.mode = m;
            float[] pr = EffectEngine.PRESETS[m];
            f.speed = pr[0]; f.fluidity = pr[1]; f.sparkle = pr[2];
            f.reset();
            long t0 = System.currentTimeMillis();
            int n = 0;
            String code = "";
            while (System.currentTimeMillis() - t0 < seconds * 1000L) {
                float[] o = f.tick(0.05f);
                code = String.format("%04x%04x%04x", Math.round(o[0]) % 360, Math.round(o[1] * 1000), 10 + Math.round(o[2] * 990));
                c.set("28", "0" + code + "00000000");
                n++;
                Thread.sleep(50);
            }
            c.set("24", code);
            long tc = System.currentTimeMillis();
            while (!echo.get().equals(code) && System.currentTimeMillis() - tc < 3000) Thread.sleep(10);
            boolean ok = echo.get().equals(code);
            System.out.printf("%-10s %d images en %d s, ampoule réactive après : %s%n", EffectEngine.MODES[m], n, seconds,
                    ok ? "oui (" + (System.currentTimeMillis() - tc) + " ms)" : "NON");
        }
        c.set("21", "white");
        Thread.sleep(800);
        c.stop();
        System.exit(0);
    }
}
