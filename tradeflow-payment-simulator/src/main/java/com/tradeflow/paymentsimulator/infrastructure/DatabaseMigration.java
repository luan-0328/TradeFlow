package com.tradeflow.paymentsimulator.infrastructure;
import org.flywaydb.core.Flyway;
/** 独立迁移入口，业务JVM不需要DDL权限或迁移账号。 */
public final class DatabaseMigration {
 private DatabaseMigration() { }
 /** 仅迁移模拟器自己的逻辑库，校验历史版本，禁止自动baseline。 */
 public static void main(String[] args){
  String host=System.getenv().getOrDefault("MYSQL_HOST","127.0.0.1"),port=System.getenv().getOrDefault("MYSQL_PORT","13316");
  String url="jdbc:mysql://"+host+":"+port+"/trade_payment_simulator?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
  Flyway.configure().dataSource(url,System.getenv("TRADEFLOW_PAYMENT_SIMULATOR_MIGRATION_USER"),System.getenv("TRADEFLOW_PAYMENT_SIMULATOR_MIGRATION_PASSWORD"))
   .locations("classpath:db/migration").baselineOnMigrate(false).validateOnMigrate(true).load().migrate();
 }
}
