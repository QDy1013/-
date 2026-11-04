package com.nopalou;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;

/** 引擎单例：界面、通知监听、读屏服务共用一个实例，状态和时间线才对得上。 */
public class EngineHolder {

    static final String TAG = "EngineHolder";
    static final String FILE_NAME = "cards.dat";

    /** 诊断日志落盘用的 SharedPreferences 文件名。 */
    static final String DIAG_PREFS = "diag";

    /** 诊断日志在 SharedPreferences 里的键。 */
    static final String DIAG_KEY = "log";

    /** 是否已经从磁盘恢复过诊断日志；只在进程内第一次 loadDiag 时恢复，避免反复覆盖内存里的新日志。 */
    private static boolean diagLoaded = false;

    private static Engine engine;

    /**
     * 记一条诊断日志并落盘。
     * 先写内存的环形日志，再把整份 dump 覆盖写到 SharedPreferences（apply 异步提交）。
     * 任何异常都吞掉：诊断只是辅助，绝不能因为它让通知处理或界面崩掉。
     */
    static void diag(Context ctx, String kind, String detail) {
        try {
            // 内存日志始终要记，即使没有 ctx 也要留下痕迹。
            DiagLog.record(System.currentTimeMillis(), kind, detail);
            // 没有上下文就没法落盘，只保留内存记录。
            if (ctx == null) return;
            // 优先用 Application 上下文，避免持有 Activity 造成泄漏。
            Context app = ctx.getApplicationContext();
            // 极少数情况下拿不到 Application，就退回传进来的 ctx。
            if (app == null) app = ctx;
            // 取出诊断日志专用的 SharedPreferences。
            SharedPreferences sp = app.getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE);
            // 整份 dump 覆盖写入，保证磁盘和内存一致；apply 异步提交，不阻塞通知线程。
            sp.edit().putString(DIAG_KEY, DiagLog.dump()).apply();
        } catch (Throwable t) {
            // 落盘失败、存储不可用等都直接忽略，绝不向外抛。
        }
    }

    /**
     * 进程内第一次调用时，从 SharedPreferences 恢复上次的诊断日志。
     * 之后的调用直接返回，避免把本次运行新产生的日志又用旧的覆盖回去。
     */
    static void loadDiag(Context ctx) {
        // 已经恢复过就直接结束，保证每个进程只执行一次。
        if (diagLoaded) return;
        // 先置位再干活，即使下面抛异常也不会反复重试。
        diagLoaded = true;
        try {
            // 没有上下文就没法读盘，内存日志保持空白状态。
            if (ctx == null) return;
            // 优先用 Application 上下文。
            Context app = ctx.getApplicationContext();
            // 拿不到 Application 就退回传进来的 ctx。
            if (app == null) app = ctx;
            // 打开同一个诊断日志文件。
            SharedPreferences sp = app.getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE);
            // 读出上次保存的文本；没有就是 null，DiagLog.load 会按空日志处理。
            DiagLog.load(sp.getString(DIAG_KEY, null));
        } catch (Throwable t) {
            // 读取或解析出错都吞掉，诊断日志缺失不影响主流程。
        }
    }

    /** 系统是否真的把通知监听服务连上了。主界面靠它显示"通知读取"状态。 */
    public static volatile boolean listenerConnected = false;

    /** 最近一次自动收到消息的时间（毫秒）；还没收到过就是 0，界面据此显示"最近一次自动收到"。 */
    public static volatile long lastReceivedAt = 0L;

    /** 最近一次自动收到消息所属的群名；还没收到过就是 null。 */
    public static volatile String lastReceivedGroup = null;

    /** 连接状态变了，让界面重画一次；引擎或界面还没准备好就跳过，不在这里加载模型。 */
    public static void refreshCards() {
        final Engine.SelectedListener l = engine == null ? null : engine.listener;
        if (l == null) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                l.onCardsChanged();
            }
        });
    }

    /** 第一次调用时建引擎并从 cards.dat 读回卡片；之后一直返回同一个实例。失败返回 null。 */
    public static synchronized Engine get(Context ctx) {
        if (engine != null) return engine;
        if (ctx == null) return null;
        Context app = ctx.getApplicationContext();
        if (app == null) app = ctx;

        // 每个进程启动后、建引擎之前先恢复上次的诊断日志，异常与跳过才有上下文可看。
        loadDiag(app);

        Engine e;
        try (InputStream model = app.getAssets().open("model.tsv")) {
            e = new Engine(model);
        } catch (Exception ex) {
            Log.w(TAG, "模型加载失败", ex);
            // 模型加载失败也要进诊断日志，界面上的自检才能说明白为什么收不到消息。
            diag(app, "出错", "模型加载失败：" + ex);
            return null;
        }

        // 档案必须先于任何消息进来之前装上，否则"跟我有关"的判断会用空档案。
        e.setProfile(ProfileStore.load(app));

        AtomicFile file = new AtomicFile(new File(app.getFilesDir(), FILE_NAME));
        try (InputStream in = file.openRead()) {
            e.loadState(in);
        } catch (FileNotFoundException firstRun) {
            // 首次启动还没有卡片存档。
        } catch (Exception ex) {
            Log.w(TAG, "读取卡片失败", ex);
        }
        engine = e;
        return engine;
    }

    /** 写入成功后提交新存档，出错时由 AtomicFile 保留上一次存档。 */
    public static synchronized void save(Context ctx) {
        if (engine == null || ctx == null) return;
        Context app = ctx.getApplicationContext();
        if (app == null) app = ctx;

        AtomicFile file = new AtomicFile(new File(app.getFilesDir(), FILE_NAME));
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            engine.saveState(out);
            file.finishWrite(out);
        } catch (Exception ex) {
            if (out != null) file.failWrite(out);
            Log.w(TAG, "保存卡片失败", ex);
            return;
        }
        ReminderReceiver.refresh(app, engine);
    }
}
