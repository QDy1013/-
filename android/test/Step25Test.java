package com.nopalou;

import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * Step25：自然语言变更 / 撤销的回归用例（Java 版），与 eval/test_natural_revisions.py 一一对应。
 *
 * 覆盖：延期、推迟、提前、顺延、改为、取消、免交、无需提交，以及“不用交了吗”这类提问、
 * 无关的人说“不用交了”、无关的“不用带电脑”等不应撤销的反例。
 *
 * 每个场景用全新的 Engine；全局 msg_id 递增，发送时间从 2026-11-04 19:00 开始，
 * 每发一条加 7 分钟。Java 的撤销会直接把卡从 all() 里移除，因此活动卡即 all()。
 *
 * 本文件只钉期望，不迁就现状：现在有若干条不通过是正常的，修复在后面做。
 * 写法沿用 Step24Test（Calendar 造时间、失败 System.exit(1)）。
 */
public final class Step25Test {

    private static final String GROUP_ID = "g1";
    private static final String GROUP_NAME = "数据库群";
    private static final String TEACHER = "王老师";
    private static final String STUDENT = "陈嘉禾";
    private static final String MONITOR = "李班长";
    private static final String BASE = "周五18:00前提交实验报告，交学习通";

    private static String model;
    private static int pass;
    private static int fail;

    private static long ts(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance();
        c.set(y, mo - 1, d, h, mi, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static final long D_FRI_1800 = ts(2026, 11, 6, 18, 0);
    private static final long D_NEXT_MON_2200 = ts(2026, 11, 9, 22, 0);
    private static final long D_SUN_2000 = ts(2026, 11, 8, 20, 0);
    private static final long D_THU_1200 = ts(2026, 11, 5, 12, 0);
    private static final long D_NEXT_TUE_2100 = ts(2026, 11, 10, 21, 0);

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
            titles.append(c.title);
            if (deadlines.length() > 0) deadlines.append(", ");
            deadlines.append(fmt(c.deadline));
        }
        return "卡数=" + cards.size() + "，标题=[" + titles + "]，deadline=[" + deadlines + "]";
    }

    private static boolean sameDeadline(long actual, long expected) {
        if (actual <= 0 || expected <= 0) return actual == expected;
        return fmt(actual).equals(fmt(expected));
    }

    private static boolean hasTitle(List<Engine.Card> cards, String needle) {
        for (Engine.Card c : cards) {
            if (c.title != null && c.title.contains(needle)) return true;
        }
        return false;
    }

    /** 全局递增 msg_id + 固定 7 分钟间隔，和 Python 的 Clock 一致。 */
    private static final class Clock {
        private final Calendar cal;
        private int n = 0;

        Clock() {
            cal = Calendar.getInstance();
            cal.set(2026, 10, 4, 19, 0, 0);
            cal.set(Calendar.MILLISECOND, 0);
        }

        void send(Engine e, String sender, String text) {
            n++;
            e.process(new Engine.Msg("m" + n, GROUP_ID, GROUP_NAME, sender, text, "demo",
                    cal.getTimeInMillis()));
            cal.add(Calendar.MINUTE, 7);
        }
    }

    private static Engine engine() throws Exception {
        return new Engine(new FileInputStream(model));
    }

    /** 每个场景的公共前提：BASE 出 1 张卡、deadline 2026-11-06 18:00。 */
    private static Engine newBase(Clock clock, String tag) throws Exception {
        Engine e = engine();
        clock.send(e, TEACHER, BASE);
        List<Engine.Card> cards = e.all();
        check(tag + " 前提：BASE 出 1 张卡", cards.size() == 1, describe(cards));
        check(tag + " 前提：BASE 的 deadline 是 2026-11-06 18:00",
                cards.size() == 1 && sameDeadline(cards.get(0).deadline, D_FRI_1800),
                describe(cards));
        return e;
    }

    private static void expectDeadline(Engine e, String tag, long expected) {
        List<Engine.Card> cards = e.all();
        check(tag, cards.size() == 1 && sameDeadline(cards.get(0).deadline, expected),
                describe(cards));
    }

    private static void expectNoActive(Engine e, String tag) {
        List<Engine.Card> cards = e.all();
        check(tag, cards.isEmpty(), describe(cards));
        check(tag + "（无“不用”类残留卡）", !hasTitle(cards, "不用"), describe(cards));
    }

