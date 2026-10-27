Set-StrictMode -Version Latest

# 进程记录带启动时间，避免 PID 被复用后误停其他 Java 程序。
function Test-TradeFlowProcess {
    param([Parameter(Mandatory)] $Record)
    try {
        $process = Get-Process -Id $Record.pid -ErrorAction Stop
        # 查询与读取属性之间进程可能退出，或PID已被不可访问的其他程序复用。
        # 无法确认启动时间时不认领、不停止该进程。
        $started = $process.StartTime
        if ($null -eq $started) { return $false }
        return $started.ToUniversalTime().Ticks -eq [long] $Record.startedTicks
    } catch {
        return $false
    }
}

# 只停止本项目记录且启动时间相同的进程，不使用按名称杀死所有 Java 的方式。
function Stop-TradeFlowProcess {
    param([Parameter(Mandatory)] $Record)
    if (Test-TradeFlowProcess -Record $Record) {
        Stop-Process -Id $Record.pid -ErrorAction Stop
        Write-Host "Stopped $($Record.service) (PID $($Record.pid))"
    }
}
