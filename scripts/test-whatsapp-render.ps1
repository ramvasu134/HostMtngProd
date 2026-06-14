# Smoke test against the Render deployment: login as host, check provider status
$base = 'https://host-student-meeting.onrender.com'
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

$loginPage = Invoke-WebRequest -Uri "$base/login" -WebSession $session -UseBasicParsing
$csrf = $null
if ($loginPage.Content -match 'name="_csrf"\s+value="([^"]+)"') { $csrf = $Matches[1] }

$form = @{ username = 'vk99'; password = '123456' }
if ($csrf) { $form['_csrf'] = $csrf }
$login = Invoke-WebRequest -Uri "$base/login" -Method Post -Body $form -WebSession $session -UseBasicParsing -MaximumRedirection 5
Write-Host "Login final URL: $($login.BaseResponse.ResponseUri)"

try {
    $status = Invoke-WebRequest -Uri "$base/api/host/whatsapp-settings/status" -WebSession $session -UseBasicParsing
    Write-Host "--- Provider status ---"
    Write-Host $status.Content
} catch { Write-Host "Status call failed: $($_.Exception.Message)" }

try {
    $test = Invoke-WebRequest -Uri "$base/api/host/whatsapp-settings/test" -Method Post -WebSession $session -UseBasicParsing
    Write-Host "--- Test send ---"
    Write-Host $test.Content
} catch { Write-Host "Test send failed: $($_.Exception.Message)" }
