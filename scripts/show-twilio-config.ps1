# Shows the Twilio env values needed for Render (auth token masked)
$sid  = [Environment]::GetEnvironmentVariable('TWILIO_ACCOUNT_SID', 'User')
$tok  = [Environment]::GetEnvironmentVariable('TWILIO_AUTH_TOKEN', 'User')
$from = [Environment]::GetEnvironmentVariable('TWILIO_WHATSAPP_FROM', 'User')
Write-Host "TWILIO_ACCOUNT_SID   = $sid"
if ($tok) {
    $masked = $tok.Substring(0, 4) + ('*' * ($tok.Length - 4))
    Write-Host "TWILIO_AUTH_TOKEN    = $masked  (run with -Reveal to print full value)"
} else {
    Write-Host "TWILIO_AUTH_TOKEN    = (not set)"
}
Write-Host "TWILIO_WHATSAPP_FROM = $from"
if ($args -contains '-Reveal') {
    Write-Host ""
    Write-Host "FULL AUTH TOKEN: $tok"
}
