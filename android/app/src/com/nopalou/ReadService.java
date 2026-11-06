package com.nopalou;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 导入以前的群消息：用户在 QQ / 微信的群聊界面点悬浮的「Offloor · 导入」，
 * 自动往上翻聊天记录（最多一个月），边翻边读，读完按时间顺序交给引擎出卡。
 *
 * 只在用户从 Offloor 里点了「导入一个月的聊天记录」之后的 30 分钟内显示按钮；
 * 只在用户点按钮时读取当前群，不点别的、不发消息。
 */
public class ReadService extends AccessibilityService {

    private static final String TAG = "NopalouRead";
    static final Set<String> CHAT_PKGS = new HashSet<>(Arrays.asList(
            "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim"));
    private static final Set<String> ROLE_TAGS = new HashSet<>(Arrays.asList(
            "管理员", "群主", "群管理", "老师", "助教"));

    private static final String PREFS = "read_import";
    private static final String KEY_ARMED_UNTIL = "armed_until";
    private static final long ARM_MS = 30 * 60_000L;
    private static final long MONTH_MS = 30L * 86400_000L;
    private static final int MAX_PAGES = 400;
    private static final long STEP_DELAY = 900L;

    private static volatile ReadService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private TextView pill;
    private boolean pillShown;

    // 当前一次导入的状态
    private boolean scanning;
    private ChatHistory history;
    private String scanPkg, scanTitle;
    private int pages, stale;
    private long scanStart;

