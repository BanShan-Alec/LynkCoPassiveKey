package com.luca.geelykeyradar;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 走动冷暖雷达视图 (Hot/Cold Radar).
 * 借鉴开源 RippleBackground 机制，采用 RenderThread / ValueAnimator 硬件加速驱动多层波纹脉冲；
 * 采用 ArgbEvaluator 实现 250ms 连续色温平滑流动；onDraw 期 0 对象分配，避免 GC 抖动。
 */
public class HotColdRadarView extends View {

    private static final int RIPPLE_COUNT = 3;
    private final RadarVisualModel visualModel = new RadarVisualModel();
    private final ArgbEvaluator argbEvaluator = new ArgbEvaluator();

    private final Paint coreFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coreStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statusTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint secondaryTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Drawable carDrawable;
    private ValueAnimator pulseAnimator;
    private ValueAnimator colorAnimator;

    private int currentColor = PaseoTheme.COLOR_SURFACE_4;
    private int currentContainerColor = PaseoTheme.COLOR_SURFACE_2;
    private float pulsePhase = 0f;
    private long currentPulseDuration = RadarVisualModel.PULSE_SLOW_MS;

    private String statusText = "正在搜索爱车钥匙...";
    private String secondaryTechText = "-- dBm · 门限 -75 dBm";

    public HotColdRadarView(Context context) {
        super(context);
        init();
    }

    public HotColdRadarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        carDrawable = getContext().getDrawable(R.drawable.ic_car);
        if (carDrawable != null) {
            carDrawable = carDrawable.mutate();
        }

        coreFillPaint.setStyle(Paint.Style.FILL);
        coreFillPaint.setColor(currentContainerColor);

        coreStrokePaint.setStyle(Paint.Style.STROKE);
        coreStrokePaint.setStrokeWidth(dp(2));
        coreStrokePaint.setColor(currentColor);

        ripplePaint.setStyle(Paint.Style.STROKE);
        ripplePaint.setStrokeWidth(dp(2));
        ripplePaint.setColor(currentColor);

        statusTextPaint.setColor(PaseoTheme.COLOR_TEXT);
        statusTextPaint.setTextSize(sp(15));
        statusTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        statusTextPaint.setTextAlign(Paint.Align.CENTER);

        secondaryTextPaint.setColor(PaseoTheme.COLOR_TEXT_TERTIARY);
        secondaryTextPaint.setTextSize(sp(12));
        secondaryTextPaint.setTypeface(Typeface.DEFAULT);
        secondaryTextPaint.setTextAlign(Paint.Align.CENTER);

        startPulseAnimation(currentPulseDuration);
    }

    private void startPulseAnimation(long durationMs) {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
        }
        if (durationMs <= 0) {
            invalidate();
            return;
        }
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(durationMs);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setInterpolator(new LinearInterpolator());
        pulseAnimator.addUpdateListener(anim -> {
            pulsePhase = (Float) anim.getAnimatedValue();
            invalidate();
        });
        pulseAnimator.start();
    }

    /**
     * 根据底层状态快照更新雷达状态。
     */
    public void updateState(PassiveKeyService.Status status) {
        if (status == null) {
            updateFromModel(visualModel.evaluate(false, false, false, -127, false, -75f, -82f));
            return;
        }

        RadarVisualModel.State state = visualModel.evaluate(
                status.serviceEnabled,
                status.bluetoothOn,
                status.geelyConnected,
                status.rssi,
                status.chargingLockBlocked,
                status.unlockDbm,
                status.lockDbm
        );
        updateFromModel(state);
    }

    private void updateFromModel(RadarVisualModel.State state) {
        this.statusText = state.statusText;
        this.secondaryTechText = state.secondaryTechText;

        // 色温连续过渡
        if (state.primaryColor != currentColor) {
            animateColorTransition(state.primaryColor, state.containerColor);
        }

        // 脉冲频率动态匹配
        if (state.pulseDurationMs != currentPulseDuration) {
            currentPulseDuration = state.pulseDurationMs;
            startPulseAnimation(currentPulseDuration);
        }

        // 跨入解锁线触觉振动
        if (state.triggerHaptic) {
            triggerHapticFeedback();
        }

        invalidate();
    }

    private void animateColorTransition(int targetColor, int targetContainerColor) {
        if (colorAnimator != null && colorAnimator.isRunning()) {
            colorAnimator.cancel();
        }
        int fromColor = currentColor;
        int fromContainer = currentContainerColor;
        colorAnimator = ValueAnimator.ofFloat(0f, 1f);
        colorAnimator.setDuration(250L);
        colorAnimator.addUpdateListener(anim -> {
            float fraction = anim.getAnimatedFraction();
            currentColor = (Integer) argbEvaluator.evaluate(fraction, fromColor, targetColor);
            currentContainerColor = (Integer) argbEvaluator.evaluate(fraction, fromContainer, targetContainerColor);
            coreFillPaint.setColor(currentContainerColor);
            coreStrokePaint.setColor(currentColor);
            ripplePaint.setColor(currentColor);
            invalidate();
        });
        colorAnimator.start();
    }

    private void triggerHapticFeedback() {
        try {
            Vibrator vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(30L);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w / 2.0f;
        float cy = h * 0.44f;
        float coreRadius = dp(44);
        float maxRippleRadius = Math.min(w / 2.0f - dp(16), cy - dp(10));
        if (maxRippleRadius <= coreRadius) {
            maxRippleRadius = coreRadius + dp(40);
        }

        // 1. 绘制扩散波纹 (RippleBackground 机制)
        if (currentPulseDuration > 0) {
            for (int i = 0; i < RIPPLE_COUNT; i++) {
                float phase = (pulsePhase + (float) i / RIPPLE_COUNT) % 1.0f;
                float radius = coreRadius + phase * (maxRippleRadius - coreRadius);
                int alpha = (int) ((1.0f - phase) * 160);
                ripplePaint.setAlpha(Math.max(0, Math.min(255, alpha)));
                canvas.drawCircle(cx, cy, radius, ripplePaint);
            }
        }

        // 2. 绘制核心圆形底色与边框
        canvas.drawCircle(cx, cy, coreRadius, coreFillPaint);
        canvas.drawCircle(cx, cy, coreRadius, coreStrokePaint);

        // 3. 绘制中心车载矢量图标
        if (carDrawable != null) {
            int iconSize = dp(40);
            int left = (int) (cx - iconSize / 2.0f);
            int top = (int) (cy - iconSize / 2.0f);
            carDrawable.setBounds(left, top, left + iconSize, top + iconSize);
            carDrawable.setTint(currentColor);
            carDrawable.draw(canvas);
        }

        // 4. 绘制中心下方的主状态文案
        float textY = cy + coreRadius + dp(32);
        canvas.drawText(statusText, cx, textY, statusTextPaint);

        // 5. 绘制低层级技术读数 (实时 RSSI 与解锁门限)
        float techY = textY + dp(22);
        canvas.drawText(secondaryTechText, cx, techY, secondaryTextPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
        }
        if (colorAnimator != null) {
            colorAnimator.cancel();
        }
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
