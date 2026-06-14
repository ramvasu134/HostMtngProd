# Updates only JAVA_OPTS on the running Render service (merges with existing vars)
$apiKey = $env:RENDER_API_KEY
$svcId  = $env:RENDER_SERVICE_ID
if (-not $apiKey -or -not $svcId) { Write-Host 'Set RENDER_API_KEY and RENDER_SERVICE_ID env vars first'; exit 1 }
$apiUrl = "https://api.render.com/v1/services/$svcId/env-vars"

$hdrs = @{ Authorization = "Bearer $apiKey"; Accept = 'application/json' }

# Fetch all current vars
$curr = Invoke-RestMethod -Uri $apiUrl -Headers $hdrs -TimeoutSec 30
Write-Host "Current vars: $($curr.Count)"

# Build updated map
$map = [ordered]@{}
foreach ($item in $curr) { $map[$item.envVar.key] = $item.envVar.value }

# Set optimised JAVA_OPTS (SerialGC + fast startup, no verbose GC logging)
$map['JAVA_OPTS'] = '-server -XX:+UseContainerSupport -XX:MaxRAMPercentage=70.0 -XX:InitialRAMPercentage=25.0 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Djava.security.egd=file:/dev/./urandom -Dspring.jmx.enabled=false'

$objs    = $map.GetEnumerator() | ForEach-Object { [PSCustomObject]@{ key=$_.Key; value=$_.Value } }
$json    = $objs | ConvertTo-Json -Compress
$tmpFile = "$env:TEMP\render-java-opts.json"
[System.IO.File]::WriteAllText($tmpFile, $json)

$resp = curl.exe -s -w "`n%{http_code}" -X PUT $apiUrl `
    -H "Authorization: Bearer $apiKey" `
    -H "Content-Type: application/json" `
    -H "Accept: application/json" `
    --data-binary "@$tmpFile"
Remove-Item $tmpFile -ErrorAction SilentlyContinue

$lines = $resp -split "`n"; $code = ($lines[-1]).Trim(); $body = ($lines[0..($lines.Count-2)]) -join ""
Write-Host "HTTP: $code"
if ($code -eq '200') { Write-Host "JAVA_OPTS updated successfully. Will apply on next deploy." }
else { Write-Host "FAILED: $body" }
