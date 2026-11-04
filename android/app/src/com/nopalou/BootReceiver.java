package com.nopalou;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.service.notification.NotificationListenerService;

/**
 * 开机自启：vivo 等系统重启后会悄悄把通知监听解绑，
 * 这里在开机、锁屏开机完成、应用更新后主动请求重新绑定，
 * 并把卡片提前读进引擎，第一条消息进来时状态已就绪。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;

        try {
            NotificationListenerService.requestRebind(new ComponentName(context, NotifListener.class));
        } catch (Exception ignored) {
        }

        try {
            EngineHolder.get(context);
        } catch (Exception ignored) {
        }
    }
}
