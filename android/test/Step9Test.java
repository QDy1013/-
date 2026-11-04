package com.nopalou;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Step9：考试卡规则的验收用例（见 docs/考试卡规则.md）。
 *
 * 规则要点：
 *   1. 可信发送者（老师/班长/…）说的、带具体考试时间的陈述句 → 一张 kind="exam" 的卡，
 *      不再另出 todo；title 含考试词，place 沿用 findPlace，bring 取"带/携带/自备"后面的内容；
 *   2. 同学提问、第一人称、含糊时间（另行通知）→ 不出卡；
 *   3. 同群可信发送者说"改到…" → 更新原卡（updated=true），不新建；同学问、别群说 → 什么都不动；
 *   4. 老师"取消" → 删卡；
 *   5. saveState/loadState 要能保住 kind 与 bring。
 *
 * 说明：Card.bring 目前还不存在，测试用反射读取（取不到当 null），因此现在就能编译。
 * 期望时间不手算，统一用 Engine.parseDeadline(同一句文字, 同一个 ts).value 得到。
 * 每个场景都新建引擎，互不影响；时间戳从 t0 起按分钟递增，全程离线。
 */
public class Step9Test {

    static int pass = 0, fail = 0;
    static String model;
    static long t0 = 1_766_000_000_000L;
    static int seq = 0;

    static final String PKG = "com.nopalou.replay";
    static final String TEACHER = "王老师";
    static final String MONITOR = "李班长";
    static final String STUDENT = "陈嘉禾";
    static final String GROUP_MATH = "高等数学2026秋";
    static final String GROUP_PHYS = "大学物理课程群";
    static final String GID_MATH = "g-math";
    static final String GID_PHYS = "g-phys";

    /** 场景 A 的原始考试通知，F/G/H/I 都拿它当"已有考试卡"的前提。 */
    static final String MSG_A = "@所有人 期中考试定在下周三上午9点，地点教三201，带学生证和2B铅笔";
    /** 场景 F/G 的改时间说法。 */
    static final String MSG_F = "期中考试改到下周四上午10点";

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

    /** 第 minute 分钟的时间戳，保证同一场景里消息顺序稳定。 */
    static long ts(int minute) {
        return t0 + minute * 60_000L;
    }

    /** 反射读 Card.bring：字段现在还没加，读不到或为 null 都返回 null。 */
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

    /**
     * 场景 G 的三条变体共用一个流程：先 A，再发一句"改时间"，
     * 检查原卡没被改、A 群卡数仍是 1；若来自别的群，还要求那个群没有 exam 卡。
     */
    static void scenarioG(String tag, int minute, String sender, String gid, String gname, String text)
            throws Exception {
        Engine e = engine();
        e.process(msg(GID_MATH, GROUP_MATH, TEACHER, MSG_A, ts(minute)));
        List<Engine.Card> before = e.all();
        Engine.Card orig = before.isEmpty() ? null : before.get(0);
        check(tag + " 前提：原通知出了 1 张卡", before.size() == 1, "出了 " + before.size() + " 张");
        long oldDeadline = orig == null ? -1 : orig.deadline;

        e.process(msg(gid, gname, sender, text, ts(minute + 1)));

        List<Engine.Card> math = cardsIn(e.all(), GID_MATH);
        check(tag + " A 群仍只有 1 张卡", math.size() == 1,
                "A 群 " + math.size() + " 张，总 " + e.all().size() + " 张");
        Engine.Card now = math.isEmpty() ? null : math.get(0);
        check(tag + " 原卡 deadline 没变", now != null && now.deadline == oldDeadline,
                "现在=" + (now == null ? "无卡" : String.valueOf(now.deadline)) + "，原来=" + oldDeadline);
        check(tag + " 原卡 updated=false", now != null && !now.updated,
                "updated=" + (now != null && now.updated));

        if (!GID_MATH.equals(gid)) {
            int otherExam = countKind(cardsIn(e.all(), gid), "exam");
            check(tag + " 另一群没有 exam 卡", otherExam == 0, "另一群 exam 卡 " + otherExam + " 张");
        }
    }

