[CmdletBinding()]
param([switch] $Build, [int] $StartupTimeoutSeconds = 150)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'LocalProcess.ps1')
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$runtimeDir = Join-Path $projectRoot '.local'
$logDir = Join-Path $runtimeDir 'logs'
$statePath = Join-Path $runtimeDir 'processes.json'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null

if (Test-Path -LiteralPath $statePath) {
    $previous = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    $alive = @($previous.processes | Where-Object { Test-TradeFlowProcess -Record $_ })
    if ($alive.Count -gt 0) {
        throw '本项目已有运行进程。需要重启时先用 Stop-Local.ps1 停止。'
    }
}

$services = @(
    @{ name = 'tradeflow-user'; port = 8081 },
    @{ name = 'tradeflow-product'; port = 8082 },
    @{ name = 'tradeflow-order'; port = 8083 },
    @{ name = 'tradeflow-payment'; port = 8084 },
    @{ name = 'tradeflow-aftersale'; port = 8085 },
    @{ name = 'tradeflow-payment-simulator'; port = 8086 },
    @{ name = 'tradeflow-settlement'; port = 8087 },
    @{ name = 'tradeflow-gateway'; port = 8080 }
)

# 检查所有端口后再启动；不终止任何已有监听程序。
foreach ($service in $services) {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $service.port)
    try { $listener.Start() }
    catch { throw "端口 $($service.port) 已被占用，未启动项目，也不会停止占用者。" }
    finally { $listener.Stop() }
}

foreach ($managementPort in 18080..18087) {
    $managementListener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $managementPort)
    try { $managementListener.Start() }
    catch { throw "管理端口 $managementPort 已占用，未启动或停止任何其他程序。" }
    finally { $managementListener.Stop() }
}

$missingJar = @($services | Where-Object {
    -not (Test-Path -LiteralPath (Join-Path $projectRoot "$($_.name)/target/$($_.name)-0.1.0-SNAPSHOT.jar"))
})
if ($Build -or $missingJar.Count -gt 0) {
    Push-Location $projectRoot
    try {
        & (Join-Path $projectRoot 'mvnw.cmd') -B -ntp '-Dmaven.test.skip=true' package
        if ($LASTEXITCODE -ne 0) { throw '构建失败，未启动任何服务。' }
    } finally { Pop-Location }
}

$javaExecutable = (Get-Command java -ErrorAction Stop).Source
if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    $javaExecutable = Join-Path $env:JAVA_HOME 'bin/java.exe'
}
# 本轮只准备本机独立开发数据库并运行迁移；不登录远程，不运行测试。
Push-Location $projectRoot
try {
    & python (Join-Path $PSScriptRoot 'Prepare-Dev.py')
    if ($LASTEXITCODE -ne 0) { throw '本机开发数据库准备失败，未启动任何业务服务。' }
} finally { Pop-Location }
# 先声明专用broker拓扑与凭据再启动发布者；不SSH、不发送业务演示消息。
& python (Join-Path $PSScriptRoot 'Prepare-RabbitMQ.py')
if ($LASTEXITCODE -ne 0) { throw '本机消息基础设施准备失败，未启动业务服务。' }
# 网关入口配额和商品展示缓存使用专用Redis；只准备本项目归属实例。
& python (Join-Path $PSScriptRoot 'Prepare-Redis.py') prepare
if ($LASTEXITCODE -ne 0) { throw '本机Redis准备失败，未启动业务服务。' }
$localSettings = Get-Content -LiteralPath (Join-Path $runtimeDir 'dev-env.json') -Raw | ConvertFrom-Json
foreach ($entry in $localSettings.PSObject.Properties) {
    [Environment]::SetEnvironmentVariable($entry.Name, [string] $entry.Value, 'Process')
}
# 可选QQ SMTP本机凭据只读取明确白名单；文件限制当前用户可读，不进入源码和共享开发指令。
$mailPath = Join-Path $runtimeDir 'mail-env.json'
if (Test-Path -LiteralPath $mailPath) {
    $mailSettings = Get-Content -LiteralPath $mailPath -Raw | ConvertFrom-Json
    foreach ($mailName in @('TRADEFLOW_MAIL_USERNAME','TRADEFLOW_MAIL_PASSWORD')) {
        $mailProperty = $mailSettings.PSObject.Properties[$mailName]
        if ($null -ne $mailProperty) { [Environment]::SetEnvironmentVariable($mailName, [string] $mailProperty.Value, 'Process') }
    }
}
# 本机业务仅监听回环，注册地址必须与之匹配；容器配置独立选择可达地址。
if (-not $env:NACOS_SERVER_ADDR) { $env:NACOS_SERVER_ADDR = '127.0.0.1:18848' }
if (-not $env:NACOS_DISCOVERY_IP) { $env:NACOS_DISCOVERY_IP = '127.0.0.1' }
if (-not $env:NACOS_GROUP) { $env:NACOS_GROUP = 'TRADEFLOW' }
Write-Host '服务间调用统一使用Nacos；请预先启动可达的Nacos，本脚本不建立SSH连接。'
$records = [System.Collections.Generic.List[object]]::new()
function Save-ProcessState {
    @{ projectRoot = $projectRoot; profile = 'dev'; processes = @($records.ToArray()) } |
        ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $statePath -Encoding utf8
}

