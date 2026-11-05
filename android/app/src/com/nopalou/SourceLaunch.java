package com.nopalou;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 来源应用启动工具。
 *
 * <p>本类只负责：按包名查询本机已安装的来源应用主入口（Launcher Activity），
 * 并返回一个可直接用于 startActivity 的 Intent。
 *
 * <p>能力边界（重要）：
 * <ul>
 *   <li>只能打开来源应用，不能定位到某条消息、某个群聊或某个会话；</li>
 *   <li>不构造、不拼接任何 deeplink（包括所谓的“群聊 deeplink”）；</li>
 *   <li>不发起网络请求，不申请或新增任何权限；</li>
 *   <li>不读取父目录文件，不获取、不解析用户聊天记录或消息内容。</li>
 * </ul>
 */
final class SourceLaunch {

    /** 允许打开的来源应用包名白名单。 */
    private static final Set<String> ALLOWED_PACKAGES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "com.tencent.mm",              // 微信
                    "com.tencent.mobileqq",        // QQ
                    "com.ss.android.lark",         // 飞书
                    "com.alibaba.android.rimet",   // 钉钉
                    "com.tencent.tim",             // TIM
                    "com.chaoxing.mobile",         // 学习通
                    "com.able.wisdomtree",         // 知到（智慧树）
                    "com.xuetangx.ykt"             // 雨课堂
            )));

    private SourceLaunch() {
        // 工具类，禁止实例化。
    }

    /**
     * 获取来源应用的主入口 Intent。
     *
     * @param context     调用方 Context，用于获取 PackageManager
     * @param packageName 来源应用包名，必须命中白名单
     * @return 已添加 {@link Intent#FLAG_ACTIVITY_NEW_TASK} 的启动 Intent；
     *         包名不在白名单、为空、应用未安装或查询异常时返回 {@code null}
     */
    static Intent intentFor(Context context, String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) {
            return null;
        }
        if (!ALLOWED_PACKAGES.contains(packageName)) {
            return null;
        }

        try {
            PackageManager pm = context.getPackageManager();
            if (pm == null) {
                return null;
            }
            // 仅查询来源应用自身的主入口，不做任何 deeplink 组装。
            Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
            if (launchIntent == null) {
                return null;
            }
            // 从非 Activity 上下文启动时必须带 NEW_TASK。
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return launchIntent;
        } catch (Exception e) {
            // 查询过程出现任何异常都视为拿不到入口，返回 null。
            return null;
        }
    }
}