    /** 无障碍里是否已经打开了 Offloor。 */
    static boolean enabled(Context ctx) {
        try {
            String flat = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (flat == null) return false;
            String me = new ComponentName(ctx, ReadService.class).flattenToString();
            String shortName = new ComponentName(ctx, ReadService.class).flattenToShortString();
            for (String s : flat.split(":")) {
                if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase(shortName)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 从 Offloor 里点了"导入"：接下来 30 分钟在 QQ / 微信里显示导入按钮。 */
    static void arm(Context ctx) {
        try {
            ctx.getApplicationContext().getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong(KEY_ARMED_UNTIL, System.currentTimeMillis() + ARM_MS).apply();
        } catch (Throwable ignored) {
        }
    }

    private boolean armed() {
        try {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_ARMED_UNTIL, 0)
                    > System.currentTimeMillis();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        EngineHolder.diag(this, "导入", "无障碍服务已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        try {
            if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
            CharSequence p = e.getPackageName();
            if (p == null) return;
            String pkg = p.toString();
            // 系统界面、输入法弹出来时不改变按钮状态。
            if (pkg.equals("com.android.systemui") || pkg.contains("inputmethod") || pkg.contains(".ime")
                    || pkg.equals(getPackageName())) {
                if (pkg.equals(getPackageName()) && !scanning) hidePill();
                return;
            }
            if (scanning) return;
            if (CHAT_PKGS.contains(pkg) && armed()) showPill("Offloor · 导入这个群");
            else hidePill();
        } catch (Throwable t) {
            Log.w(TAG, "event", t);
        }
    }

    @Override
    public void onInterrupt() {
        stopScan("已中断");
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        scanning = false;
        handler.removeCallbacksAndMessages(null);
        hidePill();
        super.onDestroy();
    }

    // ---------------- 悬浮按钮 ----------------

    private void showPill(String text) {
        if (wm == null) return;
        if (pill == null) {
            pill = new TextView(this);
            pill.setTextColor(Color.WHITE);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            int px = dp(14), py = dp(10);
            pill.setPadding(px, py, px, py);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xE61F1F1F);
            bg.setCornerRadius(dp(22));
            pill.setBackground(bg);
            pill.setOnClickListener(v -> {
                if (scanning) stopScan("已停止");
                else startScan();
            });
            pill.setOnLongClickListener(v -> {
                dumpTree();
                return true;
            });
        }
        pill.setText(text);
        if (pillShown) return;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.END | Gravity.TOP;
        lp.x = dp(8);
        lp.y = (int) (getResources().getDisplayMetrics().heightPixels * 0.28f);
        try {
            wm.addView(pill, lp);
            pillShown = true;
        } catch (Throwable t) {
            Log.w(TAG, "addView", t);
        }
    }

    private void hidePill() {
        if (!pillShown || pill == null || wm == null) return;
        try {
            wm.removeView(pill);
        } catch (Throwable ignored) {
        }
        pillShown = false;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------- 导入流程 ----------------

    private void startScan() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !CHAT_PKGS.contains(root.getPackageName().toString())) {
            showPill("请先打开一个群的聊天界面");
            return;
        }
        String pkg = root.getPackageName().toString();
        AccessibilityNodeInfo list = findList(root);
        String title = findTitle(root, pkg);
        if (list == null || title.isEmpty()) {
            showPill("这里不是群聊界面，打开一个群再点");
            EngineHolder.diag(this, "导入", "没找到聊天列表或群名：" + pkg);
            return;
        }
        scanning = true;
        scanPkg = pkg;
        scanTitle = title;
        history = new ChatHistory();
        pages = 0;
        stale = 0;
        scanStart = System.currentTimeMillis();
        showPill("开始读取「" + shortTitle(title) + "」· 点此停止");
        EngineHolder.diag(this, "导入", "开始：" + title);
        handler.post(this::step);
    }

    private void step() {
        if (!scanning) return;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null || !scanPkg.equals(root.getPackageName().toString())) {
                stopScan("离开了聊天界面，已停止");
                return;
            }
            AccessibilityNodeInfo list = findList(root);
            if (list == null) {
                stopScan("找不到聊天记录，已停止");
                return;
            }
            int added = history.addPage(readPage(list, scanPkg));
            pages++;
            stale = added == 0 ? stale + 1 : 0;
            showPill("读取中 " + history.size() + " 条 · 点此停止");
            long oldest = history.oldestTime();
            if ((oldest > 0 && oldest < scanStart - MONTH_MS) || stale >= 4 || pages >= MAX_PAGES) {
                finishScan();
                return;
            }
            boolean moved = list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            if (!moved) swipeDown(list);
            handler.postDelayed(this::step, STEP_DELAY);
        } catch (Throwable t) {
            EngineHolder.diag(this, "出错", "导入：" + t.getClass().getSimpleName() + ": " + t.getMessage());
            stopScan("读取出错，已停止");
        }
    }

    private void stopScan(String why) {
        if (!scanning) return;
        // 中途停止也把已经读到的内容交给引擎。
        finishScan();
        showPill(why + " · " + pill.getText());
    }

    private void finishScan() {
        scanning = false;
        handler.removeCallbacksAndMessages(null);
        Engine eng = null;
        try {
            eng = EngineHolder.get(this);
        } catch (Throwable ignored) {
        }
        if (eng == null || history == null) {
            showPill("引擎不可用，没有导入");
            return;
        }
        long cutoff = scanStart - MONTH_MS;
        List<ChatHistory.Item> msgs = history.messages(scanStart);
        Set<String> before = new HashSet<>();
        for (Engine.Card c : eng.all()) before.add(c.id);
        int fed = 0;
        String groupId = NotifFilter.groupId(scanPkg, scanTitle);
        for (ChatHistory.Item m : msgs) {
            if (m.ts < cutoff || m.text.trim().isEmpty()) continue;
            String id = "a" + scanTitle.hashCode() + "_" + (m.sender + "|" + m.text).hashCode() + "_" + m.ts;
            if (!NotifListener.markSeen(this, id)) continue;
            try {
                eng.process(new Engine.Msg(id, groupId, scanTitle, m.sender, m.text, scanPkg, m.ts));
                fed++;
            } catch (Throwable t) {
                Log.w(TAG, "process", t);
            }
        }
        int added = 0;
        for (Engine.Card c : eng.all()) if (!before.contains(c.id)) added++;
        NotifListener.saveSeen(this);
        EngineHolder.saveIfChanged(this);
        EngineHolder.refreshCards();
        String summary = "完成：读了 " + msgs.size() + " 条，新增 " + added + " 项";
        EngineHolder.diag(this, "导入", scanTitle + " · 页 " + pages + " · 消息 " + msgs.size()
                + "（新 " + fed + "）· 新增事项 " + added);
        showPill(summary);
        handler.postDelayed(() -> {
            if (!scanning && pillShown) showPill("Offloor · 导入这个群");
        }, 5000);
    }

    /** 往上翻不动时（部分版本不支持滚动动作），用手势从上往下划。 */
    private void swipeDown(AccessibilityNodeInfo list) {
        try {
            Rect r = new Rect();
            list.getBoundsInScreen(r);
            Path path = new Path();
            path.moveTo(r.centerX(), r.top + r.height() * 0.25f);
            path.lineTo(r.centerX(), r.top + r.height() * 0.85f);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 350)).build();
            dispatchGesture(g, null, null);
        } catch (Throwable t) {
            Log.w(TAG, "swipe", t);
        }
    }

    // ---------------- 读界面 ----------------

    /** 聊天记录列表：面积最大的可滚动列表。 */
    static AccessibilityNodeInfo findList(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo best = null;
        long bestArea = 0;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        Rect r = new Rect();
        int visited = 0;
        while (!q.isEmpty() && visited++ < 3000) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            if (n.isScrollable()) {
                CharSequence cls = n.getClassName();
                String c = cls == null ? "" : cls.toString();
                if (c.contains("RecyclerView") || c.contains("ListView") || c.contains("AbsListView")) {
                    n.getBoundsInScreen(r);
                    long area = (long) r.width() * r.height();
                    if (area > bestArea) {
                        bestArea = area;
                        best = n;
                    }
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
        return best;
    }

    /** 群名：QQ 用固定的标题控件；其他取屏幕顶部最长的一段文字，去掉末尾的人数。 */
    static String findTitle(AccessibilityNodeInfo root, String pkg) {
        String best = "";
        int screenTop = Integer.MAX_VALUE;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        Rect r = new Rect();
        Rect rootR = new Rect();
        root.getBoundsInScreen(rootR);
        int limit = rootR.top + Math.max(1, rootR.height()) * 15 / 100;
        int visited = 0;
        while (!q.isEmpty() && visited++ < 3000) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            String id = n.getViewIdResourceName();
            CharSequence t = n.getText();
            if (id != null && id.endsWith(":id/3kc") && t != null && t.length() > 0) return cleanTitle(t.toString());
            if (t != null && t.length() > 0) {
                n.getBoundsInScreen(r);
                if (r.top < limit && r.left > rootR.width() / 10 && t.length() > best.length()
                        && ChatTime.parse(t.toString(), System.currentTimeMillis()) < 0) {
                    best = t.toString();
                    screenTop = r.top;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
        return cleanTitle(best);
    }

    private static String cleanTitle(String t) {
        return t.replaceAll("\\s*[(（]\\d+[)）]\\s*$", "").trim();
    }

    private static String shortTitle(String t) {
        return t.length() <= 10 ? t : t.substring(0, 10) + "…";
    }

    /** 文字节点：内容、位置、控件编号。 */
    private static final class TextNode {
        final String text, id;
        final Rect bounds = new Rect();

        TextNode(String text, String id) {
            this.text = text;
            this.id = id == null ? "" : id;
        }
    }

    /** 读一页：列表的每个子项是一行（可能带时间分隔条），按屏幕从上到下。 */
    List<ChatHistory.Item> readPage(AccessibilityNodeInfo list, String pkg) {
        List<AccessibilityNodeInfo> rows = new ArrayList<>();
        for (int i = 0; i < list.getChildCount(); i++) {
            AccessibilityNodeInfo c = list.getChild(i);
            if (c != null) rows.add(c);
        }
        final Rect a = new Rect(), b = new Rect();
        Collections.sort(rows, (x, y) -> {
            x.getBoundsInScreen(a);
            y.getBoundsInScreen(b);
            return Integer.compare(a.top, b.top);
        });
        long now = System.currentTimeMillis();
        List<ChatHistory.Item> out = new ArrayList<>();
        for (AccessibilityNodeInfo row : rows) {
            List<TextNode> texts = new ArrayList<>();
            collectTexts(row, texts, 0);
            readRow(texts, now, out);
        }
        return out;
    }

    private static void collectTexts(AccessibilityNodeInfo n, List<TextNode> out, int depth) {
        if (n == null || depth > 25) return;
        CharSequence t = n.getText();
        String s = t == null ? "" : t.toString().trim();
        if (s.isEmpty()) {
            // 时间分隔条有时只放在 contentDescription 里。
            CharSequence d = n.getContentDescription();
            CharSequence cls = n.getClassName();
            if (d != null && cls != null && cls.toString().endsWith("TextView")) s = d.toString().trim();
        }
        if (!s.isEmpty()) {
            TextNode tn = new TextNode(s, n.getViewIdResourceName());
            n.getBoundsInScreen(tn.bounds);
            out.add(tn);
        }
        for (int i = 0; i < n.getChildCount(); i++) collectTexts(n.getChild(i), out, depth + 1);
    }

    /** 一行里的文字 → 分隔条 + 消息。QQ 按控件编号取；其他按位置和长度猜。 */
    static void readRow(List<TextNode> texts, long now, List<ChatHistory.Item> out) {
        String sender = null, body = null;
        List<TextNode> rest = new ArrayList<>();
        for (TextNode t : texts) {
            long ts = t.text.length() <= 24 ? ChatTime.parse(t.text, now) : -1;
            if (ts > 0) {
                out.add(ChatHistory.Item.header(t.text, ts));
                continue;
            }
            if (t.id.endsWith(":id/mjq")) { sender = t.text; continue; }
            if (t.id.endsWith(":id/mjn")) { body = t.text; continue; }
            // QQ 的文件、图片卡片不是文字消息，跳过。
            if (t.id.endsWith(":id/kbs") || t.id.endsWith(":id/k73") || t.id.endsWith(":id/qo5")) continue;
            if (ROLE_TAGS.contains(t.text)) continue;
            rest.add(t);
        }
        if (body == null && !rest.isEmpty()) {
            TextNode longest = rest.get(0);
            for (TextNode t : rest) if (t.text.length() > longest.text.length()) longest = t;
            body = longest.text;
            if (sender == null) {
                TextNode best = null;
                for (TextNode t : rest) {
                    if (t == longest || t.text.length() > 24) continue;
                    if (t.bounds.bottom <= longest.bounds.top + 4 && (best == null || t.bounds.top < best.bounds.top)) best = t;
                }
                if (best != null) sender = best.text;
            }
        }
        if (body != null && !body.trim().isEmpty()) {
            out.add(ChatHistory.Item.message(sender == null ? "" : sender, body.trim()));
        }
    }

    /** 调试用：长按悬浮按钮，把当前界面结构写进 logcat（标签 NopalouRead），用于适配新版本。 */
    private void dumpTree() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Log.i(TAG, "dump: no root");
            return;
        }
        int[] count = {0};
        dumpNode(root, 0, count);
        showPill("已记录界面结构（" + count[0] + "）");
    }

    private void dumpNode(AccessibilityNodeInfo n, int depth, int[] count) {
        if (n == null || count[0] > 600) return;
        count[0]++;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append(' ');
        CharSequence cls = n.getClassName();
        sb.append(cls == null ? "?" : cls.toString().replaceAll(".*\\.", ""))
                .append(" id=").append(n.getViewIdResourceName())
                .append(n.isScrollable() ? " S" : "")
                .append(" b=").append(r.toShortString())
                .append(" t=").append(n.getText())
                .append(" d=").append(n.getContentDescription());
        Log.i(TAG, sb.toString());
        for (int i = 0; i < n.getChildCount(); i++) dumpNode(n.getChild(i), depth + 1, count);
    }
}
