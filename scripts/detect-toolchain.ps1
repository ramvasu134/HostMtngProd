$ErrorActionPreference = 'SilentlyContinue'

function Try-Cmd {
    param([string]$cmd, [string[]]$args)
    try {
        $exe = Get-Command $cmd -ErrorAction Stop
        $out = & $exe @args 2>&1 | Select-Object -First 1
        return ('  OK  {0,-8} -> {1}  [{2}]' -f $cmd, $out, $exe.Source)
    } catch {
        return ('  --  {0,-8} not found on PATH' -f $cmd)
    }
}

Write-Host '=== Toolchain detection ==='
Write-Host (Try-Cmd 'node'   '--version')
Write-Host (Try-Cmd 'npm'    '--version')
Write-Host (Try-Cmd 'npx'    '--version')
Write-Host (Try-Cmd 'java'   '--version')
Write-Host (Try-Cmd 'javac'  '--version')
Write-Host (Try-Cmd 'gradle' '--version')
Write-Host (Try-Cmd 'adb'    'version')

Write-Host ''
Write-Host '=== Env vars ==='
Write-Host ('  JAVA_HOME       = ' + [string]$env:JAVA_HOME)
Write-Host ('  ANDROID_HOME    = ' + [string]$env:ANDROID_HOME)
Write-Host ('  ANDROID_SDK_ROOT= ' + [string]$env:ANDROID_SDK_ROOT)
Write-Host ('  CAPACITOR_ANDROID_STUDIO_PATH = ' + [string]$env:CAPACITOR_ANDROID_STUDIO_PATH)

Write-Host ''
Write-Host '=== Common Android Studio install paths ==='
$candidates = @(
    "$env:LOCALAPPDATA\Android\Sdk",
    "$env:USERPROFILE\AppData\Local\Android\Sdk",
    "C:\Android\Sdk",
    "C:\Program Files\Android\Android Studio"
)
foreach ($c in $candidates) {
    if (Test-Path $c) {
        Write-Host ('  FOUND  ' + $c)
    } else {
        Write-Host ('  --     ' + $c)
    }
}
