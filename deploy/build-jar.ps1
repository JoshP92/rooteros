<#
    Rebuild the good-game fat JAR and stage it at deploy/good-game.jar
    (which the Docker image copies in). Run this whenever good-game/*.scala changes,
    then commit deploy/good-game.jar and rebuild the container on the NAS.

    Usage:   .\deploy\build-jar.ps1
    Requires: sbt + JDK 17 on PATH.
#>
$ErrorActionPreference = "Stop"
$deploy = $PSScriptRoot
$root   = Split-Path $deploy -Parent

Push-Location (Join-Path $root "good-game")
try {
    Write-Host "[build-jar] sbt assembly ..." -ForegroundColor Cyan
    & sbt assembly
    if ($LASTEXITCODE -ne 0) { throw "sbt assembly failed (exit $LASTEXITCODE)" }

    $jar = Get-ChildItem "target/scala-2.13" -Filter "*assembly*.jar" |
           Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw "assembly JAR not found under good-game/target/scala-2.13" }

    Copy-Item $jar.FullName (Join-Path $deploy "good-game.jar") -Force
    Write-Host ("[build-jar] staged -> deploy/good-game.jar  ({0:N1} MB)" -f ($jar.Length / 1MB)) -ForegroundColor Green
}
finally { Pop-Location }
