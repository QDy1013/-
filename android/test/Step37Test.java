package com.nopalou;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public class Step37Test {
 static int count; static void check(String n,boolean b){if(!b)throw new AssertionError(n);count++;}
 public static void main(String[] args) throws Exception {
  TimeZone original=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
  try {
   Calendar now=Calendar.getInstance();now.set(2026,10,5,12,0,0);now.set(Calendar.MILLISECOND,0);long t=now.getTimeInMillis();
   Engine e;try(InputStream w=Files.newInputStream(Paths.get(args[0],"app/assets/model.tsv"))){e=new Engine(w);}e.setProfile(Engine.Profile.demo());
   String[] texts={"今天18点前交统计实验报告", "明天18点前交算法实验报告", "第四次作业请于11月12日下课前，交给组长"};
   for(int i=0;i<texts.length;i++)e.process(new Engine.Msg("m"+i,"g"+i,"课程群","王老师",texts[i],"com.tencent.mobileqq",t));
   check("测试卡均自动生成",e.all().size()==3);
   check("今天筛选只含当天事项",e.queryCards("",Engine.CardScope.ALL,Engine.CardWindow.TODAY,30,t).size()==1);
   check("明天筛选只含次日事项",e.queryCards("",Engine.CardScope.ALL,Engine.CardWindow.TOMORROW,30,t).size()==1);
   Engine.Card c=e.all().get(2);
   check("下课前保留原文且待确认",c.needsConfirm && "11月12日下课前".equals(c.deadlineHint()));
   check("待确认可单独查看",e.queryCards("",Engine.CardScope.ALL,Engine.CardWindow.NEEDS_CONFIRM,30,t).size()==1);
   check("待确认不触发默认23点提醒",ReminderPlan.create(Collections.singletonList(c),new HashMap<String,Long>(),t).nextAt==0);
   check("日期筛选与关键词同时生效",e.queryCards("统计",Engine.CardScope.ALL,Engine.CardWindow.TOMORROW,30,t).isEmpty());
   Engine.Card tomorrow=e.all().get(1); tomorrow.done=true;
   check("明天不显示已完成事项",e.queryCards("",Engine.CardScope.ALL,Engine.CardWindow.TOMORROW,30,t).isEmpty());
   c.needsConfirm=false;
   ByteArrayOutputStream legacy=new ByteArrayOutputStream();e.saveState(legacy);e.loadState(new ByteArrayInputStream(legacy.toByteArray()));
   c=e.all().get(2);
   check("旧卡默认钟点恢复为待确认",c.needsConfirm && c.deadlineHint()!=null);
   Calendar manual=(Calendar)now.clone();manual.add(Calendar.DAY_OF_MONTH,7);manual.set(Calendar.HOUR_OF_DAY,15);
   check("允许确认具体时间",e.editCard(c,c.displayTitle(),manual.getTimeInMillis()));
   check("人工确认后不再显示模糊截止",!c.needsConfirm && c.deadlineHint()==null);
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();e.saveState(bytes);e.loadState(new ByteArrayInputStream(bytes.toByteArray()));
   check("人工确认重启后仍保留",e.all().get(2).deadlineUserSet && e.all().get(2).deadlineHint()==null);
   check("明确时刻保持有效",Engine.uncertainClassDeadline("11月12日18点前交作业，下课前") == null);
   System.out.println("PASS "+count);
  }finally{TimeZone.setDefault(original);}
 }
}
