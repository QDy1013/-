package com.nopalou;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * 界面设计规范：统一色板、圆角背景、标签、圆点与头像等基础视觉元素。
 */
final class Ui {

    private Ui() {
    }

    // 背景色
    static final int BG = Color.parseColor("#0E1014");
    // 卡片/面板底色
    static final int SURFACE = Color.parseColor("#161A21");
    // 次级面板底色
    static final int SURFACE2 = Color.parseColor("#1C222B");
    // 描边色
    static final int STROKE = Color.parseColor("#262D39");
    // 主文字色
    static final int TEXT = Color.parseColor("#F3F5F9");
    // 次级文字色
    static final int SUB = Color.parseColor("#8A93A6");
    // 弱化文字色
    static final int MUTE = Color.parseColor("#5B6475");
    // 低紧急度强调色
    static final int TEAL = Color.parseColor("#5FD3A6");
    // 中紧急度强调色
    static final int AMBER = Color.parseColor("#F0B35A");
    // 较高紧急度强调色
    static final int ORANGE = Color.parseColor("#FF8A5C");
    // 高紧急度强调色
    static final int RED = Color.parseColor("#C9706B");
    // 信息类强调色
    static final int BLUE = Color.parseColor("#8FB8E0");

    /**
     * 按紧急程度返回强调色：0 最低，3 最高，未知级别退回次级文字色。
     */
    static int urgencyColor(int level) {
        switch (level) {
            case 0:
                return TEAL;
            case 1:
                return AMBER;
            case 2:
                return ORANGE;
            case 3:
                return RED;
            default:
                return SUB;
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
     * 生成带 1dp 描边的圆角背景，用于卡片、按钮等容器。
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
}
