package com.nopalou;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exams, grades and GPA read from the school system; stored apart from the timetable and message cards. */
final class Academic {
    static final int MAX_BYTES = 2_000_000;
    static final String[] PARTS = {"exams", "grades", "gpa"};
    final JSONObject json;
    final List<Exam> exams = new ArrayList<>();
    final List<Grade> grades = new ArrayList<>();
    final boolean hasExams, hasGrades;
    final Double gpa;

    static final class Exam {
        final String course, time, location, status, date, start, end;
        final boolean finished;
        Exam(JSONObject j) throws JSONException {
            course = Timetable.required(j, "course", 200);
            time = limit(j.optString("time", ""), 120);
            location = limit(j.optString("location", ""), 300);
            status = limit(j.optString("status", ""), 100);
            date = Timetable.calendar(j.optString("date", "")) == null ? "" : j.optString("date");
            start = clock(j.optString("start", "")); end = clock(j.optString("end", ""));
            finished = j.getBoolean("finished");
        }
        /** Days from today (local date); Integer.MIN_VALUE when the date is unknown. */
        int daysFrom(String today) {
            java.util.Calendar a = Timetable.calendar(today), b = Timetable.calendar(date);
            if (a == null || b == null) return Integer.MIN_VALUE;
            return (int) ((b.getTimeInMillis() - a.getTimeInMillis()) / 86400000L);
        }
    }

    static final class Grade {
        final String semesterId, semester, name, code, type, score, detail;
        final Double gp, credits;
        Grade(JSONObject j) throws JSONException {
            semesterId = limit(j.optString("semesterId", ""), 40);
            semester = limit(j.optString("semester", semesterId), 100);
            name = Timetable.required(j, "name", 200);
            code = limit(j.optString("code", ""), 60);
            type = limit(j.optString("type", ""), 60);
            score = limit(j.optString("score", ""), 30);
            detail = limit(j.optString("detail", ""), 500);
            gp = optNumber(j, "gp", 0, 5); credits = optNumber(j, "credits", 0, 100);
        }
    }

    Academic(String value) throws JSONException {
        if (value == null || value.length() > MAX_BYTES) throw new JSONException("考试成绩数据过大或缺失");
        json = new JSONObject(value);
        if (json.getInt("version") != 1 || TimetableSchool.byName(json.getString("school")) == null)
            throw new JSONException("考试成绩格式不受支持");
        JSONArray e = json.optJSONArray("exams"), g = json.optJSONArray("grades");
        hasExams = e != null; hasGrades = g != null;
        if (e != null && e.length() > 500 || g != null && g.length() > 5000) throw new JSONException("考试成绩数据过大");
        for (int i = 0; e != null && i < e.length(); i++) exams.add(new Exam(e.getJSONObject(i)));
        for (int i = 0; g != null && i < g.length(); i++) grades.add(new Grade(g.getJSONObject(i)));
        gpa = optNumber(json, "gpa", 0, 5);
    }

    /** Parts the school failed to return keep the previous copy; everything else is replaced by the fresh read. */
    static Academic merge(Academic previous, String fresh, long now) throws JSONException {
        JSONObject next = new JSONObject(fresh);
        JSONArray failed = next.optJSONArray("failed");
        java.util.Set<String> bad = new java.util.HashSet<>();
        for (int i = 0; failed != null && i < failed.length(); i++) bad.add(failed.getString(i));
        JSONObject updated = previous == null ? new JSONObject() : new JSONObject(previous.json.optJSONObject("updated") == null ? "{}" : previous.json.optJSONObject("updated").toString());
        for (String part : PARTS) {
            if (bad.contains(part)) {
                Object old = previous == null ? null : previous.json.opt(part);
                next.put(part, old == null ? JSONObject.NULL : old);
            } else updated.put(part, now);
        }
        next.remove("failed");
        next.put("updated", updated);
        return new Academic(next.toString());
    }

    long updated(String part) { JSONObject u = json.optJSONObject("updated"); return u == null ? 0 : u.optLong(part, 0); }

    /** Grades grouped by semester, newest semester first (school lists newest first). */
    Map<String, List<Grade>> bySemester() {
        Map<String, List<Grade>> m = new LinkedHashMap<>();
        for (Grade g : grades) { List<Grade> l = m.get(g.semester); if (l == null) m.put(g.semester, l = new ArrayList<>()); l.add(g); }
        return m;
    }
    /** Credit-weighted grade points for courses that report both; null if none do. */
    static Double weighted(List<Grade> list) {
        double sum = 0, credits = 0;
        for (Grade g : list) if (g.gp != null && g.credits != null && g.credits > 0) { sum += g.gp * g.credits; credits += g.credits; }
        return credits == 0 ? null : Math.round(sum / credits * 1000) / 1000.0;
    }
    static double credits(List<Grade> list) { double c = 0; for (Grade g : list) if (g.credits != null) c += g.credits; return c; }

    String encode() { return json.toString(); }

    static String limit(String s, int max) throws JSONException {
        s = s == null ? "" : s.trim();
        if (s.length() > max) throw new JSONException("考试成绩字段过长");
        return s;
    }
    static String clock(String s) { return s != null && s.matches("(?:[01]\\d|2[0-3]):[0-5]\\d") ? s : ""; }
    static Double optNumber(JSONObject j, String key, double min, double max) throws JSONException {
        if (!j.has(key) || j.isNull(key)) return null;
        double d = j.getDouble(key);
        if (Double.isNaN(d) || d < min || d > max) throw new JSONException("考试成绩数值超出范围");
        return d;
    }
}
