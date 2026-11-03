<#
.SYNOPSIS
    Étape 3 : compare toutes les stratégies sous le même tir Gatling, avec et sans virtual threads,
    et écrit le tableau de résultats dans docs/BENCHMARK.md.

.DESCRIPTION
    Nécessite le PostgreSQL local démarré. Avec -Redis, nécessite aussi un Redis
    (.\scripts\local-redis.ps1) : la ligne "redis" est alors indicative, le serveur local n'étant pas un vrai Redis.

    Par défaut, chaque acteur a ses propres cœurs (affinité processeur, sans droits admin) et le tir
    est à 500 req/s : c'est ainsi qu'une seule machine donne des mesures propres (0 KO).

.EXAMPLE
    .\scripts\benchmark.ps1 -Redis -SkipVirtualThreads             # la référence : docs/BENCHMARK.md
    .\scripts\benchmark.ps1 -RampSeconds 10 -Output docs\BENCHMARK-1000rps-isole.md
    .\scripts\benchmark.ps1 -RampSeconds 10 -NoCpuIsolation -Output docs\BENCHMARK-1000rps-sans-isolation.md
    .\scripts\benchmark.ps1 -Strategies atomic,pessimistic -PoolSize 30 -RampSeconds 10 -NoCpuIsolation -Output docs\BENCHMARK-1000rps-pool30-sans-isolation.md
#>
param(
    [string[]]$Strategies = @("naive", "optimistic", "pessimistic", "atomic", "check"),
    [int]$Users = 10000,
    # 20 s = 500 req/s : ce que ce portable tient sans saturer (0 KO). 10 s = test de surcharge
    [int]$RampSeconds = 20,
    [int]$Instances = 1,
    [switch]$Redis,
    [switch]$SkipVirtualThreads,
    [string]$Output = "docs\BENCHMARK.md",
    # Taille du pool de connexions Postgres de chaque instance (10 = défaut de Hikari)
    [int]$PoolSize = 10,
    # Par défaut, chaque acteur a ses propres cœurs (voir Set-CpuIsolation). Ce switch le désactive, pour comparer
    [switch]$NoCpuIsolation
)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
if ($Redis -and -not ($Strategies -contains "redis")) { $Strategies += "redis" }

$baseUrls = (0..($Instances - 1) | ForEach-Object { "http://127.0.0.1:$(8081 + $_)" }) -join ","

# Isolation des cœurs : sans elle, Gatling, l'application et Postgres se volent le processeur.
# Gatling prend du retard pour envoyer et chronométrer, le thread qui accepte les connexions de Tomcat
# ne passe plus (d'où les "Connection refused"), et on mesure la bagarre au lieu des stratégies.
# Avec une affinité processeur, chacun a son tiers des cœurs, comme sur trois machines.
# Windows l'autorise sans droits admin sur ses propres processus.
$cpuCount = [Environment]::ProcessorCount
$third = [Math]::Floor($cpuCount / 3)
function Get-CoreMask([int]$first, [int]$count) { ([long][Math]::Pow(2, $count) - 1) -shl $first }
$gatlingMask = Get-CoreMask 0 $third
$appMask = Get-CoreMask $third $third
$dataMask = Get-CoreMask (2 * $third) ($cpuCount - 2 * $third)
$allMask = Get-CoreMask 0 $cpuCount

function Set-Affinity($processes, [long]$mask) {
    foreach ($process in @($processes)) {
        if ($null -eq $process) { continue }
        try { $process.ProcessorAffinity = [IntPtr]$mask } catch { Write-Warning "Affinité impossible pour le PID $($process.Id) : $_" }
    }
}

function Set-CpuIsolation {
    # Postgres : le processus principal ET les connexions déjà ouvertes par les pools ;
    # les connexions suivantes héritent de l'affinité du processus principal
    Set-Affinity (Get-Process postgres -ErrorAction SilentlyContinue) $dataMask
    $redisPid = Join-Path $root "target\local-redis.pid"
    if (Test-Path $redisPid) { Set-Affinity (Get-Process -Id ([int](Get-Content $redisPid)) -ErrorAction SilentlyContinue) $dataMask }
    Set-Affinity (Get-Content (Join-Path $root "target\instances\pids.txt") | ForEach-Object { Get-Process -Id ([int]$_) -ErrorAction SilentlyContinue }) $appMask
    # Ce PowerShell : Maven puis Gatling, lancés depuis lui, héritent de son affinité
    Set-Affinity ([Diagnostics.Process]::GetCurrentProcess()) $gatlingMask
}

function Reset-CpuIsolation {
    Set-Affinity ([Diagnostics.Process]::GetCurrentProcess()) $allMask
    Set-Affinity (Get-Process postgres -ErrorAction SilentlyContinue) $allMask
    $redisPid = Join-Path $root "target\local-redis.pid"
    if (Test-Path $redisPid) { Set-Affinity (Get-Process -Id ([int](Get-Content $redisPid)) -ErrorAction SilentlyContinue) $allMask }
}

