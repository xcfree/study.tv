package tv.study.guard;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.StateListAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class TvUi {
    static final int INK = 0xff321c16;
    static final int WHITE = 0xfffff5e8;
    static final int MUTED = 0xffdac2b1;
    static final int ACCENT = 0xffffc38c;
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static LinearLayout column(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setClipChildren(false);
        view.setClipToPadding(false);
        return view;
    }

    static LinearLayout row(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setClipChildren(false);
        view.setClipToPadding(false);
        return view;
    }

    static TextView text(Context context, String text, int size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    static LinearLayout questionHeader(Context context, String progress, String subject) {
        LinearLayout row = row(context);
        row.setGravity(Gravity.TOP);
        row.setBaselineAligned(false);
        row.addView(text(context, progress, 15, ACCENT, true),
                new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView name = text(context, subject, 16, ACCENT, true);
        name.setGravity(Gravity.END | Gravity.TOP);
        row.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1));
        return row;
    }

    private static GradientDrawable surface(Context context, int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radius));
        return drawable;
    }

    static GradientDrawable panel(Context context, int radius) {
        return surface(context, 0xeb3b2720, radius);
    }

    static LinearLayout panelColumn(Context context) {
        LinearLayout view = column(context);
        int padding = dp(context, 24);
        view.setPadding(padding, padding, padding, padding);
        view.setBackground(panel(context, 28));
        return view;
    }

    static GradientDrawable dockPanel(Context context) {
        return surface(context, 0xf02d1e19, 28);
    }

    static Drawable backdrop() {
        return new Dusk();
    }

    static Drawable wallpaperShade() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x971d120f, 0xa3221511, 0xde1b110e});
    }

    private static StateListDrawable focusFill(Context context, int radius, boolean primary) {
        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[]{-android.R.attr.state_enabled},
                surface(context, 0xff49352d, radius));
        drawable.addState(new int[]{android.R.attr.state_pressed},
                surface(context, 0xffe9ab77, radius));
        drawable.addState(new int[]{android.R.attr.state_focused},
                surface(context, 0xffffd6a7, radius));
        drawable.addState(new int[]{}, primary
                ? surface(context, 0xff964e34, radius)
                : surface(context, 0xff54392e, radius));
        return drawable;
    }

    static void control(View view, int radius, float focusScale) {
        control(view, radius, focusScale, false);
    }

    private static void control(View view, int radius, float focusScale, boolean primary) {
        Context context = view.getContext();
        view.setBackgroundTintList(null);
        view.setBackground(focusFill(context, radius, primary));
        if (Build.VERSION.SDK_INT >= 26) view.setDefaultFocusHighlightEnabled(false);
        view.setElevation(0);
        StateListAnimator motion = new StateListAnimator();
        motion.addState(new int[]{-android.R.attr.state_enabled}, lift(1f, 0, 120));
        motion.addState(new int[]{android.R.attr.state_pressed}, lift(.98f, dp(context, 1), 85));
        motion.addState(new int[]{android.R.attr.state_focused}, lift(focusScale, dp(context, 5), 150));
        motion.addState(new int[]{}, lift(1f, 0, 150));
        view.setStateListAnimator(motion);
    }

    private static ObjectAnimator lift(float scale, int height, int duration) {
        ObjectAnimator animator = new ObjectAnimator();
        animator.setValues(
                PropertyValuesHolder.ofFloat(View.SCALE_X, scale),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, scale),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Z, height));
        animator.setDuration(duration);
        return animator;
    }

    static ColorStateList focusText() {
        return new ColorStateList(
                new int[][]{ {-android.R.attr.state_enabled}, {android.R.attr.state_focused},
                        {android.R.attr.state_pressed}, {} },
                new int[]{0xffb79b8c, INK, INK, WHITE});
    }

    static Button button(Context context, String label, Runnable click) {
        return button(context, label, click, false);
    }

    static Button primaryButton(Context context, String label, Runnable click) {
        return button(context, label, click, true);
    }

    private static Button button(Context context, String label, Runnable click, boolean primary) {
        Button view = new Button(context);
        view.setId(View.generateViewId());
        view.setAllCaps(false);
        view.setText(label);
        view.setTextSize(17);
        view.setLetterSpacing(0);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(focusText());
        control(view, 15, 1.035f, primary);
        view.setMinHeight(dp(context, 46));
        view.setMinWidth(dp(context, 56));
        view.setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6));
        view.setOnClickListener(v -> click.run());
        return view;
    }

    static TextView tab(Context context, String label, Runnable click) {
        return tab(context, label, click, false);
    }

    static TextView dateTab(Context context, String label, Runnable click) {
        return tab(context, label, click, true);
    }

    private static TextView tab(Context context, String label, Runnable click, boolean coffeeSelected) {
        TextView view = text(context, label, 17, MUTED, true);
        view.setId(View.generateViewId());
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(context, 46));
        view.setPadding(dp(context, 16), dp(context, 6), dp(context, 16), dp(context, 8));
        view.setFocusable(true);
        view.setOnClickListener(v -> click.run());
        view.setTextColor(new ColorStateList(new int[][]{
                {android.R.attr.state_selected}, {android.R.attr.state_focused}, {}
        }, new int[]{ACCENT, WHITE, MUTED}));

        StateListDrawable highlight = new StateListDrawable();
        if (coffeeSelected) {
            highlight.addState(new int[]{android.R.attr.state_selected}, panel(context, 4));
        }
        highlight.addState(new int[]{android.R.attr.state_pressed}, surface(context, 0x44ffc38c, 4));
        highlight.addState(new int[]{android.R.attr.state_focused}, surface(context, 0x30ffc38c, 4));
        highlight.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        StateListDrawable underline = new StateListDrawable();
        underline.addState(new int[]{android.R.attr.state_selected}, new ColorDrawable(ACCENT));
        underline.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));

        LayerDrawable background = new LayerDrawable(new Drawable[]{highlight, underline});
        background.setLayerHeight(1, dp(context, 2));
        background.setLayerGravity(1, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        view.setBackground(background);
        if (Build.VERSION.SDK_INT >= 26) view.setDefaultFocusHighlightEnabled(false);
        return view;
    }

    static CheckBox checkbox(Context context, String label, boolean checked) {
        CheckBox view = new CheckBox(context);
        view.setId(View.generateViewId());
        view.setText(label);
        view.setTextSize(17);
        view.setTextColor(focusText());
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        view.setButtonTintList(null);
        view.setButtonDrawable(new CheckboxMark(context));
        view.setChecked(checked);
        view.setMinHeight(dp(context, 54));
        control(view, 12, 1.015f);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{-android.R.attr.state_enabled}, surface(context, 0xff35271f, 12));
        background.addState(new int[]{android.R.attr.state_pressed}, surface(context, 0xffe9ab77, 12));
        background.addState(new int[]{android.R.attr.state_focused},
                surface(context, 0xffffd6a7, 12));
        background.addState(new int[]{android.R.attr.state_checked},
                surface(context, 0xff5b3929, 12));
        background.addState(new int[]{}, surface(context, 0xff433027, 12));
        view.setBackground(background);
        view.setPadding(0, dp(context, 10), dp(context, 16), dp(context, 10));
        return view;
    }

    private static final class CheckboxMark extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tick = new Path();
        private final float scale;
        private int alpha = 255;

        CheckboxMark(Context context) {
            scale = context.getResources().getDisplayMetrics().density;
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public boolean isStateful() { return true; }
        @Override protected boolean onStateChange(int[] state) { invalidateSelf(); return true; }
        @Override public int getIntrinsicWidth() { return Math.round(54 * scale); }
        @Override public int getIntrinsicHeight() { return Math.round(26 * scale); }

        private boolean hasState(int value) {
            for (int state : getState()) if (state == value) return true;
            return false;
        }

        @Override public void draw(Canvas canvas) {
            boolean checked = hasState(android.R.attr.state_checked);
            boolean focused = hasState(android.R.attr.state_focused);
            boolean enabled = hasState(android.R.attr.state_enabled);
            // CompoundButton positions its button drawable at the edge on older TVs.
            float x = getBounds().left + 17 * scale;
            float y = getBounds().top + (getBounds().height() - 24 * scale) / 2;
            int color = !enabled ? 0xffa68b77 : focused ? INK : checked ? ACCENT : MUTED;
            paint.setStyle(checked ? Paint.Style.FILL : Paint.Style.STROKE);
            paint.setStrokeWidth(2 * scale);
            paint.setColor(color);
            paint.setAlpha(alpha);
            canvas.drawRoundRect(x, y, x + 24 * scale, y + 24 * scale, 5 * scale, 5 * scale, paint);
            if (checked) {
                paint.setColor(focused ? WHITE : INK);
                paint.setAlpha(alpha);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.5f * scale);
                tick.reset();
                tick.moveTo(x + 5 * scale, y + 12 * scale);
                tick.lineTo(x + 10 * scale, y + 17 * scale);
                tick.lineTo(x + 19 * scale, y + 7 * scale);
                canvas.drawPath(tick, paint);
            }
        }

        @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static void add(LinearLayout column, View child, int marginTop) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(MATCH, WRAP);
        layout.topMargin = dp(column.getContext(), marginTop);
        column.addView(child, layout);
    }

    static void gap(LinearLayout row, View child, int marginStart) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(WRAP, WRAP);
        layout.leftMargin = dp(row.getContext(), marginStart);
        row.addView(child, layout);
    }

    static ScrollView scroll(Context context, LinearLayout content) {
        ScrollView view = new ScrollView(context);
        view.setFillViewport(true);
        view.setClipToPadding(false);
        view.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));
        return view;
    }

    static void focus(View view) {
        if (view != null) view.post(view::requestFocus);
    }

    static void immersive(View view) {
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    static String duration(long millis) {
        long seconds = (millis + 999) / 1000;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private static final class Dusk extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path farHill = new Path();
        private final Path nearHill = new Path();
        private Shader sky, light, farSlope, nearSlope;

        @Override
        protected void onBoundsChange(Rect bounds) {
            float w = bounds.width();
            float h = bounds.height();
            if (w <= 0 || h <= 0) return;
            sky = new LinearGradient(0, 0, w, h, 0xff28191c, 0xff693b32, Shader.TileMode.CLAMP);
            light = new RadialGradient(.76f * w, .43f * h, .52f * w,
                    new int[]{0xffe0a06e, 0xffab5d46, 0x00ab5d46},
                    new float[]{0f, .35f, 1f}, Shader.TileMode.CLAMP);

            farSlope = new LinearGradient(0, h, w, .40f * h, 0xff4e2a25, 0xffa8573c, Shader.TileMode.CLAMP);
            farHill.reset();
            farHill.moveTo(0, .69f * h);
            farHill.cubicTo(.23f * w, .47f * h, .37f * w, .73f * h, .57f * w, .65f * h);
            farHill.cubicTo(.72f * w, .59f * h, .84f * w, .43f * h, w, .50f * h);
            farHill.lineTo(w, h);
            farHill.lineTo(0, h);
            farHill.close();

            nearSlope = new LinearGradient(0, h, w, .63f * h, 0xff271918, 0xff6b382e, Shader.TileMode.CLAMP);
            nearHill.reset();
            nearHill.moveTo(0, .77f * h);
            nearHill.cubicTo(.20f * w, .69f * h, .33f * w, .93f * h, .59f * w, .78f * h);
            nearHill.cubicTo(.78f * w, .66f * h, .86f * w, .69f * h, w, .74f * h);
            nearHill.lineTo(w, h);
            nearHill.lineTo(0, h);
            nearHill.close();
        }

        @Override
        public void draw(Canvas canvas) {
            if (getBounds().isEmpty()) return;
            int saved = canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            paint.setShader(sky);
            canvas.drawRect(0, 0, getBounds().width(), getBounds().height(), paint);
            paint.setShader(light);
            canvas.drawRect(0, 0, getBounds().width(), getBounds().height(), paint);
            paint.setShader(farSlope);
            canvas.drawPath(farHill, paint);
            paint.setShader(nearSlope);
            canvas.drawPath(nearHill, paint);
            canvas.restoreToCount(saved);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}
