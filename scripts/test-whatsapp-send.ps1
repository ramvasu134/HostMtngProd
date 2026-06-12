# Smoke test: login as host and trigger a real WhatsApp test send via Twilio
$base = 'http://localhost:8080'
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

# 1. Fetch login page to obtain CSRF token + session cookie
$loginPage = Invoke-WebRequest -Uri "$base/login" -WebSession $session -UseBasicParsing
$csrf = $null
if ($loginPage.Content -match 'name="_csrf"\s+value="([^"]+)"') { $csrf = $Matches[1] }
elseif ($loginPage.Content -match 'name="_csrf"[^>]*content="([^"]+)"') { $csrf = $Matches[1] }
Write-Host "CSRF token: $csrf"

# 2. Form login as host vk99
$form = @{ username = 'vk99'; password = '123456' }
if ($csrf) { $form['_csrf'] = $csrf }
$login = Invoke-WebRequest -Uri "$base/login" -Method Post -Body $form -WebSession $session -UseBasicParsing -MaximumRedirection 5
Write-Host "Login status: $($login.StatusCode), final URL: $($login.BaseResponse.ResponseUri)"

# 3. Trigger the WhatsApp test send
$test = Invoke-WebRequest -Uri "$base/api/host/whatsapp-settings/test" -Method Post -WebSession $session -UseBasicParsing
Write-Host "Test send response: $($test.StatusCode)"
Write-Host $test.Content
