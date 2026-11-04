package com.nopalou;

import java.io.FileInputStream;
import java.util.Arrays;
import java.util.Map;

/**
 * 修复第 3 步「档案可设置」的验收用例（主控编写，执行端不可修改）。
 * 测纯 Java 部分：Engine.Profile 的存取、按档案判断"跟我有关"、接龙是否已接、课程群规则。
 */
public class Step3Test {

    static int pass = 0, fail = 0;
    static String model;
    static long t0 = 1_766_000_000_000L;
    static int seq = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  ✓ " + name); }
        else { fail++; System.out.println("  ✗ " + name + "   " + detail); }
    }

    static Engine engine(Engine.Profile p) throws Exception {
        Engine e = new Engine(new FileInputStream(model));
        if (p != null) e.setProfile(p);
        return e;
    }

    static Engine.Msg msg(String group, String sender, String text) {
        seq++;
        return new Engine.Msg("s" + seq, group, group, sender, text, "test", t0 + seq * 1000L);
    }

    static Engine.Profile zhang() {
        Engine.Profile p = new Engine.Profile();
        p.name = "张三";
        p.aliases = new java.util.ArrayList<>(Arrays.asList("三三"));
        p.studentId = "2024001";
        p.groupNo = "第5组";
        p.className = "软工2402";
        p.courses = new java.util.ArrayList<>(Arrays.asList("高等数学"));
        return p;
    }

    static String dump(Engine.Profile p) {
        return p.name + "|" + p.aliases + "|" + p.studentId + "|" + p.groupNo + "|" + p.className + "|" + p.courses;
    }

    static int cards(Engine e) { return e.all().size(); }

    static double atMe(Engine e, String text) {
        Map<String, Double> f = e.featurize(msg("某群", "某人", text));
        Double v = f.get("M:at_me");
        return v == null ? 0 : v;
    }

    public static void main(String[] args) throws Exception {
        model = args[0] + "/app/assets/model.tsv";

        System.out.println("== 档案本身 ==");
        Engine fresh = engine(null);
        check("新建引擎默认是空档案（不再内置任何人）", fresh.getProfile() != null && fresh.getProfile().isEmpty(),
                "默认档案：" + (fresh.getProfile() == null ? "null" : dump(fresh.getProfile())));
        Engine.Profile demo = Engine.Profile.demo();
        check("演示档案仍是李承宇（演示模式用）", "李承宇".equals(demo.name) && "20230137".equals(demo.studentId)
                && demo.courses.contains("数据库系统") && demo.courses.contains("计算机网络"), dump(demo));

        Engine.Profile odd = zhang();
        odd.name = "张,三|\n\"引号\"\\";
        odd.aliases.add("带,逗号");
        odd.courses.add("数据结构（含|竖线）");
        Engine.Profile back = Engine.Profile.decode(odd.encode());
        check("档案 encode/decode 原样还原（含逗号、竖线、换行、引号）", dump(back).equals(dump(odd)), "还原为 " + dump(back));
        boolean emptyOk;
        try {
            emptyOk = Engine.Profile.decode("").isEmpty() && Engine.Profile.decode(null).isEmpty();
        } catch (Exception ex) { emptyOk = false; }
        check("decode 空串或 null 得到空档案、不抛异常", emptyOk, "抛异常或不为空");
        Engine setNull = engine(null);
        setNull.setProfile(null);
        check("setProfile(null) 当作空档案", setNull.getProfile() != null && setNull.getProfile().isEmpty(), "getProfile 为 null 或不为空");

        System.out.println("\n== 按档案判断跟我有关 ==");
        Engine z = engine(zhang());
        check("@本名 → 1.0", z.relevance("@张三 PPT 你来做", "小组群") == 1.0, "" + z.relevance("@张三 PPT 你来做", "小组群"));
        check("别名 → 1.0", z.relevance("三三你来做一下", "小组群") == 1.0, "" + z.relevance("三三你来做一下", "小组群"));
        check("学号 → 1.0", z.relevance("2024001 还没交", "班群") == 1.0, "" + z.relevance("2024001 还没交", "班群"));
        check("小组 → 0.9", z.relevance("第5组周五答辩", "班群") == 0.9, "" + z.relevance("第5组周五答辩", "班群"));
        check("班级（忽略空格）→ 0.85", z.relevance("软工 2402 的同学注意", "年级群") == 0.85, "" + z.relevance("软工 2402 的同学注意", "年级群"));
        check("课程（群名）→ 0.8", z.relevance("周五交作业", "高等数学课程群") == 0.8, "" + z.relevance("周五交作业", "高等数学课程群"));
        check("别人的名字不算我 → 0.4", z.relevance("@李承宇 PPT 你来做", "小组群") == 0.4, "" + z.relevance("@李承宇 PPT 你来做", "小组群"));
        check("@本名 时 at_me=1", atMe(z, "@张三 展示 PPT 你来做哈") == 1.0, "" + atMe(z, "@张三 展示 PPT 你来做哈"));
        check("@别人 时 at_me=0", atMe(z, "@李承宇 展示 PPT 你来做哈") == 0.0, "" + atMe(z, "@李承宇 展示 PPT 你来做哈"));

        Engine none = engine(null);
        check("空档案：普通一句话不会被当成跟我有关（防 contains(\"\") 恒真）",
                none.relevance("随便说一句", "某群") == 0.4, "" + none.relevance("随便说一句", "某群"));
        check("空档案：普通一句话 at_me=0", atMe(none, "随便说一句") == 0.0, "" + atMe(none, "随便说一句"));
        Engine blanks = engine(null);
        Engine.Profile bp = new Engine.Profile();
        bp.name = "王五";
        bp.aliases = new java.util.ArrayList<>(Arrays.asList("", "  "));
        bp.courses = new java.util.ArrayList<>(Arrays.asList(""));
        blanks.setProfile(bp);
        check("档案里的空白别名/课程被忽略", blanks.relevance("随便说一句", "某群") == 0.4, "" + blanks.relevance("随便说一句", "某群"));

        System.out.println("\n== 接龙是否已接 ==");
        String head = "#接龙\n体测报名（周五18点截止）\n1. 周若曦\n2. 郑浩然\n";
        Engine j1 = engine(zhang());
        j1.process(msg("计科 2301 班群", "陈嘉禾", head + "3. 张三"));
        check("名单里有本名 → 不出'还没接'卡", cards(j1) == 0, "出了 " + cards(j1) + " 张");
        Engine j2 = engine(zhang());
        j2.process(msg("计科 2301 班群", "陈嘉禾", head + "3. 2024001"));
        check("名单里有学号 → 不出'还没接'卡", cards(j2) == 0, "出了 " + cards(j2) + " 张");
        Engine j3 = engine(zhang());
        j3.process(msg("计科 2301 班群", "陈嘉禾", head + "3. 孙语桐"));
        check("名单里没有我 → 出'还没接'卡", cards(j3) == 1 && j3.all().get(0).title.contains("还没接"),
                cards(j3) == 0 ? "没出卡" : j3.all().get(0).title);
        Engine j4 = engine(null);
        j4.process(msg("计科 2301 班群", "陈嘉禾", head + "3. 孙语桐"));
        check("空档案：不会因为空名字被当成'已接'", cards(j4) == 1, "出了 " + cards(j4) + " 张");

        System.out.println("\n== 课程群规则 ==");
        String hw = "@所有人 周五 18 点前交第三章作业到学习通";
        for (Engine.Profile p : new Engine.Profile[]{null, Engine.Profile.demo(), zhang()}) {
            String who = p == null ? "空档案" : p.name;
            Engine c1 = engine(p);
            c1.process(msg("高等数学课程群", "张老师", hw));
            check(who + "：课程群里老师 @所有人 布置作业 → 出卡", cards(c1) >= 1, "出了 " + cards(c1) + " 张");
        }
        Engine c2 = engine(Engine.Profile.demo());
        c2.process(msg("大学物理课程群", "王老师", "周五 18 点前交实验报告到学习通"));
        check("不在我课程表里的课程群，老师布置作业也出卡", cards(c2) >= 1, "出了 " + cards(c2) + " 张");
        Engine c3 = engine(Engine.Profile.demo());
        c3.process(msg("大学物理课程群", "陈嘉禾", "周五 18 点前交实验报告吗"));
        check("不在我课程表里的课程群，同学随口一问不出卡", cards(c3) == 0, "出了 " + cards(c3) + " 张");

        System.out.println("\n通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}
