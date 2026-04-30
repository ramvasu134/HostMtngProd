$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root

function Get-DirSizeMB {
    param([string]$path)
    if (-not (Test-Path $path)) { return 0 }
    $bytes = (Get-ChildItem -LiteralPath $path -Recurse -Force -ErrorAction SilentlyContinue |
              Measure-Object -Sum Length -ErrorAction SilentlyContinue).Sum
    if ($null -eq $bytes) { return 0 }
    return [math]::Round($bytes / 1MB, 2)
}

Write-Host ''
Write-Host '=== POST-CLEANUP WORKSPACE SIZE ==='
Write-Host ''
$dirs = @(
    'src',
    'mobile',
    'www',
    'scripts',
    '.mvn',
    'data',
    'uploads',
    'recordings',
    'profile-photos',
    'target',
    'dist',
    'build',
    'out',
    'node_modules',
    'android',
    'ios'
)

$total = 0
foreach ($d in $dirs) {
    $p = Join-Path $root $d
    if (Test-Path $p) {
        $sz = Get-DirSizeMB $p
        $total += $sz
        Write-Host ("  {0,-22} {1,10:N2} MB" -f $d, $sz)
    } else {
        Write-Host ("  {0,-22} {1,10}" -f $d, '(absent)')
    }
}
$rootFiles = (Get-ChildItem -LiteralPath $root -File -Force | Measure-Object -Sum Length).Sum
$rootMb = [math]::Round($rootFiles / 1MB, 2)
$total += $rootMb
Write-Host ("  {0,-22} {1,10:N2} MB" -f '(root files)', $rootMb)
Write-Host ''
Write-Host ('  TOTAL on disk:        {0,10:N2} MB' -f $total)

# Quick top-level listing (dirs only) to spot anything stray
Write-Host ''
Write-Host '=== TOP-LEVEL ENTRIES ==='
Get-ChildItem -LiteralPath $root -Force | Sort-Object Mode, Name | ForEach-Object {
    $tag = if ($_.PSIsContainer) { 'd' } else { 'f' }
    Write-Host ("  $tag  $($_.Name)")
}
