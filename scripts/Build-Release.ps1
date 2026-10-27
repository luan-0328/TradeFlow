[CmdletBinding()]
param([switch] $Restart)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalProcess.ps1')
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$runningJava = $false
$runningWeb = $false
# 构建可能替换被JVM或Node占用的文件；只有显式-Restart才停止当前项目，绝不按进程名批量杀死。
foreach ($kind in @('processes', 'web-processes')) {
    $path = Join-Path $projectRoot ".local/$kind.json"
    if (Test-Path -LiteralPath $path) {
        $state = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
        if ($state.projectRoot -ne $projectRoot) { throw '运行记录不属于当前项目。' }
        $alive = @($state.processes | Where-Object { Test-TradeFlowProcess -Record $_ })
        if ($kind -eq 'processes') { $runningJava = $alive.Count -gt 0 } else { $runningWeb = $alive.Count -gt 0 }
    }
}
if (($runningJava -or $runningWeb) -and -not $Restart) { throw '项目正在运行。请先停止，或使用 -Restart 构建成功后恢复原有服务。' }
if ($runningWeb) { & (Join-Path $PSScriptRoot 'Stop-Web.ps1') }
if ($runningJava) { & (Join-Path $PSScriptRoot 'Stop-Local.ps1') }
Push-Location $projectRoot
try {
    & ./mvnw.cmd -B -ntp '-Dmaven.test.skip=true' package
    if ($LASTEXITCODE -ne 0) { throw 'Java构建失败，未打包或启动混合版本。' }
    & npm.cmd ci --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败，未交付。' }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw '前端构建失败，未交付。' }
    & python scripts/Package-Release.py
    if ($LASTEXITCODE -ne 0) { throw '交付包生成失败。' }
    # 只恢复构建前本来运行的服务；失败保留日志与partial，不重启半构建版本。
    if ($runningJava) { & (Join-Path $PSScriptRoot 'Start-Local.ps1') }
    if ($runningWeb) { & (Join-Path $PSScriptRoot 'Start-Web.ps1') }
} finally { Pop-Location }
