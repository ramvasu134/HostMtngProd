# Restores all required environment variables to the Render web service.
# Run this if env vars are ever accidentally wiped.
#
# Usage:
#   .\restore-render-vars.ps1 `
#     -ApiKey     "rnd_xxxx"              # Render API key
#     -DbPassword "your-db-password"      # From Render Postgres dashboard
#     -JwtSecret  "your-jwt-secret"
#     -TwilioSid  "ACxxxx"
#     -TwilioToken "your-auth-token"
#
param(
    [Parameter(Mandatory)][string]$ApiKey,
    [Parameter(Mandatory)][string]$DbPassword,
    [string]$JwtSecret   = 'HostMtngProd-JWT-Secret-Key-2026-Render',
    [string]$TwilioSid   = '',
    [string]$TwilioToken = '',
    [string]$TwilioFrom  = 'whatsapp:+14155238886',
    [string]$ServiceId   = 'srv-d7hlio77f7vs738lnotg'
)

$apiUrl = "https://api.render.com/v1/services/$ServiceId/env-vars"

$objs = @(
    [PSCustomObject]@{ key='SPRING_PROFILES_ACTIVE';               value='prod' }
    [PSCustomObject]@{ key='DB_HOST';                              value='dpg-d7hlidn7f7vs738lnim0-a' }
    [PSCustomObject]@{ key='DB_PORT';                              value='5432' }
    [PSCustomObject]@{ key='DB_NAME';                              value='meeting_db_sr7r' }
    [PSCustomObject]@{ key='DB_USER';                              value='meeting_user' }
    [PSCustomObject]@{ key='DB_PASSWORD';                          value=$DbPassword }
    [PSCustomObject]@{ key='DB_POOL_SIZE';                         value='10' }
    [PSCustomObject]@{ key='JWT_SECRET';                           value=$JwtSecret }
    [PSCustomObject]@{ key='WHATSAPP_ENABLED';                     value='true' }
    [PSCustomObject]@{ key='SEED_ENABLED';                         value='true' }
    [PSCustomObject]@{ key='APP_RECORDING_DIR';                    value='/app/recordings' }
    [PSCustomObject]@{ key='APP_UPLOAD_DIR';                       value='/app/uploads' }
    [PSCustomObject]@{ key='APP_PROFILE_PHOTOS_DIR';               value='/app/profile-photos' }
    [PSCustomObject]@{ key='APP_WEBSOCKET_ALLOWED_ORIGINS';        value='https://*.onrender.com,https://your-domain.com' }
    [PSCustomObject]@{ key='JAVA_OPTS';                            value='-server -XX:+UseContainerSupport -XX:MaxRAMPercentage=70.0 -XX:InitialRAMPercentage=25.0 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Djava.security.egd=file:/dev/./urandom -Dspring.jmx.enabled=false' }
    [PSCustomObject]@{ key='NOTIFICATION_EXTERNAL_ENABLED';        value='false' }
    [PSCustomObject]@{ key='NOTIFICATION_EXTERNAL_MAX_ATTEMPTS';   value='3' }
    [PSCustomObject]@{ key='NOTIFICATION_EXTERNAL_RETRY_DELAY_MS'; value='2000' }
)

if ($TwilioSid)   { $objs += [PSCustomObject]@{ key='TWILIO_ACCOUNT_SID';   value=$TwilioSid } }
if ($TwilioToken) { $objs += [PSCustomObject]@{ key='TWILIO_AUTH_TOKEN';    value=$TwilioToken } }
if ($TwilioFrom)  { $objs += [PSCustomObject]@{ key='TWILIO_WHATSAPP_FROM'; value=$TwilioFrom } }

$json    = $objs | ConvertTo-Json -Compress
$tmpFile = "$env:TEMP\render-restore.json"
[System.IO.File]::WriteAllText($tmpFile, $json)

Write-Host "Sending $($objs.Count) env vars to Render..."
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
    Write-Host "SUCCESS."
} else {
    Write-Host "FAILED: $body"
}
