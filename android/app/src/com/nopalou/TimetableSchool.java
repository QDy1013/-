package com.nopalou;

import org.json.JSONArray;
import org.json.JSONObject;

/** Official undergraduate entry points. Trial schools remain explicitly labelled until field-tested. */
final class TimetableSchool {
    static final TimetableSchool[] ALL = {
        new TimetableSchool("nwpu", "西北工业大学", "翱翔门户账号", "翱翔门户密码",
                "https://jwxt.nwpu.edu.cn/student/sso-login", false, new String[]{"nwpu.edu.cn"}, new String[]{"uis.nwpu.edu.cn"}),
        new TimetableSchool("tyut", "太原理工大学", "学号", "教务系统密码",
                "https://utsp.sxzyckj.com/loginXsJs.html", true, new String[]{"tyut.edu.cn", "utsp.sxzyckj.com"}, new String[]{"tyut.edu.cn", "utsp.sxzyckj.com"}),
        new TimetableSchool("snnu", "陕西师范大学", "统一身份认证账号", "统一身份认证密码",
                "https://ywtbpt.snnu.edu.cn/", true, new String[]{"snnu.edu.cn"}, new String[]{"snnu.edu.cn"}),
        new TimetableSchool("hitwh", "哈尔滨工业大学（威海）", "威海校区账号", "威海校区统一认证密码",
                "https://jwts.hitwh.edu.cn/", true, new String[]{"hitwh.edu.cn", "ids.hit.edu.cn"}, new String[]{"hitwh.edu.cn"})
    };
    final String id, name, accountLabel, passwordLabel, entry;
    final boolean trial;
    final String[] domains, credentialDomains;
    private TimetableSchool(String id, String name, String accountLabel, String passwordLabel, String entry,
                            boolean trial, String[] domains, String[] credentialDomains) {
        this.id=id; this.name=name; this.accountLabel=accountLabel; this.passwordLabel=passwordLabel; this.entry=entry;
        this.trial=trial; this.domains=domains; this.credentialDomains=credentialDomains;
    }
    static TimetableSchool byId(String id) { for (TimetableSchool s : ALL) if (s.id.equals(id)) return s; return ALL[0]; }
    static TimetableSchool byName(String name) { for (TimetableSchool s : ALL) if (s.name.equals(name)) return s; return null; }
    static boolean hostAllowed(String host, String[] domains) {
        if (host == null) return false;
        for (String domain : domains) if (host.equals(domain) || (domain.endsWith(".edu.cn") && host.endsWith("." + domain))) return true;
        return false;
    }
    JSONObject config() {
        JSONObject j = new JSONObject();
        try { j.put("id", id).put("name", name).put("domains", new JSONArray(domains)).put("credentialDomains", new JSONArray(credentialDomains)); }
        catch (Exception ignored) { }
        return j;
    }
}
