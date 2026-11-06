package com.nopalou.replay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;

import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * 模拟学习通、知到、雨课堂发的通知，用来在真机上跑通「平台通知 → 出卡」的整条流程。
 *
 * 通知仍由回放器发出，但在 extras 里写上 nopalou.asPackage = 平台包名；
 * 楼外只对回放器发来的通知认这个字段，其他 App 写了也无效。
 *
 * 用法：
 * adb shell am broadcast -a com.nopalou.replay.PLATFORM -n com.nopalou.replay/.TriggerReceiver
 *   不带参数：发一组样例（3 条该出卡，2 条不该出卡）
 *   带参数：--es app 学习通|知到|雨课堂 --es title 标题 --es text 正文
 */
public final class PlatformNotifs {

    static final String KEY_AS = "nopalou.asPackage";

    private PlatformNotifs() {}

    static String pkgOf(String app) {
        if (app == null) return null;
        if (app.contains("学习通") || app.contains("chaoxing")) return "com.chaoxing.mobile";
        if (app.contains("知到") || app.contains("智慧树") || app.contains("wisdomtree")) return "com.able.wisdomtree";
        if (app.contains("雨课堂") || app.contains("ykt")) return "com.xuetangx.ykt";
        return null;
    }

    /** 发一组样例：日期都按现在往后推，保证不会因为"已截止"被丢掉。 */
    public static void postSamples(Context ctx) {
        long now = System.currentTimeMillis();
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        String d3 = f.format(now + 3 * 86400_000L);
        String d5 = f.format(now + 5 * 86400_000L);
        String d10 = f.format(now + 10 * 86400_000L);
        post(ctx, 9100, "学习通", "学习通",
                "【概率论与数理统计】第四次作业《第五章习题》已发布，截止时间：" + d5 + " 23:59");
        post(ctx, 9101, "知到", "知到",
                "《大学物理》期中考试将于" + d10 + " 14:00开始，请准时参加");
        post(ctx, 9102, "雨课堂", "雨课堂",
                "数据结构 老师发布了新作业：链表练习，截止时间：" + d3 + " 22:00");
        post(ctx, 9103, "雨课堂", "雨课堂", "数据结构 老师发起了签到，请在2分钟内完成");
        post(ctx, 9104, "知到", "知到", "你的作业《第二章 力学》已批改，得分92");
    }

    @SuppressWarnings("deprecation")
    public static boolean post(Context ctx, int id, String app, String title, String text) {
        String pkg = pkgOf(app);
        if (ctx == null || pkg == null) return false;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(FormatNotifs.CHANNEL, FormatNotifs.CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, FormatNotifs.CHANNEL)
                : new Notification.Builder(ctx);
        Bundle extras = new Bundle();
        extras.putString(KEY_AS, pkg);
        nb.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setContentTitle(title == null ? app : title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .addExtras(extras);
        nm.notify(id, nb.build());
        return true;
    }
}
