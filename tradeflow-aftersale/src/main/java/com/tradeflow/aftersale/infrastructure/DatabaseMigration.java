package com.tradeflow.aftersale.infrastructure;
import org.flywaydb.core.Flyway;
/** 独立迁移入口，业务JVM不持有DDL凭据且关闭自动Flyway。 */
public final class DatabaseMigration {
 private DatabaseMigration(){}
 public static void main(String[] args){String port=System.getenv().getOrDefault("MYSQL_PORT","13316");String host=System.getenv().getOrDefault("MYSQL_HOST","127.0.0.1");Flyway.configure().dataSource("jdbc:mysql://"+host+":"+port+"/trade_aftersale?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",System.getenv("TRADEFLOW_AFTERSALE_MIGRATION_USER"),System.getenv("TRADEFLOW_AFTERSALE_MIGRATION_PASSWORD")).locations("classpath:db/migration").baselineOnMigrate(false).validateOnMigrate(true).load().migrate();}
}
