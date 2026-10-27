package com.tradeflow.payment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThat;

/** 真正跨JVM halt/重启恢复；独立MySQL，通道为持久文件替身，不冒充真实支付通道验收。 */
@EnabledIfEnvironmentVariable(named = "TRADEFLOW_PAYMENT_CRASH_DIR", matches = ".+")
class PaymentCrashRecoveryTest {
    /** 验证扣款事实已落盘而本地未入账，旧租约过期后只查原号，一次建立流水/退款本金/Outbox。 */
    @Test @Timeout(90)
    void haltedAfterExternalSuccessRestartsByOriginalPaymentQuery() throws Exception {
        var fixture = new PaymentCrashFixture();
        assertThat(child(fixture.root, "crash", Duration.ofSeconds(15)))
                .isEqualTo(PaymentCrashFixture.HALT_AFTER_CHANNEL_SUCCESS);
        assertThat(Files.exists(fixture.root.resolve("channel-success.json"))).isTrue();
        assertThat(fixture.mapper.find(fixture.no).get("status")).isEqualTo("PENDING");
        var originalTask = fixture.jdbc.queryForMap("SELECT state,step,claim_version,attempt_count,lease_owner FROM process_task WHERE task_no=?", fixture.no);
        assertThat(originalTask.get("state")).isEqualTo("RUNNING");
        assertThat(originalTask.get("step")).isEqualTo("QUERY");
        assertThat(((Number) originalTask.get("claim_version")).longValue()).isEqualTo(1);
        assertThat(Files.readAllLines(fixture.root.resolve("commands.log"))).containsExactly(fixture.no);
        assertThat(Files.exists(fixture.root.resolve("queries.log"))).isFalse();
        assertFinancialCounts(fixture, 0);
        assertThat(child(fixture.root, "recover", Duration.ofSeconds(50))).isZero();
        assertThat(Files.exists(fixture.root.resolve("before-expiry.json"))).isTrue();
        assertThat(Files.exists(fixture.root.resolve("recovered.json"))).isTrue();
        assertThat(fixture.mapper.find(fixture.no).get("status")).isEqualTo("SUCCEEDED");
        var completed = fixture.jdbc.queryForMap("SELECT state,claim_version,attempt_count,lease_owner FROM process_task WHERE task_no=?", fixture.no);
        assertThat(completed.get("state")).isEqualTo("SUCCEEDED");
        assertThat(((Number) completed.get("claim_version")).longValue()).isEqualTo(2);
        assertThat(((Number) completed.get("attempt_count")).longValue()).isEqualTo(2);
        assertThat(completed.get("lease_owner")).isNotEqualTo(originalTask.get("lease_owner"));
        assertFinancialCounts(fixture, 1);
        assertThat(fixture.jdbc.queryForObject("SELECT paid_amount_cents FROM refund_account WHERE payment_no=?", Long.class, fixture.no)).isEqualTo(12345L);
        assertThat(fixture.jdbc.queryForObject("SELECT amount_cents FROM payment_ledger WHERE payment_no=?", Long.class, fixture.no)).isEqualTo(12345L);
        assertThat(fixture.jdbc.queryForObject("SELECT event_type FROM outbox_event WHERE aggregate_id=?", String.class, fixture.no)).isEqualTo("PaymentSucceeded");
        assertThat(Files.readAllLines(fixture.root.resolve("commands.log"))).containsExactly(fixture.no);
        assertThat(Files.readAllLines(fixture.root.resolve("queries.log"))).containsExactly(fixture.no, fixture.no, fixture.no);
        Files.writeString(fixture.root.resolve("java-result.json"), """
                {"haltExitCode":73,"oldLeaseRespected":true,"realClockLeaseExpired":true,
                 "claimVersionAfterRestart":2,"paymentCommands":1,"originalPaymentQueries":3,
                 "paymentLedgerRows":1,"refundAccountRows":1,"outboxRows":1,
                 "realPaymentServiceAndMapper":true,"channel":"durable-file-test-double"}
                """);
    }

    private static void assertFinancialCounts(PaymentCrashFixture fixture, int expected) {
        for (String table : new String[]{"payment_ledger", "refund_account"})
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE payment_no=?", Long.class, fixture.no)).isEqualTo((long) expected);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?", Long.class, fixture.no)).isEqualTo((long) expected);
    }

    /** 子进程直接使用java.home，避免Oracle shim产生无法归属的孙进程；超时只终止本测试创建的进程。 */
    private static int child(Path root, String phase, Duration timeout) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var process = new ProcessBuilder(java.toString(), "-cp", classpath, PaymentCrashFixture.class.getName(), phase)
                .redirectErrorStream(true).redirectOutput(root.resolve("jvm-" + phase + ".log").toFile()).start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new AssertionError("子JVM阶段超时：" + phase);
            return process.exitValue();
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
        }
    }
}
