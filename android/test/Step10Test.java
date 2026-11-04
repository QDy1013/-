package com.nopalou;

import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Step10：考试卡规则"补充规则"的验收用例（见 docs/考试卡规则.md 末尾"补充规则"）。
 *
 * 覆盖点：
 *   - 带"请带上…"的考试通知，bring 要抓到"学生证"；
 *   - "教二305""实验楼305""教一102"这类"楼名+房号"写法要整段进 place；
 *   - 辅导员也在可信发送者里（TEACHER_HINTS 含"辅导员"）；
 *   - 含 看完/看一下/看看/复习好/预习/背完 的"考前看资料"消息按普通待办走，不出 exam 卡；
 *   - 带"范围"但没有这些词的仍出 exam 卡；
 *   - "考试周大家注意休息"没有具体考试通知，不该出任何卡。
 *
 * 写法沿用 Step9Test：辅助函数照抄过来、不依赖它；bring 用反射读（字段可能还没加）；
 * 每个场景新建一个引擎，全程离线。时间期望不手算，统一用 parseDeadline 同一句文字。
 */
public class Step10Test {

    static int pass = 0, fail = 0;
    static String model;
    static long t0 = 1_766_000_000_000L;
    static int seq = 0;

    static final String PKG = "com.nopalou.replay";
    static final String TEACHER = "王老师";
    static final String COUNSELOR = "张辅导员";
    static final String GROUP = "高等数学2026秋";
    static final String GID = "g-math";

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  ✓ " + name); }
        else { fail++; System.out.println("  ✗ " + name + "   " + detail); }
    }

    static Engine engine() throws Exception {
        Engine e = new Engine(new FileInputStream(model));
        e.setProfile(Engine.Profile.demo());
        return e;
    }

    static Engine.Msg msg(String gid, String gname, String sender, String text, long ts) {
        seq++;
        return new Engine.Msg("m" + seq, gid, gname, sender, text, PKG, ts);
    }

    /** 第 minute 分钟的时间戳。 */
    static long ts(int minute) {
        return t0 + minute * 60_000L;
    }

    /** 反射读 Card.bring：字段现在可能还没加，读不到或为 null 都返回 null。 */
    static String bring(Engine.Card c) {
        if (c == null) return null;
        try {
            Field f = c.getClass().getField("bring");
            Object v = f.get(c);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    static List<Engine.Card> cardsIn(List<Engine.Card> cs, String gid) {
        List<Engine.Card> out = new ArrayList<>();
        for (Engine.Card c : cs) if (gid != null && gid.equals(c.groupId)) out.add(c);
        return out;
    }

    static int countKind(List<Engine.Card> cs, String kind) {
        int n = 0;
        for (Engine.Card c : cs) if (kind.equals(c.kind)) n++;
        return n;
    }

    static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 取列表里第一张 exam 卡，没有则 null（用于查 place / bring）。 */
    static Engine.Card firstExam(List<Engine.Card> cs) {
        for (Engine.Card c : cs) if ("exam".equals(c.kind)) return c;
        return null;
    }

    public static void main(String[] args) throws Exception {
        model = args[0] + "/app/assets/model.tsv";

        // 场景 1：老师发带"@所有人"和"请带上学生证和计算器"的期中考试通知。
        // 测：补充规则里 bring 抓"带/携带/自备"后面的内容 → 恰好 1 张 exam 卡且 bring 含"学生证"。
        System.out.println("== 1. 带请带上学生证的期中考试通知 ==");
        Engine e1 = engine();
        e1.process(msg(GID, GROUP, TEACHER,
                "@所有人 下周三上午9点期中考试，请带上学生证和计算器", ts(0)));
        List<Engine.Card> cards1 = e1.all();
        check("1 恰好 1 张 exam 卡", countKind(cards1, "exam") == 1,
                "exam 卡 " + countKind(cards1, "exam") + " 张，总 " + cards1.size() + " 张");
        String bring1 = bring(firstExam(cards1));
        check("1 bring 含“学生证”", bring1 != null && bring1.contains("学生证"),
                "bring=" + bring1);

        // 场景 2：老师发"教二305"写法（楼号+房号），补充规则说这种也算地点。
        // 测：恰好 1 张 exam 卡，place 整段含"教二305"。
        System.out.println("\n== 2. 教二305 写法也要进 place ==");
        Engine e2 = engine();
        e2.process(msg(GID, GROUP, TEACHER,
                "期末考试12月28日下午2点，教二305，闭卷", ts(10)));
        List<Engine.Card> cards2 = e2.all();
        check("2 恰好 1 张 exam 卡", countKind(cards2, "exam") == 1,
                "exam 卡 " + countKind(cards2, "exam") + " 张，总 " + cards2.size() + " 张");
        Engine.Card c2 = firstExam(cards2);
        check("2 place 含“教二305”", c2 != null && c2.place != null && c2.place.contains("教二305"),
                "place=" + (c2 == null ? "无卡" : c2.place));

        // 场景 3：同样是"楼名+房号"，这次是"实验楼305"。
        // 测：恰好 1 张 exam 卡，place 整段含"实验楼305"。
        System.out.println("\n== 3. 实验楼305 写法也要进 place ==");
        Engine e3 = engine();
        e3.process(msg(GID, GROUP, TEACHER,
                "期末考试12月28日下午2点，实验楼305，闭卷", ts(20)));
        List<Engine.Card> cards3 = e3.all();
        check("3 恰好 1 张 exam 卡", countKind(cards3, "exam") == 1,
                "exam 卡 " + countKind(cards3, "exam") + " 张，总 " + cards3.size() + " 张");
        Engine.Card c3 = firstExam(cards3);
        check("3 place 含“实验楼305”", c3 != null && c3.place != null && c3.place.contains("实验楼305"),
                "place=" + (c3 == null ? "无卡" : c3.place));

        // 场景 4：发送者是辅导员（TEACHER_HINTS 含"辅导员"），也算可信发送者。
        // 测：恰好 1 张 exam 卡，place 整段含"教一102"。
        System.out.println("\n== 4. 辅导员发的考试通知也出卡，教一102 进 place ==");
        Engine e4 = engine();
        e4.process(msg(GID, GROUP, COUNSELOR,
                "周四晚7点英语四级模拟考试在教一102", ts(30)));
        List<Engine.Card> cards4 = e4.all();
        check("4 恰好 1 张 exam 卡", countKind(cards4, "exam") == 1,
                "exam 卡 " + countKind(cards4, "exam") + " 张，总 " + cards4.size() + " 张");
        Engine.Card c4 = firstExam(cards4);
        check("4 place 含“教一102”", c4 != null && c4.place != null && c4.place.contains("教一102"),
                "place=" + (c4 == null ? "无卡" : c4.place));

        // 场景 5：含"看完"的"考前把资料看完"消息，补充规则说按普通待办走、不出 exam 卡。
        // 测（只检查这一点）：没有 exam 卡。
        System.out.println("\n== 5. 含“看完”的考前看资料消息不出 exam 卡 ==");
        Engine e5 = engine();
        e5.process(msg(GID, GROUP, TEACHER,
                "期中考试范围已发在群文件，周五9点前看完", ts(40)));
        check("5 没有 exam 卡", countKind(e5.all(), "exam") == 0,
                "exam 卡 " + countKind(e5.all(), "exam") + " 张");

        // 场景 6：带"范围"但没有 看完/看一下/看看/… 这类词，补充规则说仍要出 exam 卡。
        // 测：恰好 1 张 exam 卡。
        System.out.println("\n== 6. 带“范围”但没“看完”类词仍出 exam 卡 ==");
        Engine e6 = engine();
        e6.process(msg(GID, GROUP, TEACHER,
                "期中考试周三上午9点，范围第一到五章", ts(50)));
        check("6 恰好 1 张 exam 卡", countKind(e6.all(), "exam") == 1,
                "exam 卡 " + countKind(e6.all(), "exam") + " 张，总 " + e6.all().size() + " 张");

        // 场景 7："考试周大家注意休息"没有具体考试通知，不该出任何卡。
        // 测：一张卡都没有。
        System.out.println("\n== 7. 考试周提醒不出任何卡 ==");
        Engine e7 = engine();
        e7.process(msg(GID, GROUP, TEACHER, "考试周大家注意休息", ts(60)));
        check("7 没有任何卡", e7.all().isEmpty(), "出了 " + e7.all().size() + " 张");

        System.out.println("\n通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}
