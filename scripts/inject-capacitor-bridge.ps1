$ErrorActionPreference = 'Stop'
$root = Join-Path $PSScriptRoot '..\src\main\resources\templates'
$root = (Resolve-Path $root).Path
$files = Get-ChildItem -Path $root -Filter *.html -Recurse
$tag   = '    <script src="/js/capacitor-bridge.js" defer></script>'

foreach ($f in $files) {
    $content = Get-Content -Raw -LiteralPath $f.FullName
    if ($content -match 'capacitor-bridge\.js') {
        Write-Host "skip: $($f.FullName) (already injected)"
        continue
    }
    # Insert the tag right before the FIRST </head>
    $idx = $content.IndexOf('</head>')
    if ($idx -lt 0) {
        Write-Warning "no </head> in $($f.FullName)"
        continue
    }
    $new = $content.Substring(0, $idx) + $tag + "`r`n" + $content.Substring($idx)
    Set-Content -LiteralPath $f.FullName -Value $new -NoNewline -Encoding UTF8
    Write-Host "updated: $($f.FullName)"
}
