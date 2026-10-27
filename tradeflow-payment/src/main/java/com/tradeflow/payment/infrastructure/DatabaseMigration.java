package com.tradeflow.payment.infrastructure;

import org.flywaydb.core.Flyway;

/** 支付独立迁移入口；业务JVM不持有迁移凭据、不自动执行DDL。 */
public final class DatabaseMigration {
    private DatabaseMigration() { }
    /** 仅迁移trade_payment，历史校验失败立即退出，不自动baseline。 */
    public static void main(String[] args){
        String port=System.getenv().getOrDefault("MYSQL_PORT","13316");
        String host=System.getenv().getOrDefault("MYSQL_HOST","127.0.0.1");
        Flyway.configure().dataSource("jdbc:mysql://"+host+":"+port+"/trade_payment?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",System.getenv("TRADEFLOW_PAYMENT_MIGRATION_USER"),System.getenv("TRADEFLOW_PAYMENT_MIGRATION_PASSWORD"))
          .locations("classpath:db/migration").baselineOnMigrate(false).validateOnMigrate(true).load().migrate();
    }
}
