$ErrorActionPreference = "Stop"

$projectRoot = $PSScriptRoot
$repo = "MOONLAB1976/tripguard"
$appGradle = Join-Path $projectRoot "app\build.gradle.kts"
$updateJson = Join-Path $projectRoot "tripguard-update.json"
$releaseDir = Join-Path $projectRoot "release"
$apkPath = Join-Path $releaseDir "TripGuard-latest.apk"
$releaseJson = Join-Path $releaseDir "tripguard-update.json"

function Get-GradleValue {
    param(
        [string]$Path,
        [string]$Pattern
    )
    $match = Select-String -Path $Path -Pattern $Pattern | Select-Object -First 1
    if (-not $match) {
        throw "Nao encontrei o padrao '$Pattern' em $Path"
    }
    return $match.Matches[0].Groups[1].Value
}

$versionCode = [int](Get-GradleValue -Path $appGradle -Pattern 'versionCode\s*=\s*(\d+)')
$versionName = Get-GradleValue -Path $appGradle -Pattern 'versionName\s*=\s*"([^"]+)"'
$tag = "v$versionName"
$apkUrl = "https://github.com/$repo/releases/latest/download/TripGuard-latest.apk"
$publishedAt = Get-Date -Format "yyyy-MM-dd HH:mm"

Set-Location $projectRoot

Write-Host "A compilar TripGuard..."
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleDebug | Out-Host

Write-Host "A atualizar tripguard-update.json..."
$json = @"
{
  "versionCode": $versionCode,
  "versionName": "$versionName",
  "publishedAt": "$publishedAt",
  "apkUrl": "$apkUrl",
  "notes": "Versao publicada automaticamente a partir do projeto local."
}
"@
Set-Content -Path $updateJson -Value $json -Encoding UTF8

Write-Host "A preparar ficheiros de release..."
powershell -ExecutionPolicy Bypass -File (Join-Path $projectRoot "prepare-github-release.ps1") | Out-Host

Write-Host "A enviar alteracoes do repositorio..."
git add `
    app\build.gradle.kts `
    app\src\main\java\pt\tripguard\app\rules\TripOfferParser.kt `
    app\src\main\java\pt\tripguard\app\service\TripAccessibilityService.kt `
    publish-github-update.ps1 `
    tripguard-update.json `
    docs\index.html `
    site\index.html `
    release\tripguard-update.json
$pending = git diff --cached --name-only
if ($pending) {
    git commit -m "Publish TripGuard $versionName" | Out-Host
    git push origin master | Out-Host
} else {
    Write-Host "Sem alteracoes de codigo/documentacao para commit."
}

Write-Host "A publicar APK no GitHub Release..."
gh release view $tag --repo $repo *> $null
$releaseExists = ($LASTEXITCODE -eq 0)

if ($releaseExists) {
    gh release upload $tag $apkPath --repo $repo --clobber | Out-Host
} else {
    gh release create $tag $apkPath --repo $repo --title "TripGuard $versionName" --notes "Versao $versionName publicada pelo script local." | Out-Host
}

if ($LASTEXITCODE -ne 0) {
    throw "Falhou a publicacao da release $tag"
}

Write-Host ""
Write-Host "Publicado com sucesso:"
Write-Host "Repo  : https://github.com/$repo"
Write-Host "Site  : https://moonlab1976.github.io/tripguard/"
Write-Host "APK   : $apkUrl"
Write-Host "JSON  : https://raw.githubusercontent.com/MOONLAB1976/tripguard/master/tripguard-update.json"
