package com.nopalou.replay;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 消息回放器（方案书第 10 节「准备」的主方案）。
 * 同一台手机上，把准备好的群聊按固定节奏重放成真实通知，断网也能跑、每次效果一致。
 */
public class ReplayActivity extends Activity {

    static final String CHANNEL = "nopalou_demo";
    final List<String[]> msgs = new ArrayList<>();
    final Handler handler = new Handler(Looper.getMainLooper());
    NotificationManager nm;
    int cursor = 0;
    boolean playing = false;
    TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "群消息回放",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setShowBadge(true);
            nm.createNotificationChannel(ch);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        load();
        buildUi();
    }

    void load() {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(getAssets().open("messages.txt"), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] p = line.split("\\|", 3);
                if (p.length < 3) continue;
                p[2] = p[2].replace("\\n", "\n");
                msgs.add(p);
            }
        } catch (Exception e) {
            Toast.makeText(this, "脚本读取失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0F1115"));
        root.setPadding(dp(20), dp(30), dp(20), dp(16));

        TextView t = new TextView(this);
        t.setText("消息回放器");
        t.setTextColor(Color.parseColor("#F2F4F8"));
        t.setTextSize(22);
        t.setTypeface(null, 1);
        root.addView(t);

        TextView sub = new TextView(this);
        sub.setText("把 " + msgs.size() + " 条群消息按真实节奏重放成通知。\n断网可跑，每次结果一致。");
        sub.setTextColor(Color.parseColor("#7C8598"));
        sub.setTextSize(12);
        sub.setPadding(0, dp(8), 0, dp(16));
        root.addView(sub);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(btn("开始回放", v -> play(150)));
        row.addView(btn("快速 60ms", v -> play(60)));
        row.addView(btn("停止", v -> stop()));
        root.addView(row);

        // 第二排：格式测试，一次发出 5 条不同形状的通知，用于真机验证主 App 的解析。
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row2lp.topMargin = dp(8);
        row2.setLayoutParams(row2lp);
        row2.addView(btn("格式测试", v -> formatTest()));
        root.addView(row2);

        status = new TextView(this);
        status.setText("待机");
        status.setTextColor(Color.parseColor("#5FD3A6"));
        status.setTextSize(12);
        status.setPadding(0, dp(14), 0, dp(10));
        root.addView(status);

        ScrollView sv = new ScrollView(this);
        LinearLayout log = new LinearLayout(this);
        log.setOrientation(LinearLayout.VERTICAL);
        sv.addView(log);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        this.logBox = log;
    }

    LinearLayout logBox;

    Button btn(String text, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTextColor(Color.parseColor("#D7DCE6"));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#1B1F27"));
        g.setCornerRadius(dp(10));
        g.setStroke(dp(1), Color.parseColor("#2A303B"));
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    void play(int interval) {
        if (playing) return;
        if (msgs.isEmpty()) { Toast.makeText(this, "没有脚本", Toast.LENGTH_SHORT).show(); return; }
        playing = true;
        nm.cancelAll();
        cursor = 0;
        logBox.removeAllViews();
        step(interval);
    }

    void step(final int interval) {
        if (!playing || cursor >= msgs.size()) {
            playing = false;
            status.setText("回放结束，共 " + cursor + " 条");
            return;
        }
        String[] m = msgs.get(cursor);
        post(cursor, m[0], m[1], m[2]);
        status.setText("已发 " + (cursor + 1) + " / " + msgs.size() + "  " + m[0]);
        TextView line = new TextView(this);
        line.setText(m[0] + " · " + m[1] + "：" + m[2].replace("\n", " "));
        line.setTextColor(Color.parseColor("#59606E"));
        line.setTextSize(10);
        line.setPadding(0, dp(3), 0, dp(3));
        logBox.addView(line);
        cursor++;
        handler.postDelayed(() -> step(interval), interval);
    }

    void stop() {
        playing = false;
        handler.removeCallbacksAndMessages(null);
        status.setText("已停止（" + cursor + " 条）");
    }

    void post(int index, String group, String sender, String body) {
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        nb.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(group)
                .setContentText(sender + "：" + body.replace("\n", " "));
        if (body.contains("\n")) {
            nb.setStyle(new Notification.BigTextStyle().bigText(sender + "：" + body));
        }
        nb.setWhen(System.currentTimeMillis()).setShowWhen(true);
        nm.notify(NotifIds.idFor(index), nb.build());
        int old = NotifIds.cancelFor(index);
        if (old >= 0) nm.cancel(old);
    }

    /**
     * 格式测试：一次性发出 5 条不同「形状」的通知，覆盖微信/QQ 在真机上常见的几种样式，
     * 用来验证主 App 的解析。编号从 9000 起，避开回放用的 1..N，彼此也不互相覆盖。
     *
     * 具体发通知的代码已搬到 FormatNotifs，adb 广播触发时也走同一份，两处效果完全一致。
     */
    @SuppressWarnings("deprecation")
    void formatTest() {
        // 交给共用的发送逻辑，通知编号和内容都在那边。
        FormatNotifs.post(this);
        // 界面上的按钮仍保留提示，广播触发时不会弹。
        Toast.makeText(this, "已发出 5 条格式测试通知", Toast.LENGTH_SHORT).show();
    }

    /** 格式测试用的基础 Builder：沿用回放的 CHANNEL 和小图标，点击后自动消失。 */
    Notification.Builder fmtBuilder(long when) {
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        nb.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setAutoCancel(true)
                .setWhen(when)
                .setShowWhen(true);
        return nb;
    }

    @Override
    protected void onDestroy() {
        stop();
        super.onDestroy();
    }
}
