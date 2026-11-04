package com.nopalou;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/** 撞车提醒：参与条件、3 小时分组的链式归并、组排序与一句话说明。 */
public final class Step20Test {
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 3600_000L;
    private static final long DAY = 24 * HOUR;

    /** 本地时区 2026-11-10 09:00:00，秒和毫秒为 0。 */
    private static final long NOW = at(2026, 11, 10, 9, 0, 0);

    private static int failures;

    private static long at(int year, int month, int day, int hour, int minute, int second) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, day, hour, minute, second);
        return c.getTimeInMillis();
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "✓ " : "✗ ") + name);
        if (!ok) failures++;
    }

    private static void eq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        check(name + (ok ? "" : "（期望=" + expected + "，实际=" + actual + "）"), ok);
    }

    private static Engine.Card card(String id, long deadline) {
        Engine.Card card = new Engine.Card();
        card.id = id;
        card.deadline = deadline;
        return card;
    }

    private static Engine.Card titled(String id, long deadline, String title) {
        Engine.Card card = card(id, deadline);
        card.title = title;
        return card;
    }

    private static Engine.Card doneCard(String id, long deadline) {
        Engine.Card card = card(id, deadline);
        card.done = true;
        return card;
    }

    private static Engine.Card unsureCard(String id, long deadline) {
        Engine.Card card = card(id, deadline);
        card.needsConfirm = true;
        return card;
    }

    private static Engine.Card examCard(String id, long deadline) {
        Engine.Card card = card(id, deadline);
        card.kind = "exam";
        return card;
    }

    private static List<ClashPlan.Clash> find(Engine.Card... cards) {
        return ClashPlan.find(Arrays.asList(cards), NOW);
    }

    private static String ids(List<Engine.Card> cards) {
        StringBuilder b = new StringBuilder();
        for (Engine.Card c : cards) {
            if (b.length() > 0) b.append(',');
            b.append(c.id);
        }
        return b.toString();
    }

    public static void main(String[] args) {
        testSingleGroup();
        testThreeHourBoundary();
        testChain();
        testTwoGroups();
        testParticipants();
        testEmpty();
        testText();
        testExamWithTodo();

        if (failures > 0) {
            System.out.println(failures + " 项未通过");
            System.exit(1);
        }
        System.out.println("Step20 全部通过");
    }

    private static void testSingleGroup() {
        List<ClashPlan.Clash> groups = find(
                card("late", NOW + 4 * HOUR),
                card("early", NOW + 2 * HOUR));
        eq("相差 2 小时：有 1 个撞车组", 1, groups.size());
        if (groups.isEmpty()) return;
        ClashPlan.Clash g = groups.get(0);
        eq("start 是组内最早截止时间", NOW + 2 * HOUR, g.start);
        eq("end 是组内最晚截止时间", NOW + 4 * HOUR, g.end);
        eq("组内 2 张卡", 2, g.cards.size());
        eq("乱序输入后 cards 按截止时间升序", "early,late", ids(g.cards));
    }

    private static void testThreeHourBoundary() {
        List<ClashPlan.Clash> groups = find(
                card("a", NOW + 2 * HOUR),
                card("b", NOW + 5 * HOUR));
        eq("相差正好 3 小时：撞车", 1, groups.size());
        if (!groups.isEmpty()) {
            eq("3 小时整的组内 2 张卡", 2, groups.get(0).cards.size());
        }

        List<ClashPlan.Clash> apart = find(
                card("a", NOW + 2 * HOUR),
                card("b", NOW + 5 * HOUR + MINUTE));
        eq("相差 3 小时 1 分：不撞车", 0, apart.size());
    }

    private static void testChain() {
        List<ClashPlan.Clash> groups = find(
                card("c", NOW + 6 * HOUR),
                card("a", NOW + 2 * HOUR),
                card("b", NOW + 4 * HOUR));
        eq("链式相邻 2 小时、首尾 4 小时：全部同一组", 1, groups.size());
        if (groups.isEmpty()) return;
        ClashPlan.Clash g = groups.get(0);
        eq("链式组内 3 张卡", 3, g.cards.size());
        eq("链式组 start", NOW + 2 * HOUR, g.start);
        eq("链式组 end", NOW + 6 * HOUR, g.end);
        eq("链式组 cards 升序", "a,b,c", ids(g.cards));
    }

    private static void testTwoGroups() {
        List<Engine.Card> cards = Arrays.asList(
                card("d", NOW + 7 * HOUR),
                card("a", NOW + 2 * HOUR),
                card("c", NOW + 8 * HOUR),
                card("b", NOW + 2 * HOUR + 30 * MINUTE));
        List<ClashPlan.Clash> groups = ClashPlan.find(cards, NOW);
        eq("两个互不相干的组返回 2 组", 2, groups.size());
        if (groups.size() < 2) return;
        eq("第 1 组是最早的组", NOW + 2 * HOUR, groups.get(0).start);
        eq("第 1 组成员", "a,b", ids(groups.get(0).cards));
        eq("第 2 组按最早截止时间排在后", NOW + 7 * HOUR, groups.get(1).start);
        eq("第 2 组成员", "c,d", ids(groups.get(1).cards));

        ClashPlan.Clash nearest = ClashPlan.nearest(cards, NOW);
        check("nearest 返回第一组", nearest != null);
        if (nearest != null) {
            eq("nearest 的 start", NOW + 2 * HOUR, nearest.start);
            eq("nearest 的成员", "a,b", ids(nearest.cards));
        }
    }

    private static void testParticipants() {
        eq("两张已完成的卡不参与", 0,
                find(doneCard("d1", NOW + 3 * HOUR),
                        doneCard("d2", NOW + 3 * HOUR + MINUTE)).size());
        eq("已完成卡不参与，只剩一张待办也不成组", 0,
                find(doneCard("d1", NOW + 3 * HOUR),
                        card("t1", NOW + 3 * HOUR + MINUTE)).size());

        eq("两张待确认的卡不参与", 0,
                find(unsureCard("u1", NOW + 3 * HOUR),
                        unsureCard("u2", NOW + 3 * HOUR + MINUTE)).size());
        eq("待确认卡不参与，只剩一张待办也不成组", 0,
                find(unsureCard("u1", NOW + 3 * HOUR),
                        card("t1", NOW + 3 * HOUR + MINUTE)).size());

        eq("deadline 为 0 的卡不参与", 0,
                find(card("z1", 0), card("z2", 0)).size());

        eq("已过期的卡不参与", 0,
                find(card("p1", NOW - HOUR),
                        card("p2", NOW - HOUR + MINUTE)).size());
        eq("截止时间正好是 now 的卡不参与", 0,
                find(card("n1", NOW), card("n2", NOW + MINUTE)).size());

        eq("超过 7 天的卡不参与（now+7 天+1 毫秒）", 0,
                find(card("f1", NOW + 7 * DAY + 1),
                        card("f2", NOW + 7 * DAY + 2)).size());

        List<ClashPlan.Clash> groups = find(
                card("e1", NOW + 7 * DAY),
                card("e2", NOW + 7 * DAY - HOUR));
        eq("now+7 天整的卡参与：1 组", 1, groups.size());
        if (!groups.isEmpty()) {
            eq("now+7 天整的组内 2 张卡", 2, groups.get(0).cards.size());
        }
    }

    private static void testEmpty() {
        eq("没有卡时 find 返回空列表", 0,
                ClashPlan.find(Collections.<Engine.Card>emptyList(), NOW).size());
        check("没有卡时 nearest 返回 null",
                ClashPlan.nearest(Collections.<Engine.Card>emptyList(), NOW) == null);

        List<Engine.Card> one = Collections.singletonList(card("only", NOW + HOUR));
        eq("只有一张卡时 find 返回空列表", 0, ClashPlan.find(one, NOW).size());
        check("只有一张卡时 nearest 返回 null",
                ClashPlan.nearest(one, NOW) == null);
    }

    private static void testText() {
        List<ClashPlan.Clash> groups = find(
                titled("a", NOW + 2 * HOUR, "实验报告"),
                titled("b", NOW + 3 * HOUR, "体测报名"),
                titled("c", NOW + 4 * HOUR, "读书报告"));
        eq("3 张卡同一组", 1, groups.size());
        if (groups.isEmpty()) return;
        ClashPlan.Clash g = groups.get(0);
        String text = g.text();
        check("text 以 humanDeadline(start) 开头",
                text.startsWith(Engine.humanDeadline(g.start)));
        check("text 说明前后有 3 件事",
                text.contains("前后有 3 件事挤在一起："));
        check("text 含三个标题且用、连接",
                text.contains("实验报告、体测报名、读书报告"));

        List<ClashPlan.Clash> four = find(
                titled("a", NOW + 2 * HOUR, "实验报告"),
                titled("b", NOW + 2 * HOUR + 30 * MINUTE, "体测报名"),
                titled("c", NOW + 3 * HOUR, "读书报告"),
                titled("d", NOW + 3 * HOUR + 30 * MINUTE, "社团面试"));
        eq("4 张卡同一组", 1, four.size());
        if (four.isEmpty()) return;
        String text4 = four.get(0).text();
        check("4 张卡时说明 4 件事",
                text4.contains("前后有 4 件事挤在一起："));
        check("4 张卡时列出前三个标题", text4.contains("实验报告、体测报名、读书报告"));
        check("4 张卡时以等结尾", text4.endsWith("等"));
        check("4 张卡时不列第 4 个标题", !text4.contains("社团面试"));
    }

    private static void testExamWithTodo() {
        List<ClashPlan.Clash> groups = find(
                examCard("exam", NOW + 2 * HOUR),
                card("todo", NOW + 3 * HOUR));
        eq("考试卡和待办卡一起算撞车", 1, groups.size());
        if (groups.isEmpty()) return;
        eq("考试卡与待办卡组内 2 张", 2, groups.get(0).cards.size());
        eq("考试卡与待办卡按截止时间升序", "exam,todo", ids(groups.get(0).cards));
    }
}
