$ProgressPreference = 'SilentlyContinue'
Start-Sleep -Seconds 2
$login = Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:8080/login' -TimeoutSec 15
Write-Host ('login: ' + $login.StatusCode + '  size=' + $login.Content.Length)
if ($login.Content -match 'capacitor-bridge\.js') {
    Write-Host 'bridge tag PRESENT in /login'
} else {
    Write-Host 'bridge tag MISSING in /login'
}
