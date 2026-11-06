package com.nopalou;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.Calendar;

/** School login stays in its HTTPS page. Only the requested timetable crosses into Java. */
public class NwpuImportActivity extends Activity {
    static final String COURSE_URL = "https://jwxt.nwpu.edu.cn/student/for-std/course-table";
    WebView web;
    TextView status;
    Button read;
    String script;
    String token;
    long deadline;
    boolean reading, saving;
    final Handler handler = new Handler(Looper.getMainLooper());
    final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try (java.io.InputStream in = getAssets().open("nwpu-import.js");
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            script = out.toString("UTF-8");
        } catch (Exception e) { Toast.makeText(this, "导入组件无法加载", Toast.LENGTH_LONG).show(); finish(); return; }
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Ui.BG);
        TextView title = label(root, "西工大课表导入", 22);
        title.setTypeface(Ui.medium());
        label(root, "在校方网页登录并打开个人课表，选择学期后点「导入当前学期」。确认后仅将课表保存到本机。", 13);
        status = label(root, "正在打开教务课表…", 12);
        LinearLayout bar = new LinearLayout(this); root.addView(bar);
        button(bar, "打开课表", () -> { if (!saving) web.loadUrl(COURSE_URL); });
        button(bar, "刷新", () -> { if (!saving) web.reload(); });
        read = button(bar, "导入当前学期", this::begin);
        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSaveFormData(false);
        settings.setBuiltInZoomControls(true); settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true); settings.setLoadWithOverviewMode(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !navigationAllowed(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !navigationAllowed(Uri.parse(url));
            }
            @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
                stopReading(); status.setText("正在加载校方网页…");
            }
            @Override public void onPageFinished(WebView view, String url) {
                status.setText(isCourse(url) ? "选择学期，等课表加载后点击导入" : "请在学校认证网页完成登录；登录后点「打开课表」");
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) { stopReading(); status.setText("网页加载失败，请检查网络后刷新；如学校要求校内网络，请先连接。"); }
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler h, SslError e) {
                h.cancel(); stopReading(); status.setText("校方网页证书验证失败，请稍后重试");
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        button(root, "退出教务登录", () -> {
            if (saving) return;
            stopReading(); web.stopLoading(); web.loadUrl("about:blank");
            CookieManager.getInstance().removeAllCookies(value -> {
                CookieManager.getInstance().flush();
                android.webkit.WebStorage.getInstance().deleteAllData();
                if (isDestroyed()) return;
                web.clearCache(true); web.clearHistory(); status.setText("已清除本 App 中的教务登录状态，可返回查看课表");
            });
        });
        setContentView(root);
        getWindow().setStatusBarColor(Ui.BG); getWindow().setNavigationBarColor(Ui.BG);
        root.setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        web.loadUrl(COURSE_URL);
    }
    static boolean navigationAllowed(Uri u) {
        String host = u.getHost();
        return "https".equals(u.getScheme()) && (u.getPort() == -1 || u.getPort() == 443) && host != null &&
                (host.equals("nwpu.edu.cn") || host.endsWith(".nwpu.edu.cn")) && u.getUserInfo() == null;
    }
    static boolean isCourse(String url) {
        if (url == null) return false;
        Uri u = Uri.parse(url);
        String path = u.getPath();
        return navigationAllowed(u) && "jwxt.nwpu.edu.cn".equals(u.getHost()) && path != null &&
                (path.equals("/student/for-std/course-table") || path.startsWith("/student/for-std/course-table/"));
    }
    void begin() {
        if (reading || saving) return;
        if (!isCourse(web.getUrl())) { status.setText("请先登录并点击「打开课表」"); return; }
        reading = true; read.setEnabled(false);
        status.setText("正在读取当前学期课表…");
        token = java.util.UUID.randomUUID().toString();
        String active = token;
        deadline = android.os.SystemClock.elapsedRealtime() + 22000;
        web.evaluateJavascript(script + "\nNwpuImport.start(" + JSONObject.quote(active) + ");", ignored -> poll(active));
    }
    void poll(String active) {
        if (!reading || !active.equals(token) || isFinishing() || isDestroyed()) return;
        if (!isCourse(web.getUrl())) { fail("页面已改变，请回到个人课表后重试"); return; }
        if (android.os.SystemClock.elapsedRealtime() > deadline) { fail("读取超时，请刷新课表后重试"); return; }
        web.evaluateJavascript("JSON.stringify(window.__louTimetableResult || null)", value -> {
            if (!reading || !active.equals(token) || isDestroyed() || !isCourse(web.getUrl())) return;
            try {
                Object decoded = new JSONTokener(value).nextValue();
                if (decoded instanceof String) {
                    JSONObject r = new JSONObject((String) decoded);
                    if (active.equals(r.optString("token"))) {
                        if ("ok".equals(r.optString("status"))) {
                            Timetable candidate = new Timetable(r.getJSONObject("data").toString());
                            stopReading(); preview(candidate); return;
                        }
                        if ("error".equals(r.optString("status"))) { fail(r.optString("message", "读取失败")); return; }
                    }
                }
            } catch (Exception e) { fail("课表格式暂不受支持，原课表保持不变"); return; }
            handler.postDelayed(() -> poll(active), 350);
        });
    }
    void stopReading() { reading = false; token = null; handler.removeCallbacksAndMessages(null); if (read != null) read.setEnabled(!saving); }
    void fail(String text) { stopReading(); status.setText(text); }
    void preview(Timetable candidate) {
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        label(body, candidate.termName + " · " + candidate.lessons.size() + "条上课安排", 18);
        label(body, "请核对课程、单双周、节次和日期。保存会替换本机原课表。", 13);
        TextView date = label(body, "第1周周一：" + (candidate.firstMonday.isEmpty() ? "待选择" : candidate.firstMonday), 15);
        button(body, "选择／校准开学日期", () -> {
            Calendar c = Timetable.calendar(candidate.firstMonday); if (c == null) c = Calendar.getInstance();
            DatePickerDialog picker = new DatePickerDialog(this, (v, y, m, d) -> {
                String chosen = String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", y, m + 1, d);
                if (!chosen.equals(Timetable.monday(chosen))) { Toast.makeText(this, "请选择第1周的周一", Toast.LENGTH_LONG).show(); return; }
                candidate.firstMonday = chosen; date.setText("第1周周一：" + chosen);
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
            picker.setTitle("选择第1周周一"); picker.show();
        });
        if (candidate.json.optJSONArray("units").length() == 0) label(body, "未读到作息时间，本次仅显示节次。", 13);
        int count = 0;
        for (Timetable.Lesson l : candidate.lessons) {
            if (count++ == 100) { label(body, "其余课程保存后可切换周次查看", 13); break; }
            label(body, l.name + "\n周" + "一二三四五六日".charAt(l.weekday - 1) + " · " + candidate.time(l)
                    + "\n" + l.weekText() + " · " + (l.room.isEmpty() ? "地点待定" : l.room)
                    + (l.teachers.isEmpty() ? "" : " · " + l.teachers), 14);
        }
        org.json.JSONArray other = candidate.json.optJSONArray("unarranged");
        if (other != null && other.length() > 0) label(body, "另有 " + other.length() + "门未排课课程，保存后可查看名称，时间地点请以教务通知为准。", 13);
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("确认导入课表").setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton("保存课表", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (saving) return;
            if (candidate.firstMonday.isEmpty()) { date.setText("请先选择第1周周一的日期"); return; }
            saving = true; read.setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            dialog.setCancelable(false); dialog.setCanceledOnTouchOutside(false);
            io.execute(() -> {
                boolean ok;
                try { TimetableStore.save(getApplicationContext(), candidate); ok = true; } catch (Exception e) { ok = false; }
                final boolean saved = ok;
                runOnUiThread(() -> {
                    saving = false; if (isDestroyed()) return;
                    dialog.dismiss(); read.setEnabled(true);
                    if (saved) { Toast.makeText(this, "课表已保存", Toast.LENGTH_SHORT).show(); finish(); }
                    else status.setText("保存失败，原课表保持不变，请重试");
                });
            });
        }));
        status.setText("读取成功，请核对后保存"); dialog.show();
    }
    TextView label(LinearLayout p, String text, int size) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(Ui.TEXT);
        int d = Ui.dp(this, 10); v.setPadding(d, d, d, d); p.addView(v); return v;
    }
    Button button(LinearLayout p, String text, Runnable action) {
        Button b = new Button(this); b.setText(text); b.setTextSize(13); b.setTextColor(Ui.ACCENT); b.setAllCaps(false);
        p.addView(b); b.setOnClickListener(v -> action.run()); return b;
    }
    @Override public void onBackPressed() {
        if (saving) return;
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
    @Override public void onDestroy() {
        stopReading();
        if (web != null) { web.stopLoading(); web.removeAllViews(); web.destroy(); web = null; }
        io.shutdown(); super.onDestroy();
    }
}
