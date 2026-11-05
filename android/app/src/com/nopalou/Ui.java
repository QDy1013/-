package com.nopalou;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * 界面设计规范（v0.13）。
 *
 * 方向：「纸面 + 墨色」。暖白纸色打底，白卡承载内容，墨黑做主文字；
 * 只有一个靛蓝强调色。红 / 橙 / 琥珀只表达时间压力，紫 / 青只表达卡片类型，
 * 每种颜色只承担一种含义，用户看到颜色就知道是什么情况。
 */
final class Ui {

    private Ui() {
    }

    // ---------- 中性色 ----------
    /** 页面底色：暖白纸色，比冷灰更柔和，白卡浮在上面层次清楚。 */
    static final int BG = Color.parseColor("#F5F4F0");
    /** 卡片 / 面板：纯白。 */
    static final int CARD = Color.parseColor("#FFFFFF");
    /** 输入框、次级按钮的填充色：比底色再深一档。 */
    static final int FILL = Color.parseColor("#ECEBE6");
    /** 分割线 / 描边。 */
    static final int LINE = Color.parseColor("#E6E4DE");
    /** 主文字：墨黑。 */
    static final int TEXT = Color.parseColor("#1C1C1E");
    /** 次级文字：白底对比约 5:1。 */
    static final int SUB = Color.parseColor("#64646B");
    /** 弱化文字：脚注、占位符。 */
    static final int MUTE = Color.parseColor("#8E8E93");

    // ---------- 强调色 ----------
    /** 唯一的品牌强调色：靛蓝。主按钮、选中态、链接。 */
    static final int ACCENT = Color.parseColor("#3D5AFE");
    static final int ACCENT_BG = Color.parseColor("#ECEFFF");
    /** 逾期 / 3 小时内。 */
    static final int RED = Color.parseColor("#E5484D");
    static final int RED_BG = Color.parseColor("#FDECEC");
    /** 24 小时内。 */
    static final int ORANGE = Color.parseColor("#D9730D");
    static final int ORANGE_BG = Color.parseColor("#FCF0E3");
    /** 待确认：琥珀色，提示“需要你看一眼”。 */
    static final int AMBER_TX = Color.parseColor("#9A6700");
    static final int AMBER_BG = Color.parseColor("#FBF3DB");
    /** 完成。 */
    static final int GREEN = Color.parseColor("#2E9E6A");
    /** 卡片类型：考试 / 接龙。 */
    static final int VIOLET = Color.parseColor("#7C4DDB");
    static final int VIOLET_BG = Color.parseColor("#F2ECFD");
    static final int TEAL = Color.parseColor("#0F8B86");
    static final int TEAL_BG = Color.parseColor("#E3F4F2");
    /** 底部撤销条的深色底。 */
    static final int INK = Color.parseColor("#24242A");

    // ---------- 兼容旧名（ProfileActivity 等仍在用） ----------
    static final int BLUE = ACCENT;
    static final int BLUE_BG = ACCENT_BG;
    static final int SURFACE = CARD;
    static final int SURFACE2 = FILL;
    static final int STROKE = LINE;
    static final int AMBER = ORANGE;

    /** 按紧急程度返回强调色：3 逾期红、2 三小时内红、1 一天内橙、0 中性。 */
    static int urgencyColor(int level) {
        switch (level) {
            case 3:
            case 2:
                return RED;
            case 1:
                return ORANGE;
            default:
                return SUB;
        }
    }

    static int urgencyBg(int level) {
        switch (level) {
            case 3:
            case 2:
                return RED_BG;
            case 1:
                return ORANGE_BG;
            default:
                return FILL;
        }
    }

    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    static int dp(Context c, float v) {
        return MainActivity.dp(c, v);
    }

