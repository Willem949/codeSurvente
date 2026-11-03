<#
.SYNOPSIS
    Démarre (ou arrête avec -Stop) Prometheus et Grafana installés en ZIP dans %USERPROFILE%\tools.

.DESCRIPTION
    Prometheus : http://localhost:9090 (cibles : http://localhost:9090/targets)
    Grafana    : http://localhost:3000 (admin / admin), dashboard "Flash Sale" provisionné automatiquement.
    Le premier démarrage de Grafana peut prendre plusieurs minutes (enregistrement des plugins).
#>
param([switch]$Stop)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$tools = Join-Path $env:USERPROFILE "tools"
$logDir = Join-Path $root "target\ops"
$pidFile = Join-Path $logDir "pids.txt"

if ($Stop) {
    if (Test-Path $pidFile) {
        foreach ($id in (Get-Content $pidFile) -split ' ') { Stop-Process -Id ([int]$id) -Force -ErrorAction SilentlyContinue }
        Remove-Item $pidFile
        Write-Host "Prometheus et Grafana arrêtés."
    }
    return
}

foreach ($exe in "$tools\prometheus\prometheus.exe", "$tools\grafana\bin\grafana.exe") {
    if (-not (Test-Path $exe)) { throw "$exe introuvable : voir ops/README.md pour l'installation" }
}
New-Item -ItemType Directory -Force $logDir | Out-Null

$prometheus = Start-Process "$tools\prometheus\prometheus.exe" -WorkingDirectory "$tools\prometheus" -WindowStyle Hidden -PassThru `
    -ArgumentList @("--config.file=`"$root\ops\prometheus\prometheus.yml`"", "--storage.tsdb.path=`"$tools\prometheus\data`"",
                    # Local uniquement : écouter sur toutes les interfaces déclenche le pare-feu Windows (droits admin)
                    "--web.listen-address=127.0.0.1:9090") `
    -RedirectStandardError "$logDir\prometheus.log"

# Grafana lit le dossier de provisioning du projet : source Prometheus et dashboard créés au démarrage
$env:GF_PATHS_PROVISIONING = "$root\ops\grafana\provisioning"
# Local uniquement, pour la même raison que Prometheus
$env:GF_SERVER_HTTP_ADDR = "127.0.0.1"
$grafana = Start-Process "$tools\grafana\bin\grafana.exe" -WorkingDirectory "$tools\grafana" -WindowStyle Hidden -PassThru `
    -ArgumentList @("server", "--homepath", "`"$tools\grafana`"") `
    -RedirectStandardOutput "$logDir\grafana.log" -RedirectStandardError "$logDir\grafana.err.log"

"$($prometheus.Id) $($grafana.Id)" | Set-Content $pidFile

$deadline = (Get-Date).AddMinutes(5)
while ($true) {
    try { if ((Invoke-RestMethod http://localhost:3000/api/health -TimeoutSec 3).database -eq "ok") { break } } catch { }
    if ((Get-Date) -gt $deadline) { throw "Grafana ne répond pas, voir $logDir\grafana.log" }
    Start-Sleep 3
}
Write-Host "Prometheus : http://localhost:9090/targets"
Write-Host "Grafana    : http://localhost:3000/d/flashsale (admin / admin)"
