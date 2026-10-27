# 2026-10-25 本地备份校验与原生MySQL合成恢复

本次完成可执行备份完整性/隔离计划校验和原生MySQL合成恢复。未连接服务器、未使用Docker、未读取现有部署凭据、未改写开发库、未停止既有服务、未提交推送。

## 实际执行与结果

在`D:/cloud/tradeflow`执行：

```powershell
python scripts/verification/backup-tests.py
python -m py_compile scripts/Backup-Deployment.py scripts/verification/backup-verify.py scripts/verification/backup-tests.py
python scripts/Backup-Deployment.py --help
python scripts/verification/backup-mysql-fixture.py --mysql-bin D:/mysql-8.0.46-winx64/bin
python scripts/verification/backup-verify.py .local/backup-fixture-c0d214f138d140b1b22b63cace35fc20/bundle
```

- Python回归11组通过；包含正常包/计划、三个顶层文件损坏、11种非法路径、ZIP符号链接、大小写冲突、未登记成员、两类部署秘密身份不符、证据缺失/内容/长度错误、未完成和旧格式备份，以及错误来源/原项目/已有目录/原namespace/非回环目标/隔离声明缺失等拒绝场景。还覆盖原registry多地址列表、HTTP前缀、localhost回环别名和MQ原端口，避免仅比较原字符串造成绕过。
- 编译检查和备份器`--help`退出码0；未调用真实备份器连接Docker。
- 原生MySQL 8.0.46合成恢复退出码0。专属目录`.local/backup-fixture-c0d214f138d140b1b22b63cace35fc20`，结果`result.json`。源端口62521、恢复端口62522，两个实例均已优雅关闭。目录、SQL、合成秘密ZIP和日志保留，未删除任何目录或卷。
- 源实例为脚本新建，创建六个同名逻辑库，但每库仅一张合成探针表，售后库另有合成证据元数据。一次mysqldump后验证v2包；恢复前核验新实例服务端datadir及仅有四个系统schema，再导入。逐库读取探针金额123，通过；证据键/长度/SHA256与源元数据及归档一致；恢复后负金额被CHECK约束拒绝。
- CLI离线校验输出`integrity=passed`、`evidenceReferences=1`、`restoreExecuted=false`、`databaseEmptinessVerified=false`。CLI本身不会执行恢复，真实恢复仅发生于fixture工具新建的专属实例。

首轮回归暴露Windows zipfile写入时自动归一化反斜线，补直接非法路径断言后通过。首轮MySQL fixture因MySQL不支持数据库COMMENT语法失败；移除该语法后重新用全新UUID目录运行通过。失败轮仅创建的源实例也已关闭，保留其目录供排查。

## 改动与边界

备份器现在导出同一停写窗口的有效证据索引，对实际ZIP成员字节计算散列，并在改名完成前执行v2离线校验。只读工具不解压、不联网，不会输出配置秘密；隔离计划只是静态拒绝规则，不代替目标空库/归属及网络隔离现场证据。恢复前必须阻断原Nacos/MQ、配置独立Nacos实例及namespace，禁止归档env原样启动。

本机有原生MySQL，无Docker命令；未验证Compose执行、真实业务92表/Flyway历史、JWT公私钥数学配对、真实证据/真实账号权限、Nacos注册隔离、Rabbit消息恢复、恢复任务幂等或业务不变量。fixture中的密钥文件是明确标注的合成占位字节；离线校验验证字节关联，未验证其可签名。本次没有真实业务数据RPO/RTO结论。manifest与数据共同被篡改无法用SHA256发现，可信离线保存和加密仍属操作前提。
