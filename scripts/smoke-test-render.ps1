# Smoke test for https://host-student-meeting.onrender.com
$Base = 'https://host-student-meeting.onrender.com'
$pass=0; $fail=0; $warn=0

function Ok($l)      { Write-Host "  [PASS] $l" -ForegroundColor Green;  $script:pass++ }
function Ko($l,$d)   { Write-Host "  [FAIL] $l -- $d" -ForegroundColor Red;   $script:fail++ }
function Wn($l,$d)   { Write-Host "  [WARN] $l -- $d" -ForegroundColor Yellow; $script:warn++ }

function Get-Csrf($html) {
    if ($html -match 'name="?_csrf"?\s+(?:content|value)="([^"]+)"') { return $Matches[1] }
    if ($html -match '"_csrf"\s*:\s*"([^"]+)"') { return $Matches[1] }
    return ''
}

function DoLogin($user, $pw, $teacher) {
    $s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $pg = Invoke-WebRequest -Uri "$Base/login" -WebSession $s -UseBasicParsing -TimeoutSec 40
    $csrf = Get-Csrf $pg.Content
    $f = @{ username=$user; password=$pw }
    if ($teacher) { $f['teacherName'] = $teacher }
    if ($csrf)    { $f['_csrf'] = $csrf }
    $r = Invoke-WebRequest -Uri "$Base/login" -Method Post -Body $f `
         -WebSession $s -UseBasicParsing -MaximumRedirection 5 -TimeoutSec 40
    return @{ s=$s; url=$r.BaseResponse.ResponseUri.ToString(); html=$r.Content }
}

# ===========================================================================
Write-Host "`n=== 1. INFRASTRUCTURE ===" -ForegroundColor Cyan

try {
    $h = Invoke-RestMethod -Uri "$Base/actuator/health" -TimeoutSec 30
    if ($h.status -eq 'UP') { Ok "Health UP" } else { Ko "Health" $h.status }
} catch { Ko "Health" $_.Exception.Message }

try {
    $lp = Invoke-WebRequest -Uri "$Base/login" -UseBasicParsing -TimeoutSec 30
    if ($lp.StatusCode -eq 200) { Ok "Login page 200" } else { Ko "Login page" $lp.StatusCode }
} catch { Ko "Login page" $_.Exception.Message }

try {
    Invoke-WebRequest -Uri "$Base/api/public/recordings/999/x.m4a" -UseBasicParsing -TimeoutSec 15 | Out-Null
    Wn "Public media" "returned 200 (expected 404)"
} catch {
    $c = $_.Exception.Response.StatusCode.value__
    if ($c -eq 404) { Ok "Public media endpoint 404 (unauthenticated OK)" } else { Ko "Public media" "HTTP $c" }
}

# ===========================================================================
Write-Host "`n=== 2. HOST LOGIN & DASHBOARD ===" -ForegroundColor Cyan

try {
    $H = DoLogin 'vk99' '123456' ''
    if ($H.url -match 'dashboard') { Ok "Host login -> dashboard ($($H.url))" } else { Ko "Host login" $H.url }
    $hs = $H.s
    if ($H.html -match 'Meeting Controls') { Ok "Dashboard: Meeting Controls tab" } else { Ko "Dashboard" "Meeting Controls tab missing" }
    if ($H.html -match 'Students')         { Ok "Dashboard: Students tab" }         else { Wn "Dashboard" "Students tab text not found" }
    if ($H.html -match 'Recording')        { Ok "Dashboard: Recordings tab" }        else { Wn "Dashboard" "Recordings tab text not found" }
} catch { Ko "Host login/dashboard" $_.Exception.Message; $hs = $null }

# ===========================================================================
Write-Host "`n=== 3. WHATSAPP / TWILIO ===" -ForegroundColor Cyan

if ($hs) {
    try {
        $ws = Invoke-RestMethod -Uri "$Base/api/host/whatsapp-settings/status" -WebSession $hs -TimeoutSec 20
        Write-Host "    raw: $($ws | ConvertTo-Json -Compress)" -ForegroundColor DarkGray
        if ($ws.twilioReady)      { Ok "Twilio READY" }      else { Ko "Twilio" "twilioReady=false (env vars not on server)" }
        if ($ws.teacherHasNumber) { Ok "Teacher number set" } else { Wn "Teacher number" "not configured" }
        if ($ws.globallyEnabled)  { Ok "WhatsApp globally enabled" } else { Ko "WhatsApp" "globally disabled" }
    } catch { Ko "WhatsApp status" $_.Exception.Message }
}

# ===========================================================================
Write-Host "`n=== 4. CREATE STUDENT (form POST) ===" -ForegroundColor Cyan

if ($hs) {
    try {
        $rn = Get-Random -Maximum 9999
        $pg2 = Invoke-WebRequest -Uri "$Base/host/dashboard" -WebSession $hs -UseBasicParsing -TimeoutSec 30
        $csrf2 = Get-Csrf $pg2.Content
        $sf = @{
            username    = "smk$rn"
            password    = 'Test@1234'
            displayName = "Smoke $rn"
            role        = 'STUDENT'
        }
        if ($csrf2) { $sf['_csrf'] = $csrf2 }
        $sr = Invoke-WebRequest -Uri "$Base/host/students/add" -Method Post -Body $sf `
              -WebSession $hs -UseBasicParsing -MaximumRedirection 5 -TimeoutSec 30
        $finalUrl = $sr.BaseResponse.ResponseUri.ToString()
        if ($finalUrl -match 'dashboard') {
            if ($sr.Content -match 'added successfully') { Ok "Create student: added (smk$rn)" }
            elseif ($sr.Content -match 'error|Error') { Ko "Create student" "error flash on dashboard" }
            else { Wn "Create student" "redirected to dashboard (flash unclear)" }
        } else {
            Ko "Create student" "redirected to $finalUrl"
        }
    } catch { Ko "Create student" $_.Exception.Message }
}

# ===========================================================================
Write-Host "`n=== 5. MEETINGS ===" -ForegroundColor Cyan

if ($hs) {
    try {
        $ml = Invoke-RestMethod -Uri "$Base/api/host/meetings" -WebSession $hs -TimeoutSec 20
        Ok "List meetings API: $($ml.Count) meetings"
    } catch { Ko "List meetings" $_.Exception.Message }

    try {
        $qr = Invoke-WebRequest -Uri "$Base/api/host/quick-meeting/start" -Method Post `
              -Headers @{ 'Content-Type'='application/json' } -Body '{}' `
              -WebSession $hs -UseBasicParsing -TimeoutSec 30
        $qd = $qr.Content | ConvertFrom-Json
        if ($qd.success) { Ok "Quick-meeting start (code=$($qd.meetingCode))" } else { Wn "Quick-meeting" $qd.message }
    } catch { Ko "Quick-meeting" $_.Exception.Message }

    # Form-based create meeting
    try {
        $rn2 = Get-Random -Maximum 9999
        $pg3 = Invoke-WebRequest -Uri "$Base/host/meetings/new" -WebSession $hs -UseBasicParsing -TimeoutSec 30
        $csrf3 = Get-Csrf $pg3.Content
        $mf = @{
            title            = "Smoke$rn2"
            scheduledAt      = (Get-Date).AddHours(1).ToString('yyyy-MM-ddTHH:mm')
            maxParticipants  = '10'
            recordingEnabled = 'true'
            chatEnabled      = 'true'
        }
        if ($csrf3) { $mf['_csrf'] = $csrf3 }
        $mr = Invoke-WebRequest -Uri "$Base/host/meetings/create" -Method Post -Body $mf `
              -WebSession $hs -UseBasicParsing -MaximumRedirection 5 -TimeoutSec 30
        if ($mr.BaseResponse.ResponseUri -match 'dashboard') { Ok "Create meeting (form) -> dashboard" }
        else { Wn "Create meeting" "redirected to $($mr.BaseResponse.ResponseUri)" }
    } catch { Ko "Create meeting" $_.Exception.Message }
}

# ===========================================================================
Write-Host "`n=== 6. STUDENT LOGIN & ROOM ===" -ForegroundColor Cyan

try {
    $S = DoLogin 'priya' '123456' 'VK2'
    if ($S.url -match 'room') { Ok "Student login -> room ($($S.url))" } else { Ko "Student login" $S.url }
    $ss = $S.s
    $sroom = Invoke-WebRequest -Uri "$Base/student/room" -WebSession $ss -UseBasicParsing -MaximumRedirection 5 -TimeoutSec 30
    if ($sroom.StatusCode -eq 200) { Ok "Student room page 200" } else { Ko "Student room" "HTTP $($sroom.StatusCode)" }
} catch { Ko "Student login/room" $_.Exception.Message }

# ===========================================================================
Write-Host "`n=== 7. RECORDINGS ===" -ForegroundColor Cyan

if ($hs) {
    try {
        $rpage = Invoke-WebRequest -Uri "$Base/host/recordings" -WebSession $hs -UseBasicParsing -MaximumRedirection 5 -TimeoutSec 30
        if ($rpage.StatusCode -eq 200) { Ok "Recordings page 200" } else { Ko "Recordings page" "HTTP $($rpage.StatusCode)" }
    } catch { Ko "Recordings page" $_.Exception.Message }

    try {
        $rapi = Invoke-RestMethod -Uri "$Base/api/host/recordings" -WebSession $hs -TimeoutSec 20
        Ok "Recordings API: $($rapi.Count) recordings"
    } catch { Ko "Recordings API" $_.Exception.Message }
}

# ===========================================================================
Write-Host "`n=== 8. ADMIN ===" -ForegroundColor Cyan

try {
    $A = DoLogin 'superadmin' 'Admin@2026' ''
    if ($A.url -match 'admin') { Ok "Admin login -> admin ($($A.url))" } else { Ko "Admin login" $A.url }
    $ap = Invoke-WebRequest -Uri "$Base/admin/dashboard" -WebSession $A.s -UseBasicParsing -MaximumRedirection 5 -TimeoutSec 30
    if ($ap.StatusCode -eq 200) { Ok "Admin dashboard 200" } else { Ko "Admin dashboard" "HTTP $($ap.StatusCode)" }
} catch { Ko "Admin login/dashboard" $_.Exception.Message }

# ===========================================================================
Write-Host "`n=== 9. SECURITY (unauthenticated blocks) ===" -ForegroundColor Cyan

foreach ($path in @('/host/dashboard', '/admin/dashboard', '/api/host/meetings', '/host/recordings')) {
    try {
        $fresh = New-Object Microsoft.PowerShell.Commands.WebRequestSession
        $r2 = Invoke-WebRequest -Uri "$Base$path" -WebSession $fresh -UseBasicParsing -MaximumRedirection 5 -TimeoutSec 20
        if ($r2.BaseResponse.ResponseUri -match 'login') { Ok "No-auth $path -> login" }
        else { Ko "Security $path" "accessible without auth" }
    } catch {
        $c = $_.Exception.Response.StatusCode.value__
        if ($c -in 401,403) { Ok "No-auth $path -> $c (blocked)" } else { Wn "Security $path" "HTTP $c" }
    }
}

# ===========================================================================
Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  RESULT:  PASS=$pass   FAIL=$fail   WARN=$warn" -ForegroundColor White
Write-Host "============================================"
