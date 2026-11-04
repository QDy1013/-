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
 * 捕获当前窗口内的文字节点。用户回到主界面后选择具体条目并确认导入。
 */
public class ReadService extends AccessibilityService {

    private static final int MAX_NODES_WITH_TEXT = 160;
    public static ReadService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ReadPreview pendingPreview;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onDestroy() {
        cancelPendingPreview();
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不做实时抓取：只在用户主动触发时读当前界面
    }

    @Override
    public void onInterrupt() { }

    ReadPreview takePreview(long now) {
        ReadPreview preview = pendingPreview;
        pendingPreview = null;
        return preview != null && !preview.isExpired(now) ? preview : null;
    }

    void cancelPendingPreview() {
        handler.removeCallbacksAndMessages(null);
        pendingPreview = null;
    }

    /** 延时捕获一次，给用户留出切到群聊界面的时间。 */
    public void scheduleScrape(long delayMs) {
        cancelPendingPreview();
        handler.postDelayed(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            CharSequence rootPkg = root == null ? null : root.getPackageName();
            String pkg = rootPkg == null ? "" : rootPkg.toString();
            if (root == null || getPackageName().equals(pkg) || !NotifFilter.accept(pkg)) {
                Toast.makeText(this, "没在群聊界面，没读到", Toast.LENGTH_SHORT).show();
                return;
            }
            List<String> lines = new ArrayList<>();
            collect(root, lines, 0);
            pendingPreview = ReadPreview.create(pkg, System.currentTimeMillis(), lines);
            if (pendingPreview != null) {
                ReadPreview captured = pendingPreview;
                handler.postDelayed(() -> {
                    if (pendingPreview == captured) pendingPreview = null;
                }, 5 * 60_000L);
            }
            Toast.makeText(this, pendingPreview == null ? "没读到可用文字" : "已读取文字，请回不爬楼选择导入",
                    Toast.LENGTH_SHORT).show();
        }, delayMs);
    }

    void collect(AccessibilityNodeInfo node, List<String> out, int depth) {
        if (node == null || depth > 40 || out.size() >= MAX_NODES_WITH_TEXT) return;
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
