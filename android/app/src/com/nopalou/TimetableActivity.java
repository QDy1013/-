package com.nopalou;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Calendar;

/** Offline week view and the entry to NWPU's signed-in import page. */
public class TimetableActivity extends Activity {
    Timetable table;
    LinearLayout root;
    int week;
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) week = state.getInt("week", 0);
    }
    @Override public void onResume() {
        super.onResume();
        io.execute(() -> {
            try {
                Timetable loaded = TimetableStore.load(getApplicationContext());
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    table = loaded;
                    if (table != null) week = Math.max(1, Math.min(table.maxWeek, week == 0 ? table.currentWeek() : week));
                    render();
                });
            } catch (Exception e) { runOnUiThread(() -> { table = null; render(); toast("课表存档无法读取，请重新导入"); }); }
        });
    }
    @Override public void onSaveInstanceState(Bundle b) { b.putInt("week", week); super.onSaveInstanceState(b); }
    @Override public void onDestroy() { io.shutdown(); super.onDestroy(); }

    LinearLayout page() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        body.setPadding(p, p, p, p);
        scroll.addView(body);
        setContentView(scroll);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        scroll.setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        return body;
    }
    void render() {
        if (isFinishing() || isDestroyed()) return;
        root = page();
        label(root, "我的课表", 28, Ui.TEXT);
        label(root, "西北工业大学 · 导入后可离线查看", 13, Ui.SUB);
        button(root, table == null ? "从西工大教务一键导入" : "重新导入课表", () -> startActivity(new Intent(this, NwpuImportActivity.class)));
        if (table == null) {
            label(root, "还没有课表\n登录学校教务系统，打开个人课表并选择学期，再点击导入。", 16, Ui.SUB);
            return;
        }
        label(root, table.termName, 18, Ui.TEXT);
        label(root, "第1周周一：" + table.firstMonday, 13, Ui.SUB);
        button(root, "校准开学日期", () -> chooseMonday(table.firstMonday, value -> {
            Timetable updated = table;
            String previous = updated.firstMonday;
            updated.firstMonday = value;
            io.execute(() -> {
                try { TimetableStore.save(getApplicationContext(), updated); runOnUiThread(this::render); }
                catch (Exception e) { runOnUiThread(() -> { updated.firstMonday = previous; toast("保存失败，请重试"); }); }
            });
        }));
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(android.view.Gravity.CENTER_VERTICAL);
        root.addView(nav);
        Button prev = button(nav, "上一周", () -> { week--; render(); });
        prev.setEnabled(week > 1);
        TextView number = label(nav, "第 " + week + " 周", 18, Ui.TEXT);
        number.setGravity(android.view.Gravity.CENTER);
        number.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        Button next = button(nav, "下一周", () -> { week++; render(); });
        next.setEnabled(week < table.maxWeek);
        int current = table.currentWeek();
        if (current < 1 || current > table.maxWeek) label(root, "今天不在已导入课程的周次范围内，可切换周次查看。", 13, Ui.SUB);
        else button(root, "回到本周", () -> { week = current; render(); });
        int total = 0;
        for (int day = 1; day <= 7; day++) {
            boolean heading = false;
            for (Timetable.Lesson l : table.lessons) {
                if (l.weekday != day || !l.weeks.contains(week)) continue;
                if (!heading) { label(root, table.dayLabel(week, day), 17, Ui.ACCENT); heading = true; }
                TextView card = label(root, l.name + "\n" + table.time(l) + "\n" + (l.room.isEmpty() ? "地点待定" : l.room)
                        + (l.teachers.isEmpty() ? "" : " · " + l.teachers) + "\n" + l.weekText(), 15, Ui.TEXT);
                card.setBackground(Ui.round(this, Ui.CARD, 14));
                total++;
            }
        }
        if (total == 0) label(root, "这一周没有已排课课程", 16, Ui.SUB);
        org.json.JSONArray other = table.json.optJSONArray("unarranged");
        if (other != null && other.length() > 0) {
            label(root, "未排课课程（时间地点以教务通知为准）", 14, Ui.SUB);
            for (int i = 0; i < other.length(); i++) label(root, other.optString(i), 14, Ui.SUB);
        }
        button(root, "删除本机课表", () -> new AlertDialog.Builder(this).setTitle("删除课表？")
                .setMessage("会删除本机已导入的课表，之后可以重新导入。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (d, w) -> io.execute(() -> {
                    TimetableStore.clear(getApplicationContext());
                    runOnUiThread(() -> { table = null; week = 0; render(); });
                })).show());
    }
    TextView label(LinearLayout parent, String text, int size, int color) {
        TextView v = new TextView(this);
        v.setText(text); v.setTextSize(size); v.setTextColor(color);
        int p = Ui.dp(this, 12); v.setPadding(p, p, p, p);
        parent.addView(v); return v;
    }
    Button button(LinearLayout parent, String text, Runnable action) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextColor(Ui.ACCENT);
        b.setOnClickListener(v -> action.run()); parent.addView(b); return b;
    }
    void chooseMonday(String value, java.util.function.Consumer<String> callback) {
        Calendar c = Timetable.calendar(value);
        if (c == null) c = Calendar.getInstance();
        DatePickerDialog picker = new DatePickerDialog(this, (v, y, m, d) -> {
            String chosen = String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", y, m + 1, d);
            if (!chosen.equals(Timetable.monday(chosen))) { toast("请选择第1周的周一"); return; }
            callback.accept(chosen);
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        picker.setTitle("选择第1周周一"); picker.show();
    }
    void toast(String text) { if (!isFinishing() && !isDestroyed()) Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
