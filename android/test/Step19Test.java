package com.nopalou;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/** 今日视图的分组、倒计时、紧急程度和顶部一句话。 */
public final class Step19Test {
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

    private static String bucket(long deadline) {
        return TodayView.bucket(deadline, false, false, NOW);
    }

    public static void main(String[] args) {
        testBucket();
        testOrder();
        testCountdown();
        testUrgency();
        testSummary();
        testConsistency();

        if (failures > 0) {
            System.out.println(failures + " 项未通过");
            System.exit(1);
        }
        System.out.println("Step19 全部通过");
    }

    private static void testBucket() {
        eq("done 优先于一切（含待确认和过期）",
                "已完成", TodayView.bucket(NOW - HOUR, true, true, NOW));
        eq("done 优先于待确认截止时间为 0",
                "已完成", TodayView.bucket(0, true, true, NOW));
        eq("done 优先于未来截止",
                "已完成", TodayView.bucket(NOW + DAY, false, true, NOW));

        eq("needsConfirm 为真时是待确认",
                "待确认", TodayView.bucket(NOW + HOUR, true, false, NOW));
        eq("needsConfirm 且已过期仍是待确认",
                "待确认", TodayView.bucket(NOW - HOUR, true, false, NOW));
        eq("deadline 为 0 是待确认",
                "待确认", TodayView.bucket(0, false, false, NOW));

        eq("已过期（昨天）",
                "已过期", TodayView.bucket(at(2026, 11, 9, 9, 0, 0), false, false, NOW));
        eq("今天 08:59 比 now 早一分钟是已过期",
                "已过期", TodayView.bucket(at(2026, 11, 10, 8, 59, 0), false, false, NOW));
        eq("截止时刻等于 now 是已过期",
                "已过期", TodayView.bucket(NOW, false, false, NOW));

        eq("今天晚些时候（同日 20:00）",
                "今天", TodayView.bucket(at(2026, 11, 10, 20, 0, 0), false, false, NOW));
        eq("明天 00:30 虽然是十几个小时后但属于明天",
                "明天", TodayView.bucket(at(2026, 11, 11, 0, 30, 0), false, false, NOW));
        eq("明天同一时刻是明天",
                "明天", TodayView.bucket(NOW + DAY, false, false, NOW));

        eq("后天属于这几天",
                "这几天", TodayView.bucket(at(2026, 11, 12, 9, 0, 0), false, false, NOW));
        eq("now 后第 7 个自然日仍是这几天",
                "这几天", TodayView.bucket(at(2026, 11, 17, 9, 0, 0), false, false, NOW));
        eq("now 后第 8 个自然日是更晚",
                "更晚", TodayView.bucket(at(2026, 11, 18, 9, 0, 0), false, false, NOW));
        eq("相隔超过一周是更晚",
                "更晚", TodayView.bucket(at(2026, 12, 10, 9, 0, 0), false, false, NOW));
    }

    private static void testOrder() {
        String[] expected = {"今天", "明天", "这几天", "更晚", "待确认", "已过期", "已完成"};
        eq("ORDER 长度与规则一致", expected.length, TodayView.ORDER.length);
        for (int i = 0; i < expected.length && i < TodayView.ORDER.length; i++) {
            eq("ORDER[" + i + "] 顺序", expected[i], TodayView.ORDER[i]);
        }
    }

    private static void testCountdown() {
        eq("截止时间为 0",
                "时间待确认", TodayView.countdown(0, NOW));
        eq("晚 30 分钟",
                "还剩30分钟", TodayView.countdown(NOW + 30 * MINUTE, NOW));
        eq("晚 59 分 59 秒向下取整为 59 分钟",
                "还剩59分钟", TodayView.countdown(NOW + 59 * MINUTE + 59_000L, NOW));
        eq("晚 1 小时整",
                "还剩1小时", TodayView.countdown(NOW + HOUR, NOW));
        eq("晚 5 小时 40 分向下取整",
                "还剩5小时", TodayView.countdown(NOW + 5 * HOUR + 40 * MINUTE, NOW));
        eq("明天 08:59 仍不足 24 小时",
                "还剩23小时", TodayView.countdown(at(2026, 11, 11, 8, 59, 0), NOW));
        eq("明天 20:00 按自然日",
                "明天 20:00", TodayView.countdown(at(2026, 11, 11, 20, 0, 0), NOW));
        eq("后天 08:30 按自然日",
                "后天 08:30", TodayView.countdown(at(2026, 11, 12, 8, 30, 0), NOW));
        eq("now 后第 5 个自然日",
                "5天后", TodayView.countdown(at(2026, 11, 15, 9, 0, 0), NOW));
        eq("已过期 30 分钟",
                "刚过期", TodayView.countdown(NOW - 30 * MINUTE, NOW));
        eq("已过期 3 小时 10 分向下取整",
                "已过期3小时", TodayView.countdown(NOW - 3 * HOUR - 10 * MINUTE, NOW));
        eq("已过期 2 天 5 小时",
                "已过期2天", TodayView.countdown(NOW - 2 * DAY - 5 * HOUR, NOW));
    }

