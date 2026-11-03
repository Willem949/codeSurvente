<#
.SYNOPSIS
    Arrête les instances lancées par start-instances.ps1.
#>
$root = Split-Path $PSScriptRoot -Parent
$pidFile = Join-Path $root "target\instances\pids.txt"
if (-not (Test-Path $pidFile)) {
    Write-Host "Aucune instance enregistrée."
    return
}
foreach ($id in Get-Content $pidFile) {
    try {
        Stop-Process -Id ([int]$id) -Force -ErrorAction Stop
        Write-Host "Instance arrêtée (PID $id)"
    } catch {
        Write-Host "PID $id déjà arrêté"
    }
}
Remove-Item $pidFile