    static GradientDrawable round(Context c, int fill, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static GradientDrawable roundStroke(Context c, int fill, int stroke, float radiusDp, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(Math.max(1, dp(c, strokeDp)), stroke);
        return g;
    }

    /** 只有上方两个角是圆的：底部面板用。 */
    static GradientDrawable topRound(Context c, int fill, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        float r = dp(c, radiusDp);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return g;
    }

    static Typeface medium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    /** 小标签：浅底深字，11sp 中粗。 */
    static TextView tag(Context c, String text, int textColor, int bgColor) {
        TextView t = new TextView(c);
        t.setText(text == null ? "" : text);
        t.setTextSize(11);
        t.setTypeface(medium());
        t.setTextColor(textColor);
        t.setIncludeFontPadding(false);
        t.setBackground(round(c, bgColor, 6));
        t.setPadding(dp(c, 6), dp(c, 3), dp(c, 6), dp(c, 3));
        return t;
    }

    static TextView pill(Context c, String text, int textColor) {
        return tag(c, text, textColor, withAlpha(textColor, 28));
    }

    static View dot(Context c, int color, float sizeDp) {
        View v = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        int size = dp(c, sizeDp);
        v.setLayoutParams(new ViewGroup.LayoutParams(size, size));
        return v;
    }

    static GradientDrawable disc(Context c, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    static View hairline(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    static int deep(int color) {
        float f = 0.72f;
        return Color.rgb((int) (Color.red(color) * f),
                (int) (Color.green(color) * f), (int) (Color.blue(color) * f));
    }

    static Drawable ripple(Context c, Drawable content, int rippleColor, float radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(c, radiusDp));
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    static Drawable rippleCircle(Context c, int rippleColor) {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask);
    }

    /** 按压反馈统一用墨色 8% 透明度，不在白底上泛出蓝光。 */
    static int press() {
        return withAlpha(TEXT, 20);
    }

    static Drawable searchGlyph(Context c, int color, float sizeDp) {
        return new SearchGlyph(dp(c, sizeDp), color);
    }

    /** 纯 Canvas 绘制的放大镜。 */
    private static final class SearchGlyph extends Drawable {
        private final int size;
        private final Paint paint;

        SearchGlyph(int size, int color) {
            this.size = Math.max(1, size);
            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, size * 0.1f));
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float side = Math.min(b.width(), b.height());
            float cx = b.left + side * 0.43f;
            float cy = b.top + side * 0.43f;
            float r = side * 0.28f;
            canvas.drawCircle(cx, cy, r, paint);
            canvas.drawLine(cx + r * 0.72f, cy + r * 0.72f,
                    b.left + side * 0.86f, b.top + side * 0.86f, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
    }

    /**
     * 完成圈：未完成是空心圆环（颜色表达紧急程度），
     * 完成时实心填充并画出白色对勾。progress 0→1 用于点击时的填充动画。
     */
    static final class CheckCircle extends Drawable {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int diameter;
        private float progress;

        CheckCircle(Context c, int ringColor, int fillColor, float diameterDp, boolean checked) {
            diameter = dp(c, diameterDp);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(c, 1.8f));
            ring.setColor(ringColor);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(fillColor);
            tick.setStyle(Paint.Style.STROKE);
            tick.setStrokeWidth(dp(c, 2f));
            tick.setStrokeCap(Paint.Cap.ROUND);
            tick.setStrokeJoin(Paint.Join.ROUND);
            tick.setColor(Color.WHITE);
            progress = checked ? 1f : 0f;
        }

        void setProgress(float p) {
            progress = Math.max(0f, Math.min(1f, p));
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float r = diameter / 2f;
            float inner = r - ring.getStrokeWidth() / 2f;
            if (progress < 1f) canvas.drawCircle(cx, cy, inner, ring);
            if (progress > 0f) {
                canvas.drawCircle(cx, cy, r * Math.min(1f, 0.4f + progress * 0.6f), fill);
                float t = Math.max(0f, (progress - 0.35f) / 0.65f);
                if (t > 0f) {
                    float x0 = cx - r * 0.42f, y0 = cy + r * 0.02f;
                    float x1 = cx - r * 0.1f, y1 = cy + r * 0.34f;
                    float x2 = cx + r * 0.45f, y2 = cy - r * 0.3f;
                    path.reset();
                    path.moveTo(x0, y0);
                    if (t < 0.4f) {
                        float k = t / 0.4f;
                        path.lineTo(x0 + (x1 - x0) * k, y0 + (y1 - y0) * k);
                    } else {
                        float k = (t - 0.4f) / 0.6f;
                        path.lineTo(x1, y1);
                        path.lineTo(x1 + (x2 - x1) * k, y1 + (y2 - y1) * k);
                    }
                    canvas.drawPath(path, tick);
                }
            }
        }

        @Override public void setAlpha(int alpha) { ring.setAlpha(alpha); fill.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { ring.setColorFilter(cf); fill.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return diameter; }
        @Override public int getIntrinsicHeight() { return diameter; }
    }
}
