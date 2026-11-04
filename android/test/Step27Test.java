package com.nopalou;

import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * Step27：口语化提醒 / 疑问句 / 标题清理 / 考试与接龙卡的回归用例（Java 版），
 * 与 eval/test_oral_misc.py 一一对应。
 *
 * 覆盖：
 * A1 句中语气词"呢"不是提问 → 仍出 1 张卡，标题"把实验报告交一下"
 * A2 句尾"呢"是在问 → 0 张活动卡
 * A3 句尾"吗"是在问 → 0 张活动卡
 * B1 Engine.findAction 对"这个周末前/这周末前/本周末前"的标题清理
 * C1 考试通知 → kind=exam、place 含教学楼A101、deadline 2027-01-05 09:00
 * D1 接龙 → kind=jielong、标题含周末聚餐、deadline 2026-11-06 18:00
 *
 * 每个场景用全新 Engine，只发一条消息，发送时间固定 2026-11-04 21:30。
 * 本文件只钉期望，不迁就现状：现在有若干条不通过是正常的。
 */
public final class Step27Test {

    private static final String GROUP_ID = "g1";
    private static final String GROUP_NAME = "数据库群";
    private static final String TEACHER = "王老师";
    private static final String MONITOR = "李班长";
    private static final String STUDENT = "陈嘉禾";

    private static final String A1 = "那个，周五之前呢，把实验报告交一下哈";
    private static final String A2 = "周五之前交实验报告呢";
    private static final String A3 = "周五之前交实验报告吗";
    private static final String C1 = "期末考试1月5日上午9点，教学楼A101，带笔和学生证";
    private static final String D1 = "#接龙 周末聚餐，周五18点前报名\n1. 张三\n2. 李四";

    private static final String TITLE_A1 = "把实验报告交一下";

    private static String model;
    private static int pass;
    private static int fail;

    private static long ts(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance();
        c.set(y, mo - 1, d, h, mi, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static final long T0 = ts(2026, 11, 4, 21, 30);
    private static final long D_FRI_2359 = ts(2026, 11, 6, 23, 59);
    private static final long D_FRI_1800 = ts(2026, 11, 6, 18, 0);
    private static final long D_EXAM_0900 = ts(2027, 1, 5, 9, 0);

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + name);
        } else {
            fail++;
            System.out.println("  ✗ " + name + "   " + detail);
        }
    }

    private static String fmt(long ts) {
        if (ts <= 0) return "None（待确认）";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(ts));
    }

    private static String describe(List<Engine.Card> cards) {
        StringBuilder sb = new StringBuilder();
        for (Engine.Card c : cards) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("{").append(c.kind).append("|").append(c.displayTitle())
              .append("|").append(fmt(c.deadline))
              .append("|").append(c.place).append("}");
        }
        return "活动卡=" + cards.size() + " " + sb;
    }

    private static boolean sameDeadline(long actual, long expected) {
        if (actual <= 0 || expected <= 0) return actual == expected;
        return fmt(actual).equals(fmt(expected));
    }

    private static Engine engine() throws Exception {
        return new Engine(new FileInputStream(model));
    }

    /** 全新 Engine，只发这一条消息。 */
    private static List<Engine.Card> send(String sender, String text, String msgId) throws Exception {
        Engine e = engine();
        e.process(new Engine.Msg(msgId, GROUP_ID, GROUP_NAME, sender, text, "demo", T0));
        return e.all();
    }

    private static String titleOf(Engine.Card c) {
        String t = c.displayTitle();
        return t == null ? "" : t.trim();
    }

    public static void main(String[] args) throws Exception {
        model = args[0] + "/app/assets/model.tsv";

        // A1 句中的"呢"是语气词，不是提问
        System.out.println("== A1 句中语气词 ==  " + A1);
        List<Engine.Card> a1 = send(TEACHER, A1, "m1");
        check("A1 恰好 1 张活动卡", a1.size() == 1, describe(a1));
        if (a1.size() == 1) {
            Engine.Card c = a1.get(0);
            check("A1 标题为「把实验报告交一下」", titleOf(c).equals(TITLE_A1),
                    "实际标题=" + titleOf(c));
            check("A1 deadline 2026-11-06 23:59", sameDeadline(c.deadline, D_FRI_2359),
                    "实际=" + fmt(c.deadline));
        }

        // A2 句尾"呢"是在问 → 不生成活动卡
        System.out.println("\n== A2 句尾疑问（呢） ==  " + A2);
        List<Engine.Card> a2 = send(STUDENT, A2, "m2");
        check("A2 0 张活动卡", a2.size() == 0, describe(a2));

        // A3 句尾"吗"是在问 → 不生成活动卡
        System.out.println("\n== A3 句尾疑问（吗） ==  " + A3);
        List<Engine.Card> a3 = send(TEACHER, A3, "m3");
        check("A3 0 张活动卡", a3.size() == 0, describe(a3));

        // B1 标题清理：周末前的时间前缀要去干净
        System.out.println("\n== B1 findAction 标题清理 ==");
        String[][] b1 = {
                {"这个周末前把问卷填了", "把问卷填了"},
                {"这周末前交报告", "交报告"},
                {"本周末前提交总结", "提交总结"},
        };
        for (String[] item : b1) {
            String got = Engine.findAction(item[0])[0];
            check("B1 " + item[0] + " → " + item[1],
                    item[1].equals(got), "实际=" + got);
        }

        // C1 考试通知
        System.out.println("\n== C1 考试通知 ==  " + C1);
        List<Engine.Card> c1 = send(TEACHER, C1, "m4");
        check("C1 恰好 1 张活动卡", c1.size() == 1, describe(c1));
        if (c1.size() == 1) {
            Engine.Card c = c1.get(0);
            check("C1 kind=exam", "exam".equals(c.kind), "实际=" + c.kind);
            check("C1 place 包含「教学楼A101」",
                    c.place != null && c.place.contains("教学楼A101"), "实际=" + c.place);
            check("C1 deadline 2027-01-05 09:00", sameDeadline(c.deadline, D_EXAM_0900),
                    "实际=" + fmt(c.deadline));
        }

        // D1 接龙
        System.out.println("\n== D1 接龙 ==  " + D1.replace("\n", " / "));
        List<Engine.Card> d1 = send(MONITOR, D1, "m5");
        check("D1 恰好 1 张活动卡", d1.size() == 1, describe(d1));
        if (d1.size() == 1) {
            Engine.Card c = d1.get(0);
            check("D1 kind=jielong", "jielong".equals(c.kind), "实际=" + c.kind);
            check("D1 标题包含「周末聚餐」", titleOf(c).contains("周末聚餐"),
                    "实际标题=" + titleOf(c));
            check("D1 deadline 2026-11-06 18:00", sameDeadline(c.deadline, D_FRI_1800),
                    "实际=" + fmt(c.deadline));
        }

        System.out.println("\n通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("Step27 全部通过");
    }
}
