package com.loolc.shower_app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.PathInterpolator;

/** 从主题按钮开始，以圆形扩散显示新配色。 */
final class ThemeReveal extends Drawable {
    private final View host;
    private final Paint previousTheme = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outsideCircle = new Path();
    private final float centerX, centerY;
    private final ValueAnimator animator;
    private float radius;

    static ThemeReveal capture(View host, View button) {
        int width = host.getWidth();
        int height = host.getHeight();
        if (!ValueAnimator.areAnimatorsEnabled() || width <= 0 || height <= 0) return null;

        int[] hostPosition = new int[2];
        int[] buttonPosition = new int[2];
        host.getLocationInWindow(hostPosition);
        button.getLocationInWindow(buttonPosition);
        float x = buttonPosition[0] - hostPosition[0] + button.getWidth() / 2f;
        float y = buttonPosition[1] - hostPosition[1] + button.getHeight() / 2f;
        Bitmap snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        host.draw(new Canvas(snapshot));
        return new ThemeReveal(host, snapshot, x, y);
    }

    private ThemeReveal(View host, Bitmap snapshot, float x, float y) {
        this.host = host;
        centerX = x;
        centerY = y;
        int width = snapshot.getWidth();
        int height = snapshot.getHeight();
        setBounds(0, 0, width, height);
        previousTheme.setShader(new BitmapShader(snapshot, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float finalRadius = (float) Math.ceil(Math.hypot(Math.max(x, width - x), Math.max(y, height - y)));
        animator = ValueAnimator.ofFloat(0f, finalRadius);
        animator.setDuration(400);
        animator.setInterpolator(new PathInterpolator(0f, 0f, .58f, 1f));
        animator.addUpdateListener(value -> {
            radius = (float) value.getAnimatedValue();
            invalidateSelf();
        });
    }

    void start(Runnable finished) {
        host.getOverlay().add(this);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                host.getOverlay().remove(ThemeReveal.this);
                finished.run();
            }
        });
        animator.start();
    }

    void cancel() {
        animator.cancel();
    }

    @Override public void draw(Canvas canvas) {
        int saved = canvas.save();
        canvas.clipRect(getBounds());
        outsideCircle.rewind();
        outsideCircle.setFillType(Path.FillType.INVERSE_WINDING);
        outsideCircle.addCircle(centerX, centerY, radius, Path.Direction.CW);
        canvas.drawPath(outsideCircle, previousTheme);
        canvas.restoreToCount(saved);
    }

    @Override public void setAlpha(int alpha) {
        previousTheme.setAlpha(alpha);
    }

    @Override public void setColorFilter(ColorFilter filter) {
        previousTheme.setColorFilter(filter);
    }

    @SuppressWarnings("deprecation")
    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
