package com.nopalou;

import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Step26：一条消息里多件事的回归用例（Java 版），与 eval/test_multi_items.py 一一对应。
 *
 * 覆盖：一句话拆成两张卡、各自带不同 deadline、相对时间（明天晚上8点 / 周日24点）、
 * 单个时间不能被"交学习通/逾期不候"拆开、"交到邮箱"不是第二件事、
 * "A和B"仍是一件事，以及同群同人 7 分钟后重发整句的活动卡去重。
 *
 * 每个场景用全新的 Engine；发送者都是王老师，发送时间从 2026-11-04 21:30 开始，
 * 每发一条加 7 分钟。Java 的撤销会直接把卡从 all() 里移除，因此活动卡即 all()。
 *
 * 本文件只钉期望，不迁就现状：现在有若干条不通过是正常的，修复在后面做。
 */
public final class Step26Test {

    private static final String GROUP_ID = "g1";
    private static final String GROUP_NAME = "数据库群";
    private static final String TEACHER = "王老师";

    private static final String S1 = "这周要交两个东西：周三前交实验报告，周五前交读书笔记";
    private static final String S2 = "明天晚上8点前交实验报告，周日24点前交读书笔记";
    private static final String S3 = "作业1周三交，作业2周五交";
    private static final String S4 = "周五18:00前提交实验报告，交学习通，逾期不候";
    private static final String S5 = "周五前交实验报告，交到邮箱";
    private static final String S6 = "周五之前交实验报告和读书笔记";

    private static String model;
    private static int pass;
    private static int fail;

