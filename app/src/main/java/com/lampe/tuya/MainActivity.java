package com.lampe.tuya;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Locale;

public class MainActivity extends Activity implements BulbClient.Listener {

    // "Smart Bulb" only. The other bulb on the Tuya account belongs to someone else: never add it.
    private static final String DEV_ID = BuildConfig.BULB_ID;
    private static final String LOCAL_KEY = BuildConfig.BULB_KEY;
    private static final String DEFAULT_IP = BuildConfig.BULB_IP;

    // Tuya DPs for this bulb (category "dj", v3.3)
    private static final String DP_ON = "20", DP_MODE = "21", DP_BRIGHT = "22", DP_TEMP = "23", DP_COLOUR = "24";
    // DP 28 (control_data) changes the colour without storing it or reporting it to the cloud.
    // Streaming DP 24 instead stalls the bulb after ~10-40 s; DP 28 held 20/s for 30 s+.
    private static final String DP_LIVE = "28";
    private static final long ECHO_IGNORE_MS = 1500;
    private static final long FRAME_MS = 50;
    private static final long COMMIT_MS = 300;    // DP 24 is written once a gesture settles

    private static final int BG = 0xFF0E0E11, FG = 0xFFEDEDF0, DIM = 0xFF7C7C86, CHIP = 0xFF1E1E23;
    private static final int WARM = 0xFFFFB55C, COOL = 0xFFDDEBFF;
    private static final int[] RAINBOW = {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private BulbClient client;
    private SharedPreferences prefs;
    private float dp;

    // Bulb state, in Tuya units
    private boolean on;
    private boolean colourMode;
    private int bright = 1000;   // 10..1000 (white mode)
    private int temp = 500;      // 0..1000, 0 = warm
    private int hue = 30;        // 0..360
    private int sat = 1000;      // 0..1000
    private int val = 1000;      // 10..1000 (colour mode)
    private long lastLocalChange;

    private final EffectEngine fx = new EffectEngine();
    private boolean fxRunning;
    private long fxLastFrame;
    private String fxLastSent = "";

    private Glow power;
    private TextView status, tabLight, tabFx;
    private View lightPage, fxPage;
    private GradientSlider hueSlider, satSlider, brightSlider, tempSlider;
    private GradientSlider fxShift, fxSpeed, fxFluid, fxSparkle, fxBright;
    private final TextView[] modeChips = new TextView[EffectEngine.MODES.length];
    private final View[] paletteChips = new View[EffectEngine.PALETTES.length];

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("lampe", MODE_PRIVATE);
        loadFx();
        setContentView(buildUi());
        showTab(prefs.getBoolean("fxTab", false));
        refresh();
        refreshFx();
    }

    @Override
    protected void onResume() {
        super.onResume();
        client = new BulbClient(DEV_ID, LOCAL_KEY, prefs.getString("ip", DEFAULT_IP), this);
        client.start();
        discover();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopFx();
        saveFx();
        client.stop();   // free the bulb's single local connection while in background
        client = null;
    }

    private void discover() {
        Discovery.find(this, DEV_ID, ip -> ui.post(() -> {
            prefs.edit().putString("ip", ip).apply();
            if (client != null) client.setIp(ip);
        }));
    }

    // ---- UI ---------------------------------------------------------------------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = px(24);
        root.setPadding(pad, px(28), pad, px(28));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Lampe", 26, FG);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titles.addView(title);
        status = text("Connexion…", 14, DIM);
        titles.addView(status);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        tabLight = tab("Lumière", v -> showTab(false));
        tabFx = tab("Effets", v -> showTab(true));
        header.addView(tabLight);
        header.addView(tabFx);
        root.addView(header);

        power = new Glow(this);
        power.setOnClickListener(v -> {
            if (fxPage.getVisibility() == View.VISIBLE) {
                if (fxRunning) stopFx(); else startFx();
            } else {
                stopFx();
                on = !on;
                send(DP_ON, on);
                refresh();
            }
        });
        root.addView(power, new LinearLayout.LayoutParams(-1, 0, 1f));

