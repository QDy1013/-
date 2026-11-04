package com.nopalou;

import java.io.FileInputStream;
import java.lang.reflect.*;
import java.util.*;

/**
 * Step11：验收两类规则，只写测试、不动业务代码。
 *
 * 一、重复推送去重：
 *   同一群里，消息文字去掉空格后与该群已有某张卡的 sourceText 去掉空格后完全一样，
 *   就视为重复推送、不再出新卡。用 D1~D5 覆盖：同群重发、跨群、考试卡、删卡后、
 *   以及"首次确实出卡"的前提检查。
 *
 * 二、排序 ranked(int limit, long now)：
 *   该方法现在还不存在（当前只有 ranked(int)），因此用反射 getMethod("ranked", int.class, long.class)
 *   调用；找不到即把该场景判失败并写明"缺少 ranked(int,long)"，保证本文件现在能编译。
 *   不走 process，直接 new Engine.Card() 填字段后 add 进 engine.all()。
 *
 * 写法照抄 Step10Test：辅助函数（check / engine / msg / ts / 反射读字段 / countKind / cardsIn / eq）
 * 都自带一份、不依赖 Step10Test；每个场景新建一个引擎，全程离线；main 里 args[0] 拼 model 路径。
 */
public class Step11Test {

    static int pass = 0, fail = 0;
    static String model;
    static long t0 = 1_766_000_000_000L;
    static int seq = 0;