try {
    foreach ($service in $services) {
        $jarPath = Join-Path $projectRoot "$($service.name)/target/$($service.name)-0.1.0-SNAPSHOT.jar"
        $arguments = @('-Xms32m', '-Xmx160m', '-XX:MaxMetaspaceSize=160m',
            '-XX:ReservedCodeCacheSize=64m', '-XX:MaxDirectMemorySize=48m',
            '-XX:ActiveProcessorCount=2', '-XX:+UseSerialGC', '-Dfile.encoding=UTF-8',
            '-jar', "`"$jarPath`"", '--spring.profiles.active=dev', "--server.port=$($service.port)")
        # 每个JVM仅继承其所属数据库和必要认证材料；迁移/管理员凭据不注入业务进程。
        $hidden = @{}
        # 从完整进程环境建立允许清单，不能只过滤配置文件里的键；启动shell可能另有迁移/管理员凭据。
        $publicNames = @('SystemRoot','WINDIR','SystemDrive','COMSPEC','PATHEXT','PATH','TEMP','TMP',
            'USERPROFILE','APPDATA','LOCALAPPDATA','ProgramData','NUMBER_OF_PROCESSORS','PROCESSOR_ARCHITECTURE','JAVA_HOME',
            'MYSQL_HOST','MYSQL_PORT','RABBITMQ_HOST','RABBITMQ_PORT','RABBITMQ_VHOST',
            'TRADEFLOW_MONITORING_TOKEN','TRADEFLOW_JWT_PUBLIC_KEY','TRADEFLOW_JWT_ISSUER','TRADEFLOW_MESSAGING_ENABLED',
            'TRADEFLOW_NACOS_CONFIG_ENABLED','TRADEFLOW_SENTINEL_ENABLED','NACOS_SERVER_ADDR','NACOS_CONFIG_IMPORT',
            'NACOS_USERNAME','NACOS_PASSWORD','NACOS_NAMESPACE','NACOS_GROUP','NACOS_DISCOVERY_IP',
            'SENTINEL_DASHBOARD','SENTINEL_CLIENT_IP')
        # 所有服务必须发现实例；仅继承Nacos客户端凭据，不继承服务器签名密钥。
        $serviceNames = switch ($service.name) {
            'tradeflow-user' { @('USER_DB_USER','USER_DB_PASSWORD','JWT_PRIVATE_KEY','BOOTSTRAP_ENABLED','BOOTSTRAP_USERNAME','BOOTSTRAP_PASSWORD','PRODUCT_SERVICE_TOKEN','ORDER_SERVICE_TOKEN','PAYMENT_SERVICE_TOKEN','AFTERSALE_SERVICE_TOKEN','INTERNAL_SETTLEMENT_TOKEN') }
            'tradeflow-product' { @('PRODUCT_DB_USER','PRODUCT_DB_PASSWORD','PRODUCT_SERVICE_TOKEN','ORDER_SERVICE_TOKEN') }
            'tradeflow-order' { @('ORDER_DB_USER','ORDER_DB_PASSWORD','ORDER_MQ_USER','ORDER_MQ_PASSWORD','PRODUCT_SERVICE_TOKEN','ORDER_SERVICE_TOKEN','PAYMENT_SERVICE_TOKEN','AFTERSALE_SERVICE_TOKEN','INTERNAL_SETTLEMENT_TOKEN') }
            'tradeflow-payment' { @('PAYMENT_DB_USER','PAYMENT_DB_PASSWORD','PAYMENT_MQ_USER','PAYMENT_MQ_PASSWORD','PAYMENT_SERVICE_TOKEN','ORDER_SERVICE_TOKEN','CHANNEL_SERVICE_TOKEN','AFTERSALE_SERVICE_TOKEN','CALLBACK_SECRET','INTERNAL_SETTLEMENT_TOKEN') }
            'tradeflow-aftersale' { @('AFTERSALE_DB_USER','AFTERSALE_DB_PASSWORD','AFTERSALE_MQ_USER','AFTERSALE_MQ_PASSWORD','AFTERSALE_SERVICE_TOKEN','EVIDENCE_DIRECTORY') }
            'tradeflow-settlement' { @('SETTLEMENT_DB_USER','SETTLEMENT_DB_PASSWORD','INTERNAL_SETTLEMENT_TOKEN','SETTLEMENT_FEE_BPS','SETTLEMENT_FREEZE_DAYS','SETTLEMENT_WORKER_ENABLED','SETTLEMENT_SCAN_DELAY_MS') }
            'tradeflow-payment-simulator' { @('PAYMENT_SIMULATOR_DB_USER','PAYMENT_SIMULATOR_DB_PASSWORD','CHANNEL_SERVICE_TOKEN','CALLBACK_SECRET','SIMULATOR_ADMIN_TOKEN') }
            default { @() }
        }
        # 结算只通过受控HTTP同步本库，不继承MQ凭据或全局消息启用开关。
        if ($service.name -eq 'tradeflow-settlement') {
            $publicNames = @($publicNames | Where-Object { $_ -notin @('RABBITMQ_HOST','RABBITMQ_PORT','RABBITMQ_VHOST','TRADEFLOW_MESSAGING_ENABLED') })
        }
        # Redis凭据交给入口、商品缓存和邮箱验证码；SMTP授权码仅注入用户服务。
        if ($service.name -in @('tradeflow-gateway','tradeflow-product','tradeflow-user')) {
            $publicNames += @('REDIS_HOST','REDIS_PORT','REDIS_PASSWORD')
        }
        if ($service.name -eq 'tradeflow-gateway') {
            $publicNames += @('TRADEFLOW_ENTRY_KEY_PREFIX',
                'TRADEFLOW_ENTRY_TIMEOUT_MILLIS','TRADEFLOW_ENTRY_WINDOW_SECONDS','TRADEFLOW_ENTRY_LOGIN_LIMIT',
                'TRADEFLOW_ENTRY_LOGIN_TOTAL','TRADEFLOW_ENTRY_ORDER_LIMIT','TRADEFLOW_ENTRY_ORDER_TOTAL',
                'TRADEFLOW_ENTRY_QUERY_LIMIT','TRADEFLOW_ENTRY_QUERY_TOTAL','TRADEFLOW_ENTRY_RECOVERY_LIMIT',
                'TRADEFLOW_ENTRY_RECOVERY_TOTAL','TRADEFLOW_ENTRY_WRITE_LIMIT','TRADEFLOW_ENTRY_WRITE_TOTAL')
        }
        if ($service.name -eq 'tradeflow-user') {
            $publicNames += @('TRADEFLOW_MAIL_USERNAME','TRADEFLOW_MAIL_PASSWORD')
            $publicNames += @('TRADEFLOW_LOGIN_WINDOW_SECONDS','TRADEFLOW_LOGIN_ACCOUNT_LIMIT','TRADEFLOW_LOGIN_SOURCE_LIMIT')
        }
        $allowedNames = @($publicNames) + @($serviceNames | ForEach-Object { 'TRADEFLOW_' + $_ })
        foreach ($entry in @(Get-ChildItem Env:)) {
            $name = $entry.Name
            if ($name -notin $allowedNames) {
                $hidden[$name] = $entry.Value
                [Environment]::SetEnvironmentVariable($name, $null, 'Process')
            }
        }
        try {
            $process = Start-Process -FilePath $javaExecutable -ArgumentList $arguments -WorkingDirectory $projectRoot `
                -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logDir "$($service.name).out.log") `
                -RedirectStandardError (Join-Path $logDir "$($service.name).err.log")
        } finally {
            foreach ($name in $hidden.Keys) { [Environment]::SetEnvironmentVariable($name, $hidden[$name], 'Process') }
        }
        $records.Add([pscustomobject]@{
            service = $service.name; port = $service.port; pid = $process.Id;
            startedTicks = $process.StartTime.ToUniversalTime().Ticks; jarPath = $jarPath
        })
        Save-ProcessState
    }
    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $ready = @{}
    while ($ready.Count -lt $records.Count -and [DateTime]::UtcNow -lt $deadline) {
        foreach ($record in $records) {
            if (-not (Test-TradeFlowProcess -Record $record)) {
                throw "$($record.service) 进程已退出，请查看 .local/logs/$($record.service).out.log 和 err.log。"
            }
            if ($ready.ContainsKey($record.service)) { continue }
            try {
                $health = Invoke-RestMethod -Uri "http://127.0.0.1:$([int]$record.port + 10000)/actuator/health/readiness" -TimeoutSec 2
                if ($health.status -eq 'UP') {
                    $ready[$record.service] = $true
                    Write-Host "UP $($record.service) http://localhost:$($record.port)"
                }
            } catch { }
        }
        if ($ready.Count -lt $records.Count) { Start-Sleep -Milliseconds 500 }
    }
    if ($ready.Count -ne $records.Count) { throw '启动等待超时，请检查日志。' }
    Write-Host 'TradeFlow dev is ready: http://localhost:8080/'
} catch {
    foreach ($record in $records) { Stop-TradeFlowProcess -Record $record }
    throw
}
