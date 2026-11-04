package com.nopalou;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
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
        try {
            e = new Engine(app.getAssets().open("model.tsv"));
        } catch (Exception ex) {
            Log.w(TAG, "模型加载失败", ex);
            return null;
        }

        // 档案必须先于任何消息进来之前装上，否则"跟我有关"的判断会用空档案。
        e.setProfile(ProfileStore.load(app));

        File f = new File(app.getFilesDir(), FILE_NAME);
        if (f.exists()) {
            InputStream in = null;
            try {
                in = new FileInputStream(f);
                e.loadState(in);
            } catch (Exception ex) {
                Log.w(TAG, "读取卡片失败", ex);
            } finally {
                if (in != null) try { in.close(); } catch (Exception ignored) { }
            }
        }
        engine = e;
        return engine;
    }

    /** 先写 cards.dat.tmp 再改名，避免写一半被杀留下坏文件。异常一律吞掉不崩。 */
    public static synchronized void save(Context ctx) {
        if (engine == null || ctx == null) return;
        Context app = ctx.getApplicationContext();
        if (app == null) app = ctx;

        File dst = new File(app.getFilesDir(), FILE_NAME);
        File tmp = new File(app.getFilesDir(), FILE_NAME + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            engine.saveState(out);
            out.flush();
            out.close();
            out = null;
            if (!tmp.renameTo(dst)) {
                if (dst.exists() && !dst.delete()) Log.w(TAG, "旧卡片文件删不掉");
                if (!tmp.renameTo(dst)) Log.w(TAG, "卡片文件改名失败");
            }
        } catch (Exception ex) {
            Log.w(TAG, "保存卡片失败", ex);
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) { }
        }
    }
}
