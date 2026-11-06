package com.nopalou;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/**
 * 首次安装引导用到的系统跳转与状态记录：
 * 电池不受限可以直接弹系统授权框；自启动 / 后台运行没有公开接口能查询，
 * 只能按手机品牌跳到对应的管家页面，并记下用户已经去设置过。
 */
final class SetupGuide {
    private static final String PREFS = "setup_guide";
    private static final String KEY_AUTOSTART = "autostart_visited";
    private static final String KEY_SNOOZED = "snoozed_at";
    private static final long SNOOZE_MS = 24L * 3600_000L;

    private SetupGuide() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean autostartVisited(Context c) {
        return prefs(c).getBoolean(KEY_AUTOSTART, false);
    }

    static void markAutostartVisited(Context c) {
        prefs(c).edit().putBoolean(KEY_AUTOSTART, true).apply();
    }

    static void snooze(Context c) {
        prefs(c).edit().putLong(KEY_SNOOZED, System.currentTimeMillis()).apply();
    }

    /** 必需项（通知使用权）没开时每次都提醒；只差推荐项时，点过“稍后”的 24 小时内不再自动弹出。 */
    static boolean shouldAutoShow(Context c) {
        if (!WatchdogJob.accessEnabled(c)) return true;
        if (allRecommendedDone(c)) return false;
        long snoozed = prefs(c).getLong(KEY_SNOOZED, 0L);
        return System.currentTimeMillis() - snoozed > SNOOZE_MS;
    }

    static boolean allRecommendedDone(Context c) {
        return WatchdogJob.accessEnabled(c) && WatchdogJob.batteryIgnored(c) && autostartVisited(c);
    }

    /** 直接弹出系统的“允许后台运行 / 忽略电池优化”授权框；不支持时退回电池优化列表。 */
    static void requestBattery(Activity a) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + a.getPackageName()));
            a.startActivity(i);
            return;
        } catch (Exception ignored) {
        }
        try {
            a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception ignored) {
            openAppDetails(a);
        }
    }

    /** 品牌管家里的自启动 / 后台管理页面，按顺序逐个尝试，都打不开就去应用详情页。 */
    private static final String[][] AUTOSTART_PAGES = {
            // vivo / iQOO
            {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
            // 小米 / 红米
            {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            // 华为 / 荣耀
            {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
            {"com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            // OPPO / 一加 / realme
            {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
            {"com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"},
            // 魅族
            {"com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"},
            // 三星
            {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
    };

    /** 打开自启动设置；返回 true 表示进了品牌页面，false 表示退回了应用详情页。 */
    static boolean openAutostart(Activity a) {
        for (String[] page : AUTOSTART_PAGES) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(page[0], page[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (i.resolveActivity(a.getPackageManager()) == null) continue;
                a.startActivity(i);
                markAutostartVisited(a);
                return true;
            } catch (Exception ignored) {
                // 页面未导出或被系统拦截，换下一个。
            }
        }
        openAppDetails(a);
        markAutostartVisited(a);
        return false;
    }

    static void openAppDetails(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Exception ignored) {
        }
    }

    /** 给用户看的品牌提示：告诉他在打开的页面里找哪个开关。 */
    static String autostartHint() {
        String brand = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(java.util.Locale.ROOT);
        if (brand.contains("vivo") || brand.contains("iqoo")) return "在列表里打开「Offloor」的自启动 / 后台启动";
        if (brand.contains("xiaomi") || brand.contains("redmi")) return "打开「Offloor」的自启动，并把省电策略设为无限制";
        if (brand.contains("huawei") || brand.contains("honor")) return "把「Offloor」改为手动管理，三个开关都打开";
        if (brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme")) return "允许「Offloor」自启动和后台运行";
        return "在打开的页面里允许「Offloor」自启动和后台运行";
    }
}
