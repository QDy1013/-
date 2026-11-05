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
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private LinearLayout demoTools;
    private LinearLayout liveTools;
    /** 底部栏唯一的整宽主按钮：开启自动读取（通知读取权限）。 */
    private Button autoReadButton;
    private LinearLayout liveFilters;
    private TextView stateFilter;
    private final TextView[] windowTabs = new TextView[4];
    private EditText searchField;
    private TextView searchClear;
    private ScrollView listScroll;
    /** 来源 App 图标缓存：只存 ConstantState，每张卡各自 newDrawable，避免共享可变对象。 */
    private static final Map<String, Drawable.ConstantState> sourceIconCache = new HashMap<>();
    /** 查询失败也缓存，避免每次 render 反复向 PackageManager 要图标。 */
    private static final java.util.Set<String> sourceIconMiss = new java.util.HashSet<>();
    private String keywords = "";
    private Engine.CardScope scope = Engine.CardScope.ALL;
    private Engine.CardWindow window = Engine.CardWindow.ANY;
    private int visibleLimit = PAGE_SIZE;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean resumed;
    private boolean renderQueued;
    private int restoreScrollY = -1;
    private final Runnable queuedRender = () -> {
        renderQueued = false;
        if (resumed) render();
    };
    private final Runnable searchRefresh = this::render;
    private final Runnable clockRefresh = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            onCardsChanged();
            uiHandler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (b != null) {
            accessChecked = b.getBoolean("accessChecked", false);
            keywords = b.getString("keywords", "");
            Engine.CardScope[] scopes = Engine.CardScope.values();
            int selected = b.getInt("scope", 0);
            scope = scopes[Math.max(0, Math.min(selected, scopes.length - 1))];
            visibleLimit = Math.max(PAGE_SIZE, b.getInt("visibleLimit", PAGE_SIZE));
            int savedWindow = b.getInt("window", 0);
            window = Engine.CardWindow.values()[Math.max(0, Math.min(savedWindow, Engine.CardWindow.values().length - 1))];
            restoreScrollY = b.getInt("scrollY", 0);
        }
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (engine == null) return;
        resumed = true;
        // 真机问题：授权后监听服务有时没真正绑上，回前台时主动请求重绑一次。
        try {
            android.service.notification.NotificationListenerService.requestRebind(
                    new android.content.ComponentName(this, NotifListener.class));
        } catch (Exception ignored) {
        }
        if (engine != null) {
            engine.listener = this;
            ReminderReceiver.refresh(this, engine);
            render();
        }
        uiHandler.removeCallbacks(clockRefresh);
        uiHandler.postDelayed(clockRefresh, 60_000L - System.currentTimeMillis() % 60_000L);
        if (restoreScrollY >= 0) {
            int y = restoreScrollY;
            listScroll.post(() -> {
                if (resumed && restoreScrollY == y) {
                    listScroll.scrollTo(0, y);
                    restoreScrollY = -1;
                }
            });
        }
        // 档案填完回到主界面再来提示权限，避免两个弹窗叠在一起。
        if (!accessChecked && ProfileStore.exists(this)) {
            accessChecked = true;
            ensureNotificationAccess();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        uiHandler.removeCallbacks(queuedRender);
        uiHandler.removeCallbacks(searchRefresh);
        uiHandler.removeCallbacks(clockRefresh);
        renderQueued = false;
        if (engine != null && engine.listener == this) engine.listener = null;
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("accessChecked", accessChecked);
        state.putString("keywords", keywords);
        state.putInt("scope", scope.ordinal());
        state.putInt("window", window.ordinal());
        state.putInt("visibleLimit", visibleLimit);
        state.putInt("scrollY", listScroll == null ? 0 : listScroll.getScrollY());
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
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
                line = "● 自动整理中 · 等待新消息";
            } else {
                // 只留相对时间，不再拼群名，保证一行显示完整；「已连接」保留作状态锚点。
                line = "● 自动整理中 · 最近收到 " + relativeTime(last);
            }
            notifStatus.setText(line);
            notifStatus.setTextColor(Ui.HERO_STATUS);
            notifStatus.setTextSize(11);
            notifStatus.setBackground(null);
            notifStatus.setPadding(0, dp(this, 6), 0, dp(this, 4));
        } else {
            // 未连接：整条做成可点的琥珀提醒条，比一行灰字更像「该处理的事」。
            notifStatus.setText(WatchdogJob.accessEnabled(this)
                    ? "通知读取：正在重连 · 系统恢复后自动继续"
                    : "通知读取：未连接。点这里开启，群消息就会自动变成待办");
            notifStatus.setTextColor(Ui.AMBER_TX);
            notifStatus.setTextSize(11);
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
        root.setBackgroundColor(Ui.BG);
        root.setPadding(dp(this,20), dp(this,12), dp(this,20),0);
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(this);
        mark.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(mark,new LinearLayout.LayoutParams(dp(this,28),dp(this,28)));
        TextView name=uiText("不爬楼",17,Ui.TEXT,true);
        name.setPadding(dp(this,9),0,0,0);
        brand.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        TextView more=uiText("⋯",24,Ui.TEXT,false);
        more.setGravity(Gravity.CENTER);more.setContentDescription("更多");
        more.setBackground(Ui.rippleCircle(this,Ui.withAlpha(Ui.BLUE,25)));
        more.setOnClickListener(v->showMoreMenu());
        brand.addView(more,new LinearLayout.LayoutParams(dp(this,44),dp(this,44)));
        root.addView(brand);

        LinearLayout hero=new LinearLayout(this);hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(0,dp(this,17),0,dp(this,10));
        hero.setBackground(Ui.round(this,Ui.HERO,22));
        sub=uiText(subLine(),12,Ui.HERO_SUB,false);hero.addView(sub);
        TextView title=uiText("行动清单",29,Ui.TEXT,true);
        title.setPadding(0,dp(this,6),0,dp(this,4));hero.addView(title);
        stats=uiText("",13,Ui.HERO_TEXT,false);hero.addView(stats);
        notifStatus=uiText("",11,Ui.HERO_STATUS,false);
        notifStatus.setOnClickListener(v->showHealthCheck());hero.addView(notifStatus);
        LinearLayout.LayoutParams heroLp=new LinearLayout.LayoutParams(-1,-2);
        heroLp.topMargin=dp(this,10);root.addView(hero,heroLp);
        clashBanner=uiText("",12,Ui.AMBER_TX,false);
        clashBanner.setPadding(dp(this,12),dp(this,10),dp(this,12),dp(this,10));
        clashBanner.setBackground(Ui.ripple(this,Ui.round(this,Ui.AMBER_BG,14),Ui.withAlpha(Ui.AMBER_TX,25),14));
        clashBanner.setMaxLines(2);clashBanner.setEllipsize(TextUtils.TruncateAt.END);
        clashBanner.setOnClickListener(v->showClashDetail());clashBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams clashLp=new LinearLayout.LayoutParams(-1,-2);clashLp.topMargin=dp(this,10);root.addView(clashBanner,clashLp);
        demoTools=new LinearLayout(this);demoTools.setVisibility(View.GONE);
        demoButton=button("退出演示",v->toggleDemo());demoTools.addView(demoButton);
        demoUpdate=button("播放消息更新",v->{if(demo!=null){demo.applyUpdates();demoUpdate.setVisibility(View.GONE);render();}});
        demoUpdate.setVisibility(View.GONE);demoTools.addView(demoUpdate);root.addView(demoTools);
        liveFilters=buildFilters();root.addView(liveFilters);
        listScroll=new ScrollView(this);listScroll.setFillViewport(true);
        listScroll.setVerticalScrollBarEnabled(false);
        cardList=new LinearLayout(this);cardList.setOrientation(LinearLayout.VERTICAL);
        cardList.setPadding(0,dp(this,2),0,dp(this,24));listScroll.addView(cardList);
        empty=uiText("",14,Ui.SUB,false);empty.setGravity(Gravity.CENTER);
        empty.setLineSpacing(dp(this,6),1.1f);
        empty.setPadding(dp(this,24),dp(this,48),dp(this,24),dp(this,48));
        empty.setBackground(Ui.roundStroke(this,Ui.CARD,Ui.LINE,20,1));
        cardList.addView(empty,new LinearLayout.LayoutParams(-1,-2));
        root.addView(listScroll,new LinearLayout.LayoutParams(-1,0,1));
        liveTools=new LinearLayout(this);liveTools.setPadding(0,dp(this,10),0,dp(this,14));
        autoReadButton=stylePrimary(button("开启自动读取",v->{if(WatchdogJob.accessEnabled(this))showHealthCheck();else showNotifHint();}));
        liveTools.addView(autoReadButton,new LinearLayout.LayoutParams(-1,dp(this,50)));root.addView(liveTools);
        setContentView(root);getWindow().setStatusBarColor(Ui.BG);getWindow().setNavigationBarColor(Ui.BG);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (Build.VERSION.SDK_INT>=26?View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0));
        root.requestFocus();
    }

    TextView uiText(String text,float size,int color,boolean bold){
        TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);
        v.setIncludeFontPadding(false);
        if(bold)v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        return v;
    }

    private LinearLayout buildFilters(){
        LinearLayout wrap=new LinearLayout(this);wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout searchRow=new LinearLayout(this);searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setBackground(Ui.roundStroke(this,Ui.CARD,Ui.LINE,15,1));
        searchField=new EditText(this);searchField.setSingleLine(true);searchField.setTextSize(14);
        searchField.setHint("搜索事项或消息来源");searchField.setContentDescription("搜索待办卡片");
        searchField.setBackground(null);searchField.setTextColor(Ui.TEXT);searchField.setHintTextColor(Ui.MUTE);
        searchField.setPadding(dp(this,14),0,dp(this,5),0);
        searchField.setCompoundDrawablesWithIntrinsicBounds(Ui.searchGlyph(this,Ui.MUTE,17),null,null,null);
        searchField.setCompoundDrawablePadding(dp(this,9));searchField.setText(keywords);
        searchRow.addView(searchField,new LinearLayout.LayoutParams(0,dp(this,48),1));
        searchClear=uiText("×",22,Ui.SUB,false);searchClear.setGravity(Gravity.CENTER);
        searchClear.setContentDescription("清空搜索");searchClear.setBackground(Ui.rippleCircle(this,Ui.withAlpha(Ui.BLUE,30)));
        searchClear.setVisibility(keywords.isEmpty()?View.GONE:View.VISIBLE);searchClear.setOnClickListener(v->searchField.setText(""));
        searchRow.addView(searchClear,new LinearLayout.LayoutParams(dp(this,44),dp(this,44)));
        stateFilter=uiText("筛选",12,Ui.SUB,true);stateFilter.setGravity(Gravity.CENTER);
        stateFilter.setContentDescription("待办状态筛选");stateFilter.setOnClickListener(v->showFilterMenu());
        stateFilter.setBackground(Ui.ripple(this,null,Ui.withAlpha(Ui.BLUE,25),12));
        searchRow.addView(stateFilter,new LinearLayout.LayoutParams(dp(this,52),dp(this,48)));
        LinearLayout.LayoutParams searchLp=new LinearLayout.LayoutParams(-1,-2);searchLp.topMargin=dp(this,16);wrap.addView(searchRow,searchLp);
        searchField.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                keywords=s.toString();searchClear.setVisibility(keywords.isEmpty()?View.GONE:View.VISIBLE);
                visibleLimit=PAGE_SIZE;if(listScroll!=null)listScroll.scrollTo(0,0);
                uiHandler.removeCallbacks(searchRefresh);uiHandler.postDelayed(searchRefresh,180);
            }
            public void afterTextChanged(Editable s){}
        });
        LinearLayout tabs=new LinearLayout(this);tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(dp(this,4),dp(this,4),dp(this,4),dp(this,4));
        tabs.setBackground(Ui.round(this,Color.parseColor("#E9EEF4"),14));
        String[] labels={"全部","今天","明天","待确认"};
        for(int i=0;i<labels.length;i++){
            final int index=i;TextView tab=uiText(labels[i],13,Ui.SUB,true);tab.setGravity(Gravity.CENTER);
            tab.setOnClickListener(v->{scope=Engine.CardScope.ALL;window=Engine.CardWindow.values()[index];resetCardPage();});
            windowTabs[i]=tab;
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(this,44),1);if(i>0)lp.leftMargin=dp(this,5);tabs.addView(tab,lp);
        }
        LinearLayout.LayoutParams tabsLp=new LinearLayout.LayoutParams(-1,-2);tabsLp.topMargin=dp(this,10);wrap.addView(tabs,tabsLp);
        return wrap;
    }

    private void showFilterMenu(){
        String[] labels={"全部事项","未完成","已过期","已完成"};
        new AlertDialog.Builder(this).setTitle("查看事项").setSingleChoiceItems(labels,
                window==Engine.CardWindow.ANY?scope.ordinal():-1,(d,which)->{
                    scope=Engine.CardScope.values()[which];window=Engine.CardWindow.ANY;d.dismiss();resetCardPage();
                }).setNegativeButton("取消",null).show();
    }

    private void updateWindowTabs(Engine shown,long now){
        int[] counts=new int[4];
        for(Engine.Card c:shown.all()){
            counts[0]++;
            String bucket=TodayView.bucket(c.deadline,c.needsConfirm,c.done,now);
            if("今天".equals(bucket))counts[1]++;
            else if("明天".equals(bucket))counts[2]++;
            else if("待确认".equals(bucket))counts[3]++;
        }
        String[] labels={"全部","今天","明天","待确认"};
        for(int i=0;i<windowTabs.length;i++){
            boolean selected=scope==Engine.CardScope.ALL && window.ordinal()==i;
            TextView tab=windowTabs[i];tab.setText(labels[i]+" "+counts[i]);
            tab.setTextColor(selected?Ui.BLUE:Ui.SUB);
            tab.setBackground(Ui.ripple(this,Ui.round(this,selected?Color.WHITE:Color.TRANSPARENT,12),Ui.withAlpha(Ui.BLUE,35),12));
        }
        stateFilter.setText(scope==Engine.CardScope.ALL?"筛选":new String[]{"筛选","未完成","已过期","已完成"}[scope.ordinal()]);
        stateFilter.setTextColor(scope==Engine.CardScope.ALL?Ui.SUB:Ui.BLUE);
    }

    private void resetCardPage() {
        visibleLimit = PAGE_SIZE;
        render();
        if (listScroll != null) listScroll.scrollTo(0, 0);
    }

    private void showAllCards() {
        scope = Engine.CardScope.ALL;
        window = Engine.CardWindow.ANY;
        keywords = "";

        searchField.setText("");
        resetCardPage();
    }

    /** 小号描边胶囊：工具栏与列表底部「显示更多」共用，透明底 + 1dp 细描边。 */
    Button button(String text, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setAllCaps(false);
        btn.setTextColor(Ui.SUB);
        btn.setBackground(Ui.ripple(this, Ui.roundStroke(this, Color.TRANSPARENT, Ui.LINE, 16, 1),
                Ui.withAlpha(Ui.BLUE, 28), 16));
        // 真机问题：系统 Button 自带的最小高度 / 最小宽度会把胶囊撑成大灰框，全部清零。
        btn.setMinHeight(0);
        btn.setMinimumHeight(dp(this, 44));
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
        b.setBackground(Ui.round(this, Ui.BLUE_BG, 14));
        b.setTextColor(Ui.BLUE);
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
        demoTools.setVisibility(showingDemo ? View.VISIBLE : View.GONE);
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
                .setItems(new String[]{"自动读取自检", "到期提醒", "我的档案", "通知读取权限",
                                "通知回放", "诊断日志", "已移除的卡片", "清空本机数据",
                                demo == null ? "离线演示" : "退出演示"},
                        (d, which) -> {
                            switch (which) {
                                case 0:
                                    showHealthCheck();
                                    break;
                                case 1:
                                    showDeadlineReminderSettings();
                                    break;
                                case 2:
                                    startActivity(new Intent(this, ProfileActivity.class));
                                    break;
                                case 3:
                                    startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                                    break;
                                case 4:
                                    startReplay();
                                    break;
                                case 5:
                                    showDiagLog();
                                    break;
                                case 6:
                                    showRemovedCards();
                                    break;
                                case 8:
                                    toggleDemo();
                                    break;
                                case 7:
                                    new AlertDialog.Builder(this)
                                            .setTitle("清空待办和近期消息？")
                                            .setMessage("会清空本机的全部待办、已移除卡片，以及这次运行中用于识别的近期消息。")
                                            .setPositiveButton("清空", (dialog, w) -> {
                                                engine.clearLocalData();
                                                EngineHolder.save(this);
                                            })
                                            .setNegativeButton("取消", null)
                                            .show();
                                    break;
                                default:
                                    break;
                            }
                        }).show();
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

    @Override
    public void onCardsChanged() {
        runOnUiThread(() -> {
            if (!resumed || renderQueued) return;
            renderQueued = true;
            uiHandler.postDelayed(queuedRender, 80);
        });
    }

    void render() {
        if (cardList == null) return;
        uiHandler.removeCallbacks(queuedRender);
        uiHandler.removeCallbacks(searchRefresh);
        renderQueued = false;
        long now = System.currentTimeMillis();
        // 每次重绘都重算副标题：跨过中午、傍晚后回到前台，日期和问候词都能跟着变。
        if (sub != null) sub.setText(subLine());
        cardList.removeAllViews();
        Engine shown = demo == null ? engine : demo.engine;
        // 顶部改成用户看得懂的一句话：今天几件、明天几件、有多少过期和待确认。
        stats.setText(TodayView.summary(shown.all(), now));
        updateWindowTabs(shown,now);
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
        ClashPlan.Clash clash = ClashPlan.nearest(shown.all(), now);
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
            notifStatus.setTextColor(Ui.HERO_STATUS);
            notifStatus.setBackground(null);
            notifStatus.setPadding(0, dp(this, 6), 0, dp(this, 4));
        }
        // 底部栏每次刷新都按当前连接状态重算：只有「未连接且不是演示」才露出「开启自动读取」。
        if (liveTools != null) {
            boolean needSetup = demo == null && !EngineHolder.listenerConnected;
            liveTools.setVisibility(needSetup ? View.VISIBLE : View.GONE);
            if (needSetup) autoReadButton.setText(WatchdogJob.accessEnabled(this) ? "检查自动读取" : "开启自动读取");
        }

        List<Engine.Card> list = demo == null
                ? shown.queryCards(keywords, scope, window, visibleLimit + 1, now)
                : shown.ranked(PAGE_SIZE, now);
        if (list.isEmpty()) {
            if (demo != null) {
                empty.setText("演示卡片已清理。退出后可重新体验。");
            } else if (!keywords.trim().isEmpty() || scope != Engine.CardScope.ALL || window != Engine.CardWindow.ANY) {
                empty.setText("没有符合当前搜索或筛选的卡片。");
            } else {
                // 空状态说明自动读取的授权或等待状态。
                if (EngineHolder.listenerConnected) {
                    empty.setText("自动读取已开启。\n群里有作业、考试、接龙时，会自动出现在这里。");
                } else {
                    empty.setText("还没有待办。\n开启自动读取后，群里的作业、考试、接龙会自动出现在这里，自动识别并整理。");
                }
            }
            cardList.addView(empty);
            return;
        }
        int displayed = Math.min(visibleLimit, list.size());
        // 今日视图：先取前 displayed 张卡，再按 TodayView.ORDER 的固定分组顺序展示。
        Map<String, List<Engine.Card>> groups = new HashMap<>();
        for (int i = 0; i < displayed; i++) {
            Engine.Card card = list.get(i);
            String name = TodayView.bucket(card.deadline, card.needsConfirm, card.done, now);
            groups.computeIfAbsent(name, key -> new ArrayList<>()).add(card);
        }
        for (int g = 0; g < TodayView.ORDER.length; g++) {
            String groupName = TodayView.ORDER[g];
            List<Engine.Card> group = groups.get(groupName);
            // 这一组没卡就不出标题，避免出现空分组。
            if (group == null) continue;
            cardList.addView(groupHeader(groupName, group.size()));
            // 整组收进一张白卡：圆角 16dp + 极淡投影，行与行之间用细线分隔。
            LinearLayout groupBox = new LinearLayout(this);
            groupBox.setOrientation(LinearLayout.VERTICAL);
            groupBox.setBackground(Ui.roundStroke(this, Ui.CARD, Ui.LINE, 20, 1));

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

    /** 分组标题：只留「组名 + 数量」两段小字，压缩上下留白，减少列表被切碎的感觉。 */
    View groupHeader(String name, int count) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        // 基线对齐：组名和数量字号相同，底部对齐看起来更稳。
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(12);
        label.setTypeface(null, 1);
        label.setTextColor(Ui.SUB);
        label.setIncludeFontPadding(false);
        row.addView(label);

        // 数量紧跟在组名后面，颜色再浅一档，读的时候先看到组名。
        TextView num = new TextView(this);
        num.setText(String.valueOf(count));
        num.setTextSize(12);
        num.setTextColor(Ui.MUTE);
        num.setIncludeFontPadding(false);
        LinearLayout.LayoutParams numLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        numLp.leftMargin = dp(this, 5);
        row.addView(num, numLp);

        // 组与组之间靠上留白分开：左缩进 4dp 和卡片边缘错开一点，显得是标签而不是卡片内容。
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(this, 4);
        lp.topMargin = dp(this, 14);
        lp.bottomMargin = dp(this, 6);
        row.setLayoutParams(lp);
        return row;
    }

    /**
     * 组内的一行：左边完成圈，右边内容列。
     * 行本身不带背景、描边和下边距，白卡和行间距都由外层 groupBox 统一处理。
     */
    View cardView(Engine.Card c) {
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(this,16),dp(this,16),dp(this,16),dp(this,10));
        row.setBackground(Ui.ripple(this,null,Ui.withAlpha(Ui.BLUE,22),20));
        LinearLayout origin=new LinearLayout(this);origin.setGravity(Gravity.CENTER_VERTICAL);
        View pill=sourcePill(c);LinearLayout.LayoutParams pillLp=new LinearLayout.LayoutParams(-2,-2);origin.addView(pill,pillLp);
        TextView group=uiText(c.groupName==null?"":c.groupName,11,Ui.SUB,false);
        group.setSingleLine(true);group.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams groupLp=new LinearLayout.LayoutParams(0,-2,1);groupLp.leftMargin=dp(this,9);origin.addView(group,groupLp);
        row.addView(origin);
        TextView title=uiText(c.displayTitle(),17,c.done?Ui.SUB:Ui.TEXT,true);
        title.setMaxLines(3);title.setEllipsize(TextUtils.TruncateAt.END);title.setLineSpacing(dp(this,2),1.05f);
        title.setContentDescription(c.displayTitle());
        if(c.done)title.setPaintFlags(title.getPaintFlags()|Paint.STRIKE_THRU_TEXT_FLAG);
        LinearLayout.LayoutParams titleLp=new LinearLayout.LayoutParams(-1,-2);titleLp.topMargin=dp(this,12);row.addView(title,titleLp);
        if(!c.done){
            String time=c.deadlineHint()!=null?c.deadlineHint()+" · 时刻待确认"
                    :c.deadline==0?"时间待确认 · 点开补充":Engine.humanDeadline(c.deadline)+" · "+TodayView.countdown(c.deadline,System.currentTimeMillis());
            TextView due=uiText(time,12,c.needsConfirm?Ui.AMBER_TX:Ui.BLUE,true);
            due.setBackground(Ui.round(this,c.needsConfirm?Ui.AMBER_BG:Ui.BLUE_BG,8));
            due.setPadding(dp(this,9),dp(this,7),dp(this,9),dp(this,7));
            LinearLayout.LayoutParams dueLp=new LinearLayout.LayoutParams(-2,-2);dueLp.topMargin=dp(this,10);row.addView(due,dueLp);
        }
        if("exam".equals(c.kind) && !TextUtils.isEmpty(c.bring)){
            TextView bring=uiText("需携带  "+c.bring,12,Ui.SUB,false);bring.setPadding(0,dp(this,8),0,0);row.addView(bring);
        }
        LinearLayout footer=new LinearLayout(this);footer.setGravity(Gravity.CENTER_VERTICAL);
        String recipient=TextUtils.isEmpty(c.channel)?(TextUtils.isEmpty(c.sender)?"查看消息详情":c.sender):"提交到："+c.channel;
        TextView who=uiText(recipient+(c.updated?" · 已更新":""),12,Ui.SUB,false);who.setMaxLines(1);who.setEllipsize(TextUtils.TruncateAt.END);
        footer.addView(who,new LinearLayout.LayoutParams(0,-2,1));
        TextView done=uiText(c.done?"✓ 已完成":"○ 完成",13,c.done?Ui.BLUE:Ui.SUB,true);
        done.setGravity(Gravity.CENTER);done.setPadding(dp(this,12),0,0,0);
        done.setContentDescription((c.done?"恢复卡片：":"完成卡片：")+c.displayTitle());
        done.setBackground(Ui.ripple(this,null,Ui.withAlpha(Ui.BLUE,30),12));
        done.setOnClickListener(v->toggleDone(c));footer.addView(done,new LinearLayout.LayoutParams(dp(this,80),dp(this,44)));
        LinearLayout.LayoutParams footerLp=new LinearLayout.LayoutParams(-1,-2);footerLp.topMargin=dp(this,4);row.addView(footer,footerLp);
        if(!TextUtils.isEmpty(c.reason) && c.deadlineHint()==null){
            TextView reason=uiText(c.reason,11,Ui.AMBER_TX,false);reason.setPadding(0,0,0,dp(this,5));row.addView(reason);
        }
        row.setOnClickListener(v->showSource(c));row.setOnLongClickListener(v->{showCardEdit(c);return true;});
        return row;
    }

    /**
     * 来源胶囊：优先用应用真实图标，取不到时退回带品牌色的文字徽标。
     * 应用名始终跟着图标一起出现，用户不必靠图标猜来源。
     */
    View sourcePill(Engine.Card c) {
        String name = sourceName(c);
        String pkg = c.sourcePackageName();
        pkg = pkg == null ? "" : pkg.trim();

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(Ui.round(this, Ui.SURFACE2, 20));
        pill.setPadding(dp(this, 6), dp(this, 3), dp(this, 10), dp(this, 3));

        int size = dp(this, 18);
        Drawable icon = sourceIcon(pkg);
        if (icon != null) {
            // 圆角小图标：18dp 容器 + 圆角裁剪，避免方形应用图标打破胶囊圆角。
            ImageView image = new ImageView(this);
            image.setImageDrawable(icon);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setBackground(Ui.round(this, Ui.CARD, 5));
            image.setClipToOutline(true);
            pill.addView(image, new LinearLayout.LayoutParams(size, size));
        } else {
            TextView badge = new TextView(this);
            badge.setText(sourceBadge(name, pkg));
            badge.setTextSize(10);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setTextColor(Color.WHITE);
            badge.setGravity(Gravity.CENTER);
            badge.setIncludeFontPadding(false);
            badge.setBackground(Ui.round(this, sourceBadgeColor(name, pkg), 5));
            pill.addView(badge, new LinearLayout.LayoutParams(size, size));
        }

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(12);
        label.setTextColor(Ui.SUB);
        label.setIncludeFontPadding(false);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.leftMargin = dp(this, 5);
        pill.addView(label, labelLp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 7);
        pill.setLayoutParams(lp);
        return pill;
    }

    /**
     * 来源应用名：只信引擎/main 侧给出的真实名称，空值统一显示「来源待确认」，
     * 不按包名猜应用名，避免把系统通知误标成某个具体平台。
     */
    String sourceName(Engine.Card c) {
        String name = c.sourceAppName();
        return TextUtils.isEmpty(name) || name.trim().isEmpty() ? "来源待确认" : name.trim();
    }

    /**
     * 图标读取：成功只缓存 ConstantState，每张卡各自 newDrawable().mutate()；
     * 取不到图标或包名无效也记入 miss，后续 render 不再重复查询 PackageManager。
     */
    Drawable sourceIcon(String pkg) {
        if (TextUtils.isEmpty(pkg)) return null;
        Drawable.ConstantState state = sourceIconCache.get(pkg);
        if (state == null && !sourceIconMiss.contains(pkg)) {
            try {
                Drawable appIcon = getPackageManager().getApplicationIcon(pkg);
                state = appIcon == null ? null : appIcon.getConstantState();
            } catch (Exception ignored) {
                state = null;
            }
            if (state == null) {
                sourceIconMiss.add(pkg);
                return null;
            }
            sourceIconCache.put(pkg, state);
        }
        if (state == null) return null;
        try {
            Drawable drawable = state.newDrawable(getResources());
            return drawable == null ? null : drawable.mutate();
        } catch (Exception ignored) {
            // 缓存里的 ConstantState 失效时清掉并记 miss，下一轮不再反复失败。
            sourceIconCache.remove(pkg);
            sourceIconMiss.add(pkg);
            return null;
        }
    }

    /** 图标不可用时的文字徽标：微信/QQ/飞书/钉钉/TIM 用各自品牌字，其余取名称首字。 */
    String sourceBadge(String name, String pkg) {
        String key = (name + " " + pkg).toLowerCase(Locale.ROOT);
        if (key.contains("微信") || key.contains("wechat") || key.contains("com.tencent.mm")) return "微";
        if (key.contains("飞书") || key.contains("lark") || key.contains("feishu")) return "飞";
        if (key.contains("钉钉") || key.contains("dingtalk") || key.contains("rimet")) return "钉";
        if (key.contains("tim") || key.contains("com.tencent.tim")) return "T";
        if (key.contains("qq") || key.contains("com.tencent.mobileqq")) return "Q";
        String trimmed = name == null ? "" : name.trim();
        return trimmed.isEmpty() ? "源" : trimmed.substring(0, 1);
    }

    /** 徽标品牌色：只管图标缺失时的辨识度，不参与正文配色。 */
    int sourceBadgeColor(String name, String pkg) {
        String key = (name + " " + pkg).toLowerCase(Locale.ROOT);
        if (key.contains("微信") || key.contains("wechat") || key.contains("com.tencent.mm")) return Ui.GREEN;
        if (key.contains("飞书") || key.contains("lark") || key.contains("feishu")) return Color.parseColor("#5B5BD6");
        if (key.contains("钉钉") || key.contains("dingtalk") || key.contains("rimet")) return Color.parseColor("#2E7CF6");
        if (key.contains("tim") || key.contains("com.tencent.tim")) return Color.parseColor("#0FA9C9");
        if (key.contains("qq") || key.contains("com.tencent.mobileqq")) return Color.parseColor("#2B7DE9");
        return Ui.BLUE;
    }

    void showSource(Engine.Card c) {
        android.app.Dialog dialog=new android.app.Dialog(this);
        LinearLayout sheet=new LinearLayout(this);sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(this,20),dp(this,10),dp(this,20),dp(this,20));
        sheet.setBackground(Ui.round(this,Ui.CARD,24));
        View handle=new View(this);handle.setBackground(Ui.round(this,Ui.LINE,3));
        LinearLayout.LayoutParams handleLp=new LinearLayout.LayoutParams(dp(this,32),dp(this,4));handleLp.gravity=Gravity.CENTER_HORIZONTAL;sheet.addView(handle,handleLp);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(uiText("事项详情",16,Ui.TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView close=uiText("×",25,Ui.SUB,false);close.setGravity(Gravity.CENTER);close.setContentDescription("关闭详情");
        close.setBackground(Ui.rippleCircle(this,Ui.withAlpha(Ui.BLUE,25)));close.setOnClickListener(v->dialog.dismiss());
        header.addView(close,new LinearLayout.LayoutParams(dp(this,44),dp(this,44)));sheet.addView(header);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        TextView title=uiText(c.displayTitle(),23,Ui.TEXT,true);title.setLineSpacing(dp(this,4),1.05f);content.addView(title);
        String deadline=c.deadlineHint()!=null?c.deadlineHint()+" · 时刻待确认":c.deadline==0?"时间待确认":dateTimeLabel(c.deadline);
        TextView time=uiText("截止时间\n"+deadline,14,c.needsConfirm?Ui.AMBER_TX:Ui.BLUE,true);
        time.setLineSpacing(dp(this,6),1);time.setPadding(dp(this,14),dp(this,12),dp(this,14),dp(this,12));
        time.setBackground(Ui.round(this,c.needsConfirm?Ui.AMBER_BG:Ui.BLUE_BG,14));
        LinearLayout.LayoutParams timeLp=new LinearLayout.LayoutParams(-1,-2);timeLp.topMargin=dp(this,18);content.addView(time,timeLp);
        TextView origin=uiText(sourceName(c)+" · "+(c.groupName==null?"":c.groupName),12,Ui.SUB,true);
        origin.setPadding(0,dp(this,16),0,dp(this,8));content.addView(origin);
        TextView sender=uiText("发送者  "+(c.sender==null?"未知":c.sender)+(TextUtils.isEmpty(c.channel)?"":"   ·   提交到 "+c.channel),12,Ui.SUB,false);content.addView(sender);
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER_VERTICAL);
        Intent sourceIntent=SourceLaunch.intentFor(this,c.sourcePackageName());
        if(sourceIntent!=null){
            Button open=button("打开"+sourceName(c),v->{try{dialog.dismiss();startActivity(sourceIntent);}catch(Exception unavailable){Toast.makeText(this,"来源应用暂时无法打开",Toast.LENGTH_SHORT).show();}});
            actions.addView(open,new LinearLayout.LayoutParams(0,dp(this,44),1));
        }
        Button copy=button("复制摘要",v->{
            String summary=c.displayTitle()+"\n截止："+deadline+(TextUtils.isEmpty(c.channel)?"":"\n提交到："+c.channel)+"\n来源："+sourceName(c)+" · "+c.groupName;
            android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if(clipboard!=null){clipboard.setPrimaryClip(android.content.ClipData.newPlainText("事项摘要",summary));Toast.makeText(this,"已复制事项摘要",Toast.LENGTH_SHORT).show();}
        });
        LinearLayout.LayoutParams copyLp=new LinearLayout.LayoutParams(0,dp(this,44),1);copyLp.leftMargin=sourceIntent==null?0:dp(this,8);actions.addView(copy,copyLp);
        LinearLayout.LayoutParams actionsLp=new LinearLayout.LayoutParams(-1,-2);actionsLp.topMargin=dp(this,16);content.addView(actions,actionsLp);
        if(sourceIntent!=null){TextView note=uiText("打开来源应用后，请到对应群聊查看",11,Ui.MUTE,false);note.setPadding(0,dp(this,6),0,0);content.addView(note);}
        TextView section=uiText("原始消息",13,Ui.TEXT,true);section.setPadding(0,dp(this,24),0,dp(this,10));content.addView(section);
        StringBuilder original=new StringBuilder(c.sourceText==null?"原消息不可用":c.sourceText);
        if(!TextUtils.isEmpty(c.lastRevisionText))original.append("\n\n最新更正\n").append(c.lastRevisionText);
        if(c.context!=null && !c.context.isEmpty()){original.append("\n\n相关上下文");for(String line:c.context)original.append("\n· ").append(line);}
        TextView source=uiText(original.toString(),14,Ui.TEXT,false);source.setTextIsSelectable(true);source.setLineSpacing(dp(this,5),1.1f);
        source.setPadding(dp(this,14),dp(this,14),dp(this,14),dp(this,14));source.setBackground(Ui.round(this,Ui.BG,14));content.addView(source);
        LinearLayout minor=new LinearLayout(this);
        Button calendar=button("加入日历",v->{dialog.dismiss();addToCalendar(c);});minor.addView(calendar,new LinearLayout.LayoutParams(0,dp(this,44),1));
        Button remove=button("移除事项",v->new AlertDialog.Builder(this).setTitle("移除这条事项？").setMessage("可在“更多 → 已移除的卡片”中恢复。")
                .setNegativeButton("保留",null).setPositiveButton("移除",(d,w)->{Engine shown=demo==null?engine:demo.engine;if(shown.dismissCard(c,System.currentTimeMillis())){dialog.dismiss();render();if(demo==null)EngineHolder.save(this);}}).show());
        minor.addView(remove,new LinearLayout.LayoutParams(0,dp(this,44),1));
        LinearLayout.LayoutParams minorLp=new LinearLayout.LayoutParams(-1,-2);minorLp.topMargin=dp(this,16);minorLp.bottomMargin=dp(this,16);content.addView(minor,minorLp);
        ScrollView scroll=scrollForm(content);scroll.setVerticalScrollBarEnabled(false);sheet.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=new LinearLayout(this);footer.setPadding(0,dp(this,12),0,0);
        Button edit=button("修正信息",v->{dialog.dismiss();showCardEdit(c);});footer.addView(edit,new LinearLayout.LayoutParams(0,dp(this,48),1));
        Button complete=stylePrimary(button(c.done?"恢复待办":"标记完成",v->{dialog.dismiss();toggleDone(c);}));
        LinearLayout.LayoutParams completeLp=new LinearLayout.LayoutParams(0,dp(this,48),1);completeLp.leftMargin=dp(this,10);footer.addView(complete,completeLp);sheet.addView(footer);
        dialog.setContentView(sheet);dialog.setCanceledOnTouchOutside(true);dialog.show();
        android.view.Window window=dialog.getWindow();
        if(window!=null){window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));window.setGravity(Gravity.BOTTOM);window.setDimAmount(0.35f);window.setLayout(-1,(int)(getResources().getDisplayMetrics().heightPixels*0.84f));window.setNavigationBarColor(Ui.CARD);}
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

    void showRemovedCards() {
        Engine shown = demo == null ? engine : demo.engine;
        List<Engine.Card> removed = shown.removedCards();
        if (removed.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("已移除的卡片")
                    .setMessage("这里是空的。误移除的卡片可以从这里恢复。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        String[] titles = new String[removed.size()];
        for (int i = 0; i < removed.size(); i++) {
            Engine.Card card = removed.get(i);
            titles[i] = card.displayTitle() + "\n" + sourceName(card) + " · " + card.groupName;
        }
        new AlertDialog.Builder(this)
                .setTitle("已移除的卡片（" + removed.size() + "）")
                .setItems(titles, (dialog, which) -> {
                    Engine.Card card = removed.get(which);
                    new AlertDialog.Builder(this)
                            .setTitle(card.displayTitle())
                            .setMessage("来源：" + sourceName(card) + " · " + card.groupName + "\n截止时间：" + dateTimeLabel(card.deadline)
                                    + "\n移除时间：" + dateTimeLabel(card.removedAt)
                                    + "\n\n原消息：\n" + card.sourceText
                                    + "\n\n恢复后请核对时间，移除期间的群消息变更可能未应用到这张卡。")
                            .setPositiveButton("恢复", (d, w) -> {
                                if (shown.restoreCard(card)) {
                                    if (demo == null) EngineHolder.save(this);
                                    showAllCards();
                                    Toast.makeText(this, "已恢复，请核对事项时间", Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(this, "当前已有同一张卡或更新的接龙，保留当前版本", Toast.LENGTH_LONG).show();
                                }
                            })
                            .setNegativeButton("返回列表", (d, w) -> showRemovedCards())
                            .show();
                })
                .setNeutralButton("清空已移除", (dialog, which) -> new AlertDialog.Builder(this)
                        .setTitle("清空已移除的卡片？")
                        .setMessage("这些卡片将无法恢复。")
                        .setPositiveButton("清空", (d, w) -> {
                            shown.clearRemovedCards();
                            if (demo == null) EngineHolder.save(this);
                        })
                        .setNegativeButton("取消", (d, w) -> showRemovedCards())
                        .show())
                .setNegativeButton("关闭", null)
                .show();
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

    void showCardEdit(Engine.Card card) {
        LinearLayout form = dialogForm();
        EditText title = input(form, "事项标题", false);
        title.setText(card.displayTitle());
        Long[] deadline = {null};
        Button time = button("时间：" + (card.deadlineHint() == null ? dateTimeLabel(card.deadline)
                : card.deadlineHint() + "（时刻待确认）"), v -> {
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
        if (c.deadlineHint() != null || c.deadline == 0) {
            Toast.makeText(this, "请先确认具体截止时间，再加入日历", Toast.LENGTH_LONG).show();
            showCardEdit(c);
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_INSERT);
            i.setData(CalendarContract.Events.CONTENT_URI);
            i.putExtra(CalendarContract.Events.TITLE, "【不爬楼】" + c.displayTitle());
            // 描述不写群消息原文，避免系统日历云同步把原文外泄；只写来源与考试要点。
            StringBuilder desc = new StringBuilder();
            desc.append("来源：").append(sourceName(c)).append(" · ").append(c.groupName).append(" · ").append(c.sender);
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
