package com.nopalou;

import java.util.List;

/** 通知解析：单条/多条、发送者分隔符、折叠计数、占位符与纯计数清洗。 */
public final class Step21Test {

    private static int failures;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "✓ " : "✗ ") + name);
        if (!ok) failures++;
    }

    private static void eq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        check(name + (ok ? "" : "（期望=" + expected + "，实际=" + actual + "）"), ok);
    }

    /** 单条解析，另两个数组为 null。 */
    private static List<NotifParser.Parsed> single(String title, String text) {
        return NotifParser.parse(title, text, null, null, null, false);
    }

    /** 多条解析，title/text 为 null。 */
    private static List<NotifParser.Parsed> multi(String[] senders, String[] texts, long[] times) {
        return NotifParser.parse(null, null, senders, texts, times, false);
    }

    private static void one(String name, List<NotifParser.Parsed> list,
                            String sender, String body, long time) {
        if (list == null) {
            check(name + "：返回了 null", false);
            return;
        }
        eq(name + "：数量", 1, list.size());
        if (list.size() != 1) return;
        NotifParser.Parsed p = list.get(0);
        eq(name + "：sender", sender, p.sender);
        eq(name + "：body", body, p.body);
        eq(name + "：time", time, p.time);
    }

    public static void main(String[] args) {
        testSingle();
        testEnglishColon();
        testFoldCount();
        testNoSeparator();
        testClockColon();
        testPlaceholders();
        testCounter();
        testSummary();
        testMulti();
        testMultiSkipsEmpty();
        testMultiBeforeText();
        testMultiFoldCount();
        testMultiMismatch();
        testAllNull();
        testWhitespace();

        if (failures > 0) {
            System.out.println(failures + " 项未通过");
            System.exit(1);
        }
        System.out.println("Step21 全部通过");
    }

    private static void testSingle() {
        one("普通单条",
                single("数据库系统课程群", "王老师：明天20:00前交实验报告"),
                "王老师", "明天20:00前交实验报告", 0L);
    }

    private static void testEnglishColon() {
        one("半角冒号也算分隔符",
                single(null, "王老师: 明天交"),
                "王老师", "明天交", 0L);
    }

    private static void testFoldCount() {
        one("折叠计数 [3条] 要剥掉",
                single("数据库系统课程群", "[3条]王老师：明天交报告"),
                "王老师", "明天交报告", 0L);
        one("折叠计数 [12条] 带空格也要剥掉",
                single("班级群", "[12条] 班长：周五体测"),
                "班长", "周五体测", 0L);
    }

    private static void testNoSeparator() {
        one("没有分隔符且 title 非空：群友",
                single("某群", "明天交报告"),
                "群友", "明天交报告", 0L);
        one("没有分隔符且 title 为空串：群消息",
                single("", "明天交报告"),
                "群消息", "明天交报告", 0L);
        one("没有分隔符且 title 为 null：群消息",
                single(null, "明天交报告"),
                "群消息", "明天交报告", 0L);
    }

    private static void testClockColon() {
        one("钟点里的冒号不算分隔符",
                single("某群", "明天20:30前交"),
                "群友", "明天20:30前交", 0L);
    }

    private static void testPlaceholders() {
        String[] only = {"[图片]", "[表情]", "[语音]", "[视频]", "[文件]", "[动画表情]", "[位置]", "[链接]"};
        for (String p : only) {
            List<NotifParser.Parsed> list = single("某群", p);
            if (list == null) {
                check("占位符 " + p + "：返回了 null", false);
                continue;
            }
            eq("单独占位符 " + p + " 返回空列表", 0, list.size());
        }

        List<NotifParser.Parsed> withSender = single("某群", "王老师：[图片]");
        if (withSender == null) {
            check("王老师：[图片]：返回了 null", false);
        } else {
            eq("发送者后只剩占位符也视为空", 0, withSender.size());
        }

        one("夹带占位符不处理",
                single("某群", "王老师：[图片] 作业在这里"),
                "王老师", "[图片] 作业在这里", 0L);
    }

    private static void testCounter() {
        String[] counters = {"3条新消息", "你有5条消息", "[3条]"};
        for (String c : counters) {
            List<NotifParser.Parsed> list = single("某群", c);
            if (list == null) {
                check("纯计数 " + c + "：返回了 null", false);
                continue;
            }
            eq("纯计数通知 " + c + " 返回空列表", 0, list.size());
        }
    }

    private static void testSummary() {
        List<NotifParser.Parsed> list =
                NotifParser.parse("某群", "王老师：明天交报告", null, null, null, true);
        if (list == null) {
            check("isSummary：返回了 null", false);
            return;
        }
        eq("isSummary 即使有 text 也返回空列表", 0, list.size());
    }

    private static void testMulti() {
        List<NotifParser.Parsed> list = multi(
                new String[]{"王老师", "班长", ""},
                new String[]{"明天交报告", "周五体测", "收到"},
                new long[]{1000L, 2000L, 3000L});
        if (list == null) {
            check("多条消息：返回了 null", false);
            return;
        }
        eq("多条消息数量", 3, list.size());
        if (list.size() != 3) return;
        eq("第 1 条 sender", "王老师", list.get(0).sender);
        eq("第 1 条 body", "明天交报告", list.get(0).body);
        eq("第 1 条 time", 1000L, list.get(0).time);
        eq("第 2 条 sender", "班长", list.get(1).sender);
        eq("第 2 条 body", "周五体测", list.get(1).body);
        eq("第 2 条 time", 2000L, list.get(1).time);
        eq("第 3 条空 sender 用群友", "群友", list.get(2).sender);
        eq("第 3 条 body", "收到", list.get(2).body);
        eq("第 3 条 time", 3000L, list.get(2).time);
    }

    private static void testMultiSkipsEmpty() {
        List<NotifParser.Parsed> list = multi(
                new String[]{"王老师", "班长", "张三"},
                new String[]{"[图片]", "明天交", "  "},
                new long[]{1000L, 2000L, 3000L});
        if (list == null) {
            check("多条含空：返回了 null", false);
            return;
        }
        eq("占位符和空白的条被跳过", 1, list.size());
        if (list.size() != 1) return;
        eq("留下的是第 2 条 sender", "班长", list.get(0).sender);
        eq("留下的是第 2 条 body", "明天交", list.get(0).body);
        eq("留下的是第 2 条 time", 2000L, list.get(0).time);
    }

    private static void testMultiBeforeText() {
        List<NotifParser.Parsed> list = NotifParser.parse(
                "某群", "别的内容",
                new String[]{"A"}, new String[]{"收到"}, new long[]{7L}, false);
        if (list == null) {
            check("多条优先于 text：返回了 null", false);
            return;
        }
        eq("有多条时忽略 text", 1, list.size());
        if (list.size() != 1) return;
        eq("多条优先：sender", "A", list.get(0).sender);
        eq("多条优先：body", "收到", list.get(0).body);
        eq("多条优先：time", 7L, list.get(0).time);
    }

    private static void testMultiFoldCount() {
        one("多条消息里的折叠计数也清洗",
                multi(new String[]{"班长"}, new String[]{"[2条]周五交"}, new long[]{0L}),
                "班长", "周五交", 0L);
    }

    private static void testMultiMismatch() {
        List<NotifParser.Parsed> nullSenders = multi(null, new String[]{"x"}, new long[]{5L});
        if (nullSenders == null) {
            check("msgSenders 为 null：返回了 null", false);
        } else {
            eq("msgSenders 为 null 时数量", 1, nullSenders.size());
            if (nullSenders.size() == 1) {
                eq("msgSenders 为 null 时 sender", "群友", nullSenders.get(0).sender);
                eq("msgSenders 为 null 时 body", "x", nullSenders.get(0).body);
                eq("msgSenders 为 null 时 time", 5L, nullSenders.get(0).time);
            }
        }

        List<NotifParser.Parsed> shortTimes = multi(
                new String[]{"甲", "乙"}, new String[]{"a", "b"}, new long[]{1000L});
        if (shortTimes == null) {
            check("msgTimes 偏短：返回了 null", false);
        } else {
            eq("msgTimes 偏短时数量", 2, shortTimes.size());
            if (shortTimes.size() == 2) {
                eq("缺的时间取 0（第 1 条仍为 1000）", 1000L, shortTimes.get(0).time);
                eq("缺的时间取 0（第 2 条）", 0L, shortTimes.get(1).time);
                eq("缺的时间不影响 body", "b", shortTimes.get(1).body);
            }
        }
    }

    private static void testAllNull() {
        try {
            List<NotifParser.Parsed> list = NotifParser.parse(null, null, null, null, null, false);
            if (list == null) {
                check("全部为 null：返回了 null", false);
            } else {
                eq("全部为 null 返回空列表", 0, list.size());
            }
        } catch (RuntimeException e) {
            check("全部为 null 不能抛异常：" + e, false);
        }
    }

    private static void testWhitespace() {
        one("发送者和正文去首尾空白",
                single("某群", "  王老师 ：  明天交  "),
                "王老师", "明天交", 0L);
    }
}
