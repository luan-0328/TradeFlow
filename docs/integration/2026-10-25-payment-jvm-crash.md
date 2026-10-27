# 2026-10-25 支付真实JVM中断与租约到期恢复

本次在全新独占MySQL 8.0.46上执行真实子JVM的`Runtime.halt(73)`与第二子JVM重启恢复。真实使用现有`PaymentService`、`PaymentMapper`、MyBatis和Spring事务；订单权威查询及支付通道使用明确的持久文件测试替身，不代表真实HTTP/Nacos/支付通道故障验收。没有修改生产代码、生产租约时长或旧测试。

## 实际命令与证据

在`D:/cloud/tradeflow`，取得Maven独占窗口后执行：

```powershell
python -m py_compile scripts/verification/payment-crash.py
python scripts/verification/payment-crash.py --mysql-bin D:/mysql-8.0.46-winx64/bin
```

第二条命令在全新UUID目录初始化独立原生MySQL，验证服务端datadir属于本轮目录且仅含系统库，再加载支付模块全部当前迁移。脚本随后运行：

```powershell
.\mvnw.cmd -B -ntp -pl tradeflow-payment -am '-Dtest=PaymentCrashRecoveryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：`BUILD SUCCESS`；**1条测试、0失败、0错误、0跳过**，测试耗时32.51秒，Maven总计36.586秒。数据与证据保留在`.local/payment-crash-38b835791157415fa53f999e727d4dcd`，汇总为`result.json`；本轮MySQL端口54661、PID19920，测试结束后已优雅关闭，未停止任何既有实例。未连接SSH、现有业务库、原registry或MQ。

## 覆盖的真实恢复窗口

1. 第一个子JVM通过真实Mapper创建稳定支付意图与SUBMIT任务，真实`PaymentService.recover()`领取30秒租约，并在通道写入前提交QUERY恢复步骤。
2. 通道替身记录一次原支付号的command，把成功事实写入`channel-success.json`并执行`FileChannel.force(true)`；随后立即`Runtime.halt(73)`。这不是抛异常，支付用例catch、finally和正常JVM关闭流程都不运行。父测试确认退出码73、数据库支付仍PENDING、任务RUNNING/QUERY/claim_version=1，流水、退款账户和Outbox均为0。
3. 第二个子JVM创建新的真实PaymentService与owner。首先在原租约仍有效时调用recover，确认未查询通道、未应用资金事实；证据为`before-expiry.json`。
4. 不修改数据库租约字段或生产超时，等待MySQL时钟使原30秒租约自然到期。再次recover接管为claim_version=2、attempt_count=2、新owner，并只查询原支付号读取持久成功事实。任务成功，支付SUCCEEDED。
5. 重复worker调用和两次主动查询同一成功事实后，`commands.log`仍只有原支付号1条，`queries.log`为原支付号3条；支付流水1行、退款账户1行、PaymentSucceeded Outbox事件1行。流水及退款本金均为12345分。证据为`java-result.json`、`recovered.json`与`maven.log`。

## 文件与边界

- `tradeflow-payment/src/test/java/com/tradeflow/payment/PaymentCrashFixture.java`：两个独立JVM阶段、真实Mapper/Service装配、明确文件通道替身及fsync/halt位置。
- `tradeflow-payment/src/test/java/com/tradeflow/payment/PaymentCrashRecoveryTest.java`：父测试启动子JVM，断言崩溃前后持久状态、自然租约到期、新owner与唯一资金效果；无专属fixture环境变量时默认安全跳过。
- `scripts/verification/payment-crash.py`：复用本轮新增的`backup-mysql-fixture.py`实例归属检查，在全新目录创建与关闭自己的MySQL，保留数据和日志。

这是一项明确窗口的跨JVM恢复证据，不能推广为完整故障矩阵。文件通道只替代外部成功事实，不模拟真实通道网络、签名回调、Nacos发现、真实订单RPC或多通道幂等协议；未测试数据库进程/主机掉电、真实Rabbit发布/消费/ACK、真实退款执行、双机并发接管或全部业务不变量。Outbox只验证事务内唯一落库，没有发送消息。本次没有性能或生产RPO/RTO结论。
