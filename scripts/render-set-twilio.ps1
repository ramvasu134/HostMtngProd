# Sets Twilio env vars directly on the Render service via the Render API.
# Usage: .\render-set-twilio.ps1 -RenderApiKey "rnd_xxxxx" -ServiceId "srv-xxxxx"
param(
    [Parameter(Mandatory)][string]$RenderApiKey,
    [Parameter(Mandatory)][string]$ServiceId
)

$headers = @{
    Authorization  = "Bearer $RenderApiKey"
    Accept         = "application/json"
    "Content-Type" = "application/json"
}

# 1. Fetch existing env vars so we don't wipe them
$existing = Invoke-RestMethod -Uri "https://api.render.com/v1/services/$ServiceId/env-vars" -Headers $headers
Write-Host "Existing env var count: $($existing.Count)"

# Build map of current vars
$envMap = @{}
foreach ($item in $existing) { $envMap[$item.envVar.key] = $item.envVar.value }

# Overwrite/add the three Twilio vars  ← fill in your values below
$envMap["TWILIO_ACCOUNT_SID"]   = $env:TWILIO_ACCOUNT_SID   # or paste value here temporarily
$envMap["TWILIO_AUTH_TOKEN"]    = $env:TWILIO_AUTH_TOKEN
$envMap["TWILIO_WHATSAPP_FROM"] = if ($env:TWILIO_WHATSAPP_FROM) { $env:TWILIO_WHATSAPP_FROM } else { 'whatsapp:+14155238886' }

# Convert to the array format Render expects
$body = $envMap.GetEnumerator() | ForEach-Object {
    @{ key = $_.Key; value = $_.Value }
}

$json = @{ envVars = $body } | ConvertTo-Json -Depth 5

# 2. PUT the full set back
$result = Invoke-RestMethod -Uri "https://api.render.com/v1/services/$ServiceId/env-vars" -Method Put -Headers $headers -Body $json
Write-Host "Done. Render will trigger a redeploy automatically."
Write-Host "New env var count: $($result.Count)"

# Confirm the Twilio vars are present
$result | Where-Object { $_.envVar.key -like "TWILIO*" } | ForEach-Object {
    Write-Host "  $($_.envVar.key) = $($_.envVar.value)"
}
