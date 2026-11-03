<#
.SYNOPSIS
    Démarre N instances de l'application (java -jar) sur des ports consécutifs, sans Docker.

.EXAMPLE
    .\scripts\start-instances.ps1                          # 1 instance sur 8081, profil bench
    .\scripts\start-instances.ps1 -Count 3 -Redis -Queue   # 3 instances partageant Redis et la file d'attente
    .\scripts\start-instances.ps1 -VirtualThreads -NoBuild
#>
param(
    [int]$Count = 1,
    [int]$FirstPort = 8081,
    [switch]$Redis,
    [switch]$Queue,
    [switch]$VirtualThreads,
    [string]$Profile = "bench",
    [switch]$NoBuild,
    [string[]]$ExtraArgs = @()
)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$jar = Join-Path $root "target\flashsale-0.0.1-SNAPSHOT.jar"
$logDir = Join-Path $root "target\instances"

if (-not $NoBuild) {
    Write-Host "Construction du jar..."
    & "$root\mvnw.cmd" -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "La construction du jar a échoué" }
}
New-Item -ItemType Directory -Force $logDir | Out-Null

function Flag([bool]$value) { if ($value) { "true" } else { "false" } }

$started = @()
for ($i = 0; $i -lt $Count; $i++) {
    $port = $FirstPort + $i
    $javaArgs = @(
        "-jar", "`"$jar`"",
        "--server.port=$port",
        # Local uniquement : écouter sur toutes les interfaces fait apparaître le pare-feu Windows (droits admin)
        "--server.address=127.0.0.1",
        "--spring.profiles.active=$Profile",
        "--flashsale.redis.enabled=$(Flag $Redis.IsPresent)",
        "--flashsale.queue.enabled=$(Flag $Queue.IsPresent)",
        "--spring.threads.virtual.enabled=$(Flag $VirtualThreads.IsPresent)"
    ) + $ExtraArgs
    $process = Start-Process java -ArgumentList $javaArgs -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput "$logDir\app-$port.log" -RedirectStandardError "$logDir\app-$port.err.log"
    $started += [pscustomobject]@{ Port = $port; Pid = $process.Id }
}
$started | ForEach-Object { "$($_.Pid)" } | Set-Content "$logDir\pids.txt"

# Attendre que chaque instance réponde UP (Flyway, Hibernate, Tomcat : quelques secondes)
foreach ($instance in $started) {
    $deadline = (Get-Date).AddSeconds(90)
    while ($true) {
        try {
            $health = Invoke-RestMethod "http://localhost:$($instance.Port)/actuator/health" -TimeoutSec 2
            if ($health.status -eq "UP") { break }
        } catch { }
        if ((Get-Date) -gt $deadline) { throw "L'instance du port $($instance.Port) ne démarre pas, voir $logDir\app-$($instance.Port).log" }
        Start-Sleep -Milliseconds 500
    }
    Write-Host "Instance prête : http://localhost:$($instance.Port) (PID $($instance.Pid))"
}
