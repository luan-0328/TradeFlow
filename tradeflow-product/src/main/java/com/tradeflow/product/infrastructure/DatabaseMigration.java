package com.tradeflow.product.infrastructure;

import org.flywaydb.core.Flyway;

/** 独立迁移进程入口；迁移账号不注入长期运行的业务JVM。 */
public final class DatabaseMigration {
    private DatabaseMigration() { }
    /** 只迁移所属数据库；历史迁移校验失败立即退出，不自动baseline。 */
    public static void main(String[] args) {
        String prefix="TRADEFLOW_PRODUCT_MIGRATION_";
        String url="jdbc:mysql://"+System.getenv().getOrDefault("MYSQL_HOST", "127.0.0.1")+":"+System.getenv("MYSQL_PORT")+"/trade_product?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        Flyway.configure().dataSource(url,System.getenv(prefix+"USER"),System.getenv(prefix+"PASSWORD"))
                .locations("classpath:db/migration").baselineOnMigrate(false).validateOnMigrate(true)
                .load().migrate();
    }
}
