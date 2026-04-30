$ProgressPreference = 'SilentlyContinue'

try {
    $login = Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:8080/login' -TimeoutSec 10
    Write-Host ('login: ' + $login.StatusCode)
    if ($login.Content -match 'capacitor-bridge\.js') {
        Write-Host '  bridge tag: PRESENT in /login'
    } else {
        Write-Host '  bridge tag: MISSING in /login'
    }
} catch {
    Write-Host ('login error: ' + $_.Exception.Message)
}

try {
    $bridge = Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:8080/js/capacitor-bridge.js' -TimeoutSec 10
    Write-Host ('bridge.js: ' + $bridge.StatusCode + '  bytes=' + $bridge.Content.Length)
} catch {
    Write-Host ('bridge.js error: ' + $_.Exception.Message)
}

try {
    $health = Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:8080/actuator/health' -TimeoutSec 10
    Write-Host ('health: ' + $health.StatusCode + '  body=' + $health.Content)
} catch {
    Write-Host ('health error: ' + $_.Exception.Message)
}

# Confirm CORS preflight from a Capacitor origin is allowed for /api endpoints.
try {
    $headers = @{
        'Origin'                          = 'capacitor://localhost'
        'Access-Control-Request-Method'   = 'GET'
        'Access-Control-Request-Headers'  = 'content-type'
    }
    $cors = Invoke-WebRequest -UseBasicParsing -Method Options `
                -Uri 'http://localhost:8080/api/host/whatsapp/settings' `
                -Headers $headers -TimeoutSec 10
    Write-Host ('cors preflight: ' + $cors.StatusCode)
    Write-Host ('  Access-Control-Allow-Origin: '      + $cors.Headers['Access-Control-Allow-Origin'])
    Write-Host ('  Access-Control-Allow-Credentials: ' + $cors.Headers['Access-Control-Allow-Credentials'])
    Write-Host ('  Access-Control-Allow-Methods: '     + $cors.Headers['Access-Control-Allow-Methods'])
} catch {
    Write-Host ('cors error: ' + $_.Exception.Message)
}
