$ProgressPreference = 'SilentlyContinue'

Write-Host '=== Smoke: Twilio-primary WhatsApp + status endpoint ==='
Write-Host ''

# 1. /actuator/health
try {
    $h = Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:8080/actuator/health' -TimeoutSec 10
    Write-Host ('health         : ' + $h.StatusCode + '  ' + ($h.Content | Out-String).Trim())
} catch { Write-Host ('health error: ' + $_.Exception.Message) }

# 2. /api/host/whatsapp-settings/status WITHOUT cookie - should now return JSON 401
try {
    $unauth = Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:8080/api/host/whatsapp-settings/status' -TimeoutSec 10
    Write-Host ('status (no auth): ' + $unauth.StatusCode + '  ct=' + $unauth.Headers['Content-Type'])
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    $ct   = $_.Exception.Response.Headers['Content-Type']
    Write-Host ('status (no auth): HTTP ' + $code + '  ct=' + $ct + '   <-- expected 401')
}

# 3. Twilio callback endpoint reachable + form-encoded
try {
    $body = 'MessageSid=SMtest123&MessageStatus=delivered&ErrorCode=&ErrorMessage='
    $cb = Invoke-WebRequest -UseBasicParsing `
            -Uri  'http://localhost:8080/api/whatsapp/twilio-callback' `
            -Method POST `
            -ContentType 'application/x-www-form-urlencoded' `
            -Body $body -TimeoutSec 10
    Write-Host ('twilio cb POST : ' + $cb.StatusCode)
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    Write-Host ('twilio cb POST : HTTP ' + $code + '   <-- expected 204 (no auth verification token in dev) or 403 (signature mismatch)')
}

# 4. CORS preflight from capacitor://localhost still works
try {
    $headers = @{
        'Origin'                          = 'capacitor://localhost'
        'Access-Control-Request-Method'   = 'POST'
        'Access-Control-Request-Headers'  = 'content-type'
    }
    $cors = Invoke-WebRequest -UseBasicParsing -Method Options `
                -Uri 'http://localhost:8080/api/whatsapp/twilio-callback' `
                -Headers $headers -TimeoutSec 10
    Write-Host ('cors preflight : ' + $cors.StatusCode + '  Allow-Origin=' + $cors.Headers['Access-Control-Allow-Origin'])
} catch {
    Write-Host ('cors error: ' + $_.Exception.Message)
}

# 5. /login still works
try {
    $login = Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:8080/login' -TimeoutSec 15
    Write-Host ('login          : ' + $login.StatusCode + '  size=' + $login.Content.Length)
} catch {
    Write-Host ('login error: ' + $_.Exception.Message)
}
