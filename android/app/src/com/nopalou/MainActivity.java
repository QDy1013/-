package com.nopalou;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 「不爬楼」主界面：一屏出结论。
 * 卡片 = 事由 + 截止 + 方式 + 来源；点开能回到原文和上下文。
 */
public class MainActivity extends Activity implements Engine.SelectedListener {

    Engine engine;
    LinearLayout cardList;
    TextView stats;
    TextView notifStatus;
    TextView empty;
    boolean accessChecked;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        engine = EngineHolder.get(this);
        if (engine == null) {
            Toast.makeText(this, "模型加载失败", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        engine.listener = this;
        buildUi();
        render();
        // 第一次进来还没档案，先让用户填，不然"跟我有关"的判断全是空的。
        if (!ProfileStore.exists(this)) {
            startActivity(new Intent(this, ProfileActivity.class));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 真机问题：授权后监听服务有时没真正绑上，回前台时主动请求重绑一次。
        try {
            android.service.notification.NotificationListenerService.requestRebind(
                    new android.content.ComponentName(this, NotifListener.class));
        } catch (Exception ignored) {
        }
        if (engine != null) render();
        // 档案填完回到主界面再来提示权限，避免两个弹窗叠在一起。
        if (!accessChecked && ProfileStore.exists(this)) {
            accessChecked = true;
            ensureNotificationAccess();
        }
    }

    @Override
    protected void onDestroy() {
        if (engine != null && engine.listener == this) engine.listener = null;
        super.onDestroy();
    }

    /** 首次进入如果没给通知读取权限，直接引导过去，不然界面永远是空的。 */
    void ensureNotificationAccess() {
        try {
            String flat = Settings.Secure.getString(getContentResolver(),
                    "enabled_notification_listeners");
            if (flat == null || !flat.contains(getPackageName())) {
                new AlertDialog.Builder(this)
                        .setTitle("还差一步")
                        .setMessage("在「通知使用权」里打开「不爬楼」，我才能读到群消息。\n\n"
                                + "只在本机读取和处理，飞行模式下照常工作。")
                        .setPositiveButton("去开启", (d, w) -> startActivity(
                                new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")))
                        .setNegativeButton("稍后", null)
                        .show();
            }
        } catch (Exception ignored) { }
    }

    /** 状态行：连着是绿的；断了是红的，点一下给出修复步骤。 */
    void updateNotifStatus() {
        if (notifStatus == null) return;
        boolean on = EngineHolder.listenerConnected;
        notifStatus.setText(on ? "● 通知读取：已连接" : "● 通知读取：未连接，点这里重新打开");
        notifStatus.setTextColor(Color.parseColor(on ? "#5FD3A6" : "#F2705F"));
    }

    void showNotifHint() {
        new AlertDialog.Builder(this)
                .setTitle("收不到群消息")
                .setMessage("系统断开了不爬楼的通知读取（部分手机重启后会这样）。"
                        + "请在「通知使用权」里把不爬楼关掉再打开；\n"
                        + "如果仍不行，到手机管家里允许不爬楼自启动和后台运行。")
                .setPositiveButton("去设置", (d, w) -> {
                    try {
                        startActivity(new Intent(
                                "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                    } catch (Exception ignored) { }
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0F1115"));
        root.setPadding(dp(this, 18), dp(this, 28), dp(this, 18), dp(this, 10));

        TextView title = new TextView(this);
        title.setText("不爬楼");
        title.setTextColor(Color.parseColor("#F2F4F8"));
        title.setTextSize(26);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("群里的事，直接变成你要做的事");
        sub.setTextColor(Color.parseColor("#7C8598"));
        sub.setTextSize(12);
        root.addView(sub);

        stats = new TextView(this);
        stats.setTextColor(Color.parseColor("#5FD3A6"));
        stats.setTextSize(11);
        stats.setPadding(0, dp(this, 8), 0, dp(this, 10));
        root.addView(stats);

        notifStatus = new TextView(this);
        notifStatus.setTextSize(11);
        notifStatus.setPadding(0, 0, 0, dp(this, 8));
        notifStatus.setOnClickListener(v -> {
            if (!EngineHolder.listenerConnected) showNotifHint();
        });
        root.addView(notifStatus);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(button("我的档案", v -> startActivity(
                new Intent(this, ProfileActivity.class))));
        bar.addView(button("开始演示", v -> startReplay()));
        bar.addView(button("通知读取权限", v -> startActivity(new Intent(
                "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))));
        bar.addView(button("读当前群", v -> readCurrentScreen()));
        bar.addView(button("清空", v -> {
            engine.all().clear();
            render();
            EngineHolder.save(this);
        }));
        hs.addView(bar);
        root.addView(hs);

        ScrollView sv = new ScrollView(this);
        cardList = new LinearLayout(this);
        cardList.setOrientation(LinearLayout.VERTICAL);
        cardList.setPadding(0, dp(this, 12), 0, dp(this, 24));
        sv.addView(cardList);

        empty = new TextView(this);
        empty.setText("等群里来消息。\n点上面「开始演示」，会重放 200 多条消息。");
        empty.setTextColor(Color.parseColor("#59606E"));
        empty.setTextSize(13);
        empty.setLineSpacing(dp(this, 4), 1f);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(this, 60), 0, 0);
        cardList.addView(empty);

        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    Button button(String text, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setAllCaps(false);
        btn.setTextColor(Color.parseColor("#D7DCE6"));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#1B1F27"));
        g.setCornerRadius(dp(this, 10));
        g.setStroke(dp(this, 1), Color.parseColor("#2A303B"));
        btn.setBackground(g);
        btn.setPadding(dp(this, 14), dp(this, 6), dp(this, 14), dp(this, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(this, 8);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(l);
        return btn;
    }

    void startReplay() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.nopalou.replay");
            if (i == null) {
                Toast.makeText(this, "回放器没装", Toast.LENGTH_SHORT).show();
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "启动回放器失败", Toast.LENGTH_SHORT).show();
        }
    }

    void readCurrentScreen() {
        if (ReadService.instance == null) {
            Toast.makeText(this, "还没开无障碍权限", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        // 点按钮时当前窗口就是不爬楼自己，延时 6 秒等用户切到群聊界面再读，
        // 否则读屏会把自己的按钮和卡片文字当成群消息喂回引擎（审查意见第 3 条）。
        ReadService.instance.scheduleScrape(engine, 6000);
        Toast.makeText(this, "6 秒内切到要读的群聊界面", Toast.LENGTH_LONG).show();
        // 把不爬楼放到后台，腾出前台窗口给群聊界面。
        moveTaskToBack(true);
    }

    @Override
    public void onCardsChanged() {
        runOnUiThread(this::render);
    }

    void render() {
        if (cardList == null) return;
        cardList.removeAllViews();
        stats.setText("处理 " + engine.nIn + " 条 · 丢弃噪声 " + engine.nNoise
                + " · 进模型 " + engine.nLevel3 + " · 待办 " + engine.all().size()
                + (engine.nPromoted > 0 ? (" · 回复潮提升 " + engine.nPromoted) : ""));
        updateNotifStatus();

        List<Engine.Card> list = engine.ranked(30);
        if (list.isEmpty()) {
            empty.setText("等群里来消息。\n点上面「开始演示」，会重放 200 多条消息。");
            cardList.addView(empty);
            return;
        }
        for (Engine.Card c : list) cardList.addView(cardView(c));
    }

    View cardView(Engine.Card c) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(c.needsConfirm ? "#1A1712" : "#151A20"));
        g.setCornerRadius(dp(this, 14));
        g.setStroke(dp(this, 1), Color.parseColor(c.needsConfirm ? "#4A3A1E" : "#242B36"));
        box.setBackground(g);
        // 已完成的卡片整体变淡。
        if (c.done) box.setAlpha(0.5f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(this, 10);
        box.setLayoutParams(lp);

        TextView t = new TextView(this);
        // 考试卡：标题前加"📅 考试 "，一眼和普通待办区分开
        boolean isExam = "exam".equals(c.kind);
        String title = c.title == null ? "" : c.title;
        t.setText(isExam ? ("📅 考试 " + title) : title);
        t.setTextColor(Color.parseColor("#F2F4F8"));
        t.setTextSize(16);
        t.setTypeface(null, 1);
        // 已完成：标题加删除线。
        if (c.done) t.setPaintFlags(t.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        box.addView(t);

        TextView meta = new TextView(this);
        String when = c.deadline == 0 ? "截止：待确认" :
                ((isExam ? "时间：" : "截止：") + Engine.humanDeadline(c.deadline));
        // 考试卡优先显示地点（考场），其他卡仍沿用原来的渠道优先
        String where;
        if (isExam) {
            where = !TextUtils.isEmpty(c.place) ? (" · " + c.place) : "";
        } else {
            where = c.channel != null ? (" · " + c.channel) : (c.place != null ? (" · " + c.place) : "");
        }
        meta.setText(when + where);
        meta.setTextColor(Color.parseColor(c.deadline == 0 ? "#E0B458" : "#5FD3A6"));
        meta.setTextSize(13);
        meta.setPadding(0, dp(this, 6), 0, 0);
        box.addView(meta);

        // 考试卡：要带的东西单独一行，没有就不显示
        if (isExam && !TextUtils.isEmpty(c.bring)) {
            TextView b = new TextView(this);
            b.setText("带：" + c.bring);
            b.setTextColor(Color.parseColor("#8FB8E0"));
            b.setTextSize(12);
            b.setPadding(0, dp(this, 4), 0, 0);
            box.addView(b);
        }

        TextView src = new TextView(this);
        src.setText(c.groupName + " · " + c.sender + (c.updated ? " · 已更新" : "")
                + (c.done ? " · 已完成" : ""));
        src.setTextColor(Color.parseColor("#7C8598"));
        src.setTextSize(11);
        src.setPadding(0, dp(this, 6), 0, 0);
        box.addView(src);

        if (!TextUtils.isEmpty(c.reason)) {
            TextView r = new TextView(this);
            r.setText("⚠ " + c.reason);
            r.setTextColor(Color.parseColor("#C7A86B"));
            r.setTextSize(11);
            r.setPadding(0, dp(this, 6), 0, 0);
            box.addView(r);
        }

        box.setOnClickListener(v -> showSource(c));
        return box;
    }

    void showSource(Engine.Card c) {
        StringBuilder sb = new StringBuilder();
        sb.append("原文（").append(c.groupName).append(" · ").append(c.sender).append("）\n\n");
        sb.append(c.sourceText).append("\n");
        if (!c.context.isEmpty()) {
            sb.append("\n上文：\n");
            for (String s : c.context) sb.append("· ").append(s).append("\n");
        }
        new AlertDialog.Builder(this)
                .setTitle("可溯源")
                .setMessage(sb.toString())
                .setPositiveButton("加入日历", (d, w) -> addToCalendar(c))
                .setNeutralButton("与我无关", (d, w) -> {
                    engine.all().remove(c);
                    render();
                    EngineHolder.save(this);
                })
                // 第三按钮在"已完成 / 恢复"间切换：标记后重绘并落盘（排序交给引擎）。
                .setNegativeButton(c.done ? "恢复" : "已完成", (d, w) -> {
                    if (c.done) {
                        c.done = false;   // 恢复成待办
                    } else {
                        c.done = true;    // 标记已完成
                    }
                    render();
                    EngineHolder.save(this);
                })
                .show();
    }

    void addToCalendar(Engine.Card c) {
        try {
            Intent i = new Intent(Intent.ACTION_INSERT);
            i.setData(CalendarContract.Events.CONTENT_URI);
            i.putExtra(CalendarContract.Events.TITLE, "【不爬楼】" + c.title);
            // 描述不写群消息原文，避免系统日历云同步把原文外泄；只写来源与考试要点。
            StringBuilder desc = new StringBuilder();
            desc.append("来源：").append(c.groupName).append(" · ").append(c.sender);
            if ("exam".equals(c.kind)) {
                if (!TextUtils.isEmpty(c.place)) desc.append("\n地点：").append(c.place);
                if (!TextUtils.isEmpty(c.bring)) desc.append("\n带：").append(c.bring);
            }
            i.putExtra(CalendarContract.Events.DESCRIPTION, desc.toString());
            if (c.deadline == 0) {
                // 没确定时间就不塞"明天此刻"，让日历应用自己让用户选时间。
                Toast.makeText(this, "这张卡时间还没确定，请在日历里自己选时间", Toast.LENGTH_LONG).show();
            } else {
                i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, c.deadline - 3600_000L);
                i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, c.deadline);
            }
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "没有日历应用", Toast.LENGTH_SHORT).show();
        }
    }
}
