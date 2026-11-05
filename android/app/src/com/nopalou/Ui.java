package com.nopalou;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * 界面设计规范：统一色板、圆角背景、标签、圆点与头像等基础视觉元素。
 *
 * 整体风格为「简约高级的浅色风格」：以近白灰背景打底，纯白卡片承载内容，
 * 极浅分割线勾勒结构，文字用近黑到灰的四级层次拉开强弱，
 * 强调色只在必要处小面积出现，避免大面积艳色造成的廉价感。
 */
final class Ui {

    private Ui() {
    }

    // ---------- 色板 ----------
    static final int HERO = Color.parseColor("#00000000");
    static final int HERO_SUB = Color.parseColor("#718093");
    static final int HERO_TEXT = Color.parseColor("#57677A");
    static final int HERO_STATUS = Color.parseColor("#52748E");

    // 页面底色：极浅冷灰，比纯白略暗，让白色卡片能浮起来
    static final int BG = Color.parseColor("#F5F7FA");
    // 卡片/面板底色：纯白，作为内容的主要承载面
    static final int CARD = Color.parseColor("#FFFFFF");
    // 分割线：比背景略深的冷灰，只做结构提示，不抢视线
    static final int LINE = Color.parseColor("#E0E6ED");
    // 主文字色：深墨，用于标题与关键信息
    static final int TEXT = Color.parseColor("#17212D");
    // 次级文字色：中性灰，用于说明文字与副标题（白底对比约 5:1）
    static final int SUB = Color.parseColor("#66778A");
    // 弱化文字色：更浅的灰，用于来源、脚注等最次要信息
    static final int MUTE = Color.parseColor("#718093");
    // 主强调色：靛蓝，用于主按钮、来源标签与信息态
    static final int BLUE = Color.parseColor("#315F88");
    // 高危强调色：克制的红，用于逾期与高紧急度
    static final int RED = Color.parseColor("#E5484D");
    // 中等强调色：暖橙，用于临近截止
    static final int ORANGE = Color.parseColor("#D97A16");
    // 成功/完成色：低饱和绿，用于已完成状态
    static final int GREEN = Color.parseColor("#1E9E6A");
    // 琥珀色浅底：提醒类标签的背景
    static final int AMBER_BG = Color.parseColor("#EFF5FC");
    // 琥珀色文字：与 AMBER_BG 配套，保证浅底上的可读性
    static final int AMBER_TX = Color.parseColor("#315F88");
    // 蓝色浅底：信息类标签/区域的背景
    static final int BLUE_BG = Color.parseColor("#DDEFFD");
    // 橙色浅底：待确认类标签的背景
    static final int ORANGE_BG = Color.parseColor("#FFF1DF");
    // 引文底色：详情页原文面板，比页面底略灰一点
    static final int QUOTE = Color.parseColor("#F7F8FA");

    // ---------- 兼容旧名（统一指向新色板，避免调用方改动） ----------

    // 旧「卡片底色」→ 新卡片色
    static final int SURFACE = CARD;
    // 旧「次级面板底色」：比卡片略灰一点，用于按钮等次级容器
    static final int SURFACE2 = Color.parseColor("#EFF4F9");
    // 旧「描边色」→ 新分割线色
    static final int STROKE = LINE;
    // 旧「低紧急度强调色」→ 新品牌蓝（浅色风格下绿色留给"完成"语义）
    static final int TEAL = BLUE;
    // 旧「中紧急度强调色」→ 新暖橙
    static final int AMBER = ORANGE;

    /**
     * 按紧急程度返回强调色：3 最高、0 最低。
     * 高→红、中→橙、低→蓝，未知级别退回弱化灰。
     */
    static int urgencyColor(int level) {
        switch (level) {
            case 3:
                return RED;
            case 2:
                return ORANGE;
            case 1:
                return BLUE;
            case 0:
                return BLUE;
            default:
                return MUTE;
        }
    }

    /**
     * 保留颜色原有 RGB，替换为指定透明度（0~255），用于弱化背景与描边。
     */
    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    /**
     * 生成纯色圆角背景，radiusDp 为圆角半径（dp）。
     */
    static GradientDrawable round(Context c, int fill, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(MainActivity.dp(c, radiusDp));
        return g;
    }

    /**
     * 生成带描边的圆角背景，用于卡片、按钮等容器。
     * 浅色风格下描边通常传 LINE，宽度 1dp 即可。
     */
    static GradientDrawable roundStroke(Context c, int fill, int stroke, float radiusDp, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(MainActivity.dp(c, radiusDp));
        g.setStroke(MainActivity.dp(c, strokeDp), stroke);
        return g;
    }

