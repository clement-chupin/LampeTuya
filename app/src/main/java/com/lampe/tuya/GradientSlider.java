package com.lampe.tuya;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/** Thick pill-shaped slider whose track is a colour gradient. Value is 0..1. */
public class GradientSlider extends View {

    public interface Listener { void onChange(float value); }

    private final float dp;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String label = "";
    private final RectF rect = new RectF();
    private int[] colors = {0xFF000000, 0xFFFFFFFF};
    private boolean shaderDirty = true;
    private float value;
    private Listener listener;

    public GradientSlider(Context ctx) {
        super(ctx);
        dp = ctx.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3 * dp);
        ring.setColor(0xFFFFFFFF);
        knob.setColor(0x33000000);
        text.setColor(0xFFFFFFFF);
        text.setTextSize(13 * dp);
        text.setShadowLayer(3 * dp, 0, 0, 0xAA000000);
    }

    public void setLabel(String l) { label = l; invalidate(); }

    public void setListener(Listener l) { listener = l; }

    public void setColors(int... c) {
        colors = c;
        shaderDirty = true;
        invalidate();
    }

    public void setValue(float v) {
        value = Math.max(0f, Math.min(1f, v));
        invalidate();
    }

    public float getValue() { return value; }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) (52 * dp));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        shaderDirty = true;
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = getHeight(), r = h / 2;
        rect.set(0, 0, getWidth(), h);
        if (shaderDirty) {
            track.setShader(new LinearGradient(r, 0, getWidth() - r, 0, colors, null, Shader.TileMode.CLAMP));
            shaderDirty = false;
        }
        c.drawRoundRect(rect, r, r, track);
        c.drawText(label, r * 0.8f, r - (text.descent() + text.ascent()) / 2, text);
        float x = r + value * (getWidth() - 2 * r);
        c.drawCircle(x, r, r - 7 * dp, knob);
        c.drawCircle(x, r, r - 7 * dp, ring);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                float r = getHeight() / 2f;
                setValue((e.getX() - r) / (getWidth() - 2 * r));
                if (listener != null) listener.onChange(value);
                return true;
        }
        return super.onTouchEvent(e);
    }
}
