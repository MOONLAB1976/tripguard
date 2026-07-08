$projectRoot = "E:\Claude\com.mystrodriver\tripguard"
$apkSource = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"
$releaseDir = Join-Path $projectRoot "release"
$apkTarget = Join-Path $releaseDir "TripGuard-latest.apk"
$jsonSource = Join-Path $projectRoot "tripguard-update.json"
$jsonTarget = Join-Path $releaseDir "tripguard-update.json"

if (-not (Test-Path $apkSource)) {
    Write-Error "APK nao encontrado em $apkSource. Corre primeiro o build."
    exit 1
}

New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
Copy-Item $apkSource $apkTarget -Force
Copy-Item $jsonSource $jsonTarget -Force

Write-Host ""
Write-Host "Release preparada com sucesso:"
Write-Host "APK : $apkTarget"
Write-Host "JSON: $jsonTarget"
Write-Host ""
Write-Host "Antes de publicar, confirma que o campo apkUrl no JSON aponta para o teu repositorio GitHub."
