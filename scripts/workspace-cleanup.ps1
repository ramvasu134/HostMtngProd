$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root

function Get-DirSizeMB {
    param([string]$path)
    if (-not (Test-Path $path)) { return 0 }
    try {
        $bytes = (Get-ChildItem -LiteralPath $path -Recurse -Force -ErrorAction SilentlyContinue |
                  Measure-Object -Sum Length -ErrorAction SilentlyContinue).Sum
        if ($null -eq $bytes) { return 0 }
        return [math]::Round($bytes / 1MB, 2)
    } catch { return 0 }
}

function Remove-IfExists {
    param([string]$path, [string]$label)
    if (Test-Path $path) {
        $size = Get-DirSizeMB $path
        try {
            Remove-Item -LiteralPath $path -Recurse -Force -ErrorAction Stop
            Write-Host ("  REMOVED  {0,-30} ({1} MB)" -f $label, $size)
            return $size
        } catch {
            Write-Host ("  SKIP     {0,-30} (locked: $($_.Exception.Message))" -f $label)
            return 0
        }
    } else {
        Write-Host ("  -        {0,-30} (not present)" -f $label)
        return 0
    }
}

Write-Host ''
Write-Host '=== WORKSPACE CLEANUP ==='
Write-Host "Root: $root"
Write-Host ''

# Build / dependency dirs ---------------------------------------------------
$totalReclaimed = 0
Write-Host '[1] Build artefacts'
$totalReclaimed += Remove-IfExists (Join-Path $root 'target')        'target/'
$totalReclaimed += Remove-IfExists (Join-Path $root 'dist')          'dist/'
$totalReclaimed += Remove-IfExists (Join-Path $root 'build')         'build/'
$totalReclaimed += Remove-IfExists (Join-Path $root 'out')           'out/'
$totalReclaimed += Remove-IfExists (Join-Path $root '.next')         '.next/'

Write-Host ''
Write-Host '[2] Node / Capacitor cache'
$totalReclaimed += Remove-IfExists (Join-Path $root 'node_modules')  'node_modules/'
$totalReclaimed += Remove-IfExists (Join-Path $root '.eslintcache')  '.eslintcache'
$totalReclaimed += Remove-IfExists (Join-Path $root '.turbo')        '.turbo/'
$totalReclaimed += Remove-IfExists (Join-Path $root '.gradle')       '.gradle/ (root)'

# Mobile build outputs (kept native projects intact, only intermediates) ---
Write-Host ''
Write-Host '[3] Mobile (Android / iOS) intermediates'

$androidDir = Join-Path $root 'android'
if (Test-Path $androidDir) {
    Write-Host '  android/ exists — running gradlew clean'
    Push-Location $androidDir
    try {
        & .\gradlew.bat clean | Out-Null
        Write-Host '  REMOVED  android/app/build (via gradlew clean)'
    } catch {
        Write-Host "  WARN     gradlew clean failed: $($_.Exception.Message)"
    } finally {
        Pop-Location
    }
    $totalReclaimed += Remove-IfExists (Join-Path $androidDir 'app\build')   'android/app/build/'
    $totalReclaimed += Remove-IfExists (Join-Path $androidDir 'build')       'android/build/'
    $totalReclaimed += Remove-IfExists (Join-Path $androidDir '.gradle')     'android/.gradle/'
} else {
    Write-Host '  -        android/ (not yet generated — `npx cap add android` first)'
}

$iosDir = Join-Path $root 'ios'
if (Test-Path $iosDir) {
    $totalReclaimed += Remove-IfExists (Join-Path $iosDir 'DerivedData')              'ios/DerivedData/'
    $totalReclaimed += Remove-IfExists (Join-Path $iosDir 'App\Pods')                 'ios/App/Pods/'
    $totalReclaimed += Remove-IfExists (Join-Path $iosDir 'App\App.xcworkspace\xcuserdata')  'ios xcuserdata'
} else {
    Write-Host '  -        ios/ (not yet generated — `npx cap add ios` first)'
}

# Logs ----------------------------------------------------------------------
Write-Host ''
Write-Host '[4] Logs'
$logFiles = Get-ChildItem -LiteralPath $root -Filter '*.log' -File -ErrorAction SilentlyContinue
$logFiles += Get-ChildItem -LiteralPath $root -Filter 'app*.log' -File -ErrorAction SilentlyContinue
$logFiles = $logFiles | Sort-Object FullName -Unique
if ($logFiles.Count -gt 0) {
    $sumKb = [math]::Round(($logFiles | Measure-Object -Sum Length).Sum / 1KB, 2)
    foreach ($f in $logFiles) {
        Remove-Item -LiteralPath $f.FullName -Force -ErrorAction SilentlyContinue
        Write-Host ("  REMOVED  $($f.Name)")
    }
    Write-Host ("  -- $($logFiles.Count) log file(s), $sumKb KB")
    $totalReclaimed += [math]::Round($sumKb / 1024, 2)
} else {
    Write-Host '  -        no *.log files in root'
}

if (Test-Path (Join-Path $root 'logs')) {
    $totalReclaimed += Remove-IfExists (Join-Path $root 'logs') 'logs/'
}

# OS junk -------------------------------------------------------------------
Write-Host ''
Write-Host '[5] OS junk files (.DS_Store / Thumbs.db / *.tmp / ~$*)'
$junk = @()
$junk += Get-ChildItem -LiteralPath $root -Recurse -Force -Filter '.DS_Store' -ErrorAction SilentlyContinue
$junk += Get-ChildItem -LiteralPath $root -Recurse -Force -Filter 'Thumbs.db' -ErrorAction SilentlyContinue
$junk += Get-ChildItem -LiteralPath $root -Recurse -Force -Filter '*.tmp'    -ErrorAction SilentlyContinue
$junk += Get-ChildItem -LiteralPath $root -Recurse -Force -Filter '~$*'      -ErrorAction SilentlyContinue
if ($junk.Count -gt 0) {
    foreach ($f in $junk) {
        if ($f.PSIsContainer) { continue }
        Remove-Item -LiteralPath $f.FullName -Force -ErrorAction SilentlyContinue
        Write-Host ("  REMOVED  $($f.FullName.Substring($root.Length))")
    }
} else {
    Write-Host '  -        none found'
}

Write-Host ''
Write-Host ('=== TOTAL RECLAIMED: ~{0} MB ===' -f $totalReclaimed)
