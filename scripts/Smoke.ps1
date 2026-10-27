[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$checks = [System.Collections.Generic.List[object]]::new()
foreach ($code in @('user', 'product', 'order', 'payment', 'aftersale', 'payment-simulator')) {
    $url = "http://127.0.0.1:8080/api/v1/$code/system/info"
    $response = Invoke-RestMethod -Uri $url -TimeoutSec 5 -Headers @{ 'X-User-Id' = 'forged-admin' }
    if ($response.code -ne 'OK' -or $response.data.serviceName -ne "tradeflow-$code" -or $response.data.stage -ne 'SKELETON') {
        throw "$code 的网关响应不符合实际骨架契约。"
    }
    $checks.Add([pscustomobject]@{ service = "tradeflow-$code"; gatewayRoute = 'PASS'; stage = $response.data.stage })
}
foreach ($port in 8080..8086) {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/actuator/health" -TimeoutSec 5
    if ($health.status -ne 'UP') { throw "端口 $port 健康检查失败。" }
}
foreach ($path in @('/internal/v1/refunds', '/actuator/env', '/api/v1/orders')) {
    $rejected = $false
    try { Invoke-WebRequest -Uri "http://127.0.0.1:8080$path" -TimeoutSec 5 | Out-Null }
    catch {
        if ($null -ne $_.Exception.Response -and [int] $_.Exception.Response.StatusCode -eq 403) { $rejected = $true }
        else { throw }
    }
    if (-not $rejected) { throw "未实现或内部入口不应开放：$path" }
}
$runtimeDir = Join-Path $projectRoot '.local'
New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
@{ executedAt = [DateTime]::UtcNow.ToString('o'); serviceChecks = @($checks.ToArray());
   healthEndpoints = 7; gatewayRoutes = 6; deniedPaths = 3; profile = 'local' } |
    ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runtimeDir 'smoke-results.json') -Encoding utf8
$checks | Format-Table
Write-Host 'PASS: 7 health endpoints, 6 gateway routes, 3 protected paths.'
