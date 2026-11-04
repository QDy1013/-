package com.nopalou;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据源二：无障碍读屏（方案书第 5 节，负责补全免打扰群和漏掉的消息）。
 * 只在用户主动点"读当前群"时读取当前界面，不做常驻监听——权限最小化。
 *
 * 真实产品里这里会自动上滑翻页把未读读全；当前版本按屏幕可见范围内的
 * 文字节点读，够演示"接龙全文 / 免打扰群"这两个通知拿不到的场景。
 */
public class ReadService extends AccessibilityService {

    public static ReadService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不做实时抓取：只在用户主动触发时读当前界面
    }

    @Override
    public void onInterrupt() { }

    /** 读取当前界面所有文字节点，按行交给引擎。返回处理条数。 */
    public int scrapeInto(Engine engine) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return 0;
        // 用当前窗口包名参与群编号，和通知里的同一算法保持一致，避免同 App 多群混成一个群。
        String group = "当前打开的群";
        CharSequence rootPkg = root.getPackageName();
        String pkg = rootPkg == null ? "" : rootPkg.toString();
        String gid = NotifFilter.groupId(pkg, group);
        List<String> lines = new ArrayList<>();
        collect(root, lines, 0);

        int n = 0;
        long base = System.currentTimeMillis() - lines.size() * 1000L;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.length() > 200) continue;
            engine.process(new Engine.Msg("a" + i, gid, group,
                    "读屏", line, "accessibility", base + i * 1000L));
            n++;
        }
        EngineHolder.save(this);
        return n;
    }

    /** 延时读取当前前台窗口：给用户留出切到群聊界面的时间，避免读到不爬楼自己（审查意见第 3 条）。 */
    public void scheduleScrape(Engine engine, long delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            CharSequence rootPkg = root == null ? null : root.getPackageName();
            String pkg = rootPkg == null ? "" : rootPkg.toString();
            // 没窗口 / 还是不爬楼自己 / 不是群聊类 App，都不读，避免抓错数据。
            if (root == null || getPackageName().equals(pkg) || !NotifFilter.accept(pkg)) {
                Toast.makeText(this, "没在群聊界面，没读到", Toast.LENGTH_SHORT).show();
                return;
            }
            int n = scrapeInto(engine);
            Toast.makeText(this, "读到 " + n + " 条消息", Toast.LENGTH_SHORT).show();
            // 通知界面刷新：scrapeInto 里 engine.process 已回调 listener；没有独立公开刷新接口时
            // 这里再触发一次，界面回到前台 onResume 也会自行刷新。
            engine.notifyChanged();
        }, delayMs);
    }

    void collect(AccessibilityNodeInfo node, List<String> out, int depth) {
        if (node == null || depth > 40) return;
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) out.add(t.toString());
        else {
            CharSequence d = node.getContentDescription();
            if (d != null && d.length() > 0) out.add(d.toString());
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collect(node.getChild(i), out, depth + 1);
        }
    }
}
