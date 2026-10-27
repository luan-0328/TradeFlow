package com.tradeflow.settlement;
import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
/** 仅专用真实MySQL测试库运行；验证唯一事实竞争、账本守恒CHECK及任务旧版本围栏，不替代端到端验收。 */
@Tag("mysql")
@EnabledIfEnvironmentVariable(named="TRADEFLOW_SETTLEMENT_TEST_DB_URL",matches=".+")
class SettlementMySqlTest {
 static String url,user,password;
 @BeforeAll static void migrate(){url=System.getenv("TRADEFLOW_SETTLEMENT_TEST_DB_URL");if(!url.matches(".*[/]trade_settlement_test(?:[?].*)?$"))throw new IllegalArgumentException("只能在trade_settlement_test专用数据库测试");user=System.getenv("TRADEFLOW_SETTLEMENT_TEST_DB_USER");password=System.getenv("TRADEFLOW_SETTLEMENT_TEST_DB_PASSWORD");Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").load().migrate();}
 Connection connection()throws SQLException{return DriverManager.getConnection(url,user,password);}
 String id(){return UUID.randomUUID().toString().replace("-","");}
 @Test void unbalancedLedgerRejectedByDatabase()throws Exception{try(var c=connection()){c.setAutoCommit(false);String x=id();try(var p=c.prepareStatement("INSERT INTO settlement_ledger(entry_key,payment_no,merchant_id,entry_type,principal_delta,fee_delta,payable_delta,simulated_delta) VALUES(?,?,?,'PAYMENT',100,10,100,0)")){p.setString(1,x);p.setString(2,x);p.setString(3,x);assertThrows(SQLException.class,p::executeUpdate);}finally{c.rollback();}}}
 @Test void oldVersionCannotFinishNewLease()throws Exception{try(var c=connection()){c.setAutoCommit(false);String x=id();try{try(var p=c.prepareStatement("INSERT INTO settlement_task(task_no,lease_owner,claim_version,lease_until) VALUES(?,'new-owner',2,TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(3)))")){p.setString(1,x);p.executeUpdate();}try(var p=c.prepareStatement("UPDATE settlement_task SET lease_until=NULL WHERE task_no=? AND lease_owner='old-owner' AND claim_version=1 AND lease_until>UTC_TIMESTAMP(3)")){p.setString(1,x);assertEquals(0,p.executeUpdate());}}finally{c.rollback();}}}
 @Test void concurrentSameOriginalRefundFactCommitsExactlyOnce()throws Exception{
  String x=id();var executor=Executors.newSingleThreadExecutor();var attempted=new CountDownLatch(1);
  try(var first=connection()){
   first.setAutoCommit(false);insertFact(first,x);Future<Boolean> duplicate=executor.submit(()->{try(var second=connection()){second.setAutoCommit(false);attempted.countDown();try{insertFact(second,x);second.commit();return true;}catch(SQLException e){second.rollback();if(e.getErrorCode()!=1062)throw e;return false;}}});
   assertTrue(attempted.await(5,TimeUnit.SECONDS));first.commit();assertFalse(duplicate.get(10,TimeUnit.SECONDS));
   try(var q=first.prepareStatement("SELECT COUNT(*) FROM settlement_fact WHERE business_no=?")){q.setString(1,x);try(var rs=q.executeQuery()){rs.next();assertEquals(1,rs.getInt(1));}}
  }finally{executor.shutdownNow();try(var c=connection();var p=c.prepareStatement("DELETE FROM settlement_fact WHERE business_no=?")){p.setString(1,x);p.executeUpdate();}}
 }
 void insertFact(Connection c,String x)throws SQLException{try(var p=c.prepareStatement("INSERT INTO settlement_fact(kind,business_no,payment_no,order_no,merchant_id,amount_cents,currency,occurred_at) VALUES('REFUND_SUCCEEDED',?,?,?,?,20,'CNY',UTC_TIMESTAMP(3))")){for(int i=1;i<=4;i++)p.setString(i,x);p.executeUpdate();}}
}
