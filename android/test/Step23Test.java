package com.nopalou;

/**
 * 事项标题抽取的期望锁定（Java 版）。
 *
 * 真机上发现标题会残留时间的残余字（"上午交作业""前交实验报告"），
 * 本文件先把期望钉死，不迁就现状：当前有若干条不通过是正常的。
 *
 * 只测包内静态方法 Engine.findAction(String)[0]；写法同 Step21Test。
 */
public final class Step23Test {

    private static int failures;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "✓ " : "✗ ") + name);
        if (!ok) failures++;
    }

    private static void eq(String name, String expected, String actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        check(name + (ok ? "" : "（期望=" + expected + "，实际=" + actual + "）"), ok);
    }

    /** {原文, 期望标题} */
    private static final String[][] CASES = {
            {"明天20:00前交实验报告", "交实验报告"},
            {"周五18:00前报名体测", "报名体测"},
            {"明天上午9点交作业，别忘了", "交作业"},
            {"明天上午9点交作业", "交作业"},
            {"下午3点前提交读书报告", "提交读书报告"},
            {"晚上8点半前交小组展示PPT", "交小组展示PPT"},
            {"明天中午12点前交报告", "交报告"},
            {"周三下午15:30之前填报选课意向表", "填报选课意向表"},
            {"请大家周五前交作业", "交作业"},
            {"明天20点前交实验报告", "交实验报告"},
            {"11月11日08:00交读书报告", "交读书报告"},
            {"今晚22点前交实验报告", "交实验报告"},
            {"周五前交实验报告", "交实验报告"},
            {"明天早上8:30前签到", "签到"},
            {"交实验报告", "交实验报告"},
            {"报名体测", "报名体测"},
    };

    /** 这一条不要求等于某个标题，只要求结果里不含时间的残余字。 */
    private static final String SPECIAL_TEXT = "请同学们务必在周日晚上9点前报名";
    private static final String[] SPECIAL_FORBIDDEN = {"晚上", "点", "前"};

    private static String title(String text) {
        String[] r = Engine.findAction(text);
        return r == null ? null : r[0];
    }

    public static void main(String[] args) {
        for (String[] c : CASES) {
            String actual = title(c[0]);
            eq(c[0] + " → " + c[1], c[1], actual);
        }

        String actual = title(SPECIAL_TEXT);
        StringBuilder bad = new StringBuilder();
        for (String w : SPECIAL_FORBIDDEN) {
            if (actual != null && actual.contains(w)) {
                if (bad.length() > 0) bad.append("、");
                bad.append(w);
            }
        }
        check(SPECIAL_TEXT + " → 不含时间的残余字（实际=" + actual + "）", bad.length() == 0);

        if (failures > 0) {
            System.out.println(failures + " 项未通过");
            System.exit(1);
        }
        System.out.println("Step23 全部通过");
    }
}
