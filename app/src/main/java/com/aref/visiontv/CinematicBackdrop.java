package com.aref.visiontv;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * Full-bleed cinematic background: featured artwork, atmospheric fallback derived from the
 * focused app, readability scrims, slow Ken Burns drift and crossfades between titles.
 */
final class CinematicBackdrop extends FrameLayout {
    private static final long CROSSFADE_MS = 650;
    private static final long DRIFT_MS = 26000;

    private final ImageView artA;
    private final ImageView artB;
    private final FallbackLayer fallback;
    private ImageView visible;
    private Animator drift;
    private boolean motionEnabled = true;
    private boolean driftActive;
    private int accent = 0xFF3B3B4E;

    CinematicBackdrop(Context context) {
        this(context, null);
    }

    CinematicBackdrop(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(Color.BLACK);
        setClipChildren(true);

        fallback = new FallbackLayer(context);
        addView(fallback, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        artA = artworkView(context);
        artB = artworkView(context);
        addView(artA, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(artB, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        View veil = new View(getContext());
        veil.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{0x1AFFD7A0, 0x0DFFB98A, 0x00000000}));
        veil.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(veil, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        addView(scrim(Gravity.TOP, dp(150),
                0x990B0908, 0x400B0908, 0x00000000),
                new LayoutParams(LayoutParams.MATCH_PARENT, dp(150), Gravity.TOP));

        View left = scrim(Gravity.LEFT, 0, 0xD90B0908, 0x660B0908, 0x00000000);
        LayoutParams leftParams = new LayoutParams(dp(760), LayoutParams.MATCH_PARENT, Gravity.LEFT);
        addView(left, leftParams);

        View bottom = scrim(Gravity.BOTTOM, 0,
                0x00000000, 0x00000000, 0xE60B0908, 0xFF0A0807);
        addView(bottom, new LayoutParams(LayoutParams.MATCH_PARENT, dp(330), Gravity.BOTTOM));
    }

    private ImageView artworkView(Context context) {
        ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setAlpha(0f);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private View scrim(int gravity, int height, int... colors) {
        View view = new View(getContext());
        boolean vertical = gravity == Gravity.TOP || gravity == Gravity.BOTTOM;
        android.graphics.drawable.GradientDrawable drawable =
                new android.graphics.drawable.GradientDrawable(
                        vertical ? android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
                                : android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        colors);
        view.setBackground(drawable);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    void setParallax(float offset) {
        offset = Math.max(-1f, Math.min(1f, offset));
        fallback.animate()
                .translationX(offset * dp(26))
                .setDuration(500)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    void setMotionEnabled(boolean enabled) {
        motionEnabled = enabled;
        if (!enabled) stopDrift();
        else if (visible != null && visible.getAlpha() > 0.5f) startDrift(visible);
    }

    void showFallback(Drawable icon, Bitmap scene, int accentColor) {
        accent = accentColor;
        fallback.setContent(icon, scene, accentColor);
        fallback.animate().alpha(1f).setDuration(280).start();
        if (visible != null) {
            visible.animate().alpha(0f).setDuration(CROSSFADE_MS).start();
            visible = null;
        }
        stopDrift();
    }

    void showArtwork(Bitmap bitmap, int accentColor) {
        if (bitmap == null) return;
        accent = accentColor;
        fallback.setAccent(accentColor);
        ImageView incoming = visible == artA ? artB : artA;
        incoming.animate().cancel();
        incoming.setImageBitmap(bitmap);
        incoming.setAlpha(0f);
        incoming.setScaleX(1.03f);
        incoming.setScaleY(1.03f);
        incoming.setTranslationX(0f);
        incoming.setTranslationY(0f);
        incoming.animate()
                .alpha(1f)
                .setDuration(CROSSFADE_MS)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        if (visible != null && visible != incoming) {
            visible.animate()
                    .alpha(0f)
                    .setDuration(CROSSFADE_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
        fallback.animate().alpha(0f).setDuration(420).start();
        visible = incoming;
        startDrift(incoming);
    }

    void clearArtwork() {
        if (visible != null) {
            visible.animate().alpha(0f).setDuration(CROSSFADE_MS).start();
            visible = null;
        }
        fallback.animate().alpha(1f).setDuration(CROSSFADE_MS).start();
        stopDrift();
    }

    int getAccent() {
        return accent;
    }

    private void startDrift(ImageView view) {
        stopDrift();
        if (!motionEnabled || view == null) return;
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(view, View.SCALE_X, 1.06f, 1.13f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1.06f, 1.13f);
        ObjectAnimator panX = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, -dp(10), dp(10));
        ObjectAnimator panY = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, dp(6), -dp(6));
        AnimatorSet set = new AnimatorSet();
        set.playTogether(scaleX, scaleY, panX, panY);
        set.setDuration(DRIFT_MS);
        set.setInterpolator(new LinearInterpolator());
        driftActive = true;
        set.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!driftActive || view != visible) return;
                animation.start();
            }
        });
        set.start();
        drift = set;
    }

    private void stopDrift() {
        driftActive = false;
        if (drift != null) {
            drift.cancel();
            drift = null;
        }
        for (ImageView view : new ImageView[]{artA, artB}) {
            view.animate().cancel();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopDrift();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Painterly fallback: accent bloom plus a ghosted app icon, so no frame is ever blank. */
    private static final class FallbackLayer extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Rect iconRect = new Rect();
        private Bitmap ghost;
        private Bitmap scene;
        private int accent = 0xFF3B3B4E;
        private int startAccent = accent;
        private int width;
        private int height;
        private Shader bloomA;
        private Shader bloomB;
        private Shader floor;
        private ValueAnimator tintAnimator;

        FallbackLayer(Context context) {
            super(context);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            iconPaint.setAlpha(64);
            paint.setDither(true);
        }

        void setContent(Drawable ghostSource, Bitmap sceneBitmap, int accentColor) {
            scene = sceneBitmap;
            ghost = sceneBitmap == null ? drawableToBitmap(ghostSource) : null;
            setAccent(accentColor);
        }

        void setAccent(int color) {
            if (color == accent) return;
            startAccent = accent;
            if (tintAnimator != null) tintAnimator.cancel();
            tintAnimator = ValueAnimator.ofFloat(0f, 1f);
            tintAnimator.setDuration(520);
            tintAnimator.setInterpolator(new DecelerateInterpolator());
            tintAnimator.addUpdateListener(animation -> {
                float fraction = (float) animation.getAnimatedValue();
                accent = blend(startAccent, color, fraction);
                rebuild();
                invalidate();
            });
            tintAnimator.start();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            width = w;
            height = h;
            rebuild();
        }

        private void rebuild() {
            if (width == 0 || height == 0) return;
            int warm = blend(accent, 0xFFE3B778, 0.15f);
            bloomA = new RadialGradient(width * 0.28f, height * 0.30f, width * 0.72f,
                    new int[]{withAlpha(warm, 0xC0), withAlpha(warm, 0x4D), 0x00000000},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            bloomB = new RadialGradient(width * 0.86f, height * 0.10f, width * 0.55f,
                    new int[]{withAlpha(warm, 0x66), 0x00000000},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP);
            floor = new LinearGradient(0, height * 0.55f, 0, height,
                    new int[]{0x00000000, withAlpha(warm, 0x33)}, null, Shader.TileMode.CLAMP);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.BLACK);
            if (scene != null) {
                paint.setShader(null);
                paint.setAlpha(110);
                float bitmapRatio = (float) scene.getWidth() / scene.getHeight();
                float viewRatio = (float) width / height;
                float drawWidth;
                float drawHeight;
                if (bitmapRatio > viewRatio) {
                    drawHeight = height;
                    drawWidth = height * bitmapRatio;
                } else {
                    drawWidth = width;
                    drawHeight = width / bitmapRatio;
                }
                iconRect.set((int) ((width - drawWidth) / 2f),
                        (int) ((height - drawHeight) / 2f),
                        (int) ((width + drawWidth) / 2f),
                        (int) ((height + drawHeight) / 2f));
                canvas.drawBitmap(scene, null, iconRect, paint);
                paint.setAlpha(255);
                if (bloomA != null) {
                    paint.setShader(bloomA);
                    canvas.drawRect(0, 0, width, height, paint);
                    paint.setShader(bloomB);
                    canvas.drawRect(0, 0, width, height, paint);
                    paint.setShader(floor);
                    canvas.drawRect(0, height * 0.55f, width, height, paint);
                    paint.setShader(null);
                }
                return;
            }
            if (bloomA != null) {
                paint.setShader(bloomA);
                canvas.drawRect(0, 0, width, height, paint);
                paint.setShader(bloomB);
                canvas.drawRect(0, 0, width, height, paint);
                paint.setShader(floor);
                canvas.drawRect(0, height * 0.55f, width, height, paint);
                paint.setShader(null);
            }
            if (ghost != null) {
                float size = Math.min(width * 0.44f, height * 0.92f);
                float left = width * 0.72f;
                float top = height * 0.16f;
                iconRect.set((int) left, (int) top, (int) (left + size), (int) (top + size));
                canvas.save();
                canvas.rotate(-7f, left + size / 2f, top + size / 2f);
                canvas.drawBitmap(ghost, null, iconRect, iconPaint);
                canvas.restore();
            }
        }

        private static Bitmap drawableToBitmap(Drawable drawable) {
            if (drawable == null) return null;
            Bitmap bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, 256, 256);
            drawable.draw(canvas);
            return bitmap;
        }

        private static int blend(int from, int to, float fraction) {
            return Color.argb(
                    (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * fraction),
                    (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * fraction),
                    (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * fraction),
                    (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * fraction));
        }

        private static int withAlpha(int color, int alpha) {
            return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
        }
    }
}