    private static void testUrgency() {
        eq("done 为 0", 0, TodayView.urgency(NOW - HOUR, false, true, NOW));
        eq("needsConfirm 为 0", 0, TodayView.urgency(NOW + HOUR, true, false, NOW));
        eq("deadline 为 0 时紧急程度为 0", 0, TodayView.urgency(0, false, false, NOW));
        eq("已过期是 3", 3, TodayView.urgency(NOW - MINUTE, false, false, NOW));
        eq("晚 3 小时整是 2", 2, TodayView.urgency(NOW + 3 * HOUR, false, false, NOW));
        eq("晚 3 小时 1 分是 1", 1, TodayView.urgency(NOW + 3 * HOUR + MINUTE, false, false, NOW));
        eq("晚 24 小时整是 1", 1, TodayView.urgency(NOW + DAY, false, false, NOW));
        eq("晚 24 小时 1 分是 0", 0, TodayView.urgency(NOW + DAY + MINUTE, false, false, NOW));
    }

    private static void testSummary() {
        eq("空列表",
                "目前没有待办", TodayView.summary(Collections.<Engine.Card>emptyList(), NOW));
        eq("只有已完成的卡",
                "目前没有待办",
                TodayView.summary(Arrays.asList(
                        doneCard("d1", NOW + HOUR),
                        doneCard("d2", NOW - HOUR)), NOW));

        List<Engine.Card> mixed = new ArrayList<>();
        mixed.add(card("t1", at(2026, 11, 10, 20, 0, 0)));
        mixed.add(card("t2", NOW + 30 * MINUTE));
        mixed.add(card("m1", at(2026, 11, 11, 0, 30, 0)));
        mixed.add(card("p1", NOW - 2 * HOUR));
        mixed.add(unsureCard("u1", NOW + HOUR));
        eq("今天 2 张、明天 1 张、已过期 1 张、待确认 1 张",
                "今天 2 件 · 明天 1 件 · 已过期 1 件 · 待确认 1 件",
                TodayView.summary(mixed, NOW));

        eq("只有一张这几天的卡时说明今天没有截止",
                "今天没有截止",
                TodayView.summary(Collections.singletonList(card("later", NOW + 3 * DAY)), NOW));
        eq("只有一张更晚的卡时说明今天没有截止",
                "今天没有截止",
                TodayView.summary(Collections.singletonList(card("far", NOW + 30 * DAY)), NOW));
        eq("今天 1 张加一张已完成",
                "今天 1 件",
                TodayView.summary(Arrays.asList(
                        card("t1", NOW + HOUR),
                        doneCard("d1", NOW + 40 * MINUTE)), NOW));
    }

    private static void testConsistency() {
        long[] offsets = {
                -30 * DAY, -7 * DAY, -2 * DAY, -25 * HOUR, -24 * HOUR,
                -3 * HOUR - 10 * MINUTE, -HOUR, -MINUTE, 0L, MINUTE,
                30 * MINUTE, 59 * MINUTE, HOUR, 3 * HOUR, 12 * HOUR,
                23 * HOUR + 59 * MINUTE, DAY, 3 * DAY, 7 * DAY, 8 * DAY
        };
        for (long offset : offsets) {
            long deadline = NOW + offset;
            String bucket = TodayView.bucket(deadline, false, false, NOW);
            String countdown = TodayView.countdown(deadline, NOW);
            boolean bucketExpired = "已过期".equals(bucket);
            boolean countdownExpired = countdown.startsWith("已过期") || countdown.startsWith("刚过期");
            eq("偏移 " + offset + " 毫秒的分组与倒计时一致", bucketExpired, countdownExpired);
        }
    }
}
