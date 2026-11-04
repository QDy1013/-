package com.nopalou;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 端侧三级流水线（对应方案书第 6、7 节）。
 * 全程不联网：规则在本地，分类器是本地权重，时间解析是本地规则。
 *
 * 与 Python 端严格对齐：同一套 8 维手工特征、同一份字符 2-gram 权重、
 * 同一套时间解析口径。Python 端训练，这里只做推理。
 */
public class Engine {
    private static final String TIME_UNCERTAIN = "时间表达不清楚，建议核对原文";

    // ---------- 常量（与 Python 端一致）----------
    static final String[] LABELS = {"action", "notice", "discussion", "chatter"};
    static final String[] ACTION_WORDS = {"交", "提交", "填", "签到", "报名", "接龙", "回复",
            "领取", "打卡", "上传", "传", "下载", "参加", "准备", "完成", "补交", "缴费", "选课"};
    static final String[] TIME_HINTS = {"前", "点", "分", "今天", "明天", "后天", "今晚", "明晚",
            "周", "星期", "月", "日", "截止", "之前", "上课"};
    static final String[] LINK_FILE = {"http", "www.", ".com", "[文件]", "[图片]", "[链接]", ".pdf", ".docx", ".zip"};
    static final String[] IDENTITY = {"学号", "班级", "班", "组", "选课", "选修", "同学", "课代表",
            "班委", "数据库", "计算机网络", "操作系统", "数据结构", "软件工程", "概率论"};
    static final String[] CHANNELS = {"学习通", "雨课堂", "课程网站", "QQ 群文件", "邮箱", "教务系统",
            "课代表处", "微信转账", "班级接龙", "群接龙", "腾讯会议", "问卷", "飞书", "钉钉"};
    static final String[] VERBS = {"交", "提交", "上传", "传", "填", "报名", "签到", "接龙", "回复",
            "领取", "打卡", "参加", "完成", "补交", "缴费", "选课", "下载"};
    static final Set<String> ACKS = new HashSet<>(Arrays.asList(
            "收到", "好的", "1", "+1", "收到收到", "好嘞", "ok", "OK", "明白", "嗯嗯", "好"));
    static final String[] TEACHER_HINTS = {"老师", "辅导员", "班长", "课代表", "社长", "主任"};
    // 考试/日程卡（规则见 docs/考试卡规则.md）
    static final String[] EXAM_WORDS = {"考试", "测验", "小测", "笔试", "机考", "答辩"};
    // 单独的"期中""期末"要再看有没有提交动词："期末大作业周五前交"仍是普通待办
    static final String[] TERM_WORDS = {"期中", "期末"};
    static final String[] SUBMIT_WORDS = {"交", "提交", "上传"};
    // 准备类词（补充规则第二条）：说的是"考前把资料看完/预习"之类，不是考试通知。
    // 含这些词就不出考试卡，按普通待办走；带"范围"但没有这些词仍出卡。
    static final String[] EXAM_PREP = {"看完", "看一下", "看看", "复习好", "预习", "背完"};
    // 改时间：这些词 detectRevision 不认，要在撤销/更正分支之前单独处理
    static final String[] RESCHEDULE_WORDS = {"改到", "推迟到", "延期到", "提前到", "调整到", "挪到"};
    static final Pattern P_EXAM_NEW_TIME = Pattern.compile("改到|推迟到|延期到|提前到|调整到|挪到|改为|改成|调整为");
    // 提问词：带这些就不当"改时间"，只当学生在问
    static final String[] ASK_WORDS = {"吗", "？", "?", "呢", "什么", "几点", "哪"};
    // "带/携带/自备"后面到下一个标点之间的内容，就是考试要带的东西
    static final Pattern P_BRING = Pattern.compile("(?:携带|自备|带)[ \\t]*[：:]?[ \\t]*([^，。；;！!\\r\\n]*)");
    static final int EXAM_TITLE_MAX = 24;
    static final String[] VAGUE = {"尽快", "近期", "这两天", "最近", "过几天", "有空", "随时",
            "抓紧", "尽早", "改天", "之后", "等通知", "另行通知"};
    static final String[] REVISE_WORDS = {"更正", "改为", "改成", "调整为", "不作数", "说错了", "重新发一下"};
    static final String[] REVOKE_WORDS = {"作废", "取消", "忽略", "发错", "算了不", "撤销该", "撤回该"};
    static final String[] DEICTIC_WORDS = {"上条", "上一条", "刚才", "之前", "上面", "前面", "那条", "这条"};
    // 判断"说的是哪件事"时忽略的套话，避免 "@所有人""大家" 这类字样把无关消息也算成命中
    static final String[] SUBJECT_NOISE = {"所有人", "全体", "大家", "同学们", "各位", "同学",
            "注意", "记得", "务必", "一下", "那个", "这个", "消息"};
    static final long REVISION_WINDOW = 30 * 60_000L;   // 指代词只在原消息 30 分钟内有效
    static final int BURST_PROMOTE = 5;
    static final long BURST_WINDOW_MS = 10 * 60_000L;

    static final Pattern P_CLOCK = Pattern.compile("(\\d{1,2})\\s*[:：]\\s*(\\d{2})");
    // 钟点支持中文数字：八点、十点半、两点、七点；分钟紧跟在钟点后，"半"=30 分
    static final Pattern P_HOUR = Pattern.compile("(\\d{1,2}|[零〇一二两三四五六七八九十]{1,3})\\s*[点时]"
            + "(?:\\s*(半)|\\s*(\\d{1,2}|[零〇一二两三四五六七八九十]{1,2})\\s*分)?");
    // 月日写法："1月10日" / "11 月 20 日" / "12/31" / "2026-1-10"，以及 "M.D"（9.14、10.4、12.31）。
    // "M.D"（M=1~12、D=1~31）默认就当月日；只有当它紧挨着前面是"习题/第/练习/例"，
    // 或紧挨着后面跟"页/题/章/节/小节"时，才当章节编号忽略。"2-3页" 仍然不算日期。
    static final Pattern P_ABS_CN = Pattern.compile("(?:(\\d{4})\\s*年\\s*)?(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]?");
    static final Pattern P_ABS_NUM = Pattern.compile(
            "(?:(\\d{4})\\s*[/\\-]\\s*)?(?<!\\d)(\\d{1,2})\\s*[/\\-.]\\s*(\\d{1,2})(?!\\d)"
                    + "(?!\\s*[页题章节张个分秒周])");
    static final String[] MD_STOP_PRE = {"习题", "练习", "第", "例"};
    static final String[] MD_STOP_POST = {"小节", "页", "题", "章", "节"};
    // 时段词：只看钟点前面最近的一个，避免"明早8点交，晚了不收"被"晚了"带偏
    static final String[] PERIOD_PM = {"下午", "傍晚", "晚上", "晚间", "夜里", "晚"};
    static final String[] PERIOD_AM = {"上午", "早上", "早晨", "凌晨", "一大早", "明早", "今早", "早"};
    static final String[] PERIOD_NOON = {"中午", "正午"};
    static final Pattern P_WEEK = Pattern.compile("(下下|下|本|这)?\\s*(?:周|星期|礼拜)\\s*(?:周|星期|礼拜)?\\s*([一二三四五六日天七])");
    static final Pattern P_WEEKEND = Pattern.compile("(这|本|下)?\\s*周末");
    static final Pattern P_MONTHEND = Pattern.compile("(这|本|下)?\\s*个?\\s*月底");
    static final Pattern P_DAYONLY = Pattern.compile("(这个月|本月|当月)?\\s*(\\d{1,2})\\s*[日号]");
    // "中午前/中午之前"后面没有具体几点时，按当天 12:00 处理（"上午前"等不动）
    static final Pattern P_NOON_BEFORE = Pattern.compile("中午\\s*(?:之前|前)");
    static final Pattern P_TITLE_TIME_WORDS = Pattern.compile(
            "大后天|后天|明天|明日|明晚|明早|今天|今日|今晚|今早|当天|上午|早上|早晨|凌晨|中午|正午|下午|傍晚|晚上|晚间|夜里");
    static final Pattern P_ATALL = Pattern.compile("@\\s*所有人|@\\s*全体");
    static final Pattern P_SPLIT = Pattern.compile("[，。；;\\n！!？?]");
    // 点号后紧跟一两位数字时按月日处理，例如“3.23 22:00 前报名”。
    static final Pattern P_NUMLINE = Pattern.compile(
            "(?m)^[ \\t]*(\\d{1,3})[ \\t]*(?:\\.(?!\\d{1,2}(?!\\d))|[、)])[ \\t]*(\\S[^\\r\\n]*)$");
    static final Pattern P_TITLE = Pattern.compile("[（(]([^）)]*)[）)]");

