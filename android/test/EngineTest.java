package com.nopalou;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 端到端自测（不依赖模拟器）：直接喂演示脚本给 Engine，检查卡片结果。
 * 覆盖：三条主线索、接龙未接判断、变更更新、干扰项不误报、时间解析口径。
 */
public class EngineTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  ✓ " + name); }
        else { fail++; System.out.println("  ✗ " + name + "   " + detail); }
    }

    public static void main(String[] args) throws Exception {
        String assets = args.length > 0 ? args[0] : "app/assets";
        String demo = args.length > 1 ? args[1] : "replay/assets/messages.txt";

        Engine eng = new Engine(new FileInputStream(assets + "/model.tsv"));
        eng.setProfile(Engine.Profile.demo());   // 演示脚本是按演示身份（李承宇）写的

        List<String[]> msgs = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(demo), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] p = line.split("\\|", 3);
                if (p.length < 3) continue;
                p[2] = p[2].replace("\\n", "\n");
                msgs.add(p);
            }
        }

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < msgs.size(); i++) {
            String[] m = msgs.get(i);
            eng.process(new Engine.Msg("t" + i, m[0], m[0], m[1], m[2], "test", t0 + i * 1000L));
        }

        System.out.println("\n== 处理结果 ==");
        System.out.println("消息 " + eng.nIn + " 条，丢弃噪声 " + eng.nNoise
                + "，进模型 " + eng.nLevel3 + "，卡片 " + eng.all().size());
        for (Engine.Card c : eng.all()) {
            System.out.println("  · [" + c.kind + "] " + c.title + " | " + c.deadlineText
                    + " | " + (c.channel == null ? "-" : c.channel) + " | " + c.groupName
                    + (c.updated ? " | 已更新" : "") + (c.needsConfirm ? " | 待确认" : ""));
        }

        System.out.println("\n== 功能检查 ==");

        // 1) 三条主线索都在
        boolean hasHw = false, hasJl = false, hasPpt = false, hasRead = false;
        for (Engine.Card c : eng.all()) {
            String s = c.title + c.sourceText;
            if (s.contains("实验报告") && "todo".equals(c.kind)) hasHw = true;
            if ("jielong".equals(c.kind)) hasJl = true;
            if (s.contains("PPT")) hasPpt = true;
            if (s.contains("读书报告")) hasRead = true;
        }
        check("作业待办卡存在", hasHw, "未生成实验报告卡片");
        check("接龙未接提醒存在", hasJl, "未生成接龙卡片");
        check("@我的组队消息成了待办", hasPpt, "未生成 PPT 卡片");
        check("第二门课的读书报告也进了", hasRead, "未生成读书报告卡片");

        // 2) 接龙判断正确
        for (Engine.Card c : eng.all()) {
            if ("jielong".equals(c.kind)) {
                check("接龙识别出'你还没接'", c.title.contains("还没接"), c.title);
                check("接龙人数解析", c.reason.contains("人已接"), c.reason);
                check("接龙截止时间", c.deadline > 0, "截止时间为空");
            }
        }

        // 3) 变更：卡片被更新，且时间落到"下周一 22:00"
        Engine.Card hw = null;
        for (Engine.Card c : eng.all()) {
            if ("todo".equals(c.kind) && c.sourceText.contains("明晚 8 点前交实验报告")) hw = c;
        }
        check("作业卡被'更正'更新而不是新建", hw != null && hw.updated, "未标记 updated");
        if (hw != null && hw.deadline > 0) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(hw.deadline);
            check("更新后的截止是 22:00",
                    c.get(Calendar.HOUR_OF_DAY) == 22 && c.get(Calendar.MINUTE) == 0,
                    "实际 " + c.get(Calendar.HOUR_OF_DAY) + ":" + c.get(Calendar.MINUTE));
            check("更新后的截止是周一", c.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY,
                    "实际 weekday=" + c.get(Calendar.DAY_OF_WEEK));
        }

        // 4) 干扰项不误报
        boolean fp = false;
        String fpText = "";
        for (Engine.Card c : eng.all()) {
            String t = c.sourceText;
            if (t.contains("我明天把实验报告交了") || t.contains("明天一起吃饭")
                    || t.contains("明天停课") || t.contains("周五考什么")) {
                fp = true; fpText = t;
            }
        }
        check("闲聊/讨论/通知没有被当成待办", !fp, "误报：" + fpText);
        check("卡片数量合理（不是全都报）", eng.all().size() <= 6, "卡片过多：" + eng.all().size());

        // 5) 时间解析口径
        System.out.println("\n== 时间解析 ==");
        String[][] cases = {
                {"明晚 8 点前交实验报告", "20:00"},
                {"周五 18 点前提交", "18:00"},
                {"下周三上课前交", "08:00"},
                {"今晚 22 点前报名", "22:00"},
                {"11 月 20 日 18:00 前交", "18:00"},
                {"月底前交班费", "23:59"},
        };
        Calendar base = Calendar.getInstance();
        for (String[] c : cases) {
            Engine.Deadline d = Engine.parseDeadline(c[0], base.getTimeInMillis());
            boolean ok = d.ok();
            String hm = "";
            if (ok) {
                Calendar x = Calendar.getInstance();
                x.setTimeInMillis(d.value);
                hm = String.format("%02d:%02d", x.get(Calendar.HOUR_OF_DAY), x.get(Calendar.MINUTE));
            }
            check("「" + c[0] + "」→ " + c[1], ok && hm.equals(c[1]), "实际 " + (ok ? hm : "无"));
        }
        Engine.Deadline vague = Engine.parseDeadline("尽快交一下材料", base.getTimeInMillis());
        check("「尽快」标待确认而不是猜时间", !vague.ok(), "却给出了 " + vague.value);

        System.out.println("\n通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}
