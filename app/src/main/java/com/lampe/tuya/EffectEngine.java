package com.lampe.tuya;

import java.util.Random;

/**
 * Generates a colour stream for the bulb. Every mode walks through the selected palette one
 * "step" at a time; speed sets the step length, fluidity how much of a step is spent
 * crossfading (0 = hard cuts), sparkle adds random brightness flicker and white glints on top.
 * Pure Java so it can be exercised off-device.
 */
public class EffectEngine {

    public static final int FONDU = 0, ALEA = 1, SCINT = 2, PULSE = 3, BOUGIE = 4;
    public static final String[] MODES = {"Fondu", "Aléatoire", "Scintille", "Pulse", "Bougie"};
    /** Per-mode starting point for {speed, fluidity, sparkle}; the sliders stay free after. */
    public static final float[][] PRESETS = {
            {0.35f, 0.90f, 0.00f},
            {0.60f, 0.20f, 0.15f},
            {0.45f, 0.70f, 0.60f},
            {0.45f, 0.70f, 0.00f},
            {0.55f, 0.60f, 0.25f},
    };

    public static final String[] PALETTE_NAMES =
            {"Arc-en-ciel", "Feu", "Océan", "Forêt", "Néon", "Couchant", "Glace", "Bonbon"};
    /** Palettes as flat {hue 0..360, saturation 0..1} pairs. */
    public static final float[][] PALETTES = {
            {0, 1, 60, 1, 120, 1, 180, 1, 240, 1, 300, 1},
            {0, 1, 14, 1, 28, 1, 42, 1, 22, 0.85f},
            {170, 0.8f, 190, 1, 205, 1, 225, 1, 245, 0.9f},
            {75, 1, 100, 1, 125, 0.9f, 150, 1, 60, 0.8f},
            {300, 1, 275, 1, 190, 1, 325, 1, 170, 1},
            {330, 1, 350, 1, 12, 1, 32, 1, 280, 0.8f},
            {190, 0.25f, 200, 0.6f, 215, 0.85f, 0, 0, 230, 0.4f},
            {330, 0.45f, 280, 0.4f, 190, 0.4f, 50, 0.45f, 140, 0.35f},
    };

    public int mode = FONDU;
    public int palette = 0;
    public float hueShift, speed = 0.35f, fluidity = 0.9f, sparkle, brightness = 1f;

    private final Random rnd = new Random();
    private double phase;
    private long step;
    private int cur, next;
    private float flicker, flickerTarget, candle = 0.8f, candleTarget = 0.8f, glint;

    public void reset() {
        phase = 0;
        step = 0;
        cur = 0;
        next = pickNext(0);
    }

    /** Advance by dt seconds and return {hue 0..360, saturation 0..1, value 0..1}. */
    public float[] tick(float dt) {
        float[] pal = PALETTES[palette];
        if (cur * 2 >= pal.length || next * 2 >= pal.length) reset();

        float period = (float) (6.0 * Math.pow(0.1 / 6.0, speed));   // 6 s .. 0.1 s per colour
        if (mode == SCINT) period *= 4;
        if (mode == BOUGIE) period *= 3;
        phase += dt / period;
        long idx = (long) Math.floor(phase);
        if (idx - step > 8) step = idx - 1;
        while (step < idx) {
            step++;
            cur = next;
            next = pickNext(cur);
        }
        float frac = (float) (phase - idx);

        float t, v = 1f;
        switch (mode) {
            case PULSE:
                t = 0;
                float sine = 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * frac);
                float hit = (float) Math.exp(-6 * frac);
                v = 0.04f + 0.96f * (fluidity * sine + (1 - fluidity) * hit);
                break;
            case BOUGIE:
                t = smooth(frac);
                if (rnd.nextFloat() < dt * (2 + 20 * speed)) candleTarget = 0.3f + 0.7f * rnd.nextFloat();
                candle += (candleTarget - candle) * follow(dt, fluidity);
                v = candle;
                break;
            default:
                t = fluidity < 0.01f ? 0 : smooth(clamp((frac - (1 - fluidity)) / fluidity));
        }

        float h1 = pal[cur * 2], s1 = pal[cur * 2 + 1];
        float h2 = pal[next * 2], s2 = pal[next * 2 + 1];
        float dh = ((h2 - h1 + 540) % 360) - 180;
        float h = h1 + dh * t, s = s1 + (s2 - s1) * t;
        if (s1 == 0) h = h2;                      // white has no hue: don't sweep through one
        else if (s2 == 0) h = h1;

        float amount = mode == SCINT ? 0.4f + 0.6f * sparkle : sparkle;
        if (amount > 0) {
            if (rnd.nextFloat() < dt * (3 + 25 * speed)) flickerTarget = rnd.nextFloat();
            flicker += (flickerTarget - flicker) * follow(dt, fluidity * 0.7f);
            v *= 1 - 0.9f * amount * flicker;
            if (rnd.nextFloat() < dt * amount * 1.5f) glint = 1;
            glint *= (float) Math.exp(-dt * 12);
            s *= 1 - 0.9f * glint;
            v = Math.max(v, glint);
        } else {
            flicker = 0;
            glint = 0;
        }

        h = ((h + hueShift * 360) % 360 + 360) % 360;
        return new float[]{h, clamp(s), clamp(v) * brightness};
    }

    private int pickNext(int from) {
        int n = PALETTES[palette].length / 2;
        if (mode != ALEA && mode != BOUGIE && mode != SCINT) return (from + 1) % n;
        int r = rnd.nextInt(n - 1);
        return r >= from ? r + 1 : r;
    }

    /** Low-pass coefficient: fluid = slow, gentle following; 0 = near-instant. */
    private static float follow(float dt, float fluid) {
        return 1 - (float) Math.exp(-dt * (4 + 60 * (1 - fluid)));
    }

    private static float smooth(float x) { return x * x * (3 - 2 * x); }

    private static float clamp(float x) { return Math.max(0f, Math.min(1f, x)); }
}