    // ---- 标题清理用的公共正则（findAction 与 fallbackTitle 共用，与 Python clean_title 一致）----
    // 时段词里补了单字"早""晚"，但它们必须紧跟数字 + 点/时才删，
    // 所以整段写成「时段词? 数字 点/时」，不会误删"早自习"。
    static final String TITLE_PERIOD =
            "(?:凌晨|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|今晚|明晚|今早|明早|早|晚)?";
    static final Pattern P_CLEAN_HOUR = Pattern.compile(
            TITLE_PERIOD + "\\s*(\\d{1,2}|[一二三四五六七八九十]{1,3})\\s*[点时]\\s*"
                    + "(?:半|\\d{0,2}\\s*分?)\\s*(?:之前|以前|前|截止)?");
    static final Pattern P_CLEAN_CLOCK = Pattern.compile(
            TITLE_PERIOD + "\\s*(\\d{1,2})\\s*[:：]\\s*(\\d{2})\\s*(?:之前|以前|前|截止)?");
    // "本周内/这周内/下周"后面的"内"和"前/之前"一样整段删掉
    static final Pattern P_CLEAN_WEEK = Pattern.compile(
            "(下|本|这)(周|星期|礼拜)[一二三四五六日天]?\\s*(?:之前|以前|前|截止|内)?");
    // "本月底/月底/月末/本月内/月内"（含可选"前/之前"）
    static final Pattern P_CLEAN_MONTH = Pattern.compile(
            "(?:这|本|下)?\\s*个?\\s*月\\s*(?:底|末|内)\\s*(?:之前|以前|前)?");
    static final Pattern P_CLEAN_DAY = Pattern.compile(
            "(?:今天|明天|后天|明晚|今晚|大后天|本周|这周|下周|周[一二三四五六日天]"
                    + "|星期[一二三四五六日天]|\\d{1,2}\\s*[月.]\\s*\\d{1,2}\\s*[日号]?|月底|周末)\\s*"
                    + "(?:凌晨|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里)?\\s*(?:之前|以前|前|截止)?");
    // 句首孤立的时段词（如"晚上交作业"）。单字"早/晚"不放进来，避免删掉"早自习"。
    static final Pattern P_CLEAN_LEAD = Pattern.compile(
            "^(?:凌晨|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里)\\s*(?:之前|以前|前|截止)?");
    static final Pattern P_CLEAN_PREFIX = Pattern.compile(
            "^((?:请务必|请|大家|同学们|各位同学|各位|全体同学|全体|同学|记得|务必|注意|于)\\s*)+");
    // 兜底标题取第一个标点子句（不含冒号，冒号前往往是"作业："这类前缀）
    static final Pattern P_FALLBACK_SPLIT = Pattern.compile("[，。；！？,;!?]");

    // ---------- 数据 ----------
    public static class Msg {
        public String id, groupId, groupName, sender, text, pkg;
        public long ts;
        Msg(String id, String groupId, String groupName, String sender, String text, String pkg, long ts) {
            this.id = id; this.groupId = groupId; this.groupName = groupName;
            this.sender = sender; this.text = text; this.pkg = pkg; this.ts = ts;
        }
    }

    public static class Card {
        public String id, kind, title, channel, place, groupId, groupName, sender, sourceId, sourceText;
        String titleOverride;
        String lastRevisionText;
        public String bring;           // 考试卡专用：要带的东西（"带学生证和2B铅笔" → 学生证和2B铅笔）
        public String deadlineText = "待确认";
        public long deadline;          // 0 = 待确认
        public long sourceTs;          // 原消息时间，用来判断"刚才那条"是否还在 30 分钟内
        public String jielongKey;      // 接龙标题（去掉时间括注），同群同标题只保留一张卡
        public boolean needsConfirm, updated;
        public double confidence;
        public String reason = "";
        public List<String> context = new ArrayList<>();
        public boolean done;

        public String displayTitle() {
            return titleOverride == null ? title : titleOverride;
        }

        public long calendarStart() {
            return deadline == 0 || "exam".equals(kind) ? deadline : deadline - 3600_000L;
        }
    }

    /**
     * 我的档案：用户在设置页填写，引擎只读。
     * 默认空档案，不内置任何人；演示身份只出现在 demo() 里。
     */
    public static class Profile {
        public String name;              // 本名，如"张三"
        public List<String> aliases;     // 别名/昵称
        public String studentId;         // 学号
        public String groupNo;           // 小组，如"第5组"
        public String className;         // 班级，如"软工2402"
        public List<String> courses;     // 我的课程

        public Profile() {
            aliases = new ArrayList<>();
            courses = new ArrayList<>();
        }

        public boolean isEmpty() {
            return blank(name) && blank(studentId) && blank(groupNo) && blank(className)
                    && clean(aliases).isEmpty() && clean(courses).isEmpty();
        }

        /** 文本格式：每行 "key<TAB>值"，值用 esc/unesc 转义，列表项一行一条。 */
        public String encode() {
            StringBuilder b = new StringBuilder();
            b.append("NAME\t").append(esc(name)).append('\n');
            b.append("SID\t").append(esc(studentId)).append('\n');
            b.append("GROUP\t").append(esc(groupNo)).append('\n');
            b.append("CLASS\t").append(esc(className)).append('\n');
            for (String a : nonNull(aliases)) b.append("ALIAS\t").append(esc(a)).append('\n');
            for (String c : nonNull(courses)) b.append("COURSE\t").append(esc(c)).append('\n');
            return b.toString();
        }

        /** 与 encode 对称；空串 / null 得到空档案，不抛异常。 */
        public static Profile decode(String s) {
            Profile p = new Profile();
            if (s == null) return p;
            for (String line : s.split("\n")) {
                if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                int t = line.indexOf('\t');
                if (t < 0) continue;
                String k = line.substring(0, t);
                String v = unesc(line.substring(t + 1));
                if ("NAME".equals(k)) p.name = v;
                else if ("SID".equals(k)) p.studentId = v;
                else if ("GROUP".equals(k)) p.groupNo = v;
                else if ("CLASS".equals(k)) p.className = v;
                else if ("ALIAS".equals(k)) p.aliases.add(v == null ? "" : v);
                else if ("COURSE".equals(k)) p.courses.add(v == null ? "" : v);
            }
            return p;
        }

        /** 演示用档案（只给演示脚本 / 演示模式）。 */
        public static Profile demo() {
            Profile p = new Profile();
            p.name = "李承宇"; p.aliases = new ArrayList<>(Arrays.asList("承宇", "小李", "李同学")); p.studentId = "20230137";
            p.groupNo = "第3组"; p.className = "计科2301"; p.courses = new ArrayList<>(Arrays.asList("数据库系统", "计算机网络"));
            return p;
        }
    }

    static class Deadline {
        long value; boolean hasTime; String conf; boolean expired;
        Deadline(long v, boolean h, String c) { value = v; hasTime = h; conf = c; }
        boolean ok() { return value > 0 && !"none".equals(conf); }
        static Deadline none() { return new Deadline(0, false, "none"); }
    }

    // ---------- 状态 ----------
    final double[] intercept = new double[4];
    final List<Card> cards = new ArrayList<>();
    long seq;                                                // 已发出的最大卡片编号，单调递增
    final Map<String, List<Pending>> candidates = new HashMap<>();
    final Map<String, long[]> senderStat = new HashMap<>();   // group|sender -> {total, notify}
    final Map<String, List<String>> recent = new HashMap<>();

    public int nIn, nNoise, nLevel3, nPromoted;
    public SelectedListener listener;
    Profile profile = new Profile();

    public interface SelectedListener { void onCardsChanged(); }

    /** 设置我的档案；null 当作空档案（不内置任何人）。 */
    public void setProfile(Profile p) {
        profile = p == null ? new Profile() : p;
    }

    public Profile getProfile() { return profile; }

    // ---------- 档案读取（空串一律忽略，避免 contains("") 恒真）----------
    static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    static List<String> nonNull(List<String> v) { return v == null ? new ArrayList<String>() : v; }

    static List<String> clean(List<String> v) {
        List<String> out = new ArrayList<>();
        for (String s : nonNull(v)) if (!blank(s)) out.add(s.trim());
        return out;
    }

    List<String> meNames() {
        List<String> out = new ArrayList<>();
        if (!blank(profile.name)) out.add(profile.name.trim());
        out.addAll(clean(profile.aliases));
        return out;
    }

    String meSid() { return blank(profile.studentId) ? "" : profile.studentId.trim(); }
    String meGroup() { return blank(profile.groupNo) ? "" : profile.groupNo.trim(); }
    String meClass() { return blank(profile.className) ? "" : profile.className.trim(); }

    String[] meCourses() {
        List<String> c = clean(profile.courses);
        return c.toArray(new String[0]);
    }

    static String stripSpaces(String s) { return s == null ? "" : s.replace(" ", ""); }

    static class Pending {
        Msg m; boolean carded; int burst;
        Pending(Msg m) { this.m = m; }
    }

