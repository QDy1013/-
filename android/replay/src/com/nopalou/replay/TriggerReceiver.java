package com.nopalou.replay;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 命令触发入口：锁屏时可以用 adb 发一条广播，直接让回放器发出格式测试通知，
 * 不必先解锁点亮屏幕去点按钮。
 *
 * 支持两个 action：
 * 1. com.nopalou.replay.FORMAT   —— 立刻发出格式测试通知；
 * 2. com.nopalou.replay.SCHEDULE —— 定时触发，delay 秒后再发出格式测试通知。
 *
 * 用法：
 * adb shell am broadcast -a com.nopalou.replay.FORMAT -n com.nopalou.replay/.TriggerReceiver
 * adb shell am broadcast -a com.nopalou.replay.SCHEDULE --ei delay 30 -n com.nopalou.replay/.TriggerReceiver
 */
public class TriggerReceiver extends BroadcastReceiver {

    /** 触发格式测试的广播 action，必须与清单里注册的一致。 */
    static final String ACTION_FORMAT = "com.nopalou.replay.FORMAT";

    /** 定时触发的广播 action，同样要与清单里注册的一致。 */
    static final String ACTION_SCHEDULE = "com.nopalou.replay.SCHEDULE";

    /** 定时参数名：延迟多少秒后触发，缺省 60 秒。 */
    static final String EXTRA_DELAY = "delay";

    /** 延时广播默认延迟秒数。 */
    static final int DEFAULT_DELAY_SECONDS = 60;

    /** 定时闹钟使用的 PendingIntent requestCode，固定值保证重复设置时覆盖同一个闹钟。 */
    static final int ALARM_REQUEST_CODE = 7;

    /** 日志 TAG，方便 adb logcat -s ReplayTrigger 观察。 */
    static final String TAG = "ReplayTrigger";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();

        if (ACTION_FORMAT.equals(action)) {
            // 原来的一次性触发：立刻发通知，行为保持不变，只是补一条日志。
            FormatNotifs.post(context);
            Log.i(TAG, "格式测试通知已发出");
        } else if (ACTION_SCHEDULE.equals(action)) {
            // 定时触发：用 AlarmManager 在 delay 秒后向自己再发一条 FORMAT 广播。
            // delay 缺省 60 秒；这里不做上限校验，负数会让闹钟立即到期（与 set 语义一致）。
            int delay = intent.getIntExtra(EXTRA_DELAY, DEFAULT_DELAY_SECONDS);

            // 闹钟到期时要执行的动作：显式指向自己的 FORMAT 广播，所以锁屏/应用被杀也能收到。
            Intent formatIntent = new Intent(context, TriggerReceiver.class).setAction(ACTION_FORMAT);
            // FLAG_IMMUTABLE 是 API 23+ 要求（minSdk 24 可直接用）；UPDATE_CURRENT 保证重复定时是替换而非堆积。
            PendingIntent pi = PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, formatIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            // RTC_WAKEUP：按绝对时间唤醒设备；setAndAllowWhileIdle 允许在 Doze 下也尽力送达。
            // 该调用不需要额外权限（精确闹钟才需要 SCHEDULE_EXACT_ALARM）。
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + delay * 1000L, pi);

            Log.i(TAG, "已定时 " + delay + " 秒后发格式测试通知");
        }
        // 其它广播一律忽略。
    }
}
