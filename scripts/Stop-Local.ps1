[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'LocalProcess.ps1')
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$statePath = Join-Path $projectRoot '.local/processes.json'
if (-not (Test-Path -LiteralPath $statePath)) { Write-Host '没有本项目的启动记录。'; return }
$state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
if ($state.projectRoot -ne $projectRoot) { throw '进程记录不属于当前项目，拒绝停止。' }
foreach ($record in $state.processes) { Stop-TradeFlowProcess -Record $record }
Write-Host '本项目记录的进程已停止；未操作其他 Java 或数据库进程。'
