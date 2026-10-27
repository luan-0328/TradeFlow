[CmdletBinding()]
param([switch] $Build)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'LocalProcess.ps1')
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$statePath = Join-Path $projectRoot '.local/remote-tunnel.json'
if (-not (Test-Path -LiteralPath $statePath)) {
    throw '先运行 python scripts/Remote-Tunnel.py start 建立 SSH 隧道。'
}
$tunnel = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
if ($tunnel.phase -ne 'ready' -or -not (Test-TradeFlowProcess -Record $tunnel)) {
    throw '隧道未就绪或已断开；先显式重建隧道，本脚本不会自动重新登录服务器。'
}
# 仅导入本项目组件账号；密码不打印，也不写入 JVM 命令行。
$settings = Get-Content -LiteralPath (Join-Path $projectRoot '.local/remote-env.json') -Raw | ConvertFrom-Json
foreach ($entry in $settings.PSObject.Properties) {
    [Environment]::SetEnvironmentVariable($entry.Name, [string] $entry.Value, 'Process')
}
# 兼容已有隧道配置文件：显式导入配置中心时才开启，与必需的服务发现分离。
if ($env:NACOS_CONFIG_IMPORT) { $env:TRADEFLOW_NACOS_CONFIG_ENABLED = 'true' }
# 服务发现地址由应用固定服务名决定，不生成直连或网关上游覆盖。
& (Join-Path $PSScriptRoot 'Start-Local.ps1') -Build:$Build
