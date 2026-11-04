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
import java.util.ArrayList;
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
    /** 顶部问候语副标题，render() 时按小时段刷新。 */
    TextView sub;
    /** 英雄卡里的大数字：今天截止的张数。 */
    TextView heroNumber;
    /** 英雄卡里数字右侧的说明文字。 */
    TextView heroLabel;
    /** 撞车提醒横幅：几天内几件事挤在一起时显示在统计句下方。 */
    TextView clashBanner;
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

    /** 顶部问候语：按小时段返回一句人话，让首屏先有温度再谈待办。 */
    String greeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour >= 5 && hour <= 11) return "早上好，看看今天要做什么";
        if (hour >= 12 && hour <= 17) return "下午好，今天还剩这些事";
        return "晚上好，把明天的事先看一眼";
    }

    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusableInTouchMode(true);
        // 背景统一走设计规范的底色，避免各页面色调不一致。
        root.setBackgroundColor(Ui.BG);
        root.setPadding(dp(this, 18), dp(this, 28), dp(this, 18), dp(this, 10));

        TextView title = new TextView(this);
        title.setText("不爬楼");
        title.setTextColor(Ui.TEXT);
        title.setTextSize(24);
        title.setTypeface(null, 1);
        root.addView(title);

        sub = new TextView(this);
        sub.setText(greeting());
        sub.setTextColor(Ui.SUB);
        sub.setTextSize(13);
        root.addView(sub);

        // 今日英雄卡：把「今天还剩几件」放大成首屏第一结论，渐变底压在标题下方。
        LinearLayout heroCard = new LinearLayout(this);
        heroCard.setOrientation(LinearLayout.VERTICAL);
        heroCard.setPadding(dp(this, 16), dp(this, 16), dp(this, 16), dp(this, 16));
        heroCard.setBackground(Ui.gradient(this,
                Color.parseColor("#1E3B34"), Color.parseColor("#161B22"), 18));
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        heroLp.topMargin = dp(this, 12);
        heroLp.bottomMargin = dp(this, 10);
        heroCard.setLayoutParams(heroLp);

        LinearLayout heroRow = new LinearLayout(this);
        heroRow.setOrientation(LinearLayout.HORIZONTAL);
        heroRow.setGravity(Gravity.CENTER_VERTICAL);

        heroNumber = new TextView(this);
        heroNumber.setText("0");
        heroNumber.setTextSize(44);
        heroNumber.setTypeface(null, 1);
        heroNumber.setTextColor(Ui.TEAL);
        heroNumber.setIncludeFontPadding(false);
        heroRow.addView(heroNumber);

        LinearLayout heroText = new LinearLayout(this);
        heroText.setOrientation(LinearLayout.VERTICAL);
        // 数字与说明文字之间留 12dp，说明区吃掉剩余宽度。
        LinearLayout.LayoutParams heroTextLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        heroTextLp.leftMargin = dp(this, 12);
        heroRow.addView(heroText, heroTextLp);

        heroLabel = new TextView(this);
        heroLabel.setText("件事今天截止");
        heroLabel.setTextSize(14);
        heroLabel.setTypeface(null, 1);
        heroLabel.setTextColor(Ui.TEXT);
        heroText.addView(heroLabel);

        // 统计句从 root 挪进卡片，作为英雄卡的第二行说明，字号和颜色同步降级。
        stats = new TextView(this);
        stats.setTextSize(12);
        stats.setTextColor(Ui.SUB);
        stats.setPadding(0, dp(this, 2), 0, 0);
        heroText.addView(stats);

        heroCard.addView(heroRow);
        root.addView(heroCard);

        // 撞车提醒横幅：紧跟统计句、在通知状态之前，样式与卡片区分但同属暖色系。
        clashBanner = new TextView(this);
        clashBanner.setTextSize(12);
        clashBanner.setTextColor(Color.parseColor("#FF9F7A"));
        clashBanner.setPadding(dp(this, 12), dp(this, 8), dp(this, 12), dp(this, 8));
        GradientDrawable bannerBg = new GradientDrawable();
        bannerBg.setColor(Color.parseColor("#2A1A14"));
        bannerBg.setCornerRadius(dp(this, 10));
        bannerBg.setStroke(dp(this, 1), Color.parseColor("#5A3524"));
        clashBanner.setBackground(bannerBg);
        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bannerLp.bottomMargin = dp(this, 8);
        clashBanner.setLayoutParams(bannerLp);
        // 点横幅看这一组每张卡的具体时间。
        clashBanner.setOnClickListener(v -> showClashDetail());
        // 默认不占位，由 render() 决定是否显示。
        clashBanner.setVisibility(View.GONE);
        root.addView(clashBanner);

        notifStatus = new TextView(this);
        notifStatus.setTextSize(12);
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
        // 最常用的两个入口保留在工具栏最前面，其余入口收进「更多」下拉菜单。
        liveTools.addView(stylePrimary(button("粘贴群消息", v -> showMessageImport())));
        liveTools.addView(stylePrimary(button("读当前群", v -> readCurrentScreen())));
        liveTools.addView(button("更多", v -> showMoreMenu()));
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
        btn.setTextSize(13);
        btn.setAllCaps(false);
        btn.setTextColor(Ui.TEXT);
        // 次级按钮：次级面板底色 + 1dp 描边，和卡片同一套圆角语言。
        btn.setBackground(Ui.roundStroke(this, Ui.SURFACE2, Ui.STROKE, 18, 1));
        btn.setPadding(dp(this, 16), dp(this, 8), dp(this, 16), dp(this, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(this, 8);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(l);
        return btn;
    }

    /** 主行动按钮：实心强调色底 + 深色粗体字，只给首屏最常用的入口用。 */
    Button stylePrimary(Button b) {
        b.setBackground(Ui.round(this, Ui.TEAL, 18));
        b.setTextColor(Color.parseColor("#0B1F18"));
        b.setTypeface(null, 1);
        return b;
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

    /**
     * 「更多」菜单：把原来挤在工具栏上的次级入口收进一个下拉列表。
     * 每一项做的事和原按钮的点击回调完全一致，只是换了入口。
     */
    void showMoreMenu() {
        new AlertDialog.Builder(this)
                .setItems(new String[]{"到期提醒", "我的档案", "通知读取权限", "通知回放", "清空本机数据"},
                        (d, which) -> {
                            switch (which) {
                                case 0:
                                    // 到期提醒设置
                                    showDeadlineReminderSettings();
                                    break;
                                case 1:
                                    // 我的档案
                                    startActivity(new Intent(this, ProfileActivity.class));
                                    break;
                                case 2:
                                    // 通知读取权限：跳到系统的通知使用权页面
                                    startActivity(new Intent(
                                            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                                    break;
                                case 3:
                                    // 通知回放
                                    startReplay();
                                    break;
                                case 4:
                                    // 清空本机数据：确认后清掉全部卡片和近期消息，并落盘
                                    new AlertDialog.Builder(this)
                                            .setTitle("清空待办和近期消息？")
                                            .setMessage("会移除本机的全部卡片，以及这次运行中用于识别的近期消息。")
                                            .setPositiveButton("清空", (dialog, w) -> {
                                                if (ReadService.instance != null)
                                                    ReadService.instance.cancelPendingPreview();
                                                engine.clearLocalData();
                                                EngineHolder.save(this);
                                            })
                                            .setNegativeButton("取消", null)
                                            .show();
                                    break;
                                default:
                                    break;
                            }
                        })
                .show();
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
        // 每次重绘都重新算问候语：跨过中午、傍晚后回到前台文案能跟着变。
        if (sub != null) sub.setText(greeting());
        cardList.removeAllViews();
        Engine shown = demo == null ? engine : demo.engine;
        // 顶部改成用户看得懂的一句话：今天几件、明天几件、有多少过期和待确认。
        // 颜色沿用原来的绿色，不改外观基调。
        stats.setText(TodayView.summary(shown.all(), System.currentTimeMillis()));
        // 英雄卡计数：与下方分组标题共用 TodayView.bucket，保证数字和「今天」分组张数一致。
        long heroNow = System.currentTimeMillis();
        int todayCount = 0;
        List<Engine.Card> heroCards = shown.all();
        for (int i = 0; i < heroCards.size(); i++) {
            Engine.Card hc = heroCards.get(i);
            if ("今天".equals(TodayView.bucket(hc.deadline, hc.needsConfirm, hc.done, heroNow))) {
                todayCount++;
            }
        }
        heroNumber.setText(String.valueOf(todayCount));
        // 今天没有截止就弱化整块卡，别用绿色数字制造无谓压力。
        if (todayCount == 0) {
            heroLabel.setText("今天没有截止，轻松一下");
            heroLabel.setTextColor(Ui.SUB);
            heroNumber.setTextColor(Ui.SUB);
        } else {
            heroLabel.setText("件事今天截止");
            heroLabel.setTextColor(Ui.TEAL);
            heroNumber.setTextColor(Ui.TEAL);
        }
        // 开发者统计整句不删，改为长按顶部统计句时用 Toast 展示（演示模式下同样适用）。
        stats.setOnLongClickListener(v -> {
            // 原文拼接逻辑原样保留，只是从常显改成按需查看。
            Toast.makeText(MainActivity.this, "处理 " + shown.nIn + " 条 · 丢弃噪声 " + shown.nNoise
                    + " · 进模型 " + shown.nLevel3 + " · 待办 " + shown.all().size()
                    + (shown.nPromoted > 0 ? (" · 回复潮提升 " + shown.nPromoted) : ""),
                    Toast.LENGTH_LONG).show();
            return true;
        });
        // 撞车提醒：取最近的一组（7 天内、与前一张相差 3 小时内、至少两张）显示在横幅上。
        // 演示模式和真实模式都用 shown，所以两种数据源行为一致。
        ClashPlan.Clash clash = ClashPlan.nearest(shown.all(), System.currentTimeMillis());
        if (clash != null) {
            clashBanner.setText("⚠ " + clash.text());
            clashBanner.setVisibility(View.VISIBLE);
        } else {
            clashBanner.setVisibility(View.GONE);
        }
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
                // 空状态改成更温暖的引导：告诉用户下一步点哪里，而不是只描述"没有卡片"。
                empty.setText("这里还没有待办。\n点「读当前群」或「粘贴群消息」，把群里的事变成清单。");
            }
            cardList.addView(empty);
            return;
        }
        int displayed = Math.min(visibleLimit, list.size());
        // 今日视图：先取前 displayed 张卡，再按 TodayView.ORDER 的固定分组顺序展示。
        long now = System.currentTimeMillis();
        List<Engine.Card> visible = new ArrayList<>(list.subList(0, displayed));
        for (int g = 0; g < TodayView.ORDER.length; g++) {
            String groupName = TodayView.ORDER[g];
            // 先按原顺序收集这一组的卡，保证组内顺序和引擎排序一致。
            List<Engine.Card> group = new ArrayList<>();
            for (int i = 0; i < visible.size(); i++) {
                Engine.Card c = visible.get(i);
                if (groupName.equals(TodayView.bucket(c.deadline, c.needsConfirm, c.done, now))) {
                    group.add(c);
                }
            }
            // 这一组没卡就不出标题，避免出现空分组。
            if (group.isEmpty()) continue;
            cardList.addView(groupHeader(groupName, group.size()));
            for (int i = 0; i < group.size(); i++) cardList.addView(cardView(group.get(i)));
        }
        if (list.size() > displayed) {
            cardList.addView(button("显示更多", v -> {
                int y = listScroll.getScrollY();
                visibleLimit += PAGE_SIZE;
                render();
                listScroll.post(() -> listScroll.scrollTo(0, y));
            }));
        }
    }

    /** 点击撞车横幅：逐行列出这一组里每张卡的标题和截止时间。 */
    void showClashDetail() {
        // 用和 render() 相同的数据源（演示模式取 demo 引擎），结果与横幅一致。
        Engine shown = demo == null ? engine : demo.engine;
        ClashPlan.Clash clash = ClashPlan.nearest(shown.all(), System.currentTimeMillis());
        // 卡片可能在渲染之后被删掉或改了时间，这时已经不存在撞车，收掉横幅即可。
        if (clash == null) {
            clashBanner.setVisibility(View.GONE);
            return;
        }
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < clash.cards.size(); i++) {
            Engine.Card c = clash.cards.get(i);
            // 每张卡一行：标题 + 两个空格 + 自然语言截止时间。
            if (i > 0) body.append("\n");
            body.append("· ").append(c.displayTitle()).append("  ")
                    .append(Engine.humanDeadline(c.deadline));
        }
        new AlertDialog.Builder(this)
                .setTitle("这几件事挤在一起")
                .setMessage(body.toString())
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 分组标题：左侧一枚语义色圆点，右侧「组名  数量」，弱化样式，只做视觉分隔。 */
    View groupHeader(String name, int count) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        // 圆点颜色跟着分组语义走，让首屏扫视时先认色再认字。
        int dotColor;
        if ("今天".equals(name)) {
            dotColor = Ui.TEAL;
        } else if ("明天".equals(name)) {
            dotColor = Ui.BLUE;
        } else if ("这几天".equals(name)) {
            dotColor = Ui.SUB;
        } else if ("待确认".equals(name)) {
            dotColor = Ui.AMBER;
        } else if ("已过期".equals(name)) {
            dotColor = Ui.RED;
        } else {
            // 更晚、已完成统一弱化。
            dotColor = Ui.MUTE;
        }
        row.addView(Ui.dot(this, dotColor, 8));

        TextView label = new TextView(this);
        label.setText(name + "  " + count);
        label.setTextSize(13);
        label.setTypeface(null, 1);
        label.setTextColor(Ui.SUB);
        // 圆点与文字之间留 8dp。
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.leftMargin = dp(this, 8);
        row.addView(label, labelLp);

        // 分组之间留白：上 10dp 下 8dp，避免和卡片贴在一起。
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 10);
        lp.bottomMargin = dp(this, 8);
        row.setLayoutParams(lp);
        return row;
    }

    View cardView(Engine.Card c) {
        // 标题、时间与紧急度在整张卡里多处复用，先算好。
        boolean isExam = "exam".equals(c.kind);
        String title = c.displayTitle() == null ? "" : c.displayTitle();
        long now = System.currentTimeMillis();
        int level = TodayView.urgency(c.deadline, c.needsConfirm, c.done, now);
        // 待确认或没有时间统一用琥珀色，其余按紧急度取色。
        int urgent = (c.needsConfirm || c.deadline == 0) ? Ui.AMBER : Ui.urgencyColor(level);

        // 外层改为横向：左边完成圈，右边内容列。
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(this, 14), dp(this, 14), dp(this, 14), dp(this, 14));
        // 待确认的卡片用暖色底 + 暖色描边，和普通卡片一眼区分。
        if (c.needsConfirm) {
            box.setBackground(Ui.roundStroke(this, Color.parseColor("#1A1712"),
                    Color.parseColor("#4A3A1E"), 16, 1));
        } else {
            box.setBackground(Ui.roundStroke(this, Ui.SURFACE, Ui.STROKE, 16, 1));
        }
        // 已完成的卡片整体变淡。
        if (c.done) box.setAlpha(0.5f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(this, 10);
        box.setLayoutParams(lp);

        // 完成圈：未完成是空心描边圈，已完成是实心绿底对勾；点它切换完成状态。
        TextView check = new TextView(this);
        check.setGravity(Gravity.CENTER);
        check.setIncludeFontPadding(false);
        if (c.done) {
            check.setText("✓");
            check.setTextSize(14);
            check.setTypeface(null, 1);
            check.setTextColor(Color.parseColor("#0B1F18"));
            check.setBackground(Ui.round(this, Ui.TEAL, 13));
        } else {
            // 空心圈，圆圈颜色就是这张卡的紧急度颜色。
            check.setText("");
            check.setBackground(Ui.roundStroke(this, Color.TRANSPARENT, urgent, 13, 2));
        }
        check.setOnClickListener(v -> toggleDone(c));
        check.setContentDescription((c.done ? "恢复卡片：" : "完成卡片：") + title);
        LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(
                dp(this, 26), dp(this, 26));
        checkLp.rightMargin = dp(this, 12);
        checkLp.topMargin = dp(this, 2);
        box.addView(check, checkLp);

        // 内容列吃掉剩余宽度。
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        box.addView(content, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 标题行：标题 + 「修正」入口。
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        // 考试卡：标题前加"📅 考试 "，一眼和普通待办区分开
        t.setText(isExam ? ("📅 考试 " + title) : title);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(16);
        t.setTypeface(null, 1);
        // 已完成：标题加删除线。
        if (c.done) t.setPaintFlags(t.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        heading.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // 「修正」保留原来的按钮写法，再降级成文字链接样式，不抢标题的视觉重量。
        Button edit = button("修正", v -> showCardEdit(c));
        edit.setBackground(null);
        edit.setTextColor(Ui.SUB);
        edit.setTextSize(12);
        edit.setContentDescription("修正卡片：" + title);
        heading.addView(edit);
        content.addView(heading);

        // 胶囊行：倒计时 + 考试 / 接龙标记。
        LinearLayout pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setGravity(Gravity.CENTER_VERTICAL);
        // 倒计时文字就是原来 meta 里的倒计时部分：没有时间时只说待确认。
        String countdown = c.deadline == 0 ? "待确认" : TodayView.countdown(c.deadline, now);
        List<TextView> pillViews = new ArrayList<>();
        pillViews.add(Ui.pill(this, countdown, urgent));
        // 考试卡不再写"时间："前缀，改用一个蓝色「考试」胶囊表明类型。
        if (isExam) pillViews.add(Ui.pill(this, "考试", Ui.BLUE));
        // 接龙卡单独标出来，方便一眼分开两种来源。
        if ("jielong".equals(c.kind)) pillViews.add(Ui.pill(this, "接龙", Ui.AMBER));
        for (int i = 0; i < pillViews.size(); i++) {
            LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            // 胶囊之间留 6dp。
            pillLp.rightMargin = dp(this, 6);
            pills.addView(pillViews.get(i), pillLp);
        }
        LinearLayout.LayoutParams pillRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillRowLp.topMargin = dp(this, 8);
        content.addView(pills, pillRowLp);

        // 具体时间与地点：没有截止时间时整行不显示。
        if (c.deadline != 0) {
            // 考试卡优先显示地点（考场），其他卡仍沿用原来的渠道优先
            String where;
            if (isExam) {
                where = !TextUtils.isEmpty(c.place) ? (" · " + c.place) : "";
            } else {
                where = c.channel != null ? (" · " + c.channel)
                        : (c.place != null ? (" · " + c.place) : "");
            }
            TextView when = new TextView(this);
            when.setText(Engine.humanDeadline(c.deadline) + where);
            when.setTextColor(Ui.SUB);
            when.setTextSize(12);
            LinearLayout.LayoutParams whenLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            whenLp.topMargin = dp(this, 6);
            content.addView(when, whenLp);
        }

        // 考试卡：要带的东西单独一行，没有就不显示
        if (isExam && !TextUtils.isEmpty(c.bring)) {
            TextView b = new TextView(this);
            b.setText("带：" + c.bring);
            b.setTextColor(Ui.BLUE);
            b.setTextSize(12);
            b.setPadding(0, dp(this, 4), 0, 0);
            content.addView(b);
        }

        // 来源行：群名 · 发送者 · 已更新 / 已完成。
        TextView src = new TextView(this);
        src.setText(c.groupName + " · " + c.sender + (c.updated ? " · 已更新" : "")
                + (c.done ? " · 已完成" : ""));
        src.setTextColor(Ui.MUTE);
        src.setTextSize(11);
        LinearLayout.LayoutParams srcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srcLp.topMargin = dp(this, 8);
        content.addView(src, srcLp);

        // 识别原因提示：只在引擎给出原因时出现。
        if (!TextUtils.isEmpty(c.reason)) {
            TextView r = new TextView(this);
            r.setText("⚠ " + c.reason);
            r.setTextColor(Ui.AMBER);
            r.setTextSize(11);
            r.setPadding(0, dp(this, 6), 0, 0);
            content.addView(r);
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
                .setNegativeButton(c.done ? "恢复" : "已完成", (d, w) -> toggleDone(c))
                .show();
    }

    /**
     * 标记完成 / 恢复成待办。
     * 从原弹窗按钮回调原样抽出：只翻转 c.done，然后重绘；非演示模式下落盘。
     */
    void toggleDone(Engine.Card c) {
        if (c.done) {
            c.done = false;   // 恢复成待办
        } else {
            c.done = true;    // 标记已完成
        }
        render();
        if (demo == null) EngineHolder.save(this);
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
