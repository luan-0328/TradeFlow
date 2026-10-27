# 本机专用Redis

网关入口保护必须连接共享Redis，不能在无Redis时静默改为JVM限流。`Start-Local.ps1`会调用`python scripts/Prepare-Redis.py prepare`，默认二进制目录`D:/Redis/Redis-7.0.15-Windows-x64-msys2`，可用TRADEFLOW_REDIS_BIN覆盖目录或redis-server.exe路径。

专用实例只监听127.0.0.1:16379，数据/配置/归属记录在`.local/redis`。配置随机密码，Windows ACL仅授权当前用户；已有`.local/dev-env.json`的其他键原样保留，若已存在不同Redis连接或密码则拒绝覆盖。密码不进入命令行、不输出到终端。停止应用不会自动删除依赖数据。

```powershell
python scripts/Prepare-Redis.py prepare
python scripts/Prepare-Redis.py stop
```

复用和停止均核对归属UUID、PID创建时间、进程映像、配置路径/摘要与监听归属；发现其他进程占用、不同目录或记录被改动时拒绝操作。不认领系统Redis，不停止其他实例，不更改已有密码。

验证脚本使用`--runtime <全新目录> --port 0`可建立独立测试实例，凭据写在该独立目录的dev-env.json，不触碰项目原环境文件。本轮已在全新Temp目录真实验证启动、复用、停止、占用端口拒绝，以及篡改归属时拒绝停止；测试实例最终停止，证据地址在`.local/redis-lifecycle-evidence.json`。

该工具针对当前Windows原生Redis发行包。容器部署使用Compose Redis服务、受保护配置文件和独立持久卷，不能复用本机进程记录。Redis用于入口配额和商品展示缓存，队列/库存/资金正确性仍由相应服务数据库和恢复机制保证。商品MVC服务使用StringRedisTemplate，网关WebFlux使用ReactiveStringRedisTemplate；连接凭据仅传给这两个进程。商品缓存边界与故障行为见[缓存与Mapper说明](../catalog-cache-and-mapper.md)。