        lightPage = buildLightPage();
        fxPage = buildFxPage();
        root.addView(lightPage);
        root.addView(fxPage);
        return root;
    }

    private View buildLightPage() {
        LinearLayout page = column();

        hueSlider = slider(page, "Couleur", RAINBOW, v -> {
            stopFx();
            hue = Math.round(v * 360);
            enterColour();
            sendColour();
        });
        satSlider = slider(page, "Saturation", new int[]{0xFFFFFFFF, 0xFFFF0000}, v -> {
            stopFx();
            sat = Math.round(v * 1000);
            enterColour();
            sendColour();
        });
        tempSlider = slider(page, "Blanc", new int[]{WARM, 0xFFFFF4E6, COOL}, v -> {
            stopFx();
            temp = Math.round(v * 1000);
            enterWhite();
            send(DP_TEMP, temp);
        });
        brightSlider = slider(page, "Luminosité", new int[]{0xFF222226, FG}, v -> {
            stopFx();
            int b = 10 + Math.round(v * 990);
            if (colourMode) { val = b; sendColour(); }
            else { bright = b; send(DP_BRIGHT, bright); }
        });

        LinearLayout swatches = new LinearLayout(this);
        swatches.setPadding(0, px(16), 0, 0);
        addSwatch(swatches, WARM, () -> white(0));
        addSwatch(swatches, COOL, () -> white(1000));
        addSwatch(swatches, 0xFFFF3B30, () -> colour(0, 1000));
        addSwatch(swatches, 0xFFFF9500, () -> colour(28, 1000));
        addSwatch(swatches, 0xFF34C759, () -> colour(120, 1000));
        addSwatch(swatches, 0xFF0A84FF, () -> colour(220, 1000));
        addSwatch(swatches, 0xFFBF5AF2, () -> colour(285, 1000));
        addSwatch(swatches, 0xFFFF9EC7, () -> colour(330, 400));
        page.addView(swatches);
        return page;
    }

    private View buildFxPage() {
        LinearLayout page = column();

        LinearLayout modes = new LinearLayout(this);
        for (int i = 0; i < EffectEngine.MODES.length; i++) {
            final int m = i;
            TextView c = chip(EffectEngine.MODES[i], v -> {
                fx.mode = m;
                float[] p = EffectEngine.PRESETS[m];
                fx.speed = p[0];
                fx.fluidity = p[1];
                fx.sparkle = p[2];
                if (m == EffectEngine.BOUGIE && fx.palette == 0) fx.palette = 1;
                fx.reset();
                refreshFx();
                startFx();
            });
            modeChips[i] = c;
            modes.addView(c);
        }
        page.addView(scroller(modes));

        LinearLayout palettes = new LinearLayout(this);
        palettes.setPadding(0, px(10), 0, px(4));
        for (int i = 0; i < EffectEngine.PALETTES.length; i++) {
            final int p = i;
            View c = new View(this);
            c.setContentDescription(EffectEngine.PALETTE_NAMES[i]);
            c.setOnClickListener(v -> {
                fx.palette = p;
                fx.reset();
                refreshFx();
                startFx();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(64), px(32));
            lp.rightMargin = px(8);
            paletteChips[i] = c;
            palettes.addView(c, lp);
        }
        page.addView(scroller(palettes));

        fxShift = slider(page, "Teinte", RAINBOW, v -> { fx.hueShift = v; refreshPalettes(); });
        fxSpeed = slider(page, "Vitesse", new int[]{0xFF2A2A31, 0xFF5E5CE6}, v -> fx.speed = v);
        fxFluid = slider(page, "Fluidité", new int[]{0xFF2A2A31, 0xFF30B0C7}, v -> fx.fluidity = v);
        fxSparkle = slider(page, "Scintillement", new int[]{0xFF2A2A31, 0xFFFFD60A}, v -> fx.sparkle = v);
        fxBright = slider(page, "Luminosité", new int[]{0xFF222226, FG}, v -> fx.brightness = 0.05f + 0.95f * v);
        return page;
    }

    private LinearLayout column() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        return page;
    }

    private GradientSlider slider(LinearLayout page, String label, int[] colors, GradientSlider.Listener l) {
        GradientSlider s = new GradientSlider(this);
        s.setLabel(label);
        s.setColors(colors);
        s.setListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(12);
        page.addView(s, lp);
        return s;
    }

    private HorizontalScrollView scroller(View content) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(content);
        return h;
    }

    private TextView tab(String s, View.OnClickListener l) {
        TextView t = text(s, 15, DIM);
        t.setPadding(px(10), px(8), px(10), px(8));
        t.setOnClickListener(l);
        return t;
    }

    private TextView chip(String s, View.OnClickListener l) {
        TextView t = text(s, 14, FG);
        t.setPadding(px(14), px(8), px(14), px(8));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = px(8);
        t.setLayoutParams(lp);
        return t;
    }

    private void addSwatch(LinearLayout row, int color, Runnable action) {
        View v = new View(this) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c) {
                p.setColor(color);
                c.drawCircle(getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()) / 2f - px(4), p);
            }
        };
        v.setOnClickListener(x -> { stopFx(); action.run(); });
        row.addView(v, new LinearLayout.LayoutParams(0, px(40), 1f));
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private int px(int v) { return Math.round(v * dp); }

    private void showTab(boolean effects) {
        lightPage.setVisibility(effects ? View.GONE : View.VISIBLE);
        fxPage.setVisibility(effects ? View.VISIBLE : View.GONE);
        tabLight.setTextColor(effects ? DIM : FG);
        tabFx.setTextColor(effects ? FG : DIM);
        prefs.edit().putBoolean("fxTab", effects).apply();
        refresh();
    }

    // ---- manual control -----------------------------------------------------------------

    private void white(int t) {
        temp = t;
        enterWhite();
        send(DP_TEMP, temp);
        refresh();
    }

    private void colour(int h, int s) {
        hue = h;
        sat = s;
        enterColour();
        sendColour();
        refresh();
    }

    private void enterColour() {
        if (!on) { on = true; send(DP_ON, true); }
        if (!colourMode) { colourMode = true; send(DP_MODE, "colour"); }
        refresh();
    }

    private void enterWhite() {
        if (!on) { on = true; send(DP_ON, true); }
        if (colourMode) { colourMode = false; send(DP_MODE, "white"); send(DP_BRIGHT, bright); }
        refresh();
    }

    private void sendColour() {
        sendLive(hsvCode());
        ui.removeCallbacks(commitColour);
        ui.postDelayed(commitColour, COMMIT_MS);
        refresh();
    }

    private final Runnable commitColour = () -> send(DP_COLOUR, hsvCode());

    private String hsvCode() {
        return String.format(Locale.US, "%04x%04x%04x", hue, sat, val);
    }

    private void sendLive(String hsv) {
        send(DP_LIVE, "0" + hsv + "00000000");   // mode 0 = jump, then HSV, then unused white B/T
    }

    private void send(String dp, Object v) {
        lastLocalChange = System.currentTimeMillis();
        if (client != null) client.set(dp, v);
    }

    // ---- effects ------------------------------------------------------------------------

    private final Runnable fxFrame = new Runnable() {
        @Override public void run() {
            if (!fxRunning) return;
            long now = SystemClock.elapsedRealtime();
            float dt = Math.min(0.25f, (now - fxLastFrame) / 1000f);
            fxLastFrame = now;
            float[] c = fx.tick(dt);
            hue = Math.round(c[0]) % 360;
            sat = Math.round(c[1] * 1000);
            val = 10 + Math.round(c[2] * 990);
            String code = hsvCode();
            if (!code.equals(fxLastSent)) {
                fxLastSent = code;
                sendLive(code);
            }
            power.setState(true, lightColor(), fxPage.getVisibility() == View.VISIBLE ? "STOP" : "ON");
            ui.postDelayed(this, FRAME_MS);
        }
    };

    private void startFx() {
        if (fxRunning) return;
        fxRunning = true;
        enterColour();
        fxLastFrame = SystemClock.elapsedRealtime();
        fxLastSent = "";
        ui.post(fxFrame);
    }

    private void stopFx() {
        if (!fxRunning) return;
        fxRunning = false;
        ui.removeCallbacks(fxFrame);
        send(DP_COLOUR, hsvCode());
        refresh();
    }

    private void refreshFx() {
        for (int i = 0; i < modeChips.length; i++) {
            boolean sel = i == fx.mode;
            modeChips[i].setBackground(pill(sel ? FG : CHIP));
            modeChips[i].setTextColor(sel ? BG : FG);
        }
        refreshPalettes();
        fxShift.setValue(fx.hueShift);
        fxSpeed.setValue(fx.speed);
        fxFluid.setValue(fx.fluidity);
        fxSparkle.setValue(fx.sparkle);
        fxBright.setValue((fx.brightness - 0.05f) / 0.95f);
    }

    private void refreshPalettes() {
        for (int i = 0; i < paletteChips.length; i++) {
            float[] p = EffectEngine.PALETTES[i];
            int[] cols = new int[p.length / 2];
            for (int k = 0; k < cols.length; k++) {
                cols[k] = Color.HSVToColor(new float[]{(p[k * 2] + fx.hueShift * 360) % 360, p[k * 2 + 1], 1f});
            }
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, cols);
            g.setCornerRadius(px(16));
            if (i == fx.palette) g.setStroke(px(3), FG);
            paletteChips[i].setBackground(g);
        }
    }

    private GradientDrawable pill(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(18));
        return g;
    }

    private void loadFx() {
        fx.mode = Math.min(prefs.getInt("fxMode", 0), EffectEngine.MODES.length - 1);
        fx.palette = Math.min(prefs.getInt("fxPalette", 0), EffectEngine.PALETTES.length - 1);
        fx.hueShift = prefs.getFloat("fxShift", 0f);
        fx.speed = prefs.getFloat("fxSpeed", fx.speed);
        fx.fluidity = prefs.getFloat("fxFluid", fx.fluidity);
        fx.sparkle = prefs.getFloat("fxSparkle", fx.sparkle);
        fx.brightness = prefs.getFloat("fxBright", 1f);
        fx.reset();
    }

    private void saveFx() {
        prefs.edit()
                .putInt("fxMode", fx.mode).putInt("fxPalette", fx.palette)
                .putFloat("fxShift", fx.hueShift).putFloat("fxSpeed", fx.speed)
                .putFloat("fxFluid", fx.fluidity).putFloat("fxSparkle", fx.sparkle)
                .putFloat("fxBright", fx.brightness).apply();
    }

    // ---- display ------------------------------------------------------------------------

    /** Colour of the light as currently set, for the big button. */
    private int lightColor() {
        if (colourMode) {
            return Color.HSVToColor(new float[]{hue, sat / 1000f, 0.35f + 0.65f * val / 1000f});
        }
        int c = blend(WARM, COOL, temp / 1000f);
        float k = 0.35f + 0.65f * bright / 1000f;
        return Color.rgb((int) (Color.red(c) * k), (int) (Color.green(c) * k), (int) (Color.blue(c) * k));
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private void refresh() {
        boolean fxTab = fxPage != null && fxPage.getVisibility() == View.VISIBLE;
        String label = fxTab ? (fxRunning ? "STOP" : "LANCER") : (on ? "ON" : "OFF");
        power.setState(on || fxRunning, lightColor(), label);
        hueSlider.setValue(hue / 360f);
        satSlider.setValue(sat / 1000f);
        satSlider.setColors(0xFFFFFFFF, Color.HSVToColor(new float[]{hue, 1f, 1f}));
        tempSlider.setValue(temp / 1000f);
        int b = colourMode ? val : bright;
        brightSlider.setValue((b - 10) / 990f);
        int full = colourMode ? Color.HSVToColor(new float[]{hue, sat / 1000f, 1f}) : blend(WARM, COOL, temp / 1000f);
        brightSlider.setColors(0xFF222226, full);
    }

    // ---- BulbClient.Listener (background threads) ---------------------------------------

    @Override
    public void onStatus(JSONObject dps) {
        ui.post(() -> {
            if (fxRunning || System.currentTimeMillis() - lastLocalChange < ECHO_IGNORE_MS) return;
            if (dps.has(DP_ON)) on = dps.optBoolean(DP_ON);
            if (dps.has(DP_MODE)) colourMode = "colour".equals(dps.optString(DP_MODE));
            if (dps.has(DP_BRIGHT)) bright = dps.optInt(DP_BRIGHT, bright);
            if (dps.has(DP_TEMP)) temp = dps.optInt(DP_TEMP, temp);
            String c = dps.optString(DP_COLOUR, "");
            if (c.length() == 12) {
                try {
                    hue = Integer.parseInt(c.substring(0, 4), 16);
                    sat = Integer.parseInt(c.substring(4, 8), 16);
                    val = Math.max(10, Integer.parseInt(c.substring(8, 12), 16));
                } catch (NumberFormatException ignored) { }
            }
            refresh();
        });
    }

    @Override
    public void onConnection(boolean connected) {
        ui.post(() -> {
            status.setText(connected ? "Connectée" : "Hors ligne — recherche…");
            if (!connected && client != null) discover();
        });
    }

    /** Big round button that glows with the light's colour. */
    private static class Glow extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean lit;
        private int color;
        private String text = "";

        Glow(Context ctx) {
            super(ctx);
            label.setTextAlign(Paint.Align.CENTER);
            label.setTextSize(18 * ctx.getResources().getDisplayMetrics().density);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        void setState(boolean lit, int color, String text) {
            this.lit = lit;
            this.color = color;
            this.text = text;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) * 0.30f;
            if (lit) {
                halo.setShader(new RadialGradient(cx, cy, r * 1.6f,
                        new int[]{color & 0x88FFFFFF, color & 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, r * 1.6f, halo);
            }
            fill.setColor(lit ? color : 0xFF1E1E23);
            c.drawCircle(cx, cy, r, fill);
            boolean bright = lit && (Color.red(color) * 0.3 + Color.green(color) * 0.59 + Color.blue(color) * 0.11) > 150;
            label.setColor(lit ? (bright ? 0xFF111111 : 0xFFFFFFFF) : 0xFF7C7C86);
            c.drawText(text, cx, cy - (label.descent() + label.ascent()) / 2, label);
        }
    }
}
