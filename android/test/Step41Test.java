package com.nopalou;
import java.io.*;
import java.util.*;
/** 平台通知课程名：“数据结构 老师发布了新作业”这类写法要认出课程。 */
public class Step41Test {
 static int count; static void check(String n,boolean b){if(!b)throw new AssertionError(n);count++;System.out.println("PASS "+n);}
 public static void main(String[] args) throws Exception {
  TimeZone original=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
  try {
   Calendar c=Calendar.getInstance();c.set(2026,10,6,12,0,0);c.set(Calendar.MILLISECOND,0);long t=c.getTimeInMillis();
   String ykt="com.xuetangx.ykt", zd="com.able.wisdomtree", cx="com.chaoxing.mobile";
   Engine.PlatformNotice.Notice n=Engine.PlatformNotice.parse(ykt,"雨课堂","数据结构 老师发布了新作业：链表练习，截止时间：2026-11-09 22:00",t);
   check("老师前的课程名",n!=null&&"数据结构".equals(n.course));
   check("事项名不受影响","链表练习".equals(n.title)||n.title.contains("链表练习"));
   n=Engine.PlatformNotice.parse(zd,"知到","线性代数 王老师布置了作业《行列式》，截止时间：2026-11-10 23:59",t);
   check("带姓的老师",n!=null&&"线性代数".equals(n.course));
   n=Engine.PlatformNotice.parse(ykt,"雨课堂","老师发布了新作业：链表练习，截止时间：2026-11-09 22:00",t);
   check("没写课程时不乱认",n!=null&&n.course.isEmpty());
   n=Engine.PlatformNotice.parse(cx,"学习通","【概率论与数理统计】第四次作业《第五章习题》已发布，截止时间：2026-11-11 23:59",t);
   check("【】课程名照旧",n!=null&&"概率论与数理统计".equals(n.course));
   n=Engine.PlatformNotice.parse(ykt,"雨课堂","作业通知 老师发布了新作业：链表练习，截止时间：2026-11-09 22:00",t);
   check("泛称不当课程",n!=null&&n.course.isEmpty());
   // 签到：出签到卡，时限按“N 分钟内”推算；签过了、结束了不出
   n=Engine.PlatformNotice.parse(ykt,"雨课堂","数据结构 老师发起了签到，请在2分钟内完成",t);
   check("签到出卡",n!=null&&Engine.PlatformNotice.KIND_SIGNIN.equals(n.kind));
   check("签到标题含课程","签到：数据结构".equals(n.title));
   check("签到时限 2 分钟",n.deadline==t+2*60_000L);
   n=Engine.PlatformNotice.parse(cx,"学习通","【大学英语】老师发布了签到",t);
   check("没写时限默认 10 分钟",n!=null&&n.deadline==t+10*60_000L&&"签到：大学英语".equals(n.title));
   check("已签到不出卡",Engine.PlatformNotice.parse(cx,"学习通","【大学英语】你已签到成功",t)==null);
   check("签到结束不出卡",Engine.PlatformNotice.parse(ykt,"雨课堂","数据结构 签到已结束",t)==null);
   // 成绩：出成绩卡，标题带分数，当天 23:59 截止
   n=Engine.PlatformNotice.parse(cx,"作业通知","【概率论与数理统计】第二次作业已批改，得分92",t);
   check("成绩出卡",n!=null&&Engine.PlatformNotice.KIND_GRADE.equals(n.kind));
   check("成绩标题:"+(n==null?"":n.title),"成绩：第二次作业 · 92分".equals(n.title));
   check("成绩当天看",n.deadline==Engine.PlatformNotice.endOfDay(t));
   n=Engine.PlatformNotice.parse(zd,"知到","你的作业《第二章 力学》已批改，得分85.5",t);
   check("成绩书名号标题:"+(n==null?"":n.title),n!=null&&"成绩：第二章 力学 · 85.5分".equals(n.title));
   n=Engine.PlatformNotice.parse(ykt,"雨课堂","线性代数 期中考试成绩已公布",t);
   check("考试成绩:"+(n==null?"":n.title),n!=null&&Engine.PlatformNotice.KIND_GRADE.equals(n.kind)&&n.title.contains("期中考试"));
   // “计入平时成绩”的作业通知仍是作业，不当成绩
   n=Engine.PlatformNotice.parse(cx,"学习通","【大学物理】第三次作业已发布，计入平时成绩，截止时间：2026-11-12 23:59",t);
   check("计入平时成绩仍是作业",n!=null&&"todo".equals(n.kind));
   // 完整流程：引擎出卡，签到卡不提示“提交到”
   Engine e;try(java.io.InputStream w=java.nio.file.Files.newInputStream(java.nio.file.Paths.get(args[0],"app/assets/model.tsv"))){e=new Engine(w);}e.setProfile(Engine.Profile.demo());
   e.processPlatform(new Engine.Msg("s1","g","雨课堂","雨课堂","数据结构 老师发起了签到，请在2分钟内完成",ykt,t));
   e.processPlatform(new Engine.Msg("s2","g","雨课堂","雨课堂","数据结构 老师发起了签到，请在2分钟内完成",ykt,t+30_000L));
   check("重复推送的签到只留一张",e.all().size()==1);
   Engine.Card sc=e.all().get(0);
   check("签到卡时间确定且不提交",!sc.needsConfirm&&sc.channel==null&&"signin".equals(sc.kind));
   e.processPlatform(new Engine.Msg("s3","g","雨课堂","雨课堂","数据结构 老师发起了签到，请在2分钟内完成",ykt,t+86400_000L));
   check("第二天的签到是新的一张",e.all().size()==2);
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();e.saveState(bytes);e.loadState(new ByteArrayInputStream(bytes.toByteArray()));
   check("重启后签到卡类型保留","signin".equals(e.all().get(0).kind));
   System.out.println("PASS "+count);
  }finally{TimeZone.setDefault(original);}
 }
}
