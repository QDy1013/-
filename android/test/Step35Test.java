package com.nopalou;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** 自动消息来源、旧存档迁移、移除恢复与来源关键词搜索。 */
public final class Step35Test {
    private static final long NOW = 1_800_000_000_000L;
    private static int failures;
    private static Method appName, packageName;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "✓ " : "✗ ") + name);
        if (!ok) failures++;
    }

    private static String label(Engine.Card c) throws Exception { return (String) appName.invoke(c); }
    private static String pkg(Engine.Card c) throws Exception { return (String) packageName.invoke(c); }

    public static void main(String[] args) throws Exception {
        Engine e;
        try (InputStream weights = Files.newInputStream(Paths.get(args[0], "app/assets/model.tsv"))) {
            e = new Engine(weights);
        }
        e.setProfile(Engine.Profile.demo());
        try {
            appName = Engine.Card.class.getMethod("sourceAppName");
            packageName = Engine.Card.class.getMethod("sourcePackageName");
        } catch (NoSuchMethodException missing) {
            check("卡片提供明确的来源应用和包名", false);
            System.exit(1);
        }
        String[] packages = {"com.tencent.mm", "com.tencent.mobileqq", "com.ss.android.lark",
                "com.alibaba.android.rimet", "com.tencent.tim", "com.nopalou.replay", "demo"};
        String[] names = {"微信", "QQ", "飞书", "钉钉", "TIM", "测试通知", "演示样例"};
        for (int i = 0; i < packages.length; i++) {
            e.process(new Engine.Msg("source-" + i, "opaque-group-" + i, "来源验收群", "王老师",
                    "明天18点前交来源验收报告", packages[i], NOW));
            Engine.Card c = e.all().get(e.all().size() - 1);
            check("自动保留实际来源 " + names[i], packages[i].equals(pkg(c)) && names[i].equals(label(c)));
        }
        check("来源名支持搜索且不混入其他应用的卡", e.queryCards("微信", Engine.CardScope.ALL, 30, NOW).size() == 1
                && "微信".equals(label(e.queryCards("微信", Engine.CardScope.ALL, 30, NOW).get(0))));
        Engine.Card qq = e.all().get(1);
        qq.groupName = "微信消息课程群";
        check("群名不能伪造来源应用", "QQ".equals(label(qq)));
        e.dismissCard(qq, NOW);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        e.saveState(bytes);
        e.loadState(new ByteArrayInputStream(bytes.toByteArray()));
        check("组编号不含包名时，重启仍保留明确来源", e.all().size() == 6
                && "飞书".equals(label(e.all().get(1))) && "com.ss.android.lark".equals(pkg(e.all().get(1))));
        check("已移除的卡也保留来源", e.removedCards().size() == 1
                && "QQ".equals(label(e.removedCards().get(0))) && e.restoreCard(e.removedCards().get(0)));
        String old = "NPL1\nSEQ\t3\nN\t3\nCARD\nid\tc1\nkind\ttodo\ntitle\t旧QQ卡\n"
                + "groupId\tcom.tencent.mobileqq|课程群\nEND\nCARD\nid\tc2\nkind\ttodo\ntitle\t旧微信卡\n"
                + "groupId\tcom.tencent.mm\nEND\nCARD\nid\tc3\nkind\ttodo\ntitle\t微信通知\n"
                + "groupId\tlegacy-unknown\ngroupName\t微信通知群\nEND\n";
        e.loadState(new ByteArrayInputStream(old.getBytes(StandardCharsets.UTF_8)));
        check("旧存档按群编号中的包名恢复来源", "QQ".equals(label(e.all().get(0)))
                && "微信".equals(label(e.all().get(1))));
        check("无法确认的旧来源明确提示", "来源待确认".equals(label(e.all().get(2)))
                && pkg(e.all().get(2)).isEmpty());
        String manifest = Files.readString(Paths.get(args[0], "app/AndroidManifest.xml"));
        boolean queries = true;
        for (int i = 0; i < 5; i++) queries &= manifest.contains("<package android:name=\"" + packages[i] + "\"");
        check("来源图标的包可见性只声明支持的应用", queries && !manifest.contains("QUERY_ALL_PACKAGES"));
        if (failures > 0) System.exit(1);
    }
}
