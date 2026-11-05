package com.nopalou;

import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/** 今日视图的纯逻辑：分组、倒计时、紧急程度和顶部一句话。 */
final class TodayView {
    /** 分组的固定显示顺序。 */
    static final String[] ORDER = {"今天", "明天", "这几天", "更晚", "待确认", "已过期", "已完成"};

    /** 一个自然日的毫秒数，只用于区分“小时级”和“天级”的粗判断。 */
    private static final long DAY_MS = 24 * 3600_000L;
    /** 一小时的毫秒数。 */
    private static final long HOUR_MS = 3600_000L;
    /** 一分钟的毫秒数。 */
    private static final long MINUTE_MS = 60_000L;

    /** 工具类，不实例化。 */
    private TodayView() {
    }

    /** 按规则顺序判断卡片属于哪个分组。 */
    static String bucket(long deadline, boolean needsConfirm, boolean done, long now) {
        TimeZone zone = TimeZone.getDefault();
        return bucket(deadline, needsConfirm, done, now, localDay(now, zone), zone);
    }

    private static String bucket(long deadline, boolean needsConfirm, boolean done, long now,
                                 long today, TimeZone zone) {
        // 已完成优先于其他所有状态。
        if (done) return "已完成";
        // 需要确认，或者没有截止时间，都归入待确认。
        if (needsConfirm || deadline == 0L) return "待确认";
        // 截止时刻已到或已过，属于已过期。
        if (deadline <= now) return "已过期";
        // 计算 deadline 比 now 晚几个自然日。
        long days = localDay(deadline, zone) - today;
        // 同一个自然日归入今天。
        if (days == 0) return "今天";
        // 下一个自然日归入明天。
        if (days == 1) return "明天";
        // 相隔 2 到 7 个自然日归入这几天。
        if (days <= 7) return "这几天";
        // 超过 7 个自然日归入更晚。
        return "更晚";
    }

    /** 生成倒计时文字，所有除法都向下取整。 */
    static String countdown(long deadline, long now) {
        // 没有截止时间时无法倒计时。
        if (deadline == 0L) return "时间待确认";
        // 先算出与当前时刻的差值，负数表示已过期。
        long diff = deadline - now;
        if (diff <= 0L) {
            // 过期时长取正值。
            long late = -diff;
            // 不到 1 小时只说刚过期。
            if (late < HOUR_MS) return "刚过期";
            // 不到 24 小时按小时报，向下取整。
            if (late < DAY_MS) return "已过期" + (late / HOUR_MS) + "小时";
            // 超过 24 小时按天报，向下取整。
            return "已过期" + (late / DAY_MS) + "天";
        }
        // 不到 60 分钟按分钟报，至少显示 1 分钟。
        if (diff < HOUR_MS) {
            long minutes = diff / MINUTE_MS;
            if (minutes < 1L) minutes = 1L;
            return "还剩" + minutes + "分钟";
        }
        // 不到 24 小时按小时报，向下取整。
        if (diff < DAY_MS) return "还剩" + (diff / HOUR_MS) + "小时";
        // 超过 24 小时改用自然日描述。
        long days = daysBetween(now, deadline);
        // 下一个自然日显示具体时刻。
        if (days <= 1) return "明天 " + hhmm(deadline);
        // 下两个自然日显示具体时刻。
        if (days == 2) return "后天 " + hhmm(deadline);
        // 更久只显示天数。
        return days + "天后";
    }

    /** 计算紧急程度：0 普通、1 一天内、2 三小时内、3 已过期。 */
    static int urgency(long deadline, boolean needsConfirm, boolean done, long now) {
        // 已完成、待确认或没有截止时间都不标紧急。
        if (done || needsConfirm || deadline == 0L) return 0;
        // 已过期给最高等级。
        if (deadline <= now) return 3;
        // 距离截止的剩余毫秒数。
        long diff = deadline - now;
        // 三小时以内为第二级。
        if (diff <= 3 * HOUR_MS) return 2;
        // 二十四小时以内为第一级。
        if (diff <= DAY_MS) return 1;
        // 其余不标紧急。
        return 0;
    }

    /** 生成顶部一句话，只统计未完成的卡片。 */
    static String summary(List<Engine.Card> cards, long now) {
        TimeZone zone = TimeZone.getDefault();
        long todayDay = localDay(now, zone);
        // 分别统计今天、明天、已过期、待确认的未完成卡片数量。
        int today = 0;
        int tomorrow = 0;
        int expired = 0;
        int confirm = 0;
        // 未完成卡片总数，用来判断是否完全没有待办。
        int unfinished = 0;
        for (Engine.Card card : cards) {
            // 已完成的卡片不参与统计。
            if (card.done) continue;
            // 累加未完成数量。
            unfinished++;
            // 用统一的分组规则判断归属。
            String bucket = bucket(card.deadline, card.needsConfirm, false, now, todayDay, zone);
            if ("今天".equals(bucket)) {
                // 今天的卡片计数。
                today++;
            } else if ("明天".equals(bucket)) {
                // 明天的卡片计数。
                tomorrow++;
            } else if ("已过期".equals(bucket)) {
                // 已过期的卡片计数。
                expired++;
            } else if ("待确认".equals(bucket)) {
                // 待确认的卡片计数。
                confirm++;
            }
            // 这几天和更晚的卡片不写进顶部一句话。
        }
        // 没有任何未完成卡片时给出明确提示。
        if (unfinished == 0) return "目前没有待办";
        // 用固定分隔符拼接各片段。
        StringBuilder sb = new StringBuilder();
        // 今天片段始终出现：有卡片报数量，没有则说明今天没有截止。
        sb.append(today > 0 ? "今天 " + today + " 件" : "今天没有截止");
        // 明天有卡片才追加。
        if (tomorrow > 0) sb.append(" · 明天 ").append(tomorrow).append(" 件");
        // 已过期有卡片才追加。
        if (expired > 0) sb.append(" · 已过期 ").append(expired).append(" 件");
        // 待确认有卡片才追加。
        if (confirm > 0) sb.append(" · 待确认 ").append(confirm).append(" 件");
        return sb.toString();
    }

    /** 各自时刻的时区偏移参与计算，夏令时的 23/25 小时不会变成错误日期。 */
    private static long localDay(long time, TimeZone zone) {
        // 先拆成整天和余数，避免 time + offset 在极端时间戳处溢出。
        return Math.floorDiv(time, DAY_MS)
                + Math.floorDiv(Math.floorMod(time, DAY_MS) + zone.getOffset(time), DAY_MS);
    }

    /**
     * 计算两个时刻相隔的自然日数，to 晚于 from 为正数。
     * 直接比较本地日期的日序号，成本不随两日期之间的天数增长。
     */
    private static long daysBetween(long from, long to) {
        TimeZone zone = TimeZone.getDefault();
        return localDay(to, zone) - localDay(from, zone);
    }

    /** 按本地时区格式化为 HH:mm。 */
    private static String hhmm(long time) {
        // 用本地时区读取时分。
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(time);
        return String.format("%02d:%02d",
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }
}
