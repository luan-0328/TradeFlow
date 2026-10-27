[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalProcess.ps1')
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$statePath = Join-Path $projectRoot '.local/web-processes.json'
$records = @()
if (Test-Path -LiteralPath $statePath) {
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    if ($state.projectRoot -ne $projectRoot) { throw '前端进程记录不属于当前项目。' }
    $records = @($state.processes | Where-Object { Test-TradeFlowProcess -Record $_ })
}
$node = (Get-Command node -ErrorAction Stop).Source
$vite = Join-Path $projectRoot 'node_modules/vite/bin/vite.js'
if (-not (Test-Path -LiteralPath $vite)) { throw '请先在项目根目录执行 npm.cmd ci。' }
New-Item -ItemType Directory -Path (Join-Path $projectRoot '.local') -Force | Out-Null
foreach ($entry in @(@{name='web-user';port=5173},@{name='web-admin';port=5174})) {
    if (@($records | Where-Object service -eq $entry.name).Count) { Write-Host "$($entry.name) 已启动。"; continue }
    if (Get-NetTCPConnection -LocalPort $entry.port -State Listen -ErrorAction SilentlyContinue) { throw "端口 $($entry.port) 已被其他进程占用，未停止该进程。" }
    # 直接记录Node启动时间，不认领shell子进程；仅监听本机，不连接远程环境。
    $process = Start-Process -FilePath $node -ArgumentList @(('"' + $vite + '"')) -WorkingDirectory (Join-Path $projectRoot $entry.name) -WindowStyle Hidden -RedirectStandardOutput (Join-Path $projectRoot ".local/$($entry.name).out.log") -RedirectStandardError (Join-Path $projectRoot ".local/$($entry.name).err.log") -PassThru
    $records += @{service=$entry.name;pid=$process.Id;startedTicks=$process.StartTime.ToUniversalTime().Ticks}
    @{projectRoot=$projectRoot;processes=@($records)} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $statePath -Encoding utf8
    Write-Host "$($entry.name): http://127.0.0.1:$($entry.port)"
}