    static final String PKG = "com.nopalou.replay";
    static final String TEACHER = "王老师";
    static final String GROUP = "高等数学2026秋";       // 主群
    static final String GID = "g-math";
    static final String GROUP2 = "大学物理课程群";       // 另一群（跨群场景用）
    static final String GID2 = "g-phy";

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  ✓ " + name); }
        else { fail++; System.out.println("  ✗ " + name + "   " + detail); }
    }

    /** 每个场景新建一个引擎，离线加载 model、设 demo profile。 */
    static Engine engine() throws Exception {
        Engine e = new Engine(new FileInputStream(model));
        e.setProfile(Engine.Profile.demo());
        return e;
    }

    static Engine.Msg msg(String gid, String gname, String sender, String text, long ts) {
        seq++;
        return new Engine.Msg("m" + seq, gid, gname, sender, text, PKG, ts);
    }

    /** 第 minute 分钟的时间戳：1 分钟 = 60_000ms。 */
    static long ts(int minute) {
        return t0 + minute * 60_000L;
    }

    /** 反射读 Card 的 public 字段；字段不存在或读不到返回 null（写法照抄 Step10 的 bring()）。 */
    static Object field(Engine.Card c, String name) {
        if (c == null) return null;
        try {
            Field f = c.getClass().getField(name);
            return f.get(c);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 用反射读 title 拼成 "A,B,C" 形式，便于比对 ranked 的返回顺序。 */
    static String titles(List<Engine.Card> cs) {
        StringBuilder sb = new StringBuilder();
        if (cs == null) return "<null>";
        for (Engine.Card c : cs) {
            if (sb.length() > 0) sb.append(",");
            Object t = field(c, "title");
            sb.append(t == null ? "?" : t);
        }
        return sb.toString();
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

    /** 造一张卡：直接填 public 字段，不走 process。id 随意、title 用 A~G 便于看顺序。 */
    static Engine.Card mk(String title, String kind, long deadline, boolean done) {
        Engine.Card c = new Engine.Card();
        c.id = "id-" + title;
        c.title = title;
        c.kind = kind;
        c.deadline = deadline;
        c.done = done;
        c.confidence = 1.0;
        c.groupId = GID;
        c.groupName = GROUP;
        c.sender = TEACHER;
        c.sourceText = title;
        return c;
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        model = args[0] + "/app/assets/model.tsv";

        // ============================================================
        // 一、重复推送去重
        // ============================================================

        // D1/D5：同一群内同一条消息 1 分钟后原样重发，应去重、共 1 张卡。
        // D5 是前提检查：必须先确认第一次发送确实出了 1 张卡，去重结论才有意义，
        // 所以先 check 前提、再 check 结果。
        System.out.println("== D1/D5 同群同文重复推送去重 ==");
        Engine eD1 = engine();
        String d1 = "@所有人 周五18点前交实验报告到学习通";
        eD1.process(msg(GID, GROUP, TEACHER, d1, ts(0)));
        check("D5 前提：D1 首次发送后恰好 1 张卡", eD1.all().size() == 1,
                "首次出了 " + eD1.all().size() + " 张");
        eD1.process(msg(GID, GROUP, TEACHER, d1, ts(1)));   // 1 分钟后原样再发
        check("D1 原样重发后仍共 1 张卡", eD1.all().size() == 1,
                "共 " + eD1.all().size() + " 张");

        // D2：同一段文字分别发到两个不同的群，不算"同群重复"，应各出 1 张、共 2 张卡。
        System.out.println("\n== D2 同文跨两个群不去重 ==");
        Engine eD2 = engine();
        String d2 = "@所有人 周五18点前交实验报告到学习通";
        eD2.process(msg(GID, GROUP, TEACHER, d2, ts(0)));
        eD2.process(msg(GID2, GROUP2, TEACHER, d2, ts(0)));
        check("D2 两个群共 2 张卡", eD2.all().size() == 2,
                "共 " + eD2.all().size() + " 张");
        check("D2 主群恰好 1 张", cardsIn(eD2.all(), GID).size() == 1,
                "主群 " + cardsIn(eD2.all(), GID).size() + " 张");
        check("D2 物理群恰好 1 张", cardsIn(eD2.all(), GID2).size() == 1,
                "物理群 " + cardsIn(eD2.all(), GID2).size() + " 张");

        // D3：考试通知原样重发也要去重，结果仍是一张 exam 卡（而不是两张、也不变成别的类型）。
        System.out.println("\n== D3 考试卡重复推送去重 ==");
        Engine eD3 = engine();
        String d3 = "@所有人 下周三上午9点期中考试，教三201";
        eD3.process(msg(GID, GROUP, TEACHER, d3, ts(0)));
        eD3.process(msg(GID, GROUP, TEACHER, d3, ts(1)));
        check("D3 原样重发后共 1 张 exam 卡", countKind(eD3.all(), "exam") == 1,
                "exam " + countKind(eD3.all(), "exam") + " 张，总 " + eD3.all().size() + " 张");
        check("D3 总数仍为 1 张（确实去重）", eD3.all().size() == 1,
                "总 " + eD3.all().size() + " 张");

        // D4：把已经出现的卡删掉（模拟用户标"与我无关"），去重基线随之消失，
        // 再原样发一次应重新出 1 张卡。
        System.out.println("\n== D4 删卡（与我无关）后原样重发 ==");
        Engine eD4 = engine();
        String d4 = "@所有人 周五18点前交实验报告到学习通";
        eD4.process(msg(GID, GROUP, TEACHER, d4, ts(0)));
        check("D4 前提：首次出 1 张卡", eD4.all().size() == 1,
                "首次出了 " + eD4.all().size() + " 张");
        if (!eD4.all().isEmpty()) {
            Engine.Card card = eD4.all().get(0);
            eD4.all().remove(card);                         // 模拟"与我无关"删除
        }
        eD4.process(msg(GID, GROUP, TEACHER, d4, ts(1)));
        check("D4 删卡后原样重发又出 1 张卡", eD4.all().size() == 1,
                "共 " + eD4.all().size() + " 张");

        // ============================================================
        // 二、排序 ranked(int limit, long now)
        //    新方法，当前业务代码里还没有：用反射调用 getMethod("ranked", int.class, long.class)，
        //    找不到（NoSuchMethodException）就把该场景判失败并写明"缺少 ranked(int,long)"，
        //    这样本文件在方法补齐之前也能编译。
        //    规则：先接龙未过期 → 再按时间近的未过期 → 再没时间的 → 再已过期（最近过期的在前）
        //          → 已完成的永远最后。
        // ============================================================
        System.out.println("\n== 二、排序 ranked(limit, now) ==");
        Engine eR = engine();                              // 新引擎，且全程不调 process
        long now = t0;
        long H = 3_600_000L;                               // 1 小时（毫秒）
        eR.all().add(mk("A", "todo",    now + 24 * H, false));   // 未过期，24h 后
        eR.all().add(mk("B", "todo",    now + 2 * H,  false));   // 未过期，2h 后（更近）
        eR.all().add(mk("C", "todo",    now - 24 * H, false));    // 已过期，24h 前
        eR.all().add(mk("D", "todo",    now + H,     true));      // 已完成 done=true
        eR.all().add(mk("E", "todo",    0L,          false));     // 无时间 deadline=0
        eR.all().add(mk("F", "jielong", now + 72 * H, false));    // 接龙，未过期
        eR.all().add(mk("G", "jielong", now - H,     false));     // 接龙，已过期

        java.lang.reflect.Method rankedMethod = null;
        try {
            rankedMethod = eR.getClass().getMethod("ranked", int.class, long.class);
        } catch (NoSuchMethodException ex) {
            check("ranked 场景：存在 public List<Card> ranked(int,long)", false,
                    "缺少 ranked(int,long)");
        }

        if (rankedMethod != null) {
            // 期望顺序：F（接龙未过期）, B（2h 更近）, A（24h）, E（无时间）,
            //          G（最近过期，1h 前）, C（24h 前）, D（已完成，永远最后）
            List<Engine.Card> r10 = (List<Engine.Card>) rankedMethod.invoke(eR, 10, now);
            check("ranked(10,now) 顺序 F,B,A,E,G,C,D",
                    eq("F,B,A,E,G,C,D", titles(r10)),
                    "实际 " + titles(r10));

            // ranked(3, now) 只取前 3 个：F, B, A
            List<Engine.Card> r3 = (List<Engine.Card>) rankedMethod.invoke(eR, 3, now);
            check("ranked(3,now) 取前 3 个 F,B,A",
                    eq("F,B,A", titles(r3)),
                    "实际 " + titles(r3));
        }

        System.out.println("\n通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}
