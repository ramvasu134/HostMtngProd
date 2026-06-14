$apiKey = $env:RENDER_API_KEY
$svcId  = $env:RENDER_SERVICE_ID
if (-not $apiKey -or -not $svcId) { Write-Host 'Set RENDER_API_KEY and RENDER_SERVICE_ID env vars first'; exit 1 }
$apiUrl = "https://api.render.com/v1/services/$svcId/env-vars"

# Write minimal test JSON
$json = '[{"key":"TWILIO_TEST","value":"hello123"}]'
$tmpFile = "$env:TEMP\render-test.json"
[System.IO.File]::WriteAllText($tmpFile, $json)
Write-Host "JSON: $json"
Write-Host "File: $tmpFile"
Write-Host "File size: $((Get-Item $tmpFile).Length)"

# Verbose curl
curl.exe -v -X PUT $apiUrl `
    -H "Authorization: Bearer $apiKey" `
    -H "Content-Type: application/json" `
    -H "Accept: application/json" `
    --data-binary "@$tmpFile" 2>&1

Remove-Item $tmpFile -ErrorAction SilentlyContinue
