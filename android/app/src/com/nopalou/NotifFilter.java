package com.nopalou;

/**
 * 通知来源过滤 + 群编号。
 * 纯 Java，不依赖 android，方便在电脑上直接跑测试。
 *
 * 真机问题：监听器原来把所有 App 的通知都收进来，而且拿包名当群编号，
 * 同一个 App 里的所有群就被当成了同一个群。
 */
public final class NotifFilter {

    /** 只收群聊类 App：微信 / QQ / TIM / 钉钉 / 飞书 / 回放器。 */
    private static final String[] ALLOWED = {
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.tencent.tim",
            "com.alibaba.android.rimet",
            "com.ss.android.lark",
            "com.nopalou.replay",
    };

    private NotifFilter() {
    }

    public static boolean accept(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        for (String p : ALLOWED) {
            if (p.equals(pkg)) return true;
        }
        return false;
    }

    public static String groupId(String pkg, String title) {
        String p = pkg == null ? "" : pkg;
        String g = title == null ? "" : title.trim();
        if (g.isEmpty()) return p;
        return p + "|" + g;
    }
}