function Invoke-Shot([string]$strategy, [int]$users) {
    $lines = & "$root\mvnw.cmd" -q gatling:test "-Dstrategy=$strategy" "-Dusers=$users" "-DrampSeconds=$RampSeconds" "-DbaseUrls=$baseUrls" 2>&1 |
        ForEach-Object { "$_" }
    # Synthèse "Global Information" affichée par Gatling : colonnes Total | OK | KO
    function Column([string]$label, [int]$index) {
        $line = $lines | Where-Object { $_ -match "^> $label" } | Select-Object -Last 1
        if (-not $line) { return "?" }
        (($line -split '\|')[$index]).Trim() -replace ',', ''
    }
    $checkLine = $lines | Where-Object { $_ -match '^\[CHECK' } | Select-Object -Last 1
    $check = if ($checkLine) { ($checkLine -replace '^\[CHECK [^\]]+\] ', '') | ConvertFrom-Json } else { $null }
    $ko = Column "request count" 3
    [pscustomobject]@{
        Strategy     = $strategy
        Requests     = Column "request count" 1
        KO           = if ($ko -eq "-") { "0" } else { $ko }
        Throughput   = Column "mean throughput" 1
        P50          = Column "response time 50th percentile" 1
        P95          = Column "response time 95th percentile" 1
        P99          = Column "response time 99th percentile" 1
        Reservations = if ($check) { $check.reservations } else { "?" }
        Oversold     = if ($check) { $check.oversold } else { "?" }
        Consistent   = if ($check) { $check.consistent } else { "?" }
    }
}

$modes = @($false) + $(if ($SkipVirtualThreads) { @() } else { @($true) })
$results = @()
$built = $false
foreach ($virtual in $modes) {
    $startArgs = @{ Count = $Instances; Redis = $Redis; VirtualThreads = $virtual; NoBuild = $built
                    ExtraArgs = @("--spring.datasource.hikari.maximum-pool-size=$PoolSize") }
    & "$PSScriptRoot\start-instances.ps1" @startArgs
    $built = $true
    try {
        if (-not $NoCpuIsolation) { Set-CpuIsolation }
        # Tir à blanc : laisser le JIT compiler le code chaud avant de mesurer
        Write-Host "Préchauffage..."
        Invoke-Shot "atomic" 3000 | Out-Null
        foreach ($strategy in $Strategies) {
            Write-Host "Tir : $strategy (virtual threads = $virtual)"
            $row = Invoke-Shot $strategy $Users
            $row | Add-Member VirtualThreads $virtual
            $row | Format-List | Out-String | Write-Host
            $results += $row
        }
    } finally {
        if (-not $NoCpuIsolation) { Reset-CpuIsolation }
        & "$PSScriptRoot\stop-instances.ps1"
    }
}

$cpu = (Get-CimInstance Win32_Processor | Select-Object -First 1).Name.Trim()
$md = @(
    "# Résultats des tests de charge",
    "",
    "Généré par ``scripts/benchmark.ps1`` le $(Get-Date -Format 'yyyy-MM-dd HH:mm'). Ne pas modifier à la main, relancer le script.",
    "",
    "- Tir : $Users acheteurs en $RampSeconds s (rampe), 500 places, $Instances instance(s), Gatling sur la même machine",
    "- Machine : $cpu, PostgreSQL 17 local, pool Hikari de $PoolSize connexions par instance",
    $(if ($NoCpuIsolation) { "- **Sans isolation des cœurs** : Gatling, l'application et Postgres se partagent les $cpuCount cœurs logiques" }
      else { "- **Cœurs isolés** (affinité processeur) : Gatling sur les cœurs logiques 0-$($third - 1), l'application sur $third-$(2 * $third - 1), Postgres et Redis sur $(2 * $third)-$($cpuCount - 1)" }),
    "- Connexions TCP partagées par Gatling (comme derrière un load balancer) : sans ça, Windows refuse les connexions au-delà de ~600/s et on mesure le système, pas les stratégies",
    "- 201 (place obtenue) et 409 (complet) sont des réponses normales ; KO = erreurs 5xx ou timeouts",
    "- Ligne ``redis`` indicative seulement : le Redis local est jedis-mock (serveur de test en Java, une commande à la fois), bien plus lent qu'un vrai Redis",
    "- Latences en millisecondes, débit en requêtes par seconde",
    "",
    "| Stratégie | Virtual threads | Réservations | Survente | Base cohérente | KO | Débit (req/s) | p50 | p95 | p99 |",
    "|---|---|---|---|---|---|---|---|---|---|"
)
foreach ($r in $results) {
    $vt = if ($r.VirtualThreads) { "oui" } else { "non" }
    $md += "| $($r.Strategy) | $vt | $($r.Reservations) | $($r.Oversold) | $($r.Consistent) | $($r.KO) | $($r.Throughput) | $($r.P50) | $($r.P95) | $($r.P99) |"
}
[IO.File]::WriteAllLines((Join-Path $root $Output), $md, (New-Object Text.UTF8Encoding $false))
Write-Host "Résultats écrits dans $Output"
