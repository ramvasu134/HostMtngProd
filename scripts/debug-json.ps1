$apiKey = $env:RENDER_API_KEY
$svcId  = $env:RENDER_SERVICE_ID
if (-not $apiKey -or -not $svcId) { Write-Host 'Set RENDER_API_KEY and RENDER_SERVICE_ID env vars first'; exit 1 }
$apiUrl = "https://api.render.com/v1/services/$svcId/env-vars"

$existing = Invoke-RestMethod -Uri $apiUrl `
    -Headers @{ Authorization = "Bearer $apiKey"; Accept = 'application/json' } -TimeoutSec 30

$map = [ordered]@{}
foreach ($item in $existing) { $map[$item.envVar.key] = $item.envVar.value }
$map['TWILIO_ACCOUNT_SID']   = $env:TWILIO_ACCOUNT_SID
$map['TWILIO_AUTH_TOKEN']    = $env:TWILIO_AUTH_TOKEN
$map['TWILIO_WHATSAPP_FROM'] = 'whatsapp:+14155238886'

$arr = @()
foreach ($kv in $map.GetEnumerator()) {
    $arr += [PSCustomObject]@{ key=$kv.Key; value=$kv.Value }
}
$json = $arr | ConvertTo-Json -Compress
Write-Host "JSON length: $($json.Length)"
Write-Host $json.Substring(0, [Math]::Min(500, $json.Length))
Write-Host "..."

# Save to file and try curl PUT
$tmpFile = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($tmpFile, $json, [System.Text.Encoding]::UTF8)
Write-Host "Saved to: $tmpFile"

$resp = curl.exe -s -w "`n%{http_code}" -X PUT $apiUrl `
    -H "Authorization: Bearer $apiKey" `
    -H "Accept: application/json" `
    -H "Content-Type: application/json" `
    --data "@$tmpFile"

$lines = ($resp -split "`n")
$code  = $lines[-1].Trim()
$body  = ($lines[0..($lines.Count-2)] -join "`n")
Write-Host "HTTP: $code"
Write-Host $body.Substring(0, [Math]::Min(200, $body.Length))
Remove-Item $tmpFile -ErrorAction SilentlyContinue
