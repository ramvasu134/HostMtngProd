# Probe Twilio account: owned numbers + sandbox WhatsApp channel test
$sid   = $env:TWILIO_ACCOUNT_SID
$token = $env:TWILIO_AUTH_TOKEN
if (-not $sid -or -not $token) {
    # fall back to user-level env (shell may not have inherited them)
    $sid   = [Environment]::GetEnvironmentVariable('TWILIO_ACCOUNT_SID', 'User')
    $token = [Environment]::GetEnvironmentVariable('TWILIO_AUTH_TOKEN', 'User')
}
if (-not $sid -or -not $token) { Write-Host 'NO CREDS FOUND'; exit 1 }
Write-Host "Account SID: $($sid.Substring(0,6))... (len $($sid.Length))"

$pair  = "${sid}:${token}"
$auth  = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
$hdr   = @{ Authorization = "Basic $auth" }

# 1. Owned phone numbers
try {
    $nums = Invoke-RestMethod -Uri "https://api.twilio.com/2010-04-01/Accounts/$sid/IncomingPhoneNumbers.json?PageSize=20" -Headers $hdr
    Write-Host "--- Owned numbers ---"
    foreach ($n in $nums.incoming_phone_numbers) {
        Write-Host "$($n.phone_number)  capabilities: sms=$($n.capabilities.sms) voice=$($n.capabilities.voice)"
    }
} catch { Write-Host "Numbers list failed: $($_.Exception.Message)" }

# 2. Try a WhatsApp sandbox send to the teacher number
try {
    $body = @{
        From = 'whatsapp:+14155238886'
        To   = 'whatsapp:+919000995242'
        Body = 'Host Mtng: WhatsApp sandbox connectivity test'
    }
    $resp = Invoke-RestMethod -Uri "https://api.twilio.com/2010-04-01/Accounts/$sid/Messages.json" -Method Post -Headers $hdr -Body $body
    Write-Host "--- Sandbox send accepted ---"
    Write-Host "SID: $($resp.sid)  status: $($resp.status)  error: $($resp.error_code)"
    Start-Sleep -Seconds 6
    $check = Invoke-RestMethod -Uri "https://api.twilio.com/2010-04-01/Accounts/$sid/Messages/$($resp.sid).json" -Headers $hdr
    Write-Host "After 6s: status=$($check.status) error_code=$($check.error_code) error_message=$($check.error_message)"
} catch {
    Write-Host "--- Sandbox send FAILED ---"
    $stream = $_.Exception.Response.GetResponseStream()
    if ($stream) {
        $reader = New-Object IO.StreamReader($stream)
        Write-Host $reader.ReadToEnd()
    } else {
        Write-Host $_.Exception.Message
    }
}
