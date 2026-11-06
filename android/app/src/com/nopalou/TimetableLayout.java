package com.nopalou;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Merge overlapping meetings into a tappable group instead of painting over a course. */
final class TimetableLayout {
    static final class Block {
        final int weekday, start;
        int end;
        final List<Timetable.Lesson> lessons = new ArrayList<>();
        Block(Timetable.Lesson lesson) {
            weekday = lesson.weekday; start = lesson.start; end = lesson.end;
            lessons.add(lesson);
        }
    }
    static List<Block> blocks(Timetable table, int week) {
        List<Timetable.Lesson> active = new ArrayList<>();
        for (Timetable.Lesson l : table.lessons) if (l.weeks.contains(week)) active.add(l);
        active.sort(Comparator.comparingInt((Timetable.Lesson l) -> l.weekday).thenComparingInt(l -> l.start));
        List<Block> result = new ArrayList<>();
        for (Timetable.Lesson l : active) {
            Block last = result.isEmpty() ? null : result.get(result.size() - 1);
            if (last != null && last.weekday == l.weekday && l.start <= last.end) {
                last.end = Math.max(last.end, l.end); last.lessons.add(l);
            } else result.add(new Block(l));
        }
        return result;
    }
}
