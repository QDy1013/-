package com.nopalou;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TimeZone;

/** 日期分组独立对照，以及统计成本不随截止时间跨度增长的回归。 */
public final class Step30Test {
    private static final long DAY = 86400_000L;
    private static int failures;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "✓ " : "✗ ") + name);
        if (!ok) failures++;
    }

    private static Engine.Card card(long deadline) {
        Engine.Card c = new Engine.Card();
        c.deadline = deadline;
        return c;
    }

    private static String expectedBucket(long deadline, long now, ZoneId zone) {
        if (deadline == 0) return "待确认";
        if (deadline <= now) return "已过期";
        long days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(),
                Instant.ofEpochMilli(deadline).atZone(zone).toLocalDate());
        if (days == 0) return "今天";
        if (days == 1) return "明天";
        return days <= 7 ? "这几天" : "更晚";
    }

    private static final class CountingZone extends TimeZone {
        static long calls;
        CountingZone() { setID("CountingUTC"); }
        @Override public int getOffset(long date) { calls++; return 0; }
        @Override public int getOffset(int era, int year, int month, int day, int weekday, int millis) {
            calls++;
            return 0;
        }
        @Override public int getRawOffset() { return 0; }
        @Override public void setRawOffset(int offset) { }
        @Override public boolean useDaylightTime() { return false; }
        @Override public boolean inDaylightTime(java.util.Date date) { return false; }
    }

    public static void main(String[] args) {
        TimeZone original = TimeZone.getDefault();
        try {
            Random random = new Random(930);
            for (String name : new String[]{"Asia/Shanghai", "UTC", "America/New_York", "Europe/Berlin",
                    "Australia/Lord_Howe", "Pacific/Chatham"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(name));
                ZoneId zone = ZoneId.of(name);
                boolean matches = true;
                for (int i = 0; i < 500; i++) {
                    long now = 1_577_836_800_000L + Math.floorMod(random.nextLong(), 10 * 366 * DAY);
                    long deadline = now + (random.nextInt(30) - 3) * DAY + random.nextInt(86400) * 1000L;
                    matches &= expectedBucket(deadline, now, zone).equals(TodayView.bucket(deadline, false, false, now));
                }
                check(name + " 的 500 个日期与独立 LocalDate 对照一致", matches);
            }
            ZoneId ny = ZoneId.of("America/New_York");
            TimeZone.setDefault(TimeZone.getTimeZone(ny));
            for (LocalDate date : new LocalDate[]{LocalDate.of(2026, 3, 8), LocalDate.of(2026, 11, 1)}) {
                ZonedDateTime start = date.atStartOfDay(ny);
                long now = start.toInstant().toEpochMilli();
                long tomorrow = start.plusDays(1).toInstant().toEpochMilli();
                List<Engine.Card> cards = java.util.Arrays.asList(card(now + 1000), card(tomorrow), card(tomorrow - 1));
                check("夏令时切换日的统计按本地日期分组 " + date,
                        "今天 2 件 · 明天 1 件".equals(TodayView.summary(cards, now)));
            }
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            long now = LocalDate.of(2026, 11, 5).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
            LocalDate distant = LocalDate.of(9999, 12, 31);
            long far = distant.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
            check("远期日期仍有正确分组与天数", "更晚".equals(TodayView.bucket(far, false, false, now))
                    && (ChronoUnit.DAYS.between(LocalDate.of(2026, 11, 5), distant) + "天后")
                    .equals(TodayView.countdown(far, now)));

            TimeZone.setDefault(new CountingZone());
            List<Engine.Card> near = new ArrayList<>();
            List<Engine.Card> later = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                near.add(card(now + 2 * DAY));
                later.add(card(now + 5000 * DAY));
            }
            CountingZone.calls = 0;
            String nearText = TodayView.summary(near, now);
            long nearCost = CountingZone.calls;
            CountingZone.calls = 0;
            String farText = TodayView.summary(later, now);
            long farCost = CountingZone.calls;
            check("统计成本不随截止日期从两天扩大到五千天而增长",
                    nearText.equals(farText) && farCost <= nearCost * 4 + 128);
            Engine.Card unsure = card(now + 1000);
            unsure.needsConfirm = true;
            check("待确认状态不混入今天", "今天没有截止 · 待确认 1 件"
                    .equals(TodayView.summary(Collections.singletonList(unsure), now)));
        } finally {
            TimeZone.setDefault(original);
        }
        if (failures > 0) System.exit(1);
    }
}
