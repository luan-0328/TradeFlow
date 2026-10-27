[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalProcess.ps1')
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$statePath = Join-Path $projectRoot '.local/web-processes.json'
if (-not (Test-Path -LiteralPath $statePath)) { Write-Host '没有前端启动记录。'; return }
$state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
if ($state.projectRoot -ne $projectRoot) { throw '进程记录不属于当前项目，拒绝停止。' }
foreach ($record in $state.processes) { Stop-TradeFlowProcess -Record $record }
