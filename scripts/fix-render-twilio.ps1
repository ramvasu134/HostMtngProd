# Fetches current Render env vars, merges Twilio creds, and PUTs back.
# Usage: .\fix-render-twilio.ps1 -ApiKey "rnd_xxxx" -TwilioSid "ACxx" -TwilioToken "xx" -TwilioFrom "whatsapp:+14155238886"
param(
    [Parameter(Mandatory)][string]$ApiKey,
    [Parameter(Mandatory)][string]$TwilioSid,
    [Parameter(Mandatory)][string]$TwilioToken,
    [string]$TwilioFrom = 'whatsapp:+14155238886',
    [string]$ServiceId  = 'srv-d7hlio77f7vs738lnotg'
)

$apiUrl = "https://api.render.com/v1/services/$ServiceId/env-vars"

$existing = Invoke-RestMethod -Uri $apiUrl `
    -Headers @{ Authorization = "Bearer $ApiKey"; Accept = 'application/json' } -TimeoutSec 30
Write-Host "Fetched $($existing.Count) existing env vars"

$map = [ordered]@{}
foreach ($item in $existing) { $map[$item.envVar.key] = $item.envVar.value }

$map['TWILIO_ACCOUNT_SID']   = $TwilioSid
$map['TWILIO_AUTH_TOKEN']    = $TwilioToken
$map['TWILIO_WHATSAPP_FROM'] = $TwilioFrom
$map['WHATSAPP_ENABLED']     = 'true'

$objs    = $map.GetEnumerator() | ForEach-Object { [PSCustomObject]@{ key=$_.Key; value=$_.Value } }
$json    = $objs | ConvertTo-Json -Compress
$tmpFile = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($tmpFile, $json)

Write-Host "Sending PUT ($($map.Count) vars)..."
$resp = curl.exe -s -w "`n%{http_code}" -X PUT $apiUrl `
    -H "Authorization: Bearer $ApiKey" `
    -H "Content-Type: application/json" `
    -H "Accept: application/json" `
    --data-binary "@$tmpFile"

Remove-Item $tmpFile -ErrorAction SilentlyContinue

$lines = $resp -split "`n"
$code  = ($lines[-1]).Trim()
$body  = ($lines[0..($lines.Count-2)]) -join ""
Write-Host "HTTP: $code"

if ($code -eq '200') {
    Write-Host "SUCCESS. Triggering redeploy..."
    $hdrs = @{ Authorization = "Bearer $ApiKey"; 'Content-Type' = 'application/json'; Accept = 'application/json' }
    $d = Invoke-RestMethod -Uri "https://api.render.com/v1/services/$ServiceId/deploys" `
         -Method Post -Headers $hdrs -Body '{"clearCache":"do_not_clear"}' -TimeoutSec 30
    Write-Host "Deploy: id=$($d.id) status=$($d.status)"
} else {
    Write-Host "FAILED: $body"
}