    public Engine(InputStream weightsTsv) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(weightsTsv, "UTF-8"));
        String line;
        while ((line = br.readLine()) != null) {
            if (line.isEmpty()) continue;
            if (line.startsWith("#labels")) continue;
            if (line.startsWith("#intercept")) {
                String[] p = line.split("\t");
                String[] v = p[1].split(",");
                for (int i = 0; i < 4 && i < v.length; i++) intercept[i] = Double.parseDouble(v[i]);
                continue;
            }
            int t = line.lastIndexOf('\t');
            if (t <= 0) continue;
            String key = line.substring(0, t);
            String[] v = line.substring(t + 1).split(",");
            double[] w = new double[4];
            for (int i = 0; i < 4 && i < v.length; i++) w[i] = Double.parseDouble(v[i]);
            multi.put(key, w);
        }
        br.close();
    }

    final Map<String, double[]> multi = new HashMap<>();

    // ---------- 特征 ----------
    static boolean containsAny(String s, String[] arr) {
        for (String a : arr) if (s.contains(a)) return true;
        return false;
    }

    static String stripPunct(String s) {
        return s.replaceAll("[\\s\\p{Punct}，。、；：！？…—·（）《》\"'“”‘’]", "");
    }

    public static boolean isNoise(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return true;
        String core = stripPunct(t);
        if (core.isEmpty()) return true;
        if (ACKS.contains(core)) return true;
        if (core.matches("[0-9+\\-]+")) return true;
        if (core.matches("(哈|h|H|2|3|笑|2333)+")) return true;
        if (t.contains("撤回了一条消息") || t.contains("撤回一条消息")) return true;
        return core.length() <= 2 && ("？?。…额哦嗯啊".contains(core));
    }

    double notifier(String gid, String sender) {
        long[] v = senderStat.get(gid + "|" + sender);
        if (v == null || v[0] < 3) return 0;
        return (v[1] / (double) v[0] >= 0.30) ? 1 : 0;
    }

    void observe(String gid, String sender, boolean notify) {
        long[] v = senderStat.get(gid + "|" + sender);
        if (v == null) { v = new long[]{0, 0}; senderStat.put(gid + "|" + sender, v); }
        v[0]++;
        if (notify) v[1]++;
    }

    Map<String, Double> featurize(Msg m) {
        Map<String, Double> f = new HashMap<>();
        String t = m.text;
        boolean atMe = false;
        for (String n : meNames()) if (t.contains(n)) atMe = true;
        if (t.contains("@我")) atMe = true;
        f.put("M:at_me", atMe ? 1.0 : 0.0);
        f.put("M:at_all", P_ATALL.matcher(t).find() ? 1.0 : 0.0);
        f.put("M:has_time", containsAny(t, TIME_HINTS) ? 1.0 : 0.0);
        f.put("M:has_action", containsAny(t, ACTION_WORDS) ? 1.0 : 0.0);
        f.put("M:has_link_file", containsAny(t, LINK_FILE) ? 1.0 : 0.0);
        f.put("M:sender_notifier", notifier(m.groupId, m.sender));
        boolean ident = containsAny(t, IDENTITY);
        String myGroup = meGroup();
        if (!myGroup.isEmpty() && t.contains(myGroup)) ident = true;
        String myClass = meClass();
        if (!myClass.isEmpty() && stripSpaces(t).contains(stripSpaces(myClass))) ident = true;
        for (String e : meCourses()) if (t.contains(e)) ident = true;
        f.put("M:has_identity", ident ? 1.0 : 0.0);
        f.put("M:reply_burst", 0.0);          // 线上判断当刻，未来的"收到"还没出现
        // 交互项
        if (f.get("M:has_time") > 0 && f.get("M:has_action") > 0) f.put("X:time_action", 1.0);
        if (f.get("M:has_time") > 0 && f.get("M:has_action") > 0 && f.get("M:sender_notifier") > 0)
            f.put("X:time_action_notifier", 1.0);
        if (atMe && f.get("M:has_action") > 0) f.put("X:atme_action", 1.0);
        // 文本 2-gram
        String s = stripPunct(t);
        for (int i = 0; i + 1 < s.length(); i++) f.put("T:" + s.substring(i, i + 2), 1.0);
        return f;
    }

    double[] scores(Map<String, Double> f) {
        double[] sc = intercept.clone();
        for (Map.Entry<String, Double> e : f.entrySet()) {
            double[] w = multi.get(e.getKey());
            if (w == null) continue;
            for (int i = 0; i < 4; i++) sc[i] += w[i] * e.getValue();
        }
        return sc;
    }

    static int argmax(double[] a) {
        int k = 0;
        for (int i = 1; i < a.length; i++) if (a[i] > a[k]) k = i;
        return k;
    }

    // ---------- 时间归一化 ----------
    /** 单个中文字符转数字；不是数字返回 -1。 */
    static int cnDigit(char c) {
        if (c == '〇') return 0;
        if (c == '两') return 2;
        return "零一二三四五六七八九".indexOf(c);
    }

    /** 中文数字转整数（"十"=10、"二十三"=23、"两"=2）；认不出返回 -1。 */
    static int cn2int(String s) {
        if (s == null || s.isEmpty()) return -1;
        if (s.matches("\\d+")) return Integer.parseInt(s);
        if (s.length() == 1) {
            if (s.charAt(0) == '十') return 10;
            return cnDigit(s.charAt(0));
        }
        int shi = s.indexOf('十');
        if (shi < 0) return -1;
        int hi = shi == 0 ? 1 : cnDigit(s.charAt(0));
        int lo = shi == s.length() - 1 ? 0 : cnDigit(s.charAt(s.length() - 1));
        if (hi < 0 || lo < 0) return -1;
        return hi * 10 + lo;
    }

    static long at(Calendar c, int hh, int mm) {
        Calendar x = (Calendar) c.clone();
        x.set(Calendar.HOUR_OF_DAY, hh);
        x.set(Calendar.MINUTE, mm);
        x.set(Calendar.SECOND, 0);
        x.set(Calendar.MILLISECOND, 0);
        return x.getTimeInMillis();
    }

    static long addDays(long ts, int days) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTimeInMillis();
    }

    static int weekday(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        int w = c.get(Calendar.DAY_OF_WEEK);   // 1=Sun
        return (w + 5) % 7;                     // 0=Mon
    }

    /** 钟点前面最近出现的时段词；没有就返回 null。 */
    static String periodBefore(String t, int pos) {
        int best = -1;
        String hit = null;
        String[][] sets = {PERIOD_PM, PERIOD_AM, PERIOD_NOON};
        for (String[] set : sets) {
            for (String w : set) {
                int i = t.lastIndexOf(w, Math.max(0, pos - 1));
                if (i < 0 || i + w.length() > pos) continue;
                if (i > best) { best = i; hit = w; }
            }
        }
        return hit;
    }

    /** 时段修正：晚/晚上→+12，"中午12点"→12:00，上午/早上不改。 */
    static int adjustHour(int hh, String period) {
        if (period == null) return hh;
        for (String w : PERIOD_PM) if (w.equals(period)) return hh < 12 ? hh + 12 : hh;
        for (String w : PERIOD_NOON) if (w.equals(period)) return hh < 11 ? 12 : hh;
        return hh;
    }

    /** 绝对日期；返回 {年(0=没写), 月, 日}，不是日期返回 null。 */
    static int[] absDate(String t) {
        int bestAt = -1;
        int[] hit = null;
        Matcher cn = P_ABS_CN.matcher(t);
        while (cn.find()) {
            int[] d = dateOf(cn);
            if (d != null) { bestAt = cn.start(); hit = d; break; }
        }
        Matcher num = P_ABS_NUM.matcher(t);
        while (num.find()) {
            if (isSectionNumber(t, num.start(), num.end())) continue;
            int[] d = dateOf(num);
            if (d != null) {
                if (bestAt < 0 || num.start() < bestAt) { hit = d; bestAt = num.start(); }
                break;
            }
        }
        return hit;
    }

    /**
     * "M.D" 紧挨着前面是"习题/第/练习/例"（中间的空格不算间隔），或紧挨着后面跟"页/题/章/节/小节"，
     * 说明是章节/页码编号而不是日期。
     */
    static boolean isSectionNumber(String t, int start, int end) {
        int i = start;
        while (i > 0 && Character.isWhitespace(t.charAt(i - 1))) i--;
        for (String w : MD_STOP_PRE) {
            int from = i - w.length();
            if (from >= 0 && t.regionMatches(from, w, 0, w.length())) return true;
        }
        int j = end;
        while (j < t.length() && Character.isWhitespace(t.charAt(j))) j++;
        for (String w : MD_STOP_POST) {
            if (t.regionMatches(j, w, 0, w.length())) return true;
        }
        return false;
    }

    static int[] dateOf(Matcher m) {
        int mo = Integer.parseInt(m.group(2)), dd = Integer.parseInt(m.group(3));
        if (mo < 1 || mo > 12 || dd < 1 || dd > 31) return null;
        int yy = m.group(1) != null ? Integer.parseInt(m.group(1)) : 0;
        return new int[]{yy, mo, dd};
    }

    public static Deadline parseDeadline(String text, long send) {
        if (text == null || text.isEmpty()) return Deadline.none();
        String t = text.replace("：", ":");
        for (String v : VAGUE) if (t.contains(v)) return Deadline.none();

        boolean hasTime = false;
        boolean rollover = false;       // "24点/24:00" = 次日 0 点，日期要 +1 天
        int hh = -1, mm = 0;
        Matcher mc = P_CLOCK.matcher(t);
        if (mc.find()) {
            hh = Integer.parseInt(mc.group(1));
            mm = Integer.parseInt(mc.group(2));
            if (hh == 24 && mm == 0) {
                hh = 0; mm = 0; rollover = true;
            } else if (hh > 23 || mm > 59) {
                return Deadline.none();
            } else {
                hh = adjustHour(hh, periodBefore(t, mc.start()));
            }
            hasTime = true;
        } else {
            Matcher mh = P_HOUR.matcher(t);
            if (mh.find()) {
                int v = cn2int(mh.group(1));
                if (v < 0 || v > 24) return Deadline.none();
                if (mh.group(2) != null) {
                    mm = 30;
                } else if (mh.group(3) != null) {
                    mm = cn2int(mh.group(3));
                    if (mm < 0 || mm > 59) return Deadline.none();
                } else {
                    mm = 0;
                }
                if (v == 24) {
                    if (mm != 0) return Deadline.none();
                    hh = 0; mm = 0; rollover = true;
                } else {
                    hh = adjustHour(v, periodBefore(t, mh.start()));
                }
                hasTime = true;
            }
        }

        // "中午前/中午之前"没有具体钟点 → 当天 12:00
        if (!hasTime && P_NOON_BEFORE.matcher(t).find()) {
            hh = 12; mm = 0; hasTime = true;
        }

        long base = 0; boolean high = false;
        int[] ad = absDate(t);
        if (ad != null) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(send);
            int year = ad[0] > 0 ? ad[0] : c.get(Calendar.YEAR);
            c.set(year, ad[1] - 1, ad[2], 0, 0, 0);
            c.set(Calendar.MILLISECOND, 0);
            // Calendar 会把 2 月 30 日顺延成 3 月 2 日，回读对不上就当没写日期
            if (c.get(Calendar.MONTH) == ad[1] - 1 && c.get(Calendar.DAY_OF_MONTH) == ad[2]) {
                // 只写月日、且比发送时间早 60 天以上 → 说的是明年
                if (ad[0] <= 0 && c.getTimeInMillis()
                        < sendCalendar(send).getTimeInMillis() - 60L * 86400000L) {
                    c.add(Calendar.YEAR, 1);
                }
                base = c.getTimeInMillis(); high = true;
            }
        }
        if (base == 0) {
            Matcher md = P_DAYONLY.matcher(t);
            if (md.find()) {
                int dd = Integer.parseInt(md.group(2));
                if (dd >= 1 && dd <= 31) {
                    Calendar c = Calendar.getInstance();
                    c.setTimeInMillis(send);
                    int month = c.get(Calendar.MONTH);
                    int year = c.get(Calendar.YEAR);
                    c.set(year, month, dd, 0, 0, 0);
                    c.set(Calendar.MILLISECOND, 0);
                    base = c.getTimeInMillis();
                    if (base < at(sendCalendar(send), 0, 0) && md.group(1) == null) {
                        month++;
                        if (month > 11) { month = 0; year++; }
                        c.set(year, month, dd, 0, 0, 0);
                        base = c.getTimeInMillis();
                    }
                    high = true;
                }
            }
        }
        if (base == 0) {
            String[] words = {"大后天", "后天", "明天", "明日", "明晚", "明早", "今天", "今日", "今晚", "今早", "当天"};
            int[] deltas = {3, 2, 1, 1, 1, 1, 0, 0, 0, 0, 0};
            for (int i = 0; i < words.length; i++) {
                if (t.contains(words[i])) {
                    Calendar c = sendCalendar(send);
                    c.add(Calendar.DAY_OF_MONTH, deltas[i]);
                    base = at(c, 0, 0); high = true;
                    break;
                }
            }
        }
        if (base == 0) {
            Matcher mw = P_WEEK.matcher(t);
            if (mw.find()) {
                int wd = "一二三四五六日天七".indexOf(mw.group(2).charAt(0));
                if (wd == 6) wd = 6;
                if (wd >= 0) {
                    String pre = mw.group(1);
                    Calendar c = sendCalendar(send);
                    int cur = weekday(send);
                    int delta;
                    if ("下下".equals(pre)) delta = 14 - cur + wd;
                    else if ("下".equals(pre)) delta = 7 - cur + wd;
                    else if ("本".equals(pre) || "这".equals(pre)) delta = wd - cur;
                    else delta = (wd >= cur) ? (wd - cur) : (7 - cur + wd);
                    c.add(Calendar.DAY_OF_MONTH, delta);
                    base = at(c, 0, 0); high = true;
                }
            }
        }
        if (base == 0) {
            Matcher mw = P_WEEKEND.matcher(t);
            if (mw.find()) {
                int cur = weekday(send);
                int delta = ("下".equals(mw.group(1))) ? (6 - cur + 7) : (6 - cur);
                Calendar c = sendCalendar(send);
                c.add(Calendar.DAY_OF_MONTH, delta);
                base = at(c, 0, 0);
            }
        }
        if (base == 0) {
            Matcher mm2 = P_MONTHEND.matcher(t);
            if (mm2.find()) {
                Calendar c = sendCalendar(send);
                c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH));
                base = at(c, 0, 0);
            }
        }
        // "24点"是当天结束/次日 0 点：日期整体后移一天
        if (base != 0 && rollover) base = addDays(base, 1);
        if (base != 0 && !hasTime && t.contains("上课前")) {
            hh = 8; mm = 0; hasTime = true;
        }
        if (base == 0 && !hasTime) return Deadline.none();

        if (base == 0) {                    // 只有时刻
            if (rollover) {
                // "24点"只有时刻：直接按次日 0 点，不再做"已过就顺延"的判断
                return new Deadline(at(sendCalendar(addDays(send, 1)), 0, 0), true, "medium");
            }
            long cand = at(sendCalendar(send), hh, mm);
            if (cand <= send) cand = at(sendCalendar(addDays(send, 1)), hh, mm);
            return new Deadline(cand, true, "medium");
        }
        if (!hasTime) {
            long cand = at(sendCalendar(base), 23, 59);
            return new Deadline(cand, false, high ? "high" : "medium");
        }
        long cand = at(sendCalendar(base), hh, mm);
        boolean expired = cand < send;
        return new Deadline(cand, true, expired ? "low" : (high ? "high" : "medium"));
    }

    static Calendar sendCalendar(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    // ---------- 抽取 ----------
    static String findChannel(String t) {
        for (String c : CHANNELS) if (t.contains(c)) return c;
        Matcher m = Pattern.compile("(?:交|提交|上传|发)到\\s*([^\\s，。；、]{2,8})").matcher(t);
        if (m.find()) return m.group(1);
        return null;
    }

    static String findPlace(String t) {
        // "教三201""三教201"两种说法都认
        Matcher m = Pattern.compile("(三教|二教|一教|四教|五教|教三|教二|教一|教四|教五|实验楼|图书馆|体育馆|报告厅|线上|腾讯会议)[^，。；\\n]{0,8}").matcher(t);
        if (m.find()) return m.group(0).trim();
        return null;
    }

    /**
     * 命中的考试词；没有返回 null。
     * 单独的"期中/期末"只在消息里没有提交动词时才算考试（"期末大作业…提交"仍是普通待办）。
     * 含准备类词（看完/看一下/看看/复习好/预习/背完）时说的是考前准备，不是考试通知，
     * 直接返回 null"没命中考试词"，让它走原来的普通待办流程（docs 补充规则第二条）。
     */
    static String examHit(String t) {
        if (t == null) return null;
        // 准备类词优先判断：命中就不算考试，和提交动词一样走普通待办
        if (containsAny(t, EXAM_PREP)) return null;
        for (String w : EXAM_WORDS) if (t.contains(w)) return w;
        if (containsAny(t, SUBMIT_WORDS)) return null;
        for (String w : TERM_WORDS) if (t.contains(w)) return w;
        return null;
    }

    /** 考试名称和时间分开显示，避免改期后标题仍留有原来的时刻。 */
    static String examTitle(String t, String hit) {
        for (String part : P_SPLIT.split(t)) {
            String s = part.trim();
            int index = s.indexOf(hit);
            if (index < 0) continue;
            String subject = P_ATALL.matcher(s.substring(0, index)).replaceAll(" ");
            for (Pattern pattern : new Pattern[]{P_ABS_CN, P_ABS_NUM, P_DAYONLY, P_WEEK, P_WEEKEND,
                    P_MONTHEND, P_CLOCK, P_HOUR, P_TITLE_TIME_WORDS}) {
                subject = pattern.matcher(subject).replaceAll(" ");
            }
            subject = subject.trim().replaceFirst("^(?:举行|进行|将于|定于|于)\\s*", "").trim();
            if (subject.length() + hit.length() > EXAM_TITLE_MAX) {
                subject = subject.substring(0, EXAM_TITLE_MAX - hit.length()).trim();
            }
            return subject + hit;
        }
        return hit;
    }

    /** 要带的东西："带学生证和2B铅笔" → 学生证和2B铅笔；没有就 null。 */
    static String findBring(String t) {
        Matcher m = P_BRING.matcher(t == null ? "" : t);
        if (!m.find()) return null;
        String s = m.group(1).trim();
        return s.isEmpty() ? null : s;
    }

    /** 去掉子句里的时间表达、称呼前缀、渠道后缀，供 findAction / 兜底标题共用。 */
    static String cleanTitle(String clause) {
        String s = clause == null ? "" : clause;
        // 时段词 + 钟点（含"半"）+ 可选后缀"之前/以前/前/截止"，整段一起删
        s = P_CLEAN_HOUR.matcher(s).replaceAll("");
        // hh:mm 前面同样可能带时段词，后面同样可能带"之前/前/截止"
        s = P_CLEAN_CLOCK.matcher(s).replaceAll("");
        s = P_CLEAN_WEEK.matcher(s).replaceAll("");
        s = P_CLEAN_MONTH.matcher(s).replaceAll("");
        s = P_CLEAN_DAY.matcher(s).replaceAll("");
        s = P_CLEAN_LEAD.matcher(s).replaceAll("");
        s = s.replaceAll("(上课前|放学前|上课之前)", "");
        s = s.replaceAll("^[一二三四五六日天下本周这\\s]+", "");
        // 去掉渠道后缀
        s = s.replaceAll("(交|提交|上传|发)到[^，。；、]{0,12}", "");
        s = s.replaceAll("[，,]?\\s*(学习通|雨课堂|课程网站|QQ 群文件|邮箱|教务系统|课代表处|微信转账)[^，。；]{0,6}", "");
        s = P_CLEAN_PREFIX.matcher(s).replaceAll("");
        s = s.replaceAll("(啊|哈|呀|哦|啦|吧|喔|哟)+$", "");
        s = s.replaceAll("[，,、]+$", "");
        return s.trim();
    }

    /**
     * 返回 {事项短语, 命中的行动词}。
     * 按标点切子句，挑第一个含行动词且不像闲聊的子句，用 cleanTitle 去掉
     * 时间表达、称呼前缀和渠道后缀。清理后太短时，退到第一个非空子句
     * （它通常是"作业：…习题"这种事项名，时间在后面的子句里）。
     */
    static String[] findAction(String text) {
        if (text == null) return new String[]{null, null};
        List<String> clauses = new ArrayList<>();
        for (String c : P_SPLIT.split(text)) {
            c = c.trim();
            if (!c.isEmpty()) clauses.add(c);
        }
        String best = null, verb = null;
        int bestI = -1;
        for (int i = 0; i < clauses.size(); i++) {
            String clause = clauses.get(i);
            // 排除"我明天交"这类第一人称陈述（是讨论，不是给你的通知）
            if (clause.startsWith("我") || clause.startsWith("俺") || clause.startsWith("咱")) continue;
            String v = null;
            for (String vb : VERBS) if (clause.contains(vb)) { v = vb; break; }
            if (v == null) continue;
            best = clause; bestI = i; verb = v;
            break;
        }
        if (best == null) return new String[]{null, null};
        String s = cleanTitle(best);
        if (s.length() < 3) {
            // 前一个子句（第一个非空子句，且不是被选中的那个）同样清理后当标题
            String prev = bestI > 0 ? cleanTitle(clauses.get(0)) : "";
            if (!prev.isEmpty()) return new String[]{prev, verb};
            // 没有可用前句：沿用旧办法（<2 才退回子句原文）
            if (s.length() < 2) return new String[]{best, verb};
        }
        return new String[]{s, verb};
    }

    static boolean isJielong(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (!t.contains("接龙")) return false;    // 只有编号行的作业清单不算接龙
        if (t.contains("#接龙")) return true;
        return P_NUMLINE.matcher(t).find();       // 光说"接龙"没名单的，不当接龙
    }

    String[] jielongInfo(String text) {   // {title, joined("1"/"0"), rosterSize}
        String title = null;
        for (String line : text.split("\\n")) {
            if (line.trim().isEmpty() || line.trim().startsWith("#接龙")) continue;
            Matcher mt = P_TITLE.matcher(line);
            title = mt.find() ? line.substring(0, mt.start()).trim() : line.trim();
            break;
        }
        if (title == null || title.isEmpty()) title = "未命名接龙";
        List<String> roster = new ArrayList<>();
        Matcher m = P_NUMLINE.matcher(text);
        while (m.find()) {
            String body = m.group(2).trim();
            String name = body.split("[\\s（(：:]")[0];
            if (!name.isEmpty()) roster.add(name);
        }
        List<String> names = meNames();
        String sid = meSid();
        boolean joined = false;
        for (String r : roster) {
            for (String n : names) if (r.contains(n)) joined = true;
            if (!sid.isEmpty() && r.contains(sid)) joined = true;
        }
        return new String[]{title, joined ? "1" : "0", String.valueOf(roster.size())};
    }

    // ---------- 主流程 ----------
    public String ruleLabel(Map<String, Double> f, String text, String sender) {
        boolean hasTime = f.get("M:has_time") > 0, hasAction = f.get("M:has_action") > 0;
        boolean atMe = f.get("M:at_me") > 0, atAll = f.get("M:at_all") > 0;
        boolean notifier = f.get("M:sender_notifier") > 0, teacher = containsAny(sender, TEACHER_HINTS);
        boolean asking = text.contains("吗") || text.contains("？") || text.contains("?") || text.contains("呢");
        boolean firstPerson = text.trim().startsWith("我") || text.trim().startsWith("俺")
                || text.trim().startsWith("咱们");
        if (firstPerson && hasAction) return "discussion";
        if (hasAction && hasTime && !firstPerson && !asking) {
            if (atMe || atAll || notifier || teacher) return "action";
        }
        if (hasAction && teacher && !firstPerson && !asking && text.trim().length() <= 40) {
            if (containsAny(text, new String[]{"交", "提交", "填", "报名", "签到", "上传", "领"})) return "action";
        }
        return null;
    }

    public void process(Msg m) {
        nIn++;
        String t = m.text == null ? "" : m.text;
        if (isNoise(t)) {
            nNoise++;
            observe(m.groupId, m.sender, false);
            remember(m);
            if (ACKS.contains(t.trim())) trackAck(m);
            return;
        }

        // ---------- 重复推送去重 ----------
        // 同一群里若已有某张卡的原文（去空格后）与本条消息完全相同，说明是同一条消息
        // 被重复推送：直接丢弃，不出新卡、不更新已有卡、也不 remember。
        // 只比较同群的卡：不同群同文字仍各出一张；卡被删掉后基线消失，再发可重新出卡。
        String flatText = stripSpaces(t);
        for (Card c : cards) {
            if (c.sourceText == null) continue;                  // sourceText 可能为 null，判空跳过
            boolean sameGroup = m.groupId == null
                    ? c.groupId == null : m.groupId.equals(c.groupId);
            if (!sameGroup) continue;
            // 只认 30 分钟内的重复推送：隔很多天再发同一句话（如每周的"交周报"）是新通知
            if (flatText.equals(stripSpaces(c.sourceText)) && Math.abs(m.ts - c.sourceTs) <= 30 * 60_000L) return;
        }

        Map<String, Double> f = featurize(m);
        int idx = argmax(scores(f));
        String label = LABELS[idx];
        String rule = ruleLabel(f, t, m.sender);
        if (rule != null) label = rule;
        observe(m.groupId, m.sender, "action".equals(label) || "notice".equals(label));

        double rel = relevance(t, m.groupName);
        String rev = detectRevision(t);

        // 考试变更先按考试名称定位，避免通用待办的关键词把同群另一场考试选中。
        String examWord = examHit(t);
        if (examWord != null && (containsAny(t, RESCHEDULE_WORDS)
                || (rev != null && containsAny(t, EXAM_WORDS)))) {
            applyExamRevision(m, t, examWord, rev);
            remember(m);
            return;
        }

        if (rev != null) {
            Card target = revisionTarget(m, t);
            if (target != null) {
                if ("revoke".equals(rev)) {
                    cards.remove(target);
                    notifyChanged();
                    remember(m);
                    return;
                }
                Deadline d = parseDeadline(t, m.ts);
                if (d.ok()) {
                    target.deadline = d.value;
                    target.deadlineText = humanDeadline(d.value);
                    target.needsConfirm = false;
                    target.updated = true;
                    target.lastRevisionText = t;
                    notifyChanged();
                    remember(m);
                    return;
                }
            }
            // 对不上任何卡：撤销/更正只对"说得清指的是哪条"的消息生效，
            // 不能顺势把这句"作废/更正"当成新待办再建一张卡
            remember(m);
            return;
        }

        if (isJielong(t)) {
            String[] jl = jielongInfo(t);
            Card same = null;
            for (Card c : cards) {
                if ("jielong".equals(c.kind) && m.groupId != null && m.groupId.equals(c.groupId)
                        && jl[0] != null && jl[0].equals(c.jielongKey)) { same = c; break; }
            }
            if ("1".equals(jl[1])) {
                if (same != null) {
                    cards.remove(same);
                    notifyChanged();
                }
                remember(m);
                return;          // 已接过：撤掉提醒卡，既不是待办也不是闲聊
            }
            Deadline d = parseDeadline(t, m.ts);
            if (same != null) {
                same.title = "接龙：" + jl[0] + "（你还没接）";
                same.reason = jl[2] + " 人已接，名单里没有你";
                same.sourceText = t;
                same.sourceId = m.id;
                same.sourceTs = m.ts;
                if (d.ok()) {
                    same.deadline = d.value;
                    same.deadlineText = humanDeadline(d.value);
                    same.needsConfirm = false;
                }
                notifyChanged();
                remember(m);
                return;          // 同群同标题只更新原来那张卡
            }
            Card c = new Card();
            c.id = newCardId();
            c.kind = "jielong";
            c.title = "接龙：" + jl[0] + "（你还没接）";
            c.jielongKey = jl[0];
            c.deadline = d.ok() ? d.value : 0;
            c.deadlineText = humanDeadline(c.deadline);
            c.needsConfirm = c.deadline == 0;
            c.channel = "群接龙";
            c.groupId = m.groupId;
            c.groupName = m.groupName;
            c.sender = m.sender;
            c.sourceId = m.id;
            c.sourceText = t;
            c.sourceTs = m.ts;
            c.reason = jl[2] + " 人已接，名单里没有你";
            c.confidence = 0.95;
            c.context = new ArrayList<>(recent.getOrDefault(m.groupId, new ArrayList<>()));
            cards.add(c);
            remember(m);
            notifyChanged();
            return;
        }

        // ---------- 考试/日程卡（规则见 docs/考试卡规则.md）----------
        // 放在"接龙之后、按分类标签分流之前"：分类器判成通知/闲聊的考试通知也照样出卡
        // （下面 notice 分支会直接丢消息），且不再另出一张 todo 卡。
        if (examWord != null) {
            Deadline ed = parseDeadline(t, m.ts);
            String sender = m.sender == null ? "" : m.sender;
            boolean trusted = containsAny(sender, TEACHER_HINTS) || P_ATALL.matcher(t).find();
            boolean ask = containsAny(t, ASK_WORDS);
            boolean firstPerson = t.trim().startsWith("我") || t.trim().startsWith("俺")
                    || t.trim().startsWith("咱们");
            if (ed.ok() && trusted && !ask && !firstPerson) {
                Card c = new Card();
                c.id = newCardId();
                c.kind = "exam";
                c.title = examTitle(t, examWord);
                c.deadline = ed.value;
                c.deadlineText = humanDeadline(ed.value);
                c.needsConfirm = "low".equals(ed.conf);       // 置信度低才要用户核对
                c.channel = findChannel(t);
                c.place = findPlace(t);
                c.bring = findBring(t);
                c.groupId = m.groupId;
                c.groupName = m.groupName;
                c.sender = m.sender;
                c.sourceId = m.id;
                c.sourceText = t;
                c.sourceTs = m.ts;
                c.confidence = 0.85;
                c.reason = c.needsConfirm ? TIME_UNCERTAIN : "";
                c.context = new ArrayList<>(recent.getOrDefault(m.groupId, new ArrayList<>()));
                cards.add(c);
                remember(m);
                notifyChanged();
                return;
            }
            // 提问 / 第一人称 / 不可信 / 时间含糊（"另行通知"）：考试类消息不出卡
            remember(m);
            return;
        }

        if ("discussion".equals(label) || "chatter".equals(label)) {
            if (f.get("M:has_action") > 0 && f.get("M:has_time") > 0) {
                List<Pending> group = candidates.computeIfAbsent(m.groupId, key -> new ArrayList<>());
                group.removeIf(p -> m.ts - p.m.ts > BURST_WINDOW_MS);
                group.add(new Pending(m));
            }
            remember(m);
            return;
        }

        nLevel3++;
        Deadline d = parseDeadline(t, m.ts);
        if ("notice".equals(label)) {
            remember(m);
            return;                      // ②类只出信息卡，不占待办位
        }
        boolean keepGroup = containsAny(m.sender == null ? "" : m.sender, TEACHER_HINTS)
                || P_ATALL.matcher(t).find();
        if (rel <= 0.4 && m.groupName.contains("课") && !keepGroup
                && !containsAny(m.groupName, meCourses())) {
            remember(m);
            return;                      // 与我无关的课程群广播不打扰
        }
        String[] act = findAction(t);
        Card c = new Card();
        c.id = newCardId();
        c.kind = "todo";
        c.title = act[0] != null ? act[0] : fallbackTitle(t);
        c.deadline = d.ok() ? d.value : 0;
        c.deadlineText = humanDeadline(c.deadline);
        c.needsConfirm = !d.ok() || "low".equals(d.conf);
        c.channel = findChannel(t);
        c.place = findPlace(t);
        c.groupId = m.groupId;
        c.groupName = m.groupName;
        c.sender = m.sender;
        c.sourceId = m.id;
        c.sourceText = t;
        c.sourceTs = m.ts;
        c.confidence = 0.8;
        c.reason = c.needsConfirm ? TIME_UNCERTAIN : "";
        c.context = new ArrayList<>(recent.getOrDefault(m.groupId, new ArrayList<>()));
        cards.add(c);
        remember(m);
        notifyChanged();
    }

    static String detectRevision(String t) {
        for (String w : REVOKE_WORDS) if (t.contains(w)) return "revoke";
        for (String w : REVISE_WORDS) if (t.contains(w)) return "supersede";
        return null;
    }

    Card latestCard(String gid) {
        for (int i = cards.size() - 1; i >= 0; i--) if (gid.equals(cards.get(i).groupId)) return cards.get(i);
        return null;
    }

    /** 从考试词前取名称，略过通知开头的格式词。 */
    private static String examReference(String text, String hit) {
        String reference = hit;
        for (String part : text.split("[，,。；;\\n！!？?：:]")) {
            int index = part.indexOf(hit);
            if (index < 0) continue;
            String prefix = part.substring(0, index).trim().replaceFirst(
                    "^(?:@\\s*(?:所有人|全体)\\s*|(?:关于|更正(?:一下)?|通知|提醒|取消|作废|撤销(?:该)?|撤回(?:该)?|忽略|原定的?|这场|那场)\\s*)+", "");
            String name = stripSpaces(prefix + hit).toLowerCase(Locale.ROOT);
            if (name.length() > reference.length()) reference = name;
        }
        return reference;
    }

    private void applyExamRevision(Msg m, String text, String examWord, String revision) {
        String trimmed = text.trim();
        if (m.groupId == null || containsAny(text, ASK_WORDS)
                || trimmed.startsWith("我") || trimmed.startsWith("俺") || trimmed.startsWith("咱")) return;
        String reference = examReference(text, examWord);
        Card target = null;
        for (Card card : cards) {
            if (!"exam".equals(card.kind) || !m.groupId.equals(card.groupId) || card.sourceText == null) continue;
            if (!stripSpaces(card.sourceText).toLowerCase(Locale.ROOT).contains(reference)) continue;
            if (target != null) return; // 同一群内仍有多个匹配的考试，等待明确通知。
            target = card;
        }
        if (target == null) return;
        String sender = m.sender == null ? "" : m.sender;
        if (!containsAny(sender, TEACHER_HINTS) && !P_ATALL.matcher(text).find()
                && (sender.isEmpty() || !sender.equals(target.sender))) return;
        if ("revoke".equals(revision)) {
            cards.remove(target);
        } else {
            Matcher change = P_EXAM_NEW_TIME.matcher(text);
            int start = 0;
            while (change.find()) start = change.end();
            Deadline deadline = parseDeadline(text.substring(start), m.ts);
            if (!deadline.ok()) return;
            target.deadline = deadline.value;
            target.deadlineText = humanDeadline(deadline.value);
            target.needsConfirm = "low".equals(deadline.conf);
            target.updated = true;
            target.lastRevisionText = text;
        }
        notifyChanged();
    }

    /** 撤销/更正专用来词：出现在任何卡里都说明不了指向哪一条，匹配时排除。 */
    static final String[] REVISION_STOP = {"刚才", "上条", "之前", "那条", "截止", "更正",
            "取消", "作废", "改成", "改为", "一下", "今晚", "明天"};

    /** "刚才"类指代词：只认 30 分钟内发的那条。 */
    static final String[] REVISION_PRONOUN_NEAR = {"刚才", "刚刚", "那条"};
    /** "之前"类指代词：原发送者本人说时不限时间，直接取此人在本群最新的一张卡。 */
    static final String[] REVISION_PRONOUN_FAR = {"之前", "上条", "上面", "前面"};

    /**
     * 找这条撤销/更正消息真正指向的那张卡：只在同一群里、从新到旧找。
     * 发送者要是原发送者或老师一类的人，且消息对得上事项（共同中文词）；
     * 指代词分两类："刚才"类只认 30 分钟内发的，"之前"类由原发送者本人说时不限时间，
     * 取此人在本群最新的一张卡。对不上就返回 null，调用方因此什么都不做。
     */
    Card revisionTarget(Msg m, String t) {
        String sender = m.sender == null ? "" : m.sender;
        String gid = m.groupId == null ? "" : m.groupId;
        boolean trusted = containsAny(sender, TEACHER_HINTS);
        boolean near = containsAny(t, REVISION_PRONOUN_NEAR);
        boolean far = containsAny(t, REVISION_PRONOUN_FAR);
        List<String> keys = new ArrayList<>();
        for (int i = 0; i + 1 < t.length(); i++) {
            char a = t.charAt(i), b = t.charAt(i + 1);
            if (a < 0x4E00 || a > 0x9FFF || b < 0x4E00 || b > 0x9FFF) continue;
            String g = t.substring(i, i + 2);
            boolean stop = false;
            for (String s : REVISION_STOP) if (s.equals(g)) { stop = true; break; }
            if (!stop) keys.add(g);
        }
        for (int i = cards.size() - 1; i >= 0; i--) {
            Card c = cards.get(i);
            if (!gid.equals(c.groupId)) continue;
            if (!trusted && !sender.equals(c.sender)) continue;
            String blob = (c.title == null ? "" : c.title) + (c.sourceText == null ? "" : c.sourceText);
            boolean hit = false;
            for (String g : keys) {
                if (blob.contains(g)) { hit = true; break; }
            }
            if (!hit && near) {                      // "刚才"类：30 分钟内
                long dt = m.ts - c.sourceTs;
                if (dt >= 0 && dt <= 30 * 60_000L) hit = true;
            }
            if (!hit && far && sender.equals(c.sender)) hit = true;   // "之前"类：原发送者本人，不限时间
            if (hit) return c;
        }
        return null;
    }

    void trackAck(Msg m) {
        List<Pending> group = candidates.get(m.groupId);
        if (group == null) return;
        group.removeIf(p -> m.ts - p.m.ts > BURST_WINDOW_MS);
        for (Pending p : group) {
            if (m.ts <= p.m.ts) continue;
            p.burst++;
            if (p.burst >= BURST_PROMOTE) promote(p);
        }
        group.removeIf(p -> p.carded);
        if (group.isEmpty()) candidates.remove(m.groupId);
    }

    void promote(Pending p) {
        // 护栏：第一人称陈述、反问、"停课/取消"这类，回复潮再大也不是待办
        String t = p.m.text.trim();
        if (t.startsWith("我") || t.startsWith("俺") || t.startsWith("咱们")) return;
        if (t.endsWith("吗") || t.endsWith("呢") || t.endsWith("？") || t.endsWith("?")) return;
        if (t.contains("停课") || t.contains("取消") || t.contains("不用来")) return;
        Deadline d = parseDeadline(p.m.text, p.m.ts);
        String[] act = findAction(p.m.text);
        if (!d.ok() && act[0] == null) return;
        Card c = new Card();
        c.id = newCardId();
        c.kind = "todo";
        c.title = act[0] != null ? act[0] : fallbackTitle(p.m.text);
        c.deadline = d.ok() ? d.value : 0;
        c.deadlineText = humanDeadline(c.deadline);
        c.needsConfirm = !d.ok();
        c.channel = findChannel(p.m.text);
        c.groupId = p.m.groupId;
        c.groupName = p.m.groupName;
        c.sender = p.m.sender;
        c.sourceId = p.m.id;
        c.sourceText = p.m.text;
        c.sourceTs = p.m.ts;
        c.confidence = 0.62;
        c.reason = "回复潮 " + p.burst + " 条确认回复，升为待办";
        c.context = new ArrayList<>(recent.getOrDefault(p.m.groupId, new ArrayList<>()));
        cards.add(c);
        p.carded = true;
        nPromoted++;
        notifyChanged();
    }

    static String fallbackTitle(String t) {
        // 先取第一个标点子句，做与 findAction 相同的时间/称呼清理；太短再退回旧办法
        if (t == null) return null;
        String trimmed = t.trim();
        String first = trimmed.isEmpty() ? "" : trimmed.split("\\r\\n|\\r|\\n")[0];
        String clause = "";
        for (String c : P_FALLBACK_SPLIT.split(first)) {
            c = c.trim();
            if (!c.isEmpty()) { clause = c; break; }
        }
        String cleaned = clause.isEmpty() ? "" : cleanTitle(clause);
        String title = cleaned.length() >= 2 ? cleaned : first;
        return title.length() > 18 ? title.substring(0, 18) + "…" : title;
    }

    void remember(Msg m) {
        List<String> r = recent.computeIfAbsent(m.groupId, k -> new ArrayList<>());
        r.add(m.sender + "：" + (m.text.length() > 40 ? m.text.substring(0, 40) : m.text));
        while (r.size() > 3) r.remove(0);
    }

    /** 跟我有关的程度：全部读当前档案，空字段一律跳过。 */
    double relevance(String t, String groupName) {
        String s = t == null ? "" : t;
        for (String n : meNames()) if (s.contains(n)) return 1.0;
        String sid = meSid();
        if (!sid.isEmpty() && s.contains(sid)) return 1.0;
        String grp = meGroup();
        if (!grp.isEmpty() && s.contains(grp)) return 0.9;
        String cls = meClass();
        if (!cls.isEmpty() && stripSpaces(s).contains(stripSpaces(cls))) return 0.85;
        for (String e : meCourses()) {
            if (s.contains(e) || (groupName != null && groupName.contains(e))) return 0.8;
        }
        return 0.4;
    }

    /** 手动输入的群使用独立编号，避免改期或撤销碰到来自真实通知的同名群。 */
    public boolean importText(String group, String sender, String body, long sentAt) {
        String name = group.trim();
        String text = body.trim();
        if (name.isEmpty() || text.isEmpty() || sentAt <= 0) return false;
        String who = sender.trim();
        process(new Msg("manual-" + UUID.randomUUID(), "manual|" + name, name,
                who.isEmpty() ? "手动粘贴" : who, text, "manual", sentAt));
        return true;
    }

    /** null 保留当前时间，0 将时间标记为待确认。原始标题仍用于匹配群更正。 */
    public boolean editCard(Card card, String title, Long deadline) {
        String label = title.trim();
        if (!cards.contains(card) || label.isEmpty() || (deadline != null && deadline < 0)) return false;
        card.titleOverride = label.equals(card.title) ? null : label;
        if (deadline != null) {
            card.deadline = deadline;
            card.deadlineText = humanDeadline(deadline);
            card.needsConfirm = deadline == 0;
            if (TIME_UNCERTAIN.equals(card.reason)) card.reason = "";
        }
        notifyChanged();
        return true;
    }

    public static String humanDeadline(long ts) {
        if (ts == 0) return "待确认";
        Calendar now = Calendar.getInstance();
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        long d0 = sendCalendar(now.getTimeInMillis()).getTimeInMillis();
        long d1 = sendCalendar(ts).getTimeInMillis();
        int days = (int) Math.round((d1 - d0) / 86400000.0);
        String hm = String.format(Locale.CHINA, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        if (days == 0) return "今天 " + hm;
        if (days == 1) return "明天 " + hm;
        if (days == 2) return "后天 " + hm;
        if (days >= 3 && days <= 6) return "周" + "一二三四五六日".charAt(weekday(ts)) + " " + hm;
        return (c.get(Calendar.MONTH) + 1) + " 月 " + c.get(Calendar.DAY_OF_MONTH) + " 日 " + hm;
    }

    void notifyChanged() {
        if (listener != null) listener.onCardsChanged();
    }

    // ---------- 落盘 ----------
    // 纯文本行格式，字段逐行 "key<TAB>值"，值里的反斜杠/换行/回车/制表符转义，
    // null 写成 \0；卡片以 CARD 开始、END 结束，上下文用 CTX 行重复。全部 UTF-8。

    String newCardId() {
        seq++;
        return "c" + seq;
    }

    public void saveState(OutputStream out) throws Exception {
        Writer w = new OutputStreamWriter(out, "UTF-8");
        w.write("NPL1\n");
        w.write("SEQ\t" + seq + "\n");
        w.write("N\t" + cards.size() + "\n");
        for (Card c : cards) {
            w.write("CARD\n");
            w.write("id\t" + esc(c.id) + "\n");
            w.write("kind\t" + esc(c.kind) + "\n");
            w.write("title\t" + esc(c.title) + "\n");
            w.write("channel\t" + esc(c.channel) + "\n");
            w.write("place\t" + esc(c.place) + "\n");
            w.write("bring\t" + esc(c.bring) + "\n");
            w.write("groupId\t" + esc(c.groupId) + "\n");
            w.write("groupName\t" + esc(c.groupName) + "\n");
            w.write("sender\t" + esc(c.sender) + "\n");
            w.write("sourceId\t" + esc(c.sourceId) + "\n");
            w.write("sourceText\t" + esc(c.sourceText) + "\n");
            w.write("deadlineText\t" + esc(c.deadlineText) + "\n");
            if (c.titleOverride != null) w.write("titleOverride\t" + esc(c.titleOverride) + "\n");
            if (c.lastRevisionText != null) w.write("lastRevisionText\t" + esc(c.lastRevisionText) + "\n");
            w.write("deadline\t" + c.deadline + "\n");
            w.write("sourceTs\t" + c.sourceTs + "\n");
            w.write("jielongKey\t" + esc(c.jielongKey) + "\n");
            w.write("needsConfirm\t" + (c.needsConfirm ? "1" : "0") + "\n");
            w.write("updated\t" + (c.updated ? "1" : "0") + "\n");
            w.write("done\t" + (c.done ? "1" : "0") + "\n");
            w.write("confidence\t" + Double.toString(c.confidence) + "\n");
            w.write("reason\t" + esc(c.reason) + "\n");
            if (c.context == null) {
                w.write("CTXN\t-1\n");
            } else {
                w.write("CTXN\t" + c.context.size() + "\n");
                for (String s : c.context) w.write("CTX\t" + esc(s) + "\n");
            }
            w.write("END\n");
        }
        w.flush();
    }

    public void loadState(InputStream in) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line = br.readLine();
        if (line == null || !"NPL1".equals(line.trim())) {
            cards.clear();                       // 空文件 / 非本格式：当作没有卡片，不抛异常
            return;
        }
        List<Card> loaded = new ArrayList<>();
        long loadedSeq = -1;
        Card cur = null;
        while ((line = br.readLine()) != null) {
            if (line.isEmpty()) continue;
            int t = line.indexOf('\t');
            String key = t < 0 ? line : line.substring(0, t);
            String val = t < 0 ? "" : line.substring(t + 1);
            if ("SEQ".equals(key)) { loadedSeq = toLong(val, loadedSeq); continue; }
            if ("N".equals(key)) continue;
            if ("CARD".equals(key)) { cur = new Card(); continue; }
            if ("END".equals(key)) { if (cur != null) loaded.add(cur); cur = null; continue; }
            if (cur == null) continue;
            if ("id".equals(key)) cur.id = unesc(val);
            else if ("kind".equals(key)) cur.kind = unesc(val);
            else if ("title".equals(key)) cur.title = unesc(val);
            else if ("channel".equals(key)) cur.channel = unesc(val);
            else if ("place".equals(key)) cur.place = unesc(val);
            else if ("bring".equals(key)) cur.bring = unesc(val);
            else if ("groupId".equals(key)) cur.groupId = unesc(val);
            else if ("groupName".equals(key)) cur.groupName = unesc(val);
            else if ("sender".equals(key)) cur.sender = unesc(val);
            else if ("sourceId".equals(key)) cur.sourceId = unesc(val);
            else if ("sourceText".equals(key)) cur.sourceText = unesc(val);
            else if ("deadlineText".equals(key)) cur.deadlineText = unesc(val);
            else if ("titleOverride".equals(key)) cur.titleOverride = unesc(val);
            else if ("lastRevisionText".equals(key)) cur.lastRevisionText = unesc(val);
            else if ("deadline".equals(key)) cur.deadline = toLong(val, 0);
            else if ("sourceTs".equals(key)) cur.sourceTs = toLong(val, 0);
            else if ("jielongKey".equals(key)) cur.jielongKey = unesc(val);
            else if ("needsConfirm".equals(key)) cur.needsConfirm = "1".equals(val.trim());
            else if ("updated".equals(key)) cur.updated = "1".equals(val.trim());
            else if ("done".equals(key)) cur.done = "1".equals(val.trim());
            else if ("confidence".equals(key)) cur.confidence = toDouble(val, 0);
            else if ("reason".equals(key)) cur.reason = unesc(val);
            else if ("CTXN".equals(key)) {
                long n = toLong(val, 0);
                cur.context = n < 0 ? null : new ArrayList<String>();
            } else if ("CTX".equals(key)) {
                if (cur.context == null) cur.context = new ArrayList<>();
                cur.context.add(unesc(val));
            }
        }
        cards.clear();
        cards.addAll(loaded);
        if (loadedSeq > seq) seq = loadedSeq;
        for (Card c : loaded) {                  // 兜底：老数据里的编号也不能被撞
            long n = idNumber(c.id);
            if (n > seq) seq = n;
            if ("exam".equals(c.kind)) {
                String hit = examHit(c.sourceText);
                if (hit != null) c.title = examTitle(c.sourceText, hit);
            }
        }
    }

    static long idNumber(String id) {
        if (id == null || id.length() < 2 || id.charAt(0) != 'c') return -1;
        long v = 0;
        for (int i = 1; i < id.length(); i++) {
            char ch = id.charAt(i);
            if (ch < '0' || ch > '9') return -1;
            v = v * 10 + (ch - '0');
        }
        return v;
    }

    static long toLong(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    static double toDouble(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }

    static String esc(String s) {
        if (s == null) return "\\0";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\') b.append("\\\\");
            else if (ch == '\n') b.append("\\n");
            else if (ch == '\r') b.append("\\r");
            else if (ch == '\t') b.append("\\t");
            else b.append(ch);
        }
        return b.toString();
    }

    static String unesc(String s) {
        if (s == null) return null;
        if ("\\0".equals(s)) return null;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '\\' || i + 1 >= s.length()) { b.append(ch); continue; }
            char n = s.charAt(++i);
            if (n == 'n') b.append('\n');
            else if (n == 'r') b.append('\r');
            else if (n == 't') b.append('\t');
            else if (n == '\\') b.append('\\');
            else b.append('\\').append(n);
        }
        return b.toString();
    }

    public List<Card> ranked(int limit) {
        return ranked(limit, System.currentTimeMillis());
    }

    // 排序档次（小的在前）：
    //   0 = 未完成且未过期的接龙卡；
    //   1 = 未完成、有 deadline 且 deadline>=now 的其他卡；
    //   2 = 未完成、deadline==0 的卡；
    //   3 = 未完成且已过期（deadline>0 且 deadline<now，含接龙）；
    //   4 = c.done 为真的卡（不管其他条件，永远最后）。
    static int rankTier(Card c, long now) {
        if (c.done) return 4;
        if (c.deadline > 0 && c.deadline < now) return 3;   // 已过期，接龙也算
        if ("jielong".equals(c.kind)) return 0;             // 未过期的接龙卡
        if (c.deadline > 0) return 1;                       // 有 deadline 且未过期
        return 2;                                           // 无时间
    }

    public List<Card> ranked(int limit, long now) {
        return rankCards(new ArrayList<>(cards), limit, now);
    }

    public enum CardScope { ALL, OPEN, OVERDUE, COMPLETED }

    public List<Card> queryCards(String keywords, CardScope scope, int limit, long now) {
        List<Card> result = new ArrayList<>();
        if (limit <= 0) return result;
        String normalized = keywords == null ? "" : keywords.trim().toLowerCase(Locale.ROOT);
        String[] terms = normalized.isEmpty() ? new String[0] : normalized.split("\\s+");
        for (Card card : cards) {
            if (matchesScope(card, scope, now) && matchesTerms(card, terms)) result.add(card);
        }
        return rankCards(result, limit, now);
    }

    private static boolean matchesScope(Card card, CardScope scope, long now) {
        switch (scope) {
            case OPEN: return !card.done;
            case OVERDUE: return rankTier(card, now) == 3;
            case COMPLETED: return card.done;
            default: return true;
        }
    }

    private static boolean matchesTerms(Card card, String[] terms) {
        if (terms.length == 0) return true;
        StringBuilder fields = new StringBuilder();
        for (String value : new String[]{card.displayTitle(), card.title, card.groupName,
                card.sender, card.channel, card.place}) {
            if (value != null) fields.append(value).append('\n');
        }
        String searchable = fields.toString().toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (!searchable.contains(term)) return false;
        }
        return true;
    }

    private static List<Card> rankCards(List<Card> out, int limit, long now) {
        out.sort((a, b) -> {
            int ta = rankTier(a, now), tb = rankTier(b, now);
            if (ta != tb) return Integer.compare(ta, tb);
            switch (ta) {
                case 0:                                       // 接龙：deadline 从小到大
                case 1:                                       // 未过期其他卡：deadline 从小到大
                    return Long.compare(a.deadline, b.deadline);
                case 2:                                       // 无时间：confidence 从大到小
                    return Double.compare(b.confidence, a.confidence);
                case 3:                                       // 已过期：deadline 从大到小（最近过期在前）
                    return Long.compare(b.deadline, a.deadline);
                default:                                      // 已完成：deadline 从小到大，==0 放最后
                    boolean za = a.deadline == 0, zb = b.deadline == 0;
                    if (za != zb) return za ? 1 : -1;
                    return za ? 0 : Long.compare(a.deadline, b.deadline);
            }
        });
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    public void clearLocalData() {
        cards.clear();
        candidates.clear();
        senderStat.clear();
        recent.clear();
        nIn = nNoise = nLevel3 = nPromoted = 0;
        notifyChanged();
    }

    public List<Card> all() { return cards; }
}
