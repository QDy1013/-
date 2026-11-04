package com.nopalou;

import android.content.Context;
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

    private static Engine engine;

    /** 系统是否真的把通知监听服务连上了。主界面靠它显示"通知读取"状态。 */
    public static volatile boolean listenerConnected = false;

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

        Engine e;
        try (InputStream model = app.getAssets().open("model.tsv")) {
            e = new Engine(model);
        } catch (Exception ex) {
            Log.w(TAG, "模型加载失败", ex);
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
