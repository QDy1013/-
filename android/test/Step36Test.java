package com.nopalou;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
public final class Step36Test {
 static int count;
 static void check(String n, boolean b) { if(!b) throw new AssertionError(n); count++; }
 public static void main(String[] args) throws Exception {
  String message="📢第四次作业（作业集第五章和第六章）请于12月15日下课前（下周四），交给组长，过期不候！";
  String expected="第四次作业（作业集第五章和第六章）";
  check("保留作业序号和章节", expected.equals(Engine.findAction(message)[0]));
  check("提交对象单独提取", "组长".equals(Engine.findChannel(message)));
  check("不把修饰语里的组长当成完整事项", !Engine.deliveryOnly("交给组长的实验报告"));
  check("仅有提交对象时不猜事项", Engine.findAction("明天18点前，交给组长")[0]==null);
  check("课代表标题与提交对象分离", "实验报告".equals(Engine.findAction("实验报告，明天18点前，提交给课代表")[0]) && "课代表".equals(Engine.findChannel("实验报告，明天18点前，提交给课代表")));
  check("保留正常有宾语的动词标题", "交实验报告给组长".equals(Engine.findAction("明天18点前交实验报告给组长")[0]));
  check("老师姓名不丢失", "李老师".equals(Engine.findChannel("明天交给李老师")));
  Engine e;
  try(InputStream weights=Files.newInputStream(Paths.get(args[0],"app/assets/model.tsv"))) {e=new Engine(weights);}
  e.setProfile(Engine.Profile.demo());
  e.process(new Engine.Msg("m","g","课程群","王老师",message,"com.tencent.mobileqq",1800000000000L));
  check("自动通知真实出卡名称", e.all().size()==1 && expected.equals(e.all().get(0).title));
  Engine.Card c=e.all().get(0); c.title="交给组长"; c.titleOverride="我自定义的作业标题"; c.done=true;
  ByteArrayOutputStream out=new ByteArrayOutputStream(); e.saveState(out); e.loadState(new ByteArrayInputStream(out.toByteArray()));
  c=e.all().get(0);
  check("旧卡恢复标题及提交对象", expected.equals(c.title) && "组长".equals(c.channel));
  check("迁移保留自定义标题和完成状态", "我自定义的作业标题".equals(c.displayTitle()) && c.done);
  check("迁移结果等待持久化",e.hasStateChanges());
  System.out.println("PASS "+count);
 }
}
