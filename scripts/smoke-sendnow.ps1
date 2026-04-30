$ProgressPreference = 'SilentlyContinue'

Write-Host '=== Smoke: centralised WhatsApp gateway ==='
Write-Host ''

# 1. health
try {
    $h = Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:8080/actuator/health' -TimeoutSec 10
    $body = ($h.Content | ConvertFrom-Json).status
    Write-Host ('health           : ' + $h.StatusCode + '  status=' + $body)
} catch { Write-Host ('health error: ' + $_.Exception.Message) }

# 2. status endpoint without cookie -> 401 JSON
try {
    Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:8080/api/host/whatsapp-settings/status' -TimeoutSec 10 | Out-Null
    Write-Host '  status (no auth) : unexpectedly succeeded'
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    Write-Host ('status (no auth) : HTTP ' + $code + '   <-- expected 401')
}

# 3. send-now without cookie -> 401 JSON
try {
    Invoke-WebRequest -UseBasicParsing -Method POST `
        -Uri 'http://localhost:8080/api/host/whatsapp-settings/send-now' `
        -ContentType 'application/json' `
        -Body '{"recipient":"+919000995242","url":"https://hoststudentmeeting.onrender.com/host/recordings"}' `
        -TimeoutSec 10 | Out-Null
    Write-Host '  send-now (no auth): unexpectedly succeeded'
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    Write-Host ('send-now (no auth): HTTP ' + $code + '   <-- expected 401')
}

# 4. twilio callback still works
try {
    $body = 'MessageSid=SMtest777&MessageStatus=delivered&ErrorCode=&ErrorMessage='
    $cb = Invoke-WebRequest -UseBasicParsing -Method POST `
            -Uri  'http://localhost:8080/api/whatsapp/twilio-callback' `
            -ContentType 'application/x-www-form-urlencoded' `
            -Body $body -TimeoutSec 10
    Write-Host ('twilio callback  : ' + $cb.StatusCode + '   <-- expected 204')
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    Write-Host ('twilio callback  : HTTP ' + $code)
}

# 5. login page renders
try {
    $login = Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:8080/login' -TimeoutSec 15
    Write-Host ('login            : ' + $login.StatusCode + '  size=' + $login.Content.Length)
    if ($login.Content -match 'capacitor-bridge\.js') { Write-Host '  bridge tag PRESENT (mobile-ready)' }
} catch {
    Write-Host ('login error: ' + $_.Exception.Message)
}
