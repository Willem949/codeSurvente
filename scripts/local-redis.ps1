<#
.SYNOPSIS
    Démarre un serveur compatible Redis (jedis-mock, 100 % Java) sur le port 6379.

.DESCRIPTION
    Remplace Redis sur ce poste (pas de Docker, pas de Redis Windows officiel).
    Suffisant pour démontrer le partage d'état entre plusieurs instances ;
    PAS représentatif des performances d'un vrai Redis.
    Le serveur tourne en arrière-plan ; arrêt : .\scripts\local-redis.ps1 -Stop
#>
param(
    [int]$Port = 6379,
    [switch]$Stop
)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$pidFile = Join-Path $root "target\local-redis.pid"

if ($Stop) {
    if (Test-Path $pidFile) {
        Stop-Process -Id ([int](Get-Content $pidFile)) -Force -ErrorAction SilentlyContinue
        Remove-Item $pidFile
        Write-Host "Redis local arrêté."
    }
    return
}

& "$root\mvnw.cmd" -q test-compile dependency:build-classpath "-Dmdep.outputFile=target/test-classpath.txt" "-Dmdep.includeScope=test"
if ($LASTEXITCODE -ne 0) { throw "Préparation du classpath impossible" }
$classpath = "$root\target\test-classes;$root\target\classes;" + (Get-Content "$root\target\test-classpath.txt" -Raw).Trim()

$process = Start-Process java -ArgumentList @("-cp", "`"$classpath`"", "com.wk.flashsale.support.LocalRedisServer", "$Port") `
    -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput "$root\target\local-redis.log" -RedirectStandardError "$root\target\local-redis.err.log"
$process.Id | Set-Content $pidFile

$deadline = (Get-Date).AddSeconds(30)
while (-not (Test-NetConnection localhost -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue)) {
    if ((Get-Date) -gt $deadline) { throw "Redis local ne démarre pas, voir target\local-redis.err.log" }
    Start-Sleep -Milliseconds 500
}
Write-Host "Redis local (jedis-mock) prêt sur le port $Port (PID $($process.Id))"
