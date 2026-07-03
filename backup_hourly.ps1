$ErrorActionPreference = "Stop"

$source = "E:\Claude\com.mystrodriver\tripguard"
$backupRoot = "E:\Claude\com.mystrodriver\tripguard_backups"
$timestamp = Get-Date -Format "yyyy-MM-dd_HH-mm-ss"
$target = Join-Path $backupRoot $timestamp
$logFile = Join-Path $backupRoot "backup.log"

New-Item -ItemType Directory -Force -Path $backupRoot | Out-Null
New-Item -ItemType Directory -Force -Path $target | Out-Null

$excludeDirs = @(
    ".gradle",
    "build",
    ".idea"
)

$excludeFiles = @(
    "_assemble_debug.err.log",
    "_assemble_debug.out.log",
    "_assemble_full.err.log",
    "_assemble_full.out.log"
)

$robocopyArgs = @(
    $source,
    $target,
    "/E",
    "/R:1",
    "/W:1",
    "/NFL",
    "/NDL",
    "/NJH",
    "/NJS",
    "/NP",
    "/XD"
) + $excludeDirs + @("/XF") + $excludeFiles

& robocopy @robocopyArgs | Out-Null
$robocopyExitCode = $LASTEXITCODE

if ($robocopyExitCode -ge 8) {
    throw "Robocopy falhou com codigo $robocopyExitCode"
}

"[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] Backup criado em $target" | Add-Content -Path $logFile

$snapshots = Get-ChildItem -Path $backupRoot -Directory |
    Sort-Object Name -Descending

$snapshots |
    Select-Object -Skip 72 |
    Remove-Item -Recurse -Force
