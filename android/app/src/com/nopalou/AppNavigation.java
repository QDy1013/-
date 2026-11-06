package com.nopalou;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Always-visible navigation between the app's two main screens. */
final class AppNavigation {
    private static Bundle timetableState;

    static void rememberTimetable(int week, int scrollY) {
        timetableState = new Bundle();
        timetableState.putInt("week", week);
        timetableState.putInt("scrollY", scrollY);
    }

    static void openTimetable(Activity from) {
        Intent intent = new Intent(from, TimetableActivity.class);
        if (timetableState != null) intent.putExtra("navigation_state", new Bundle(timetableState));
        from.startActivity(intent);
    }

    static View bar(Activity from, boolean timetable) {
        LinearLayout shell = CourseUi.column(from);
        shell.setBackgroundColor(Ui.BG);
        View line = new View(from); line.setBackgroundColor(Ui.LINE);
        shell.addView(line, new LinearLayout.LayoutParams(-1, 1));
        LinearLayout row = new LinearLayout(from);
        int pad = Ui.dp(from, 8);
        row.setPadding(Ui.dp(from, 16), pad, Ui.dp(from, 16), pad);
        shell.addView(row);
        for (int i = 0; i < 2; i++) {
            boolean tabIsTimetable = i == 1;
            boolean selected = timetable == tabIsTimetable;
            String title = tabIsTimetable ? "课表" : "待办";
            TextView tab = CourseUi.text(from, title, 15, selected ? Color.WHITE : Ui.SUB, true);
            tab.setGravity(Gravity.CENTER);
            tab.setContentDescription(title + "主导航");
            tab.setSelected(selected);
            tab.setFocusable(true);
            tab.setBackground(Ui.ripple(from, Ui.round(from, selected ? Ui.TEXT : Ui.BG, 14), Ui.press(), 14));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Ui.dp(from, 48), 1);
            if (i == 0) p.rightMargin = pad;
            row.addView(tab, p);
            tab.setOnClickListener(v -> {
                if (selected) return;
                if (tabIsTimetable) openTimetable(from);
                else {
                    from.startActivity(new Intent(from, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                    from.finish();
                }
            });
        }
        return shell;
    }
}
