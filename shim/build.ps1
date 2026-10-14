# Optional shim-only build; regular APK builds run this task automatically.
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    & .\gradlew.bat :app:buildShim @args
    if ($LASTEXITCODE -ne 0) { throw "Shim build failed: $LASTEXITCODE" }
} finally {
    Pop-Location
}
