package com.nopalou.replay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/**
 * 格式测试通知的发送逻辑，从 ReplayActivity.formatTest() 搬出来，
 * 让界面按钮和 adb 广播（锁屏时）走同一份代码，内容完全一致。
 */
public final class FormatNotifs {

    /** 与回放共用同一个渠道：id 和名称都必须和 ReplayActivity 里的一致。 */
    public static final String CHANNEL = "nopalou_demo";

    /** 渠道显示名，同样与 ReplayActivity 保持一致。 */
    public static final String CHANNEL_NAME = "群消息回放";

    /** 工具类不允许实例化。 */
    private FormatNotifs() {
    }

    /**
     * 一次性发出 5 条不同「形状」的通知，覆盖微信/QQ 在真机上常见的几种样式。
     * 编号 9000..9004 与界面按钮发出的完全一样；重复调用只是覆盖同编号通知，无副作用。
     */
    @SuppressWarnings("deprecation")
    public static void post(Context ctx) {
        if (ctx == null) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // 渠道已存在时重复创建是无害的，这里建一遍保证广播触发时渠道一定在。
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setShowBadge(true);
            nm.createNotificationChannel(ch);
        }

        long now = System.currentTimeMillis();

        // A：多条消息会话（MessagingStyle 群聊），每条消息带发送者和独立时间。
        Notification.MessagingStyle ms = new Notification.MessagingStyle("我");
        if (Build.VERSION.SDK_INT >= 28) {
            // 会话标题和「这是群聊」的标记只在 API 28+ 生效，低版本跳过。
            ms.setConversationTitle("格式测试群");
            ms.setGroupConversation(true);
        }
        ms.addMessage("明天20:00前交实验报告", now - 3000, "王老师")
                .addMessage("周五18:00前报名体测", now - 2000, "班长")
                .addMessage("收到", now - 1000, "周同学");
        Notification.Builder a = builder(ctx, now);
        a.setStyle(ms)
                .setContentTitle("格式测试群")
                .setContentText("周同学: 收到");
        nm.notify(9000, a.build());

        // B：微信折叠群的形状，正文里直接带「[3条]发送者：内容」。
        Notification.Builder b = builder(ctx, now);
        b.setContentTitle("折叠测试群")
                .setContentText("[3条]王老师：下周三下午3点期中考试，教二305");
        nm.notify(9001, b.build());

        // C：只给条数、不给内容（QQ 的「N条新消息」）。
        Notification.Builder c = builder(ctx, now);
        c.setContentTitle("计数测试群")
                .setContentText("3条新消息");
        nm.notify(9002, c.build());

        // D：图片消息占位，正文只有「发送者：[图片]」。
        Notification.Builder d = builder(ctx, now);
        d.setContentTitle("图片测试群")
                .setContentText("班长：[图片]");
        nm.notify(9003, d.build());

        // E：私聊形状，正文没有「发送者：」前缀。
        Notification.Builder e = builder(ctx, now);
        e.setContentTitle("私聊测试")
                .setContentText("明天上午9点交作业，别忘了");
        nm.notify(9004, e.build());
    }

    /** 格式测试用的基础 Builder：沿用回放的渠道和小图标，点击后自动消失。 */
    @SuppressWarnings("deprecation")
    private static Notification.Builder builder(Context ctx, long when) {
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL)
                : new Notification.Builder(ctx);
        nb.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setAutoCancel(true)
                .setWhen(when)
                .setShowWhen(true);
        return nb;
    }
}
