package com.nopalou;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
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
    /** 顶部副标题（日期 + 问候），render() 时按小时段刷新。 */
    TextView sub;
    /** 撞车提醒横幅：几天内几件事挤在一起时显示在统计句下方。 */
    TextView clashBanner;
    TextView notifStatus;
    TextView empty;
    boolean accessChecked;
    private DemoScenario demo;
    private Button demoButton;
    private Button demoUpdate;
    private LinearLayout liveTools;
    /** 手动导入的两个兜底入口：仍然构造出来备用，但不再常驻底部栏，只从「更多」菜单进入。 */
    private Button pasteButton;
    private Button readButton;
    /** 底部栏唯一的整宽主按钮：开启自动读取（通知读取权限）。 */
    private Button autoReadButton;
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
        // 每次打开 App 都确保看门狗已登记（已存在时不会重复登记，不影响原有周期）。
        WatchdogJob.schedule(this);
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

    /**
     * 「自动读取状态」小条：主界面最重要的一句话。
     * 已连接 → 一行绿色小字，说明自动读取在跑、最近一条是什么时候；
     * 未连接 → 整条变成浅琥珀可点提醒条，点一下给出重新开启的步骤；
     * 离线演示 → 保持原来的演示提示（由 render() 单独处理）。
     */
    void updateNotifStatus() {
        if (notifStatus == null) return;
        boolean on = demo == null && EngineHolder.listenerConnected;
        if (on) {
            // 已连接：绿点 + 绿色小字，背景去掉，回到干净的一行文字。
            long last = EngineHolder.lastReceivedAt;
            String line;
            if (last == 0) {
                // 真机问题：原本文案太长会折成两行，这里压到一行放得下。
                line = "通知读取：已连接 · 等群里来新消息";
            } else {
                // 只留相对时间，不再拼群名，保证一行显示完整；「已连接」保留作状态锚点。
                line = "通知读取：已连接 · 最近一条 " + relativeTime(last);
            }
            notifStatus.setText(line);
            notifStatus.setTextColor(Ui.GREEN);
            notifStatus.setTextSize(12);
            notifStatus.setBackground(null);
            notifStatus.setPadding(0, dp(this, 6), 0, dp(this, 4));
        } else {
            // 未连接：整条做成可点的琥珀提醒条，比一行灰字更像「该处理的事」。
            notifStatus.setText("通知读取：未连接。点这里开启，群消息就会自动变成待办");
            notifStatus.setTextColor(Ui.AMBER_TX);
            notifStatus.setTextSize(12);
            notifStatus.setBackground(Ui.round(this, Ui.AMBER_BG, 12));
            notifStatus.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
        }
    }

    /**
     * 相对时间：不到 1 分钟「刚刚」，不到 60 分钟「N 分钟前」，
     * 不到 24 小时「N 小时前」，再久就是「N 天前」。手机时间被回拨时不出现负数。
     */
    String relativeTime(long when) {
        long diff = System.currentTimeMillis() - when;
        if (diff < 0) diff = 0;
        long minutes = diff / 60000L;
        if (minutes < 1) return "刚刚";
        if (minutes < 60) return minutes + " 分钟前";
        long hours = minutes / 60;
        if (hours < 24) return hours + " 小时前";
        return (hours / 24) + " 天前";
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

    /**
     * 顶部问候词：只返回三个短词，界面里和日期拼在同一行，
     * 长句子会把「M月d日 · 问候」这一行撑换行，浅色风格也不适合喊话式文案。
     */
    String greeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour >= 5 && hour <= 11) return "早上好";
        if (hour >= 12 && hour <= 17) return "下午好";
        return "晚上好";
    }

    /** 副标题整行：日期 + 星期 + 问候词，形如「11月4日 星期三 · 晚上好」。 */
    String subLine() {
        Calendar c = Calendar.getInstance();
        String[] week = {"日", "一", "二", "三", "四", "五", "六"};
        String day = c.get(Calendar.MONTH) + 1 + "月" + c.get(Calendar.DAY_OF_MONTH) + "日";
        return day + " 星期" + week[c.get(Calendar.DAY_OF_WEEK) - 1] + " · " + greeting();
    }

    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusableInTouchMode(true);
        // 背景统一走设计规范的底色，避免各页面色调不一致。
        root.setBackgroundColor(Ui.BG);
        // 下内边距留给底部操作栏自己撑（那里带 20dp 的底部留白），
        // 否则两处留白叠加会让列表和按钮之间空一大块。
        root.setPadding(dp(this, 20), dp(this, 40), dp(this, 20), 0);

        // 标题行：左边「今天」大标题，右边一颗圆形「更多」，与设计稿同一套对齐方式（底部对齐）。
        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.BOTTOM);

        TextView title = new TextView(this);
        title.setText("今天");
        title.setTextColor(Ui.TEXT);
        title.setTextSize(32);
        title.setTypeface(null, 1);
        title.setIncludeFontPadding(false);
        // 标题吃掉剩余宽度，把「更多」顶到最右边。
        headRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 圆形「更多」：白底 + 极淡投影，在浅灰背景上轻轻浮起，与设计稿的 36dp 圆一致。
        TextView more = new TextView(this);
        more.setText("⋯");
        more.setTextSize(18);
        more.setTextColor(Ui.SUB);
        more.setGravity(Gravity.CENTER);
        more.setIncludeFontPadding(false);
        more.setContentDescription("更多");
        more.setBackground(Ui.round(this, Ui.CARD, 18));
        Ui.lift(more, this);
        headRow.addView(more, new LinearLayout.LayoutParams(dp(this, 36), dp(this, 36)));
        more.setOnClickListener(v -> showMoreMenu());
        root.addView(headRow);

        // 日期 + 问候：一行带过，字号降级成辅助信息，不和标题抢注意力。
        sub = new TextView(this);
        sub.setText(subLine());
        sub.setTextColor(Ui.SUB);
        sub.setTextSize(14);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(this, 4);
        root.addView(sub, subLp);

        // 统计句：不再放大数字做英雄卡，只留一行说明今天/明天/过期的分布。
        stats = new TextView(this);
        stats.setTextSize(14);
        stats.setTextColor(Ui.SUB);
        LinearLayout.LayoutParams statsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statsLp.topMargin = dp(this, 10);
        root.addView(stats, statsLp);

        // 撞车提醒横幅：浅琥珀底 + 深琥珀字，属于暖色系但不刺眼，和白色卡片区分开。
        clashBanner = new TextView(this);
        clashBanner.setTextSize(13);
        clashBanner.setTextColor(Ui.AMBER_TX);
        clashBanner.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        // 1.2 倍行距：横幅里常有两行文案，行距太紧会糊成一块。
        clashBanner.setLineSpacing(0, 1.2f);
        clashBanner.setBackground(Ui.round(this, Ui.AMBER_BG, 14));
        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bannerLp.topMargin = dp(this, 8);
        bannerLp.bottomMargin = dp(this, 4);
        clashBanner.setLayoutParams(bannerLp);
        // 点横幅看这一组每张卡的具体时间。
        clashBanner.setOnClickListener(v -> showClashDetail());
        // 默认不占位，由 render() 决定是否显示。
        clashBanner.setVisibility(View.GONE);
        root.addView(clashBanner);

        // 自动读取状态小条：默认一行绿色小字，未连接时整条变成可点的琥珀提醒条（样式在 updateNotifStatus 里切换）。
        notifStatus = new TextView(this);
        notifStatus.setTextSize(12);
        // 1.15 倍行距：琥珀条里两行文案时不至于挤在一起。
        notifStatus.setLineSpacing(0, 1.15f);
        notifStatus.setPadding(0, dp(this, 6), 0, dp(this, 4));
        notifStatus.setOnClickListener(v -> {
            // 只有未连接时才可点：点一次给出重新授权步骤；已连接后点击不做任何事。
            if (demo == null && !EngineHolder.listenerConnected) showNotifHint();
        });
        LinearLayout.LayoutParams notifLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        notifLp.topMargin = dp(this, 6);
        root.addView(notifStatus, notifLp);

        // 工具栏：原来横向滚动的整排按钮，改成两颗小号描边胶囊，
        // 只留在演示模式里用得上的两个入口，桌面更干净。
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams toolbarLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toolbarLp.topMargin = dp(this, 12);
        toolbarLp.bottomMargin = dp(this, 4);
        toolbar.setLayoutParams(toolbarLp);

        demoButton = button("离线演示", v -> toggleDemo());
        toolbar.addView(demoButton);
        demoUpdate = button("播放群消息更新", v -> {
            if (demo == null) return;
            demo.applyUpdates();
            demoUpdate.setVisibility(View.GONE);
            render();
        });
        demoUpdate.setVisibility(View.GONE);
        toolbar.addView(demoUpdate);
        root.addView(toolbar);

        liveFilters = buildFilters();
        root.addView(liveFilters);
        listScroll = new ScrollView(this);
        cardList = new LinearLayout(this);
        cardList.setOrientation(LinearLayout.VERTICAL);
        cardList.setPadding(0, dp(this, 12), 0, dp(this, 24));
        listScroll.addView(cardList);

        empty = new TextView(this);
        empty.setTextColor(Ui.SUB);
        empty.setTextSize(13);
        empty.setLineSpacing(dp(this, 4), 1f);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(this, 60), 0, 0);
        cardList.addView(empty);

        root.addView(listScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部栏：全自动优先，这里不再常驻「粘贴群消息 / 读当前群」。
        // 只有还没连上通知读取时，才冒出一颗整宽的蓝色主按钮「开启自动读取」；
        // 连上之后底部整条收掉，用户什么都不用点，卡片自己出现。
        liveTools = new LinearLayout(this);
        liveTools.setOrientation(LinearLayout.HORIZONTAL);
        liveTools.setGravity(Gravity.CENTER_VERTICAL);
        liveTools.setPadding(0, dp(this, 10), 0, dp(this, 20));

        // 手动导入降级成兜底：两个入口照旧构造（功能完整保留），但不再挂到底部栏，
        // 改从「更多」菜单里的「手动补录：…」进入。
        pasteButton = button("粘贴群消息", v -> showMessageImport());
        // 次要动作：白底胶囊 + 极淡投影，留作备用样式。
        pasteButton.setBackground(Ui.round(this, Ui.CARD, 25));
        pasteButton.setTextColor(Ui.TEXT);
        pasteButton.setTextSize(15);
        pasteButton.setTypeface(null, 1);
        pasteButton.setPadding(0, 0, 0, 0);
        Ui.lift(pasteButton, this);

        readButton = button("读当前群", v -> readCurrentScreen());
        // 主要动作：品牌蓝实心胶囊 + 白字粗体。
        readButton.setBackground(Ui.round(this, Ui.BLUE, 25));
        readButton.setTextColor(Color.WHITE);
        readButton.setTextSize(15);
        readButton.setTypeface(null, 1);
        readButton.setPadding(0, 0, 0, 0);

        // 底部栏唯一的动作：开启自动读取。
        autoReadButton = button("开启自动读取", v -> showNotifHint());
        autoReadButton.setBackground(Ui.round(this, Ui.BLUE, 25));
        autoReadButton.setTextColor(Color.WHITE);
        autoReadButton.setTextSize(15);
        autoReadButton.setTypeface(null, 1);
        autoReadButton.setPadding(0, 0, 0, 0);
        liveTools.addView(autoReadButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 50)));

        root.addView(liveTools, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        // 浅色界面：状态栏和导航栏用浅色底、深色图标，不然顶部会有一条突兀的黑边。
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        if (Build.VERSION.SDK_INT >= 26) {
            root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } else if (Build.VERSION.SDK_INT >= 23) {
            root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        root.requestFocus();
    }

    private LinearLayout buildFilters() {
        // 外层容器：上面一行筛选，下面压一条 1px 细线；容器本身高度只多 1px，滚动区域位置不变。
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        // 筛选行：固定 44dp 行高，下拉框和搜索框都按整行高对齐，视觉上才是同一行。
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        Spinner state = new Spinner(this);
        state.setContentDescription("待办状态筛选");
        // 去掉系统默认的灰底下拉框，浅色界面里只留文字，避免多出一块色块。
        state.setBackgroundColor(Color.TRANSPARENT);
        Engine.CardScope[] scopes = {Engine.CardScope.ALL, Engine.CardScope.OPEN,
                Engine.CardScope.OVERDUE, Engine.CardScope.COMPLETED};
        // 下拉框文字统一走主文字色，和右边的搜索文字同一层级。
        ArrayAdapter<String> options = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"全部", "未完成", "已过期", "已完成"}) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(Ui.TEXT);
                v.setTextSize(14);
                return v;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getDropDownView(position, convertView, parent);
                v.setTextColor(Ui.TEXT);
                return v;
            }
        };
        options.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        state.setAdapter(options);
        // 高度撑满 44dp 行高，文字由 Spinner 自身垂直居中。
        row.addView(state, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
        // 搜索框去底色：背景置空（完全透明），主色文字 + 浅灰提示，左边距 8dp 和下拉开一点距离。
        search.setBackground(null);
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.MUTE);
        search.setPadding(dp(this, 8), 0, dp(this, 8), 0);
        search.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(search, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                keywords = s.toString();
                resetCardPage();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        wrap.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 44)));
        // 行下细线：放在容器底部，和筛选行一起显示 / 隐藏，不影响下方滚动区域的位置。
        wrap.addView(Ui.hairline(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return wrap;
    }

    private void resetCardPage() {
        visibleLimit = PAGE_SIZE;
        render();
        if (listScroll != null) listScroll.scrollTo(0, 0);
    }

    /** 小号描边胶囊：工具栏与列表底部「显示更多」共用，透明底 + 1dp 细描边。 */
    Button button(String text, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setAllCaps(false);
        btn.setTextColor(Ui.SUB);
        btn.setBackground(Ui.roundStroke(this, Color.TRANSPARENT, Ui.LINE, 16, 1));
        // 真机问题：系统 Button 自带的最小高度 / 最小宽度会把胶囊撑成大灰框，全部清零。
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        // 去掉系统默认的按压阴影动画，浅色界面里只要一颗扁平小胶囊（API 21 起可用）。
        btn.setStateListAnimator(null);
        // 不继承系统按钮的默认内边距，左右 12dp、上下 7dp 让胶囊看起来紧凑。
        btn.setPadding(dp(this, 12), dp(this, 7), dp(this, 12), dp(this, 7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(this, 8);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(l);
        return btn;
    }

    /** 主行动按钮：品牌蓝实心底 + 白字粗体，只给底部最常用的入口用。 */
    Button stylePrimary(Button b) {
        b.setBackground(Ui.round(this, Ui.BLUE, 25));
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
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
                // 第一项固定是「自动读取自检」：让用户和评委一眼看到自动读取现在健不健康。
                .setItems(new String[]{"自动读取自检", "到期提醒", "我的档案", "通知读取权限",
                                "手动补录：粘贴群消息", "手动补录：读当前群", "通知回放", "诊断日志",
                                "清空本机数据"},
                        (d, which) -> {
                            switch (which) {
                                case 0:
                                    // 自动读取自检：权限、连接、电池、最近收消息时间一次讲清楚。
                                    showHealthCheck();
                                    break;
                                case 1:
                                    // 到期提醒设置
                                    showDeadlineReminderSettings();
                                    break;
                                case 2:
                                    // 我的档案
                                    startActivity(new Intent(this, ProfileActivity.class));
                                    break;
                                case 3:
                                    // 通知读取权限：跳到系统的通知使用权页面
                                    startActivity(new Intent(
                                            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                                    break;
                                case 4:
                                    // 手动补录一：粘贴一条群消息（兜底路径）
                                    showMessageImport();
                                    break;
                                case 5:
                                    // 手动补录二：读当前群屏幕（兜底路径）
                                    readCurrentScreen();
                                    break;
                                case 6:
                                    // 通知回放
                                    startReplay();
                                    break;
                                case 7:
                                    // 诊断日志：最近 30 条收到 / 跳过 / 出错 / 连接记录。
                                    showDiagLog();
                                    break;
                                case 8:
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

    /**
     * 「自动读取自检」：把 HealthCheck 的结论原样摆在用户面前。
     * 没问题就给一句让人放心的确认；有问题就逐条列出，并只给最该点的那一个按钮。
     */
    void showHealthCheck() {
        // 四项输入全部来自真实状态：权限、服务连接、电池放行、最近一次收到消息的时间。
        HealthCheck.Result r = HealthCheck.evaluate(WatchdogJob.accessEnabled(this),
                EngineHolder.listenerConnected, WatchdogJob.batteryIgnored(this),
                EngineHolder.lastReceivedAt, System.currentTimeMillis());

        // 正文按「结论 + 保活说明」两个部分拼，先建好 StringBuilder。
        StringBuilder body = new StringBuilder();
        // 没有问题：用一句话说清三项都正常。
        if (r.issues.isEmpty()) {
            body.append("通知读取权限已开，服务已连接，电池不受限。群里有新消息会自动出现在待办里。");
        } else {
            // 有问题：每个问题占一行，前面带「· 」，顺序就是规则顺序。
            for (int i = 0; i < r.issues.size(); i++) {
                // 第一条不加换行，后面每条先换行再写。
                if (i > 0) body.append("\n");
                body.append("· ").append(r.issues.get(i).text);
            }
        }
        // 收到过消息才补一行「最近一次自动收到」，没收到过就不提，避免显示 0。
        if (EngineHolder.lastReceivedAt > 0) {
            body.append("\n最近一次自动收到：").append(ago(EngineHolder.lastReceivedAt));
        }
        // 保活说明固定接在正文末尾，原「让它一直在后台运行」的四行内容一字不改。
        body.append("\n\n保持自动读取不被系统杀掉：\n")
                .append("1. 在系统设置里允许「不爬楼」自启动和后台运行（vivo：设置 > 应用与权限 > 权限管理 > 自启动）。\n")
                .append("2. 电池设置里把「不爬楼」设为不限制或允许后台高耗电。\n")
                .append("3. 在最近任务里不要上滑清掉它；可以长按卡片锁定。\n")
                .append("4. 重启手机后打开一次 App，确认状态是「已连接」。");

        // 三个问题标记先取出来，再决定两个按钮分别放哪个动作。
        boolean needAccess = hasFix(r, "NOTIF_ACCESS");
        boolean needRebind = hasFix(r, "REBIND");
        boolean needBattery = hasFix(r, "BATTERY");

        // 标题直接用 HealthCheck 给的一句话结论（正常 / 发现 N 个问题）。
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(r.summary())
                .setMessage(body.toString())
                // 负按钮固定是「知道了」，只关窗不做动作。
                .setNegativeButton("知道了", null);

        // 正按钮只放一个，按权限 > 重连 > 电池的优先级依次降级。
        if (needAccess) {
            builder.setPositiveButton("去开启", (d, w) -> {
                try {
                    // 跳到系统的通知使用权页面，用户在那里打开本 App 的开关。
                    startActivity(new Intent(
                            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                } catch (Exception ignored) {
                    // 个别设备没有这个页面，给提示不让点击崩掉。
                    Toast.makeText(this, "这台手机没有通知使用权页面，请到系统设置里手动找",
                            Toast.LENGTH_LONG).show();
                }
            });
        } else if (needRebind) {
            builder.setPositiveButton("重新连接", (d, w) -> {
                try {
                    // 请求系统重新绑定通知监听服务，和看门狗用的是同一个动作。
                    NotificationListenerService.requestRebind(
                            new ComponentName(this, NotifListener.class));
                } catch (Exception ignored) {
                    // 重绑失败不崩溃，用户还可以再点一次。
                }
                // 留一条诊断痕迹，之后在「诊断日志」里能看到这次手动重连。
                EngineHolder.diag(this, "重连", "用户在自检里点了重新连接");
                // 绑定是异步的，明确告诉用户几秒后再看。
                Toast.makeText(this, "已请求重新连接，几秒后再看状态", Toast.LENGTH_SHORT).show();
            });
        } else if (needBattery) {
            builder.setPositiveButton("去电池设置", (d, w) -> openBatterySettings());
        }

        // 电池问题不是正按钮时，降级放到中性按钮，保证两个按钮以内还能处理它。
        if (needBattery && (needAccess || needRebind)) {
            builder.setNeutralButton("电池设置", (d, w) -> openBatterySettings());
        }

        // 一次性弹出完整结论。
        builder.show();
    }

    /** 结果里是否含某个处理标记；fix 可能是 null，这里一律按字符串比较避免空指针。 */
    boolean hasFix(HealthCheck.Result r, String fix) {
        // 逐条比对 fix 字段，命中就返回 true。
        for (int i = 0; i < r.issues.size(); i++) {
            // equals 的方向固定成常量在前，null 也不会抛异常。
            if (fix.equals(r.issues.get(i).fix)) return true;
        }
        // 一条都没命中说明不需要这个动作。
        return false;
    }

    /** 打开系统的电池优化页面；没有这个页面的设备给一句提示，不让点击崩掉。 */
    void openBatterySettings() {
        try {
            // 直接跳「电池优化」总列表，用户在里面把不爬楼设为不限制。
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception ignored) {
            // 个别 ROM 没有这个页面，退回文字提示。
            Toast.makeText(this, "这台手机没有电池优化页面，请到系统设置里手动找",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** 相对时间：自检里沿用状态条那套写法，两处口径保持一致。 */
    String ago(long ms) {
        // 直接复用主界面的 relativeTime，避免出现两种说法。
        return relativeTime(ms);
    }

    /**
     * 「诊断日志」：最近 30 条自动读取的流水，配合顶部累计计数，
     * 用户和评委不用连电脑就能看出是哪一步卡住了。
     */
    void showDiagLog() {
        // 最近 30 条，最新的在前。
        List<DiagLog.Entry> list = DiagLog.recent(30);
        // 时间只显示到秒，日志看的是顺序不是日期。
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        // 正文整体放进一个 TextView，用等宽字保证每行对得齐。
        StringBuilder body = new StringBuilder();
        // 第一行固定是各类累计数，不受 50 条容量限制。
        body.append("累计 收到 ").append(DiagLog.count("收到"))
                .append(" / 跳过 ").append(DiagLog.count("跳过"))
                .append(" / 出错 ").append(DiagLog.count("出错"))
                .append(" / 连接 ").append(DiagLog.count("连接"))
                .append(" / 断开 ").append(DiagLog.count("断开"))
                .append(" / 重连 ").append(DiagLog.count("重连"));
        // 一条日志都没有时只留一句说明，不留空白页。
        if (list.isEmpty()) {
            body.append("\n\n还没有记录");
        } else {
            // 逐条拼成「时间 类别 明细」的等宽一行。
            for (int i = 0; i < list.size(); i++) {
                // 取出这一条日志。
                DiagLog.Entry e = list.get(i);
                // 明细里的换行会把一行撑成多行，先压成一个空格。
                String detail = e.detail == null ? "" : e.detail.replace('\n', ' ').replace('\r', ' ');
                // 太长就截断，后面加省略号，保证一行能读完。
                if (detail.length() > 40) detail = detail.substring(0, 40) + "…";
                // 每条日志单独占一行，时间、类别、明细之间各空一格。
                body.append('\n').append(fmt.format(new java.util.Date(e.time)))
                        .append(' ').append(e.kind).append(' ').append(detail);
            }
        }

        // 等宽小字：日志对齐才好扫，字号压到 12 不抢注意力。
        TextView text = new TextView(this);
        text.setText(body.toString());
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(12);
        // 四周 20dp 内边距，和别的弹窗保持同一种呼吸感。
        text.setPadding(dp(this, 20), dp(this, 20), dp(this, 20), dp(this, 20));
        // 日志可能很长，套一层 ScrollView 让它自己滚。
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);

        // 弹窗本身只留一个「关闭」，看完就走。
        new AlertDialog.Builder(this)
                .setTitle("诊断日志")
                .setView(scroll)
                .setPositiveButton("关闭", null)
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
        // 每次重绘都重算副标题：跨过中午、傍晚后回到前台，日期和问候词都能跟着变。
        if (sub != null) sub.setText(subLine());
        cardList.removeAllViews();
        Engine shown = demo == null ? engine : demo.engine;
        // 顶部改成用户看得懂的一句话：今天几件、明天几件、有多少过期和待确认。
        stats.setText(TodayView.summary(shown.all(), System.currentTimeMillis()));
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
            // 演示态同样用成功绿，保持状态行颜色语义一致。
            notifStatus.setTextColor(Ui.GREEN);
            notifStatus.setBackground(null);
            notifStatus.setPadding(0, dp(this, 6), 0, dp(this, 4));
        }
        // 底部栏每次刷新都按当前连接状态重算：只有「未连接且不是演示」才露出「开启自动读取」。
        if (liveTools != null) {
            boolean needSetup = demo == null && !EngineHolder.listenerConnected;
            liveTools.setVisibility(needSetup ? View.VISIBLE : View.GONE);
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
                // 空状态也按连接状态分开说：已连接就承诺「会自动来」，没连上才提手动导入当兜底。
                if (EngineHolder.listenerConnected) {
                    empty.setText("自动读取已开启。\n群里有作业、考试、接龙时，会自动出现在这里。");
                } else {
                    empty.setText("还没有待办。\n开启自动读取后，群里的作业、考试、接龙会自动出现在这里，不用手动导入。");
                }
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
            // 整组收进一张白卡：圆角 16dp + 极淡投影，行与行之间用细线分隔。
            LinearLayout groupBox = new LinearLayout(this);
            groupBox.setOrientation(LinearLayout.VERTICAL);
            groupBox.setBackground(Ui.round(this, Ui.CARD, 16));
            Ui.lift(groupBox, this);
            LinearLayout.LayoutParams groupLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            groupLp.bottomMargin = dp(this, 4);
            groupBox.setLayoutParams(groupLp);
            for (int i = 0; i < group.size(); i++) {
                // 第 2 行起先垫一条分隔线，从文字起始处（52dp）起画，不做通栏。
                if (i > 0) {
                    View line = Ui.hairline(this);
                    LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 1);
                    lineLp.leftMargin = dp(this, 52);
                    groupBox.addView(line, lineLp);
                }
                groupBox.addView(cardView(group.get(i)));
            }
            cardList.addView(groupBox);
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

    /** 分组标题：只留「组名 + 数量」两段小字，去掉圆点，靠字号和灰阶弱化，不做视觉噪音。 */
    View groupHeader(String name, int count) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        // 基线对齐：组名和数量字号相同，底部对齐看起来更稳。
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(13);
        label.setTypeface(null, 1);
        label.setTextColor(Ui.SUB);
        label.setIncludeFontPadding(false);
        row.addView(label);

        // 数量紧跟在组名后面，颜色再浅一档，读的时候先看到组名。
        TextView num = new TextView(this);
        num.setText(String.valueOf(count));
        num.setTextSize(13);
        num.setTextColor(Ui.MUTE);
        num.setIncludeFontPadding(false);
        LinearLayout.LayoutParams numLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        numLp.leftMargin = dp(this, 6);
        row.addView(num, numLp);

        // 组与组之间靠上留白分开：左缩进 4dp 和卡片边缘错开一点，显得是标签而不是卡片内容。
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(this, 4);
        lp.topMargin = dp(this, 20);
        lp.bottomMargin = dp(this, 8);
        row.setLayoutParams(lp);
        return row;
    }

    /**
     * 组内的一行：左边完成圈，右边内容列。
     * 行本身不带背景、描边和下边距，白卡和行间距都由外层 groupBox 统一处理。
     */
    View cardView(Engine.Card c) {
        // 类型和标题在一行里多处复用，先算好。
        boolean isExam = "exam".equals(c.kind);
        String title = c.displayTitle() == null ? "" : c.displayTitle();
        long now = System.currentTimeMillis();
        int level = TodayView.urgency(c.deadline, c.needsConfirm, c.done, now);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        // 顶部对齐：完成圈和第一行标题齐平，内容再长也不会把圈压到中间。
        row.setGravity(Gravity.TOP);
        row.setPadding(dp(this, 16), dp(this, 15), dp(this, 16), dp(this, 15));
        // 已完成的行整体变淡，弱化但不至于看不清。
        if (c.done) row.setAlpha(0.55f);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 完成圈：未完成是空心环，已完成是绿底对勾；点它切换完成状态。
        TextView check = new TextView(this);
        check.setGravity(Gravity.CENTER);
        check.setIncludeFontPadding(false);
        if (c.done) {
            check.setText("✓");
            check.setTextSize(13);
            check.setTypeface(null, 1);
            check.setTextColor(Color.WHITE);
            check.setBackground(Ui.disc(this, Ui.GREEN));
        } else {
            check.setText("");
            // 待确认 / 无时间用最浅的灰，其余跟着紧急度走：过期红、三小时内橙、其他蓝。
            int ringColor;
            if (c.needsConfirm || c.deadline == 0) {
                ringColor = Ui.MUTE;
            } else if (level == 3) {
                ringColor = Ui.RED;
            } else if (level == 2) {
                ringColor = Ui.ORANGE;
            } else {
                ringColor = Ui.BLUE;
            }
            check.setBackground(Ui.ring(this, ringColor, 22, 2));
        }
        check.setOnClickListener(v -> toggleDone(c));
        check.setContentDescription((c.done ? "恢复卡片：" : "完成卡片：") + title);
        LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(
                dp(this, 22), dp(this, 22));
        checkLp.rightMargin = dp(this, 14);
        checkLp.topMargin = dp(this, 1);
        row.addView(check, checkLp);

        // 内容列吃掉剩余宽度。
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        row.addView(content, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 标题：不再用 emoji 前缀，只有考试类追加一个小标签；接龙卡标题本身已带「接龙：」，不再重复挂标签。
        TextView t = new TextView(this);
        String tag = isExam ? "  考试" : "";
        SpannableString label = new SpannableString(title + tag);
        if (tag.length() > 0) {
            int start = title.length();
            int end = label.length();
            // 标签缩到 0.75 倍并加粗，颜色对应卡片类型，视觉上比标题低一档。
            label.setSpan(new RelativeSizeSpan(0.75f), start, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            label.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            label.setSpan(new ForegroundColorSpan(Ui.BLUE), start, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        t.setText(label);
        t.setTextColor(c.done ? Ui.MUTE : Ui.TEXT);
        t.setTextSize(16);
        // 已完成：标题加删除线，和其它灰色信息一起表示这件事过去了。
        if (c.done) t.setPaintFlags(t.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        content.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 元信息行：具体时刻 · 倒计时（· 地点/渠道）。已完成的卡不显示这一行。
        if (!c.done) {
            StringBuilder meta = new StringBuilder();
            int metaColor = Ui.SUB;
            boolean metaBold = false;
            if (c.deadline == 0) {
                // 没读到时间时不假装有结论，直接引导用户点开补一下。
                meta.append("没有读到明确时间，点开补一下");
            } else {
                // 真机问题：倒计时本身已是「明天 09:00 / 后天 14:00」时，前面再拼具体时刻就重复了，
                // 这时只显示倒计时；其余情况（还剩 N 分钟 / N 小时 / N 天后 / 已过期…）保留「具体时刻 · 倒计时」。
                String cd = TodayView.countdown(c.deadline, now);
                if (cd.startsWith("明天") || cd.startsWith("后天")) {
                    meta.append(cd);
                } else {
                    meta.append(Engine.humanDeadline(c.deadline)).append(" · ").append(cd);
                }
                // 三小时内或已过期时整行提色加粗，扫一眼就能看出最紧的几件。
                if (level == 2) {
                    metaColor = Ui.ORANGE;
                    metaBold = true;
                } else if (level == 3) {
                    metaColor = Ui.RED;
                    metaBold = true;
                }
            }
            // 地点 / 渠道沿用原来的优先级：考试卡看考场，其余渠道优先，接在同一行末尾。
            String where;
            if (isExam) {
                where = TextUtils.isEmpty(c.place) ? "" : c.place;
            } else if (c.channel != null) {
                where = c.channel;
            } else {
                where = c.place == null ? "" : c.place;
            }
            if (!TextUtils.isEmpty(where)) meta.append(" · ").append(where);

            TextView info = new TextView(this);
            info.setText(meta.toString());
            info.setTextColor(metaColor);
            info.setTextSize(13);
            // 1.15 倍行距：地点和时间偶尔会折成两行，不至于糊在一起。
            info.setLineSpacing(0, 1.15f);
            if (metaBold) info.setTypeface(null, 1);
            LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            infoLp.topMargin = dp(this, 4);
            content.addView(info, infoLp);
        }

        // 考试卡：要带的东西单独一行，没有就不显示。
        if (isExam && !TextUtils.isEmpty(c.bring)) {
            TextView b = new TextView(this);
            b.setText("带：" + c.bring);
            b.setTextColor(Ui.SUB);
            b.setTextSize(13);
            LinearLayout.LayoutParams bringLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bringLp.topMargin = dp(this, 3);
            content.addView(b, bringLp);
        }

        // 来源行：群名 · 发送者 · 已更新 / 已完成。
        TextView src = new TextView(this);
        src.setText(c.groupName + " · " + c.sender + (c.updated ? " · 已更新" : "")
                + (c.done ? " · 已完成" : ""));
        src.setTextColor(Ui.MUTE);
        src.setTextSize(12);
        LinearLayout.LayoutParams srcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srcLp.topMargin = dp(this, 5);
        content.addView(src, srcLp);

        // 识别原因提示：只在引擎给出原因时出现。
        if (!TextUtils.isEmpty(c.reason)) {
            TextView r = new TextView(this);
            r.setText("⚠ " + c.reason);
            r.setTextColor(Ui.ORANGE);
            r.setTextSize(12);
            LinearLayout.LayoutParams reasonLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            reasonLp.topMargin = dp(this, 5);
            content.addView(r, reasonLp);
        }

        // 「修正」入口从标题右侧挪到长按：短按看原文，长按改标题和时间。
        // 原来的按钮写法（含无障碍描述）保留但不挂到视图树上，避免一行里再挤一个小按钮。
        Button edit = button("修正", v -> showCardEdit(c));
        edit.setContentDescription("修正卡片：" + title);

        row.setOnClickListener(v -> showSource(c));
        row.setOnLongClickListener(v -> {
            showCardEdit(c);
            return true;
        });
        return row;
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