    private static long ts(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance();
        c.set(y, mo - 1, d, h, mi, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static final long D_WED_2359 = ts(2026, 11, 4, 23, 59);
    private static final long D_FRI_2359 = ts(2026, 11, 6, 23, 59);
    private static final long D_THU_2000 = ts(2026, 11, 5, 20, 0);
    private static final long D_MON_0000 = ts(2026, 11, 9, 0, 0);
    private static final long D_FRI_1800 = ts(2026, 11, 6, 18, 0);

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
        StringBuilder titles = new StringBuilder();
        StringBuilder deadlines = new StringBuilder();
        for (Engine.Card c : cards) {
            if (titles.length() > 0) titles.append(", ");
            titles.append(c.displayTitle());
            if (deadlines.length() > 0) deadlines.append(", ");
            deadlines.append(fmt(c.deadline));
        }
        return "卡数=" + cards.size() + "，标题=[" + titles + "]，deadline=[" + deadlines + "]";
    }

    private static boolean sameDeadline(long actual, long expected) {
        if (actual <= 0 || expected <= 0) return actual == expected;
        return fmt(actual).equals(fmt(expected));
    }

    private static Engine engine() throws Exception {
        return new Engine(new FileInputStream(model));
    }

    /** 全局递增 msg_id + 固定 7 分钟间隔，起点 2026-11-04 21:30。 */
    private static final class Clock {
        private final Calendar cal;
        private int n = 0;

        Clock() {
            cal = Calendar.getInstance();
            cal.set(2026, 10, 4, 21, 30, 0);
            cal.set(Calendar.MILLISECOND, 0);
        }

        void send(Engine e, String text) {
            n++;
            e.process(new Engine.Msg("m" + n, GROUP_ID, GROUP_NAME, TEACHER, text, "demo",
                    cal.getTimeInMillis()));
            cal.add(Calendar.MINUTE, 7);
        }
    }

    private static List<Engine.Card> expectCount(Engine e, String tag, int n) {
        List<Engine.Card> cards = e.all();
        check(tag, cards.size() == n, describe(cards));
        return cards;
    }

    /** 活动卡里恰好有一张标题含 needle，且 deadline 对得上。 */
    private static void expectItem(Engine e, String tag, String needle, long deadline) {
        List<Engine.Card> cards = e.all();
        Engine.Card matched = null;
        int hits = 0;
        for (Engine.Card c : cards) {
            String t = c.displayTitle();
            if (t != null && t.contains(needle)) {
                hits++;
                matched = c;
            }
        }
        boolean ok = hits == 1 && matched != null && sameDeadline(matched.deadline, deadline);
        check(tag, ok, describe(cards));
    }

    public static void main(String[] args) throws Exception {
        model = args[0] + "/app/assets/model.tsv";
        Clock clock = new Clock();

        // 1. 一句话两个截止时间
        System.out.println("== 1. 周三实验报告 + 周五读书笔记 ==  " + S1);
        Engine e1 = engine();
        clock.send(e1, S1);
        expectCount(e1, "1 恰好 2 张活动卡", 2);
        expectItem(e1, "1 实验报告 → deadline 2026-11-04 23:59", "实验报告", D_WED_2359);
        expectItem(e1, "1 读书笔记 → deadline 2026-11-06 23:59", "读书笔记", D_FRI_2359);

        // 2. 相对时间
        System.out.println("\n== 2. 明天晚上8点 + 周日24点 ==  " + S2);
        Engine e2 = engine();
        clock.send(e2, S2);
        expectCount(e2, "2 恰好 2 张活动卡", 2);
        expectItem(e2, "2 实验报告 → deadline 2026-11-05 20:00", "实验报告", D_THU_2000);
        expectItem(e2, "2 读书笔记 → deadline 2026-11-09 00:00", "读书笔记", D_MON_0000);

        // 3. 两个作业，各自一个截止时间（不看顺序）
        System.out.println("\n== 3. 作业1周三交，作业2周五交 ==  " + S3);
        Engine e3 = engine();
        clock.send(e3, S3);
        List<Engine.Card> cards3 = expectCount(e3, "3 恰好 2 张活动卡", 2);
        List<String> got3 = new ArrayList<String>();
        for (Engine.Card c : cards3) got3.add(fmt(c.deadline));
        List<String> want3 = new ArrayList<String>();
        want3.add(fmt(D_WED_2359));
        want3.add(fmt(D_FRI_2359));
        Collections.sort(got3);
        Collections.sort(want3);
        check("3 deadline 分别为 2026-11-04 23:59 和 2026-11-06 23:59",
                cards3.size() == 2 && got3.equals(want3), describe(cards3));

        // 4. 只有一个时间，交学习通/逾期不候 不能被拆成第二件事
        System.out.println("\n== 4. 周五18:00 一整个通知 ==  " + S4);
        Engine e4 = engine();
        clock.send(e4, S4);
        List<Engine.Card> cards4 = expectCount(e4, "4 恰好 1 张活动卡（时间不能被拆）", 1);
        check("4 deadline 2026-11-06 18:00",
                cards4.size() == 1 && sameDeadline(cards4.get(0).deadline, D_FRI_1800),
                describe(cards4));

        // 5. 交到邮箱不是第二件事
        System.out.println("\n== 5. 周五前交实验报告，交到邮箱 ==  " + S5);
        Engine e5 = engine();
        clock.send(e5, S5);
        expectCount(e5, "5 恰好 1 张活动卡", 1);

        // 6. "和"连接的两件事仍是一份作业集合
        System.out.println("\n== 6. 周五之前交实验报告和读书笔记 ==  " + S6);
        Engine e6 = engine();
        clock.send(e6, S6);
        expectCount(e6, "6 恰好 1 张活动卡", 1);

        // 7. 同群同人 7 分钟后重发整句 → 活动卡仍 2 张
        System.out.println("\n== 7. 重发场景 1 的整句 ==  " + S1);
        Engine e7 = engine();
        clock.send(e7, S1);
        List<Engine.Card> first7 = expectCount(e7, "7 前提：首发后 2 张活动卡", 2);
        clock.send(e7, S1);
        List<Engine.Card> second7 = expectCount(e7, "7 7 分钟后重发 → 活动卡仍是 2 张", 2);
        check("7 重发没有新增任何卡（含拆出来的每一件）",
                first7.size() == 2 && second7.size() == 2, describe(second7));

        System.out.println("\n通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("Step26 全部通过");
    }
}
