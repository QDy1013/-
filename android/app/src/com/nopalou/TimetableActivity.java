package com.nopalou;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ImageView;
import java.util.Calendar;
import java.util.List;

/** A compact seven-day timetable. Course details and management stay behind taps. */
public class TimetableActivity extends Activity {
    private static final int[] FILLS = {0xFFECE8FA, 0xFFE0EFEB, 0xFFF8EADD, 0xFFE3EBF9, 0xFFF5E4EB, 0xFFEBEDDC};
    private static final int[] INKS = {0xFF5D498F, 0xFF306E61, 0xFF935B34, 0xFF3D6095, 0xFF984F70, 0xFF64703A};
    Timetable table;
    int week;
    int savedScrollY;
    ScrollView timetableScroll;
    LinearLayout swipeBoard;
    View swipeStart;
    float downX, downY;
    int swipeAxis;
    boolean swipeCandidate, changingWeek;
    VelocityTracker velocity;
    static final int GUTTER = 34;
    final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle b) { super.onCreate(b); if (b != null) { week = b.getInt("week", 0); savedScrollY = b.getInt("scrollY", 0); } }
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
    @Override public void onSaveInstanceState(Bundle b) { b.putInt("week", week); b.putInt("scrollY", timetableScroll == null ? savedScrollY : timetableScroll.getScrollY()); super.onSaveInstanceState(b); }
    @Override public void onPause() { if (timetableScroll != null) savedScrollY = timetableScroll.getScrollY(); super.onPause(); }
    @Override public void onDestroy() { if (velocity != null) velocity.recycle(); io.shutdown(); super.onDestroy(); }
    int dp(float n) { return Ui.dp(this, n); }
    TextView text(String s, float size, int color, boolean bold) { return CourseUi.text(this, s, size, color, bold); }
    ImageView icon(String s, String description, Runnable action) {
        return CourseUi.icon(this, s, description, action);
    }
    void importTable(boolean reuse) {
        startActivity(new Intent(this, NwpuImportActivity.class).putExtra("reuse_session", reuse));
    }
    String date(int day) {
        String label = table.dayLabel(week, day);
        return label.length() >= 10 ? label.substring(5) : "--/--";
    }
    void render() {
        if (isFinishing() || isDestroyed()) return;
        LinearLayout root = CourseUi.column(this); root.setBackgroundColor(Ui.BG);
        root.setPadding(dp(12), dp(8), dp(12), dp(4));
        setContentView(root);
        getWindow().setStatusBarColor(Ui.BG); getWindow().setNavigationBarColor(Ui.BG);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR |
                (android.os.Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
        LinearLayout toolbar = new LinearLayout(this); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(toolbar, CourseUi.row(dp(44)));
        toolbar.addView(icon("back", "返回", this::finish));
        TextView title = text("我的课表", 20, Ui.TEXT, true);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (table != null) toolbar.addView(icon("sync", "重新同步课表", () -> importTable(true)));
        toolbar.addView(icon("more", "课表设置", this::settings));
        if (table == null) { empty(root); return; }

        TextView school = text("西北工业大学  /  " + table.termName, 11, Ui.SUB, false);
        school.setPadding(dp(8), dp(10), 0, dp(14)); root.addView(school);
        swipeStart = school;
        LinearLayout navigation = new LinearLayout(this); navigation.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(navigation);
        LinearLayout weekInfo = CourseUi.column(this); weekInfo.setPadding(dp(8), 0, 0, 0);
        LinearLayout weekTitle = new LinearLayout(this); weekTitle.setGravity(Gravity.CENTER_VERTICAL);
        TextView number = text("第 " + week + " 周", 27, Ui.TEXT, true);
        number.setContentDescription("第" + week + "周，点击选择教学周");
        weekTitle.addView(number);
        ImageView down = new ImageView(this); down.setImageDrawable(CourseUi.glyph(this, "down"));
        LinearLayout.LayoutParams arrow = new LinearLayout.LayoutParams(dp(17), dp(17)); arrow.leftMargin = dp(8); weekTitle.addView(down, arrow);
        weekInfo.addView(weekTitle); weekInfo.setOnClickListener(v -> chooseWeek()); weekInfo.setFocusable(true);
        String date = date(1).replace('-', '.') + " — " + date(7).replace('-', '.');
        TextView range = text(date, 11, Ui.SUB, false); range.setPadding(0, dp(7), 0, 0); weekInfo.addView(range);
        navigation.addView(weekInfo, new LinearLayout.LayoutParams(0, -2, 1));
        TextView today = text("本周", 12, week == table.currentWeek() ? Ui.SUB : Ui.ACCENT, true);
        today.setPadding(dp(10), dp(10), dp(10), dp(10));
        today.setMinHeight(dp(44)); today.setMinWidth(dp(48)); today.setGravity(Gravity.CENTER);
        today.setBackground(Ui.ripple(this, Ui.round(this, Ui.CARD, 10), Ui.press(), 10));
        boolean inTerm = table.currentWeek() >= 1 && table.currentWeek() <= table.maxWeek;
        today.setText(inTerm ? "本周" : "非教学周"); today.setEnabled(inTerm);
        today.setContentDescription(inTerm ? "回到本周" : "当前日期不在已导入的教学周内");
        today.setOnClickListener(v -> changeWeek(table.currentWeek(), true));
        navigation.addView(today);
        ImageView previous = icon("back", "上一周", () -> changeWeek(week - 1, true));
        previous.setEnabled(week > 1); previous.setAlpha(week > 1 ? 1f : .25f); navigation.addView(previous);
        ImageView next = icon("next", "下一周", () -> changeWeek(week + 1, true));
        next.setEnabled(week < table.maxWeek); next.setAlpha(week < table.maxWeek ? 1f : .25f); navigation.addView(next);
        CourseUi.gap(root, 20);
        swipeBoard = CourseUi.column(this); root.addView(swipeBoard, new LinearLayout.LayoutParams(-1, 0, 1));
        weekdayHeader(swipeBoard);

        ScrollView scroll = new ScrollView(this); scroll.setVerticalScrollBarEnabled(false);
        timetableScroll = scroll; swipeBoard.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        List<TimetableLayout.Block> blocks = TimetableLayout.blocks(table, week);
        int units = 12;
        for (Timetable.Lesson l : table.lessons) units = Math.max(units, l.end);
        Grid grid = new Grid(blocks, units); scroll.addView(grid, new ScrollView.LayoutParams(-1, dp(grid.top(units) + 55)));
        final int restoreY = savedScrollY;
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (scroll.getHeight() <= 0 || grid.getHeight() <= 0) return;
                scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                scroll.scrollTo(0, restoreY);
            }
        });
        int meetings = 0; for (TimetableLayout.Block b : blocks) meetings += b.lessons.size();
        TextView foot = text(meetings == 0 ? "本周没有已排课课程 · 左右滑动换周" : "左右滑动换周  ·  本周 " + meetings + " 次课程  ·  点课程看详情", 10, Ui.SUB, false);
        foot.setGravity(Gravity.CENTER); foot.setPadding(0, dp(9), 0, dp(8)); root.addView(foot);
    }
    void weekdayHeader(LinearLayout root) {
        FrameLayout days = new FrameLayout(this);
        root.addView(days, CourseUi.row(dp(50)));
        days.post(() -> {
        days.removeAllViews(); float columnWidth = (days.getWidth() - dp(GUTTER)) / 7f;
        TextView month = text(date(1).substring(0, 2) + "\n月", 10, Ui.SUB, true);
        month.setGravity(Gravity.CENTER); days.addView(month, new FrameLayout.LayoutParams(dp(GUTTER), dp(44)));
        Calendar now = Calendar.getInstance();
        String today = String.format(java.util.Locale.ROOT, "%02d-%02d", now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH));
        for (int d = 1; d <= 7; d++) {
            String date = date(d);
            boolean active = week == table.currentWeek() && date.equals(today);
            LinearLayout column = CourseUi.column(this); column.setGravity(Gravity.CENTER);
            int left = dp(GUTTER) + Math.round((d - 1) * columnWidth), right = dp(GUTTER) + Math.round(d * columnWidth);
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(right - left - dp(4), dp(44)); p.leftMargin = left + dp(2);
            days.addView(column, p);
            if (active) column.setBackground(Ui.round(this, Ui.ACCENT, 12));
            TextView label = text("一二三四五六日".substring(d - 1, d), 10, active ? Color.WHITE : d >= 6 ? Ui.MUTE : Ui.SUB, false);
            TextView dateLabel = text(date.substring(3), 12, active ? Color.WHITE : Ui.TEXT, true);
            label.setGravity(Gravity.CENTER); dateLabel.setGravity(Gravity.CENTER);
            column.addView(label); dateLabel.setPadding(0, dp(5), 0, 0); column.addView(dateLabel);
        }
        });
    }
    final class Grid extends FrameLayout {
        final List<TimetableLayout.Block> blocks;
        final int units;
        Grid(List<TimetableLayout.Block> blocks, int units) { super(TimetableActivity.this); this.blocks = blocks; this.units = units; }
        int top(int u) { return (u - 1) * 53 + (u > 4 ? 12 : 0) + (u > 8 ? 12 : 0); }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { super.onSizeChanged(w, h, oldw, oldh); post(this::paintGrid); }
        void paintGrid() {
            removeAllViews(); if (getWidth() == 0) return;
            float col = (getWidth() - dp(GUTTER)) / 7f;
            if (week == table.currentWeek()) {
                int day = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7;
                View today = new View(TimetableActivity.this); today.setBackgroundColor(Ui.withAlpha(Ui.ACCENT, 7));
                FrameLayout.LayoutParams stripe = new FrameLayout.LayoutParams(Math.round((day + 1) * col) - Math.round(day * col), -1);
                stripe.leftMargin = dp(GUTTER) + Math.round(day * col); addView(today, stripe);
            }
            for (int u = 1; u <= units; u++) {
                LinearLayout unit = CourseUi.column(TimetableActivity.this); unit.setGravity(Gravity.CENTER_HORIZONTAL); unit.setPadding(0, dp(9), 0, 0);
                TextView label = text(String.format(java.util.Locale.ROOT, "%02d", u), 10, Ui.SUB, true); label.setGravity(Gravity.CENTER); unit.addView(label);
                org.json.JSONArray times = table.json.optJSONArray("units"); String start = "";
                for (int i = 0; times != null && i < times.length(); i++) { org.json.JSONObject time = times.optJSONObject(i); if (time != null && time.optInt("number") == u) start = time.optString("start"); }
                if (!start.isEmpty()) { TextView clock = text(start, 8, Ui.MUTE, false); clock.setGravity(Gravity.CENTER); clock.setPadding(0, dp(4), 0, 0); unit.addView(clock); }
                FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(GUTTER), dp(48)); p.topMargin = dp(top(u)); addView(unit, p);
                View line = new View(TimetableActivity.this); line.setBackgroundColor(Ui.withAlpha(Ui.LINE, 125));
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, 1); lp.topMargin = dp(top(u)); lp.leftMargin = dp(GUTTER); addView(line, lp);
            }
            for (TimetableLayout.Block block : blocks) {
                Timetable.Lesson first = block.lessons.get(0);
                int color = (first.name.hashCode() & Integer.MAX_VALUE) % FILLS.length;
                LinearLayout card = CourseUi.column(TimetableActivity.this);
                card.setPadding(dp(4), dp(7), dp(3), dp(5));
                card.setBackground(Ui.ripple(TimetableActivity.this, Ui.round(TimetableActivity.this, FILLS[color], 8), Ui.press(), 8));
                boolean overlap = block.lessons.size() > 1;
                TextView name = text(overlap ? block.lessons.size() + "门课程\n时段重叠" : first.name, 10, INKS[color], true);
                name.setLineSpacing(dp(1), 1f); name.setMaxLines(block.end == block.start ? 2 : 5); name.setEllipsize(TextUtils.TruncateAt.END);
                card.addView(name, new LinearLayout.LayoutParams(-1, 0, 1));
                if (block.end > block.start) {
                    TextView place = text(overlap ? "点击查看" : (first.room.isEmpty() ? "地点待定" : first.room), 9, INKS[color], false);
                    place.setMaxLines(2); place.setEllipsize(TextUtils.TruncateAt.END); place.setPadding(0, dp(3), 0, 0); card.addView(place);
                }
                card.setContentDescription(overlap ? block.lessons.size() + "门课程时段重叠，点击查看" : first.name + "，" + table.time(first) + "，" + first.room);
                card.setFocusable(true); card.setOnClickListener(v -> details(block));
                int left = dp(GUTTER) + Math.round((block.weekday - 1) * col), right = dp(GUTTER) + Math.round(block.weekday * col);
                FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(Math.max(1, right - left - dp(4)), dp(top(block.end) + 53 - top(block.start)) - dp(4));
                p.leftMargin = left + dp(2);
                p.topMargin = dp(top(block.start)) + dp(2); addView(card, p);
            }
        }
    }
    void details(TimetableLayout.Block block) {
        if (block.lessons.size() == 1) { detail(block.lessons.get(0)); return; }
        String[] names = new String[block.lessons.size()];
        for (int i = 0; i < names.length; i++) names[i] = block.lessons.get(i).name + " · " + table.time(block.lessons.get(i));
        new AlertDialog.Builder(this).setTitle("同一时段的课程").setItems(names, (d, i) -> detail(block.lessons.get(i))).setNegativeButton("关闭", null).show();
    }
    void detail(Timetable.Lesson l) {
        LinearLayout body = CourseUi.column(this); body.setPadding(dp(24), dp(12), dp(24), dp(8));
        TextView name = text(l.name, 20, Ui.TEXT, true); body.addView(name); CourseUi.gap(body, 22);
        String[][] fields = {{"上课时间", table.dayLabel(week, l.weekday) + "\n" + table.time(l)}, {"上课地点", l.room.isEmpty() ? "地点待定" : l.room},
                {"任课教师", l.teachers.isEmpty() ? "待确认" : l.teachers}, {"教学周次", l.weekText()}};
        for (String[] f : fields) {
            body.addView(text(f[0], 11, Ui.SUB, false)); CourseUi.gap(body, 6);
            body.addView(text(f[1], 15, Ui.TEXT, false)); CourseUi.gap(body, 18);
        }
        new AlertDialog.Builder(this).setView(body).setPositiveButton("知道了", null).show();
    }
    void empty(LinearLayout root) {
        LinearLayout center = CourseUi.column(this); center.setGravity(Gravity.CENTER); center.setPadding(dp(20), 0, dp(20), 0);
        root.addView(center, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView mark = text("▦", 72, Ui.ACCENT, false); center.addView(mark);
        CourseUi.gap(center, 18); center.addView(text("把这一学期，装进口袋", 21, Ui.TEXT, true));
        CourseUi.gap(center, 12); TextView tip = text("输入翱翔门户账号密码，自动导入本学期课表", 13, Ui.SUB, false);
        tip.setGravity(Gravity.CENTER); center.addView(tip); CourseUi.gap(center, 28);
        center.addView(CourseUi.action(this, "一键导入西工大课表", true, () -> importTable(false)), CourseUi.row(dp(50)));
    }
    void chooseWeek() {
        String[] weeks = new String[table.maxWeek]; for (int i = 0; i < weeks.length; i++) weeks[i] = "第 " + (i + 1) + " 周";
        new AlertDialog.Builder(this).setTitle("选择教学周").setSingleChoiceItems(weeks, week - 1, (d, i) -> { d.dismiss(); changeWeek(i + 1, true); }).setNegativeButton("取消", null).show();
    }
    void changeWeek(int target, boolean animate) {
        if (table == null || changingWeek || target < 1 || target > table.maxWeek || target == week) {
            if (swipeBoard != null) swipeBoard.animate().translationX(0).alpha(1).setDuration(140).start();
            return;
        }
        savedScrollY = timetableScroll == null ? 0 : timetableScroll.getScrollY();
        if (!animate || swipeBoard == null) { week = target; render(); return; }
        changingWeek = true; int direction = target > week ? -1 : 1;
        swipeBoard.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        swipeBoard.animate().translationX(dp(25) * direction).alpha(.45f).setDuration(100).withEndAction(() -> {
            changingWeek = false; if (isFinishing() || isDestroyed()) return;
            week = target; render(); swipeBoard.setAlpha(.5f); swipeBoard.setTranslationX(dp(-12) * direction);
            swipeBoard.animate().translationX(0).alpha(1).setDuration(150).start();
        }).start();
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (velocity != null) velocity.recycle(); velocity = VelocityTracker.obtain();
            downX = event.getRawX(); downY = event.getRawY(); swipeAxis = 0;
            int[] start = new int[2]; if (swipeStart != null) swipeStart.getLocationOnScreen(start);
            swipeCandidate = table != null && swipeBoard != null && swipeStart != null && downY >= start[1] && !changingWeek;
        }
        if (velocity != null) velocity.addMovement(event);
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            swipeCandidate = false;
            if (swipeAxis == 1 && swipeBoard != null) swipeBoard.animate().translationX(0).setDuration(140).start();
            swipeAxis = 0;
        }
        if (swipeCandidate && action == MotionEvent.ACTION_MOVE) {
            float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
            if (swipeAxis == 0 && Math.max(Math.abs(dx), Math.abs(dy)) > ViewConfiguration.get(this).getScaledTouchSlop()) {
                if (Math.abs(dx) > Math.abs(dy) * 1.35f) {
                    swipeAxis = 1; MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel); cancel.recycle();
                } else swipeAxis = 2;
            }
            if (swipeAxis == 1) { swipeBoard.setTranslationX(Math.max(-dp(60), Math.min(dp(60), dx * .24f))); return true; }
        }
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && swipeAxis == 1) {
            float dx = event.getRawX() - downX;
            if (velocity != null) velocity.computeCurrentVelocity(1000);
            float speed = velocity == null ? 0 : velocity.getXVelocity();
            boolean committed = action == MotionEvent.ACTION_UP && (Math.abs(dx) >= dp(42) || (Math.abs(dx) >= dp(20) && Math.abs(speed) > dp(600)));
            swipeCandidate = false; swipeAxis = 0; if (velocity != null) { velocity.recycle(); velocity = null; }
            if (committed) changeWeek(week + (dx < 0 ? 1 : -1), true);
            else swipeBoard.animate().translationX(0).setDuration(140).start();
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            swipeCandidate = false; swipeAxis = 0; if (velocity != null) { velocity.recycle(); velocity = null; }
        }
        return super.dispatchTouchEvent(event);
    }
    void settings() {
        if (table == null) { importTable(false); return; }
        new AlertDialog.Builder(this).setTitle("课表设置")
                .setItems(new String[]{"重新同步当前账号", "使用其他账号导入", "校准开学日期", "未排课课程", "删除本机课表"}, (d, which) -> {
                    if (which == 0) importTable(true);
                    if (which == 1) importTable(false);
                    if (which == 2) chooseMonday();
                    if (which == 3) {
                        org.json.JSONArray a = table.json.optJSONArray("unarranged"); StringBuilder s = new StringBuilder();
                        for (int i = 0; a != null && i < a.length(); i++) s.append(a.optString(i)).append("\n");
                        new AlertDialog.Builder(this).setTitle("未排课课程").setMessage(s.length() == 0 ? "没有读到未排课课程" : s + "\n时间地点以教务通知为准").setPositiveButton("知道了", null).show();
                    }
                    if (which == 4) new AlertDialog.Builder(this).setTitle("删除本机课表？").setMessage("删除后可重新导入。")
                            .setNegativeButton("取消", null).setPositiveButton("删除", (x, y) -> io.execute(() -> {
                                TimetableStore.clear(getApplicationContext()); runOnUiThread(() -> { table = null; week = 0; render(); });
                            })).show();
                }).setNegativeButton("关闭", null).show();
    }
    void chooseMonday() {
        Calendar c = Timetable.calendar(table.firstMonday); if (c == null) c = Calendar.getInstance();
        DatePickerDialog picker = new DatePickerDialog(this, (v, y, m, d) -> {
            String chosen = String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", y, m + 1, d);
            if (!chosen.equals(Timetable.monday(chosen))) { toast("请选择第1周的周一"); return; }
            Timetable updated = table; String previous = updated.firstMonday; updated.firstMonday = chosen;
            io.execute(() -> {
                try { TimetableStore.save(getApplicationContext(), updated); runOnUiThread(this::render); }
                catch (Exception e) { runOnUiThread(() -> { updated.firstMonday = previous; toast("保存失败，请重试"); }); }
            });
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        picker.setTitle("选择第1周周一"); picker.show();
    }
    void toast(String s) { if (!isFinishing() && !isDestroyed()) android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show(); }
}
