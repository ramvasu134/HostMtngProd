param([string]$MsgSid)
$sid   = [Environment]::GetEnvironmentVariable('TWILIO_ACCOUNT_SID', 'User')
$token = [Environment]::GetEnvironmentVariable('TWILIO_AUTH_TOKEN', 'User')
if (-not $sid)   { $sid   = $env:TWILIO_ACCOUNT_SID }
if (-not $token) { $token = $env:TWILIO_AUTH_TOKEN }
$auth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${sid}:${token}"))
$hdr  = @{ Authorization = "Basic $auth" }
$m = Invoke-RestMethod -Uri "https://api.twilio.com/2010-04-01/Accounts/$sid/Messages/$MsgSid.json" -Headers $hdr
Write-Host "status=$($m.status) error_code=$($m.error_code) error_message=$($m.error_message)"
