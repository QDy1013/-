package com.nopalou;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * 数据源一：通知监听（方案书第 5 节主数据源）。
 * 用户授权后可以读到其他 App 发出的通知，不需要打开群。
 *
 * 限制（方案书里写明）：免打扰群不发通知；同一会话的通知会合并替换；
 * 用户关掉"显示消息详情"后没有正文。所以还要配无障碍读屏兜底。
 */
public class NotifListener extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // 真机问题：手机上所有 App 的通知都被读了进来，这里先按来源过滤。
        if (sbn == null || !NotifFilter.accept(sbn.getPackageName())) return;

        Engine eng;
        try {
            eng = EngineHolder.get(this);
        } catch (Exception e) {
            return;
        }
        if (eng == null || sbn == null || sbn.getNotification() == null) return;

        Bundle ex = sbn.getNotification().extras;
        if (ex == null) return;

        CharSequence titleCs = ex.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textCs = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (textCs == null) textCs = ex.getCharSequence(Notification.EXTRA_TEXT);
        if (textCs == null) return;

        String title = titleCs == null ? "" : titleCs.toString().trim();
        String text = textCs.toString().trim();
        if (text.isEmpty()) return;

        // 通知标题放群名，正文放"发送者：内容"
        String sender = title.isEmpty() ? "群消息" : "群友";
        String body = text;
        int cut = Math.max(text.indexOf('：'), text.indexOf(':'));
        if (cut > 0 && cut <= 12) {
            sender = text.substring(0, cut);
            body = text.substring(cut + 1).trim();
        }
        String group = title.isEmpty() ? sbn.getPackageName() : title;
        String id = "n" + sbn.getId() + "_" + sbn.getPostTime();

        eng.process(new Engine.Msg(id, NotifFilter.groupId(sbn.getPackageName(), group), group, sender, body,
                sbn.getPackageName(), System.currentTimeMillis()));
        EngineHolder.save(this);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        EngineHolder.listenerConnected = true;
        EngineHolder.refreshCards();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        EngineHolder.listenerConnected = false;
        EngineHolder.refreshCards();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // 撤回/删除不作为待办来源；原文在卡片里已经留存
    }
}