    public static void main(String[] args) throws Exception {
        model = args[0] + "/app/assets/model.tsv";
        Clock clock = new Clock();

        // 1. 延期到下周一晚上10点
        System.out.println("== 1. 延期到下周 ==  " + BASE);
        Engine e1 = newBase(clock, "1");
        clock.send(e1, TEACHER, "实验报告延期到下周一晚上10点");
        expectDeadline(e1, "1 延期到下周一晚上10点 → 1 张卡，deadline 2026-11-09 22:00", D_NEXT_MON_2200);

        // 2. 推迟到周日晚上8点
        System.out.println("\n== 2. 推迟到周日 ==");
        Engine e2 = newBase(clock, "2");
        clock.send(e2, TEACHER, "实验报告推迟到周日晚上8点");
        expectDeadline(e2, "2 推迟到周日晚上8点 → 1 张卡，deadline 2026-11-08 20:00", D_SUN_2000);

        // 3. 提前到周四中午12点
        System.out.println("\n== 3. 提前到周四 ==");
        Engine e3 = newBase(clock, "3");
        clock.send(e3, TEACHER, "实验报告提前到周四中午12点");
        expectDeadline(e3, "3 提前到周四中午12点 → 1 张卡，deadline 2026-11-05 12:00", D_THU_1200);

        // 4. 原发送者说不用交了
        System.out.println("\n== 4. 老师说不交 ==");
        Engine e4 = newBase(clock, "4");
        clock.send(e4, TEACHER, "实验报告不用交了");
        expectNoActive(e4, "4 老师说“实验报告不用交了” → 活动卡 0 张");

        // 5. 班长说“周五那个不用交了”
        System.out.println("\n== 5. 班长撤销课程设计 ==");
        Engine e5 = engine();
        clock.send(e5, TEACHER, "周五交课程设计");
        List<Engine.Card> base5 = e5.all();
        check("5 前提：课程设计出 1 张卡", base5.size() == 1, describe(base5));
        clock.send(e5, MONITOR, "周五那个不用交了");
        expectNoActive(e5, "5 李班长说“周五那个不用交了” → 活动卡 0 张");

        // 6. 无需提交了
        System.out.println("\n== 6. 无需提交 ==");
        Engine e6 = engine();
        clock.send(e6, TEACHER, "周五交课程设计");
        List<Engine.Card> base6 = e6.all();
        check("6 前提：课程设计出 1 张卡", base6.size() == 1, describe(base6));
        clock.send(e6, TEACHER, "课程设计无需提交了");
        expectNoActive(e6, "6 老师说“课程设计无需提交了” → 活动卡 0 张");

        // 7. 免交
        System.out.println("\n== 7. 免交 ==");
        Engine e7 = engine();
        clock.send(e7, TEACHER, "周五交课程设计");
        List<Engine.Card> base7 = e7.all();
        check("7 前提：课程设计出 1 张卡", base7.size() == 1, describe(base7));
        clock.send(e7, TEACHER, "课程设计免交");
        expectNoActive(e7, "7 老师说“课程设计免交” → 活动卡 0 张");

        // 8. 同学提问“不用交了吗”不该撤销
        System.out.println("\n== 8. 同学提问 ==");
        Engine e8 = newBase(clock, "8");
        clock.send(e8, STUDENT, "实验报告不用交了吗");
        List<Engine.Card> cards8 = e8.all();
        check("8 同学问“实验报告不用交了吗” → 仍 1 张", cards8.size() == 1, describe(cards8));
        expectDeadline(e8, "8 deadline 仍 2026-11-06 18:00", D_FRI_1800);
        check("8 无标题含“不用”的卡", !hasTitle(cards8, "不用"), describe(cards8));

        // 9. 既非老师也非原发送者说“不用交了” → 不撤销
        System.out.println("\n== 9. 无关的人说不用交了 ==");
        Engine e9 = newBase(clock, "9");
        clock.send(e9, STUDENT, "实验报告不用交了");
        List<Engine.Card> cards9 = e9.all();
        check("9 陈嘉禾说“实验报告不用交了” → 仍 1 张", cards9.size() == 1, describe(cards9));
        expectDeadline(e9, "9 deadline 仍 2026-11-06 18:00", D_FRI_1800);
        check("9 无标题含“不用”的卡", !hasTitle(cards9, "不用"), describe(cards9));

        // 10. 顺延到下周二晚上9点
        System.out.println("\n== 10. 顺延 ==");
        Engine e10 = newBase(clock, "10");
        clock.send(e10, TEACHER, "实验报告顺延到下周二晚上9点");
        expectDeadline(e10, "10 顺延到下周二晚上9点 → 1 张卡，deadline 2026-11-10 21:00", D_NEXT_TUE_2100);

        // 11. 更正改为周日晚上8点，再取消
        System.out.println("\n== 11. 更正后再取消 ==");
        Engine e11 = newBase(clock, "11");
        clock.send(e11, TEACHER, "更正一下，实验报告改为周日晚上8点");
        expectDeadline(e11, "11 更正为周日晚上8点 → 1 张卡，deadline 2026-11-08 20:00", D_SUN_2000);
        clock.send(e11, TEACHER, "实验报告取消");
        expectNoActive(e11, "11 老师说“实验报告取消” → 活动卡 0 张");

        // 12. 无关的“不用带电脑”不能撤销实验报告卡
        System.out.println("\n== 12. 无关的“不用” ==");
        Engine e12 = newBase(clock, "12");
        clock.send(e12, TEACHER, "下周一上课，大家不用带电脑");
        List<Engine.Card> cards12 = e12.all();
        Engine.Card target = null;
        for (Engine.Card c : cards12) {
            if (c.title != null && c.title.contains("实验报告")) {
                target = c;
                break;
            }
        }
        check("12 老师说“下周一上课，大家不用带电脑” → 实验报告的卡仍在",
                cards12.size() == 1 && target != null, describe(cards12));
        check("12 实验报告卡的 deadline 仍 2026-11-06 18:00",
                target != null && sameDeadline(target.deadline, D_FRI_1800), describe(cards12));

        System.out.println("\n通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
        System.out.println("Step25 全部通过");
    }
}
