<#
    rooteros - one-command launcher for the HRF / Root boardgame app.

    On first run it creates the good-game (HSQLDB) database, then starts the
    Akka-HTTP server that serves the game. The prebuilt JavaScript is already
    committed, so no Scala.js build step is required just to play.

    Usage:
        .\run.ps1                # serve http://localhost:7070/play
        .\run.ps1 -Port 8080     # use a different port

    Requirements: sbt and a JDK (17+) on PATH.
#>
param([int]$Port = 7070)

$ErrorActionPreference = "Stop"
$root   = $PSScriptRoot
$gg     = Join-Path $root "good-game"
$dbBase = Join-Path $root "good-game-database"

$url    = "http://localhost:$Port"
$cdn    = "$url/hrf/"
$ggArgs = "../good-game-database ../haunt-roll-fail $url $cdn $Port"

Set-Location $gg

if (-not (Test-Path "$dbBase.script")) {
    Write-Host "[rooteros] Creating database..." -ForegroundColor Cyan
    & sbt "run create $ggArgs"
}
else {
    Write-Host "[rooteros] Database already exists - skipping create." -ForegroundColor DarkGray
}

Write-Host "[rooteros] Starting server -> $url/play  (Ctrl+C to stop)" -ForegroundColor Green
& sbt "run run $ggArgs"
