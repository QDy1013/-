package com.nopalou;
import java.io.*;
import java.nio.file.*;
import java.util.*;
/** 「今天起」筛选：今天还没到点的 + 以后的，排除已过点、已完成、待确认。 */
public class Step40Test {
 static int count; static void check(String n,boolean b){if(!b)throw new AssertionError(n);count++;}
 public static void main(String[] args) throws Exception {
  TimeZone original=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
  try {
   Calendar send=Calendar.getInstance();send.set(2026,10,5,7,0,0);send.set(Calendar.MILLISECOND,0);long ts=send.getTimeInMillis();
   Engine e;try(InputStream w=Files.newInputStream(Paths.get(args[0],"app/assets/model.tsv"))){e=new Engine(w);}e.setProfile(Engine.Profile.demo());
   String[] texts={"今天10点前交统计实验报告","今天18点前交算法实验报告","后天18点前交数据库作业","昨天说的物理作业今天8点前交","第四次作业请于11月12日下课前，交给组长","下周三18点前交英语作文"};
   for(int i=0;i<texts.length;i++)e.process(new Engine.Msg("m"+i,"g"+i,"课程群","王老师",texts[i],"com.tencent.mobileqq",ts+i*1000L));
   Calendar now=(Calendar)send.clone();now.set(Calendar.HOUR_OF_DAY,12);long t=now.getTimeInMillis();
   List<Engine.Card> up=e.queryCards("",Engine.CardScope.ALL,Engine.CardWindow.UPCOMING,30,t);
   for(Engine.Card c:up){
    check("今天起不含已过点事项:"+c.displayTitle(),c.needsConfirm||c.deadline>t);
   }
   boolean hasConfirm=false;for(Engine.Card c:up)if(c.needsConfirm&&c.deadlineHint()!=null&&c.deadlineHint().contains("11月12日"))hasConfirm=true;
   check("只知道日期的待确认事项（11月12日下课前）也在今天起",hasConfirm);
   int expect=0;for(Engine.Card c:e.all())if(Engine.isUpcoming(c,t))expect++;
   check("今天起数量与未过期已定时事项一致",up.size()==expect&&expect>=2);
   check("今天起非空",!up.isEmpty());
   Engine.Card first=up.get(0);first.done=true;
   check("今天起不含已完成",e.queryCards("",Engine.CardScope.ALL,Engine.CardWindow.UPCOMING,30,t).size()==expect-1);
   check("旧序号不变",Engine.CardWindow.ANY.ordinal()==0&&Engine.CardWindow.NEEDS_CONFIRM.ordinal()==3);
   System.out.println("PASS "+count);
  }finally{TimeZone.setDefault(original);}
 }
}
