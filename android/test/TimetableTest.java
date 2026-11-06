package com.nopalou;

import java.nio.file.Files;
import java.nio.file.Paths;
import org.json.JSONObject;

/** Run with org.json on the JVM; the Android build supplies it from the platform. */
public class TimetableTest {
    static int checks;
    static void ok(boolean b, String name) { if (!b) throw new AssertionError(name); checks++; System.out.println("PASS " + name); }
    public static void main(String[] args) throws Exception {
        String fixture = Files.readString(Paths.get(args[0]));
        Timetable t = new Timetable(fixture);
        ok(t.firstMonday.equals("2026-08-31"), "first week Monday");
        ok(t.maxWeek == 5, "sparse week range");
        ok(t.lessons.get(0).weekText().equals("1、3、5周"), "do not invent even weeks");
        ok(!t.lessons.get(0).weeks.contains(2), "even weeks excluded");
        ok(t.time(t.lessons.get(0)).contains("08:00–09:40"), "school period times");
        ok(Timetable.monday("2026-09-06").equals("2026-08-31"), "Sunday belongs to previous Monday");
        ok(Timetable.monday("2026-09-07").equals("2026-09-07"), "Monday preserved");
        ok(Timetable.monday("2026-02-30").isEmpty(), "invalid dates rejected");
        ok(Timetable.monday("2026-08-31suffix").isEmpty(), "date trailing content rejected");
        ok(t.dayLabel(2, 7).equals("周日 · 09-13"), "week navigation dates");
        Timetable restored = new Timetable(t.encode());
        ok(restored.lessons.size() == 1 && restored.firstMonday.equals(t.firstMonday), "storage serialization roundtrip");
        t.firstMonday = "2026-09-01";
        boolean invalid = false;
        try { t.encode(); } catch (Exception e) { invalid = true; }
        ok(invalid, "save requires a Monday");
        JSONObject json = new JSONObject(fixture);
        json.getJSONArray("lessons").getJSONObject(0).put("weekday", 0);
        invalid = false;
        try { new Timetable(json.toString()); } catch (Exception e) { invalid = true; }
        ok(invalid, "invalid stored courses cannot be loaded");
        System.out.println(checks + " checks passed");
    }
}