    /**
     * 生成从左上到右下的线性渐变圆角背景，用于强调区域。
     * 浅色下建议用同色系的两个很浅的颜色，避免渐变过重。
     */
    static GradientDrawable gradient(Context c, int from, int to, float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{from, to});
        g.setCornerRadius(MainActivity.dp(c, radiusDp));
        return g;
    }

    /**
     * 生成小胶囊标签：11 号粗体文字，文字色半透明作底色。
     */
    static TextView pill(Context c, String text, int textColor) {
        TextView t = new TextView(c);
        t.setText(text == null ? "" : text);
        t.setTextSize(11);
        t.setTypeface(null, 1);
        t.setTextColor(textColor);
        t.setBackground(round(c, withAlpha(textColor, 40), 20));
        t.setPadding(MainActivity.dp(c, 9), MainActivity.dp(c, 3), MainActivity.dp(c, 9), MainActivity.dp(c, 3));
        return t;
    }

    /**
     * 生成圆形色点，sizeDp 同时作为宽高（dp），常用于紧急度标记。
     */
    static View dot(Context c, int color, float sizeDp) {
        View v = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        int size = MainActivity.dp(c, sizeDp);
        v.setLayoutParams(new ViewGroup.LayoutParams(size, size));
        return v;
    }

    /**
     * 生成直径 34dp 的圆形头像：取名字首字符居中显示，背景为同色半透明。
     */
    static TextView avatar(Context c, String name, int color) {
        TextView t = new TextView(c);
        String n = name == null ? "" : name.trim();
        t.setText(n.isEmpty() ? "?" : n.substring(0, 1));
        t.setTextSize(14);
        t.setTypeface(null, 1);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(withAlpha(color, 50));
        t.setBackground(g);
        int size = MainActivity.dp(c, 34);
        t.setLayoutParams(new ViewGroup.LayoutParams(size, size));
        t.setIncludeFontPadding(false);
        return t;
    }

    /**
     * 给视图加一层极淡的投影，让白色卡片在浅灰背景上有轻微的悬浮感。
     * 只抬 1dp，过重的阴影会让浅色界面显脏。
     */
    static void lift(View v, Context c) {
        v.setElevation(MainActivity.dp(c, 1));
    }

    /**
     * 生成 1px 高的极细分割线，宽度由外部布局决定（通常是 MATCH_PARENT）。
     */
    static View hairline(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    /**
     * 生成空心圆环，用于进度环、未选中态、图标外圈等。
     *
     * @param color    描边颜色
     * @param sizeDp   圆环直径（dp）
     * @param strokeDp 描边宽度（dp）
     */
    static GradientDrawable ring(Context c, int color, float sizeDp, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.TRANSPARENT);
        g.setStroke(MainActivity.dp(c, strokeDp), color);
        int size = MainActivity.dp(c, sizeDp);
        g.setSize(size, size);
        return g;
    }

    /**
     * 生成实心圆，作为纯色圆点/圆底的背景，实际尺寸随视图边界拉伸。
     */
    static GradientDrawable disc(Context c, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /**
     * 同色系加深：深色小字压在浅色胶囊上时保证对比，不另起一套色板。
     */
    static int deep(int color) {
        float f = 0.72f;
        return Color.rgb((int) (Color.red(color) * f),
                (int) (Color.green(color) * f), (int) (Color.blue(color) * f));
    }

    /**
     * 原生水波纹：content 是常态背景（可为 null），rippleColor 是按压色。
     * radiusDp 用圆角遮罩裁切，避免方形水波溢到圆角按钮外面。
     */
    static Drawable ripple(Context c, Drawable content, int rippleColor, float radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(MainActivity.dp(c, radiusDp));
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    /**
     * 圆形水波纹：用于完成圈这类小圆点控件，点击范围由视图自身决定。
     */
    static Drawable rippleCircle(Context c, int rippleColor) {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask);
    }

    /**
     * 矢量放大镜：避免引入图片资源，随字号缩放，颜色跟主题走。
     */
    static Drawable searchGlyph(Context c, int color, float sizeDp) {
        return new SearchGlyph(MainActivity.dp(c, sizeDp), color);
    }

    /** 纯 Canvas 绘制的放大镜，无外部资源依赖。 */
    private static final class SearchGlyph extends Drawable {
        private final int size;
        private final Paint paint;

        SearchGlyph(int size, int color) {
            this.size = Math.max(1, size);
            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, size * 0.095f));
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float side = Math.min(b.width(), b.height());
            float cx = b.left + side * 0.43f;
            float cy = b.top + side * 0.43f;
            float r = side * 0.27f;
            canvas.drawCircle(cx, cy, r, paint);
            canvas.drawLine(cx + r * 0.72f, cy + r * 0.72f,
                    b.left + side * 0.85f, b.top + side * 0.85f, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }
    }
}
