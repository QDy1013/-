package com.nopalou;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

/**
 * 真实群通知用语的时间/标题期望锁定（Java 版）。
 *
 * 发送时间固定为 2026-11-04 21:30（周三晚），与 eval/test_real_phrases.py 三张表一致：
 *   表 1 → Engine.parseDeadline(原文, 发送毫秒).value
 *   表 2 → Engine.findAction(原文)[0]（找不到行动词时返回 {null, null}）
 *   表 3 → Engine.fallbackTitle(原文)
 *
 * 本文件只钉期望，不迁就现状：现在有若干条不通过是正常的，修复在后面做。
 * 写法沿用 Step23Test。
 */
public final class Step24Test {

    private static int failures;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "✓ " : "✗ ") + name);
        if (!ok) failures++;
    }

    private static void eq(String name, String expected, String actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        check(name + (ok ? "" : "（期望=" + expected + "，实际=" + actual + "）"), ok);
    }

    private static long ts(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance();
        c.set(y, mo - 1, d, h, mi, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static final long SEND = ts(2026, 11, 4, 21, 30);

    private static String fmt(long ts) {
        if (ts <= 0) return "None（待确认）";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(ts));
    }

    /** {原文, 期望截止时间 yyyy-MM-dd HH:mm} */
    private static final String[][] TIME_CASES = {
            {"大家记得本周日24点前提交选课表格", "2026-11-09 00:00"},
            {"明天24点前交作业", "2026-11-06 00:00"},
            {"今晚24点前交实验报告", "2026-11-05 00:00"},
            {"收一下学生证复印件，明天中午前给我", "2026-11-05 12:00"},
            {"后天中午前交报告", "2026-11-06 12:00"},
            {"各位同学注意，本周五（11月6日）下午5点前把实验报告发到课代表邮箱", "2026-11-06 17:00"},
            {"后天中午12点之前交团费，找学委", "2026-11-06 12:00"},
            {"明晚8点开会，别迟到", "2026-11-05 20:00"},
            {"今晚十点前必须交，不然扣分", "2026-11-04 22:00"},
            {"11.8号之前把表格填一下", "2026-11-08 23:59"},
            {"请于本月底前完成实习报告提交", "2026-11-30 23:59"},
    };

    /** {原文, 期望标题}；action 均不应为 null */
    private static final String[][] TITLE_CASES = {
            {"明天早八点在教三201签到，迟到扣分", "在教三201签到"},
            {"作业：课本P56-58习题，下周一上课前交", "作业：课本P56-58习题"},
            {"请各位同学于11月10日前完成问卷填写，链接见群文件", "完成问卷填写"},
            {"请于本月底前完成实习报告提交", "完成实习报告提交"},
            {"本周内完成线上学习，系统明天关闭", "完成线上学习"},
            {"提醒一下，后天中午12点之前交团费，找学委", "交团费"},
            {"大家记得本周日24点前提交选课表格", "提交选课表格"},
    };

    /** {原文, 期望 fallback 标题} */
    private static final String[][] FALLBACK_CASES = {
            {"明晚8点开会，别迟到", "开会"},
            {"周六上午九点体测，带学生证和运动鞋", "体测"},
            {"收一下学生证复印件，明天中午前给我", "收一下学生证复印件"},
    };

    public static void main(String[] args) {
        System.out.println("== 表 1 时间（发送时间 2026-11-04 21:30）==");
        for (String[] c : TIME_CASES) {
            long actual = Engine.parseDeadline(c[0], SEND).value;
            eq(c[0] + " → " + c[1], c[1], fmt(actual));
        }

        System.out.println("\n== 表 2 标题（Engine.findAction）==");
        for (String[] c : TITLE_CASES) {
            String[] r = Engine.findAction(c[0]);
            String actual = r == null ? null : r[0];
            eq(c[0] + " → " + c[1], c[1], actual);
        }

        System.out.println("\n== 表 3 fallback 标题 ==");
        for (String[] c : FALLBACK_CASES) {
            eq(c[0] + " → " + c[1], c[1], Engine.fallbackTitle(c[0]));
        }

        if (failures > 0) {
            System.out.println("\n" + failures + " 项未通过");
            System.exit(1);
        }
        System.out.println("\nStep24 全部通过");
    }
}
