package com.nopalou;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.Calendar;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * 「不爬楼」主界面：一屏出结论。
 * 卡片 = 事由 + 截止 + 方式 + 来源；点开能回到原文和上下文。
 */
public class MainActivity extends Activity implements Engine.SelectedListener {
    private static final int PAGE_SIZE = 30;
    private static final int REQUEST_REMINDER_PERMISSION = 8420;

    Engine engine;
    LinearLayout cardList;
    TextView stats;
    TextView notifStatus;
    TextView empty;
    boolean accessChecked;
    private DemoScenario demo;
    private Button demoButton;
    private Button demoUpdate;
    private LinearLayout liveTools;
    private LinearLayout liveFilters;
    private ScrollView listScroll;
    private String keywords = "";
    private Engine.CardScope scope = Engine.CardScope.ALL;
    private int visibleLimit = PAGE_SIZE;

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
        if (engine != null) {
            ReminderReceiver.refresh(this, engine);
            render();
        }
        // 档案填完回到主界面再来提示权限，避免两个弹窗叠在一起。
        if (!accessChecked && ProfileStore.exists(this)) {
            accessChecked = true;
            ensureNotificationAccess();
        }
        if (demo == null && ReadService.instance != null) {
            ReadPreview preview = ReadService.instance.takePreview(System.currentTimeMillis());
            if (preview != null) showReadPreview(preview);
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
        root.setFocusableInTouchMode(true);
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
            if (demo == null && !EngineHolder.listenerConnected) showNotifHint();
        });
        root.addView(notifStatus);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        demoButton = button("离线演示", v -> toggleDemo());
        bar.addView(demoButton);
        demoUpdate = button("播放群消息更新", v -> {
            if (demo == null) return;
            demo.applyUpdates();
            demoUpdate.setVisibility(View.GONE);
            render();
        });
        demoUpdate.setVisibility(View.GONE);
        bar.addView(demoUpdate);
        liveTools = new LinearLayout(this);
        liveTools.setOrientation(LinearLayout.HORIZONTAL);
        liveTools.addView(button("粘贴群消息", v -> showMessageImport()));
        liveTools.addView(button("到期提醒", v -> showDeadlineReminderSettings()));
        liveTools.addView(button("我的档案", v -> startActivity(
                new Intent(this, ProfileActivity.class))));
        liveTools.addView(button("通知读取权限", v -> startActivity(new Intent(
                "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))));
        liveTools.addView(button("读当前群", v -> readCurrentScreen()));
        liveTools.addView(button("通知回放", v -> startReplay()));
        liveTools.addView(button("清空", v -> new AlertDialog.Builder(this)
                .setTitle("清空待办和近期消息？")
                .setMessage("会移除本机的全部卡片，以及这次运行中用于识别的近期消息。")
                .setPositiveButton("清空", (dialog, which) -> {
                    if (ReadService.instance != null) ReadService.instance.cancelPendingPreview();
                    engine.clearLocalData();
                    EngineHolder.save(this);
                })
                .setNegativeButton("取消", null)
                .show()));
        bar.addView(liveTools);
        hs.addView(bar);
        root.addView(hs);

        liveFilters = buildFilters();
        root.addView(liveFilters);
        listScroll = new ScrollView(this);
        cardList = new LinearLayout(this);
        cardList.setOrientation(LinearLayout.VERTICAL);
        cardList.setPadding(0, dp(this, 12), 0, dp(this, 24));
        listScroll.addView(cardList);

        empty = new TextView(this);
        empty.setTextColor(Color.parseColor("#59606E"));
        empty.setTextSize(13);
        empty.setLineSpacing(dp(this, 4), 1f);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(this, 60), 0, 0);
        cardList.addView(empty);

        root.addView(listScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        root.requestFocus();
    }

    private LinearLayout buildFilters() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Spinner state = new Spinner(this);
        state.setContentDescription("待办状态筛选");
        Engine.CardScope[] scopes = {Engine.CardScope.ALL, Engine.CardScope.OPEN,
                Engine.CardScope.OVERDUE, Engine.CardScope.COMPLETED};
        ArrayAdapter<String> options = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"全部", "未完成", "已过期", "已完成"});
        options.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        state.setAdapter(options);
        row.addView(state);
        state.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                scope = scopes[position];
                resetCardPage();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setHint("搜索事项、群名、地点");
        search.setContentDescription("搜索待办卡片");
        row.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                keywords = s.toString();
                resetCardPage();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        return row;
    }

    private void resetCardPage() {
        visibleLimit = PAGE_SIZE;
        render();
        if (listScroll != null) listScroll.scrollTo(0, 0);
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

    void toggleDemo() {
        if (demo == null) {
            try (InputStream model = getAssets().open("model.tsv")) {
                demo = new DemoScenario(model, System.currentTimeMillis());
            } catch (Exception e) {
                Toast.makeText(this, "演示样例加载失败", Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            demo = null;
        }
        boolean showingDemo = demo != null;
        demoButton.setText(showingDemo ? "退出演示" : "离线演示");
        demoUpdate.setVisibility(showingDemo ? View.VISIBLE : View.GONE);
        liveTools.setVisibility(showingDemo ? View.GONE : View.VISIBLE);
        liveFilters.setVisibility(showingDemo ? View.GONE : View.VISIBLE);
        render();
        listScroll.scrollTo(0, 0);
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
        ReadService.instance.scheduleScrape(6000);
        Toast.makeText(this, "6 秒内切到群聊，读取后返回不爬楼确认", Toast.LENGTH_LONG).show();
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
        Engine shown = demo == null ? engine : demo.engine;
        stats.setText("处理 " + shown.nIn + " 条 · 丢弃噪声 " + shown.nNoise
                + " · 进模型 " + shown.nLevel3 + " · 待办 " + shown.all().size()
                + (shown.nPromoted > 0 ? (" · 回复潮提升 " + shown.nPromoted) : ""));
        if (demo == null) {
            updateNotifStatus();
        } else {
            notifStatus.setText("● 离线演示 · 以下为样例群消息");
            notifStatus.setTextColor(Color.parseColor("#5FD3A6"));
        }

        List<Engine.Card> list = demo == null
                ? shown.queryCards(keywords, scope, visibleLimit + 1, System.currentTimeMillis())
                : shown.ranked(PAGE_SIZE);
        if (list.isEmpty()) {
            if (demo != null) {
                empty.setText("演示卡片已清理。退出后可重新体验。");
            } else if (!keywords.trim().isEmpty() || scope != Engine.CardScope.ALL) {
                empty.setText("没有符合当前搜索或筛选的卡片。");
            } else {
                empty.setText("可以先体验「离线演示」，也可以直接粘贴一条群消息。\n要自动处理消息，请填写档案并开启通知读取。");
            }
            cardList.addView(empty);
            return;
        }
        int displayed = Math.min(visibleLimit, list.size());
        for (int i = 0; i < displayed; i++) cardList.addView(cardView(list.get(i)));
        if (list.size() > displayed) {
            cardList.addView(button("显示更多", v -> {
                int y = listScroll.getScrollY();
                visibleLimit += PAGE_SIZE;
                render();
                listScroll.post(() -> listScroll.scrollTo(0, y));
            }));
        }
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
        String title = c.displayTitle() == null ? "" : c.displayTitle();
        t.setText(isExam ? ("📅 考试 " + title) : title);
        t.setTextColor(Color.parseColor("#F2F4F8"));
        t.setTextSize(16);
        t.setTypeface(null, 1);
        // 已完成：标题加删除线。
        if (c.done) t.setPaintFlags(t.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button edit = button("修正", v -> showCardEdit(c));
        edit.setContentDescription("修正卡片：" + title);
        heading.addView(edit);
        box.addView(heading);

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
        if (!TextUtils.isEmpty(c.lastRevisionText)) {
            sb.append("\n最新更正：\n").append(c.lastRevisionText).append("\n");
        }
        if (c.context != null && !c.context.isEmpty()) {
            sb.append("\n上文：\n");
            for (String s : c.context) sb.append("· ").append(s).append("\n");
        }
        new AlertDialog.Builder(this)
                .setTitle("可溯源")
                .setMessage(sb.toString())
                .setPositiveButton("加入日历", (d, w) -> addToCalendar(c))
                .setNeutralButton("与我无关", (d, w) -> {
                    (demo == null ? engine : demo.engine).all().remove(c);
                    render();
                    if (demo == null) EngineHolder.save(this);
                })
                // 第三按钮在"已完成 / 恢复"间切换：标记后重绘并落盘（排序交给引擎）。
                .setNegativeButton(c.done ? "恢复" : "已完成", (d, w) -> {
                    if (c.done) {
                        c.done = false;   // 恢复成待办
                    } else {
                        c.done = true;    // 标记已完成
                    }
                    render();
                    if (demo == null) EngineHolder.save(this);
                })
                .show();
    }

    void showMessageImport() {
        LinearLayout form = dialogForm();
        EditText group = input(form, "群名（必填）", false);
        EditText sender = input(form, "发送者（可留空）", false);
        EditText text = input(form, "粘贴一条群消息，可保留换行", true);
        long[] sentAt = {0L};
        Button time = button("发送时间：现在", v -> chooseDateTime(sentAt[0], value -> {
            sentAt[0] = value;
            ((Button) v).setText("发送时间：" + dateTimeLabel(value));
        }));
        form.addView(time);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("粘贴群消息")
                .setView(scrollForm(form))
                .setNegativeButton("取消", null)
                .setPositiveButton("识别", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (group.getText().toString().trim().isEmpty()) {
                group.setError("请输入群名");
                return;
            }
            if (text.getText().toString().trim().isEmpty()) {
                text.setError("请粘贴消息正文");
                return;
            }
            if (engine.importText(group.getText().toString(), sender.getText().toString(),
                    text.getText().toString(), sentAt[0] == 0 ? System.currentTimeMillis() : sentAt[0])) {
                EngineHolder.save(this);
                render();
                dialog.dismiss();
                Toast.makeText(this, "已分析消息，请查看待办列表", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showDeadlineReminderSettings() {
        boolean enabled = ReminderReceiver.isEnabled(this);
        String state = enabled
                ? (ReminderReceiver.canNotify(this) ? "已开启" : "已开启，系统通知权限待确认")
                : "已关闭";
        new AlertDialog.Builder(this)
                .setTitle("到期提醒 · " + state)
                .setMessage("给时间已确认的未完成事项安排提醒。提前两小时进入提醒窗口，实际送达时间受系统调度影响。通知栏只提示有待办即将到期。")
                .setPositiveButton(enabled ? "关闭提醒" : "开启提醒", (dialog, which) -> {
                    if (enabled) {
                        ReminderReceiver.setEnabled(this, engine, false);
                    } else if (Build.VERSION.SDK_INT >= 33
                            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_REMINDER_PERMISSION);
                    } else {
                        enableDeadlineReminder();
                    }
                })
                .setNeutralButton("系统设置", (dialog, which) -> openAppNotifications())
                .setNegativeButton("返回", null)
                .show();
    }

    private void openAppNotifications() {
        Intent settings;
        if (Build.VERSION.SDK_INT >= 26) {
            settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        } else {
            settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
        }
        startActivity(settings);
    }

    private void enableDeadlineReminder() {
        ReminderReceiver.setEnabled(this, engine, true);
        Toast.makeText(this, ReminderReceiver.canNotify(this) ? "已开启到期提醒" : "请在系统设置中允许通知",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_REMINDER_PERMISSION) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            enableDeadlineReminder();
        } else {
            Toast.makeText(this, "系统未授予通知权限", Toast.LENGTH_SHORT).show();
        }
    }

    void showReadPreview(ReadPreview preview) {
        LinearLayout form = dialogForm();
        EditText group = input(form, "当前群名称（必填）", false);
        boolean[] selected = new boolean[preview.lines.size()];
        for (int i = 0; i < selected.length; i++) {
            int index = i;
            CheckBox choice = new CheckBox(this);
            choice.setText(preview.lines.get(i));
            choice.setOnCheckedChangeListener((button, checked) -> selected[index] = checked);
            form.addView(choice);
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("勾选要导入的群文字")
                .setView(scrollForm(form))
                .setNegativeButton("取消", null)
                .setPositiveButton("确认导入", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (preview.isExpired(now)) {
                Toast.makeText(this, "这次预览已过期，请重新读取", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                return;
            }
            String name = group.getText().toString().trim();
            if (name.isEmpty()) {
                group.setError("请输入当前群名称");
                return;
            }
            int imported = preview.importSelection(engine, name, selected, now);
            if (imported == 0) {
                Toast.makeText(this, "请至少勾选一条", Toast.LENGTH_SHORT).show();
                return;
            }
            EngineHolder.save(this);
            render();
            dialog.dismiss();
            Toast.makeText(this, "已导入 " + imported + " 条文字", Toast.LENGTH_SHORT).show();
        });
    }

    void showCardEdit(Engine.Card card) {
        LinearLayout form = dialogForm();
        EditText title = input(form, "事项标题", false);
        title.setText(card.displayTitle());
        Long[] deadline = {null};
        Button time = button("时间：" + dateTimeLabel(card.deadline), v -> {
            long initial = deadline[0] == null ? card.deadline : deadline[0];
            chooseDateTime(initial, value -> {
                deadline[0] = value;
                ((Button) v).setText("时间：" + dateTimeLabel(value));
            });
        });
        form.addView(time);
        form.addView(button("标为时间待确认", v -> {
            deadline[0] = 0L;
            time.setText("时间：待确认");
        }));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("修正卡片")
                .setView(scrollForm(form))
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String label = title.getText().toString().trim();
            if (label.isEmpty()) {
                title.setError("请输入事项标题");
                return;
            }
            Engine shown = demo == null ? engine : demo.engine;
            if (shown.editCard(card, label, deadline[0])) {
                if (demo == null) EngineHolder.save(this);
                render();
            } else {
                Toast.makeText(this, "这张卡片已移除", Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
        });
    }

    private LinearLayout dialogForm() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(this, 22), dp(this, 6), dp(this, 22), dp(this, 8));
        return form;
    }

    private ScrollView scrollForm(LinearLayout form) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        return scroll;
    }

    private EditText input(LinearLayout form, String hint, boolean multiline) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        if (multiline) {
            field.setMinLines(3);
            field.setMaxLines(6);
        } else {
            field.setSingleLine(true);
        }
        form.addView(field);
        return field;
    }

    private String dateTimeLabel(long value) {
        return value == 0 ? "待确认" : android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", value).toString();
    }

    private void chooseDateTime(long initial, LongConsumer selected) {
        Calendar value = Calendar.getInstance();
        if (initial > 0) value.setTimeInMillis(initial);
        new DatePickerDialog(this, (date, year, month, day) ->
                new TimePickerDialog(this, (time, hour, minute) -> {
                    value.set(year, month, day, hour, minute, 0);
                    value.set(Calendar.MILLISECOND, 0);
                    selected.accept(value.getTimeInMillis());
                }, value.get(Calendar.HOUR_OF_DAY), value.get(Calendar.MINUTE), true).show(),
                value.get(Calendar.YEAR), value.get(Calendar.MONTH), value.get(Calendar.DAY_OF_MONTH)).show();
    }

    void addToCalendar(Engine.Card c) {
        try {
            Intent i = new Intent(Intent.ACTION_INSERT);
            i.setData(CalendarContract.Events.CONTENT_URI);
            i.putExtra(CalendarContract.Events.TITLE, "【不爬楼】" + c.displayTitle());
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
                i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, c.calendarStart());
                if (!"exam".equals(c.kind)) i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, c.deadline);
            }
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "没有日历应用", Toast.LENGTH_SHORT).show();
        }
    }
}
