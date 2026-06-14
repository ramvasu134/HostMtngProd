$apiKey = $env:RENDER_API_KEY
$svcId  = $env:RENDER_SERVICE_ID
if (-not $apiKey -or -not $svcId) { Write-Host 'Set RENDER_API_KEY and RENDER_SERVICE_ID env vars first'; exit 1 }

# 1. App health
Write-Host "=== App Health ==="
try {
    $h = Invoke-RestMethod -Uri 'https://host-student-meeting.onrender.com/actuator/health' -TimeoutSec 20
    Write-Host "Status: $($h.status)"
} catch {
    Write-Host "FAILED: $($_.Exception.Message)"
}

# 2. Latest deploy status
Write-Host ""
Write-Host "=== Latest Deploys ==="
$hdrs = @{ Authorization = "Bearer $apiKey"; Accept = 'application/json' }
$deploys = Invoke-RestMethod -Uri "https://api.render.com/v1/services/$svcId/deploys?limit=5" -Headers $hdrs -TimeoutSec 20
foreach ($d in $deploys) {
    Write-Host "  $($d.deploy.id) | $($d.deploy.status) | $($d.deploy.createdAt) | commit=$($d.deploy.commit.id)"
}

# 3. Current env vars (especially DB vars)
Write-Host ""
Write-Host "=== Current DB Env Vars ==="
$vars = Invoke-RestMethod -Uri "https://api.render.com/v1/services/$svcId/env-vars" -Headers $hdrs -TimeoutSec 20
foreach ($item in $vars) {
    if ($item.envVar.key -match 'DB_|SPRING_|TWILIO') {
        $val = $item.envVar.value
        if ($item.envVar.key -match 'PASS|TOKEN|SECRET') {
            $val = $val.Substring(0, [Math]::Min(6, $val.Length)) + '***'
        }
        Write-Host "  $($item.envVar.key) = $val"
    }
}