    public static void main(String[] args) throws Exception {
        model = args[0] + "/app/assets/model.tsv";

        // ---------- A. 老师发期中考试通知 → 恰好一张 exam 卡 ----------
        System.out.println("== A. 老师发期中考试通知 ==");
        Engine eA = engine();
        long tsA = ts(0);
        long expA = Engine.parseDeadline(MSG_A, tsA).value;
        check("A 前提：通知里的时间能解析", expA > 0, "parseDeadline=" + expA);
        eA.process(msg(GID_MATH, GROUP_MATH, TEACHER, MSG_A, tsA));
        List<Engine.Card> a = eA.all();
        Engine.Card ca = a.isEmpty() ? null : a.get(0);
        check("A 恰好出 1 张卡", a.size() == 1, "出了 " + a.size() + " 张");
        check("A kind 为 exam", ca != null && "exam".equals(ca.kind),
                "kind=" + (ca == null ? "无卡" : ca.kind));
        check("A title 含“期中”", ca != null && ca.title != null && ca.title.contains("期中"),
                "title=" + (ca == null ? "无卡" : ca.title));
        check("A deadline 与 parseDeadline 一致", ca != null && ca.deadline == expA,
                "卡上=" + (ca == null ? 0 : ca.deadline) + "，期望=" + expA);
        check("A place 含“教三”", ca != null && ca.place != null && ca.place.contains("教三"),
                "place=" + (ca == null ? "无卡" : ca.place));
        String bringA = bring(ca);
        check("A bring 含“学生证”", bringA != null && bringA.contains("学生证"), "bring=" + bringA);
        check("A bring 含“铅笔”", bringA != null && bringA.contains("铅笔"), "bring=" + bringA);
        check("A 不再另出 todo 卡", countKind(a, "todo") == 0, "todo 卡 " + countKind(a, "todo") + " 张");

        // ---------- B. 班长发小测通知 → 一张 exam 卡 ----------
        System.out.println("\n== B. 班长发小测通知 ==");
        Engine eB = engine();
        eB.process(msg(GID_MATH, GROUP_MATH, MONITOR, "周五下午3点线性代数小测，闭卷", ts(10)));
        List<Engine.Card> b = eB.all();
        check("B 恰好出 1 张卡", b.size() == 1, "出了 " + b.size() + " 张");
        check("B kind 为 exam", b.size() == 1 && "exam".equals(b.get(0).kind),
                "kind=" + (b.isEmpty() ? "无卡" : b.get(0).kind));

        // ---------- C. 答辩通知 → 一张 exam 卡，标题含“答辩” ----------
        System.out.println("\n== C. 答辩通知 ==");
        Engine eC = engine();
        long tsC = ts(20);
        String textC = "毕业设计答辩 12月20日 14:00 在实验楼305";
        long expC = Engine.parseDeadline(textC, tsC).value;
        check("C 前提：答辩时间能解析", expC > 0, "parseDeadline=" + expC);
        eC.process(msg(GID_MATH, GROUP_MATH, TEACHER, textC, tsC));
        List<Engine.Card> c = eC.all();
        Engine.Card cc = c.isEmpty() ? null : c.get(0);
        check("C 恰好出 1 张卡", c.size() == 1, "出了 " + c.size() + " 张");
        check("C kind 为 exam", cc != null && "exam".equals(cc.kind),
                "kind=" + (cc == null ? "无卡" : cc.kind));
        check("C title 含“答辩”", cc != null && cc.title != null && cc.title.contains("答辩"),
                "title=" + (cc == null ? "无卡" : cc.title));

        // ---------- D. 提问 / 第一人称 / 含糊时间 → 一条都不出卡 ----------
        System.out.println("\n== D. 这些消息都不出卡 ==");
        String[][] d = {
                {STUDENT, "周五考什么"},
                {STUDENT, "期末考试几点啊？"},
                {STUDENT, "我周五上午9点考试"},
                {STUDENT, "听说周三下午2点考试"},
                {TEACHER, "期末考试时间另行通知"},
        };
        for (int i = 0; i < d.length; i++) {
            Engine eD = engine();
            eD.process(msg(GID_MATH, GROUP_MATH, d[i][0], d[i][1], ts(30 + i)));
            check("D-" + (i + 1) + " 不出卡：" + d[i][0] + "“" + d[i][1] + "”",
                    eD.all().isEmpty(), "出了 " + eD.all().size() + " 张");
        }

        // ---------- E. 期末大作业（有提交动词）→ 有卡，但不是 exam ----------
        System.out.println("\n== E. 期末大作业仍是普通待办 ==");
        Engine eE = engine();
        eE.process(msg(GID_MATH, GROUP_MATH, TEACHER, "期末大作业周五18点前提交到学习通", ts(40)));
        List<Engine.Card> ec = eE.all();
        check("E 前提：提交类消息本来就出卡", !ec.isEmpty(), "一张卡都没出");
        check("E 这不是考试卡", countKind(ec, "exam") == 0, "出了 " + countKind(ec, "exam") + " 张 exam 卡");

        // ---------- F. 老师改时间 → 更新原卡，不新建 ----------
        System.out.println("\n== F. 老师把期中考试改到下周四 ==");
        Engine eF = engine();
        long tsF1 = ts(50), tsF2 = ts(51);
        eF.process(msg(GID_MATH, GROUP_MATH, TEACHER, MSG_A, tsF1));
        List<Engine.Card> beforeF = eF.all();
        Engine.Card f0 = beforeF.isEmpty() ? null : beforeF.get(0);
        check("F 前提：原通知出了 1 张卡", beforeF.size() == 1, "出了 " + beforeF.size() + " 张");
        String idF0 = f0 == null ? null : f0.id;
        long expF = Engine.parseDeadline(MSG_F, tsF2).value;
        check("F 前提：新时间能解析", expF > 0, "parseDeadline=" + expF);

        eF.process(msg(GID_MATH, GROUP_MATH, TEACHER, MSG_F, tsF2));
        List<Engine.Card> afterF = eF.all();
        Engine.Card f1 = afterF.isEmpty() ? null : afterF.get(0);
        check("F 仍只有 1 张卡（没有新建）", afterF.size() == 1, "出了 " + afterF.size() + " 张");
        check("F 还是原来那张卡", f1 != null && idF0 != null && idF0.equals(f1.id),
                "现在 id=" + (f1 == null ? "无卡" : f1.id) + "，原 id=" + idF0);
        check("F updated=true", f1 != null && f1.updated, "updated=" + (f1 != null && f1.updated));
        check("F deadline 等于新时间的 parseDeadline", f1 != null && f1.deadline == expF,
                "卡上=" + (f1 == null ? 0 : f1.deadline) + "，期望=" + expF);
        check("F kind 仍是 exam", f1 != null && "exam".equals(f1.kind),
                "kind=" + (f1 == null ? "无卡" : f1.kind));

        // ---------- G. 同学问、同学说、别群说 → 原卡一律不动 ----------
        System.out.println("\n== G. 不该改的三种说法 ==");
        scenarioG("G-1 同学问“改了吗”", 60, STUDENT, GID_MATH, GROUP_MATH, "考试改到下周四了吗？");
        scenarioG("G-2 同学自己说改时间", 65, STUDENT, GID_MATH, GROUP_MATH, MSG_F);
        scenarioG("G-3 老师在另一个群说改时间", 70, TEACHER, GID_PHYS, GROUP_PHYS, MSG_F);

        // ---------- H. 老师取消考试 → 卡被删掉 ----------
        System.out.println("\n== H. 老师取消期中考试 ==");
        Engine eH = engine();
        eH.process(msg(GID_MATH, GROUP_MATH, TEACHER, MSG_A, ts(80)));
        check("H 前提：A 先出了一张 exam 卡", countKind(eH.all(), "exam") == 1,
                "exam 卡 " + countKind(eH.all(), "exam") + " 张，总 " + eH.all().size() + " 张");
        eH.process(msg(GID_MATH, GROUP_MATH, TEACHER, "期中考试取消", ts(81)));
        check("H 取消后 cards 为空", eH.all().isEmpty(), "还剩 " + eH.all().size() + " 张");

        // ---------- I. 保存 / 恢复 → kind、place、bring、deadline 都不丢 ----------
        System.out.println("\n== I. 保存再恢复 ==");
        Engine eI = engine();
        eI.process(msg(GID_MATH, GROUP_MATH, TEACHER, MSG_A, ts(90)));
        List<Engine.Card> ic = eI.all();
        Engine.Card i0 = ic.isEmpty() ? null : ic.get(0);
        check("I 前提：保存前有 1 张 exam 卡",
                ic.size() == 1 && i0 != null && "exam".equals(i0.kind),
                "卡数=" + ic.size() + "，kind=" + (i0 == null ? "无卡" : i0.kind));
        String placeI = i0 == null ? null : i0.place;
        String bringI = bring(i0);
        long deadlineI = i0 == null ? 0 : i0.deadline;

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        eI.saveState(bos);
        Engine eI2 = engine();
        eI2.loadState(new ByteArrayInputStream(bos.toByteArray()));
        List<Engine.Card> ic2 = eI2.all();
        Engine.Card i1 = ic2.isEmpty() ? null : ic2.get(0);
        check("I 恢复后仍有 1 张卡", ic2.size() == 1, "恢复出 " + ic2.size() + " 张");
        check("I 恢复后 kind 仍是 exam", i1 != null && "exam".equals(i1.kind),
                "kind=" + (i1 == null ? "无卡" : i1.kind));
        check("I 恢复后 place 不变", i1 != null && eq(placeI, i1.place),
                "现在=" + (i1 == null ? "无卡" : i1.place) + "，原来=" + placeI);
        check("I 恢复后 bring 不变", eq(bringI, bring(i1)),
                "现在=" + bring(i1) + "，原来=" + bringI);
        check("I 恢复后 deadline 不变", i1 != null && i1.deadline == deadlineI,
                "现在=" + (i1 == null ? "无卡" : String.valueOf(i1.deadline)) + "，原来=" + deadlineI);

        System.out.println("\n通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}
