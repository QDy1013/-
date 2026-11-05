package com.nopalou.replay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/** 通过真实系统通知验证主应用自动出卡、更正和取消，无需向真实群发消息。 */
final class AutomaticFlow {
    private static final String CHANNEL = "automatic_acceptance";

    static void post(Context context, int stage) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || stage < 0 || stage > 2) return;
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                    "自动识别验收", NotificationManager.IMPORTANCE_LOW));
        }
        if (stage == 0) {
            send(context, manager, 9100, "明天18点前交性能验收报告，交到学习通。");
            send(context, manager, 9101, "明天9点性能验收考试，地点：教二305，带：学生证。");
        } else if (stage == 1) {
            send(context, manager, 9100, "性能验收报告延期到后天20点。");
            send(context, manager, 9101, "性能验收考试改到后天10点。");
        } else {
            send(context, manager, 9100, "性能验收报告不用交了。");
            send(context, manager, 9101, "性能验收考试取消。");
        }
    }

    @SuppressWarnings("deprecation")
    private static void send(Context context, NotificationManager manager, int id, String body) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL) : new Notification.Builder(context);
        manager.notify(id, builder.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("AUTO09自动验收群")
                .setContentText("计挑赛石老师：" + body)
                .setStyle(new Notification.BigTextStyle().bigText("[有全体消息]计挑赛石老师：" + body))
                .setAutoCancel(true)
                .build());
    }
}
