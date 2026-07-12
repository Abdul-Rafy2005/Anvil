# Chaos Test Script for Anvil
# Tests single-instance crash recovery: kills the entire backend process (API + worker)
# and confirms zero job loss via outbox pattern + orphan reclamation on restart.
#
# Scope: This tests single-process crash recovery, NOT multi-instance fault tolerance.
# It proves that if the sole backend process crashes, all submitted jobs eventually
# reach a terminal state after restart. Multi-instance fault tolerance (where Worker B
# recovers Worker A's orphaned job while A is down) is architecturally supported by
# reclaimOrphanedJobs() + Redis claimed-set check, but is NOT exercised by this script.

param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Iterations = 20,
    [int]$JobsPerIteration = 3
)

$ErrorActionPreference = "Stop"

Write-Host "=== Anvil Chaos Test ===" -ForegroundColor Cyan
Write-Host "Iterations: $Iterations, Jobs per iteration: $JobsPerIteration"
Write-Host "Tests outbox recovery: submit jobs, kill backend, restart, verify all jobs reach terminal state"
Write-Host ""

function Kill-Backend {
    Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
    Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
}

function Start-Backend {
    Start-Process -FilePath "mvn" -ArgumentList "spring-boot:run" -WorkingDirectory "D:\Projects\Anvil\backend" -WindowStyle Hidden
    $ready = $false
    $retryCount = 0
    while (-not $ready -and $retryCount -lt 40) {
        Start-Sleep -Seconds 3
        try {
            $r = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -Method Get -TimeoutSec 3 -ErrorAction Stop
            if ($r.status -eq "UP") { $ready = $true }
        } catch { $retryCount++ }
    }
    return $ready
}

function Get-JobStatus($jid, $headers) {
    try {
        $j = Invoke-RestMethod -Uri "$BaseUrl/api/v1/jobs/$jid" -Method Get -Headers $headers -ErrorAction Stop
        return $j.status
    } catch {
        return $null
    }
}

# 1. Register and get JWT
Write-Host "1. Registering chaos test user..." -ForegroundColor Yellow
$email = "chaostest-$(Get-Random)@example.com"
$registerBody = @{ email = $email; password = "TestPass123!" } | ConvertTo-Json
try {
    Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/register" -Method Post -ContentType "application/json" -Body $registerBody | Out-Null
    $loginResp = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/login" -Method Post -ContentType "application/json" -Body $registerBody
    $token = $loginResp.accessToken
} catch {
    Write-Host "Registration/login failed: $_" -ForegroundColor Red
    exit 1
}

$headers = @{ Authorization = "Bearer $token" }

# 2. Submit jobs in batches, kill between batches
$allJobIds = @()
$terminalStates = @("COMPLETED", "FAILED", "FAILED_PERMANENTLY", "CANCELLED")

Write-Host "2. Running chaos iterations..." -ForegroundColor Yellow

for ($i = 1; $i -le $Iterations; $i++) {
    Write-Host "  Iteration $i/$Iterations..." -NoNewline

    # Submit jobs
    for ($j = 0; $j -lt $JobsPerIteration; $j++) {
        $body = @{ jobType = "CSV_IMPORT"; payload = '{"fileUrl":"http://example.com/data.csv","delimiter":",","totalRows":10}' } | ConvertTo-Json
        try {
            $resp = Invoke-RestMethod -Uri "$BaseUrl/api/v1/jobs" -Method Post -ContentType "application/json" -Headers $headers -Body $body -ErrorAction Stop
            $allJobIds += $resp.id
        } catch {
            Write-Host " [submit error]" -NoNewline -ForegroundColor Red
        }
    }

    # Wait for outbox relay to enqueue jobs
    Start-Sleep -Seconds 4

    # Kill the backend
    Kill-Backend
    Write-Host " [killed]" -NoNewline -ForegroundColor Yellow

    Start-Sleep -Seconds 3

    # Restart the backend
    $restarted = Start-Backend
    if ($restarted) {
        Write-Host " [restarted]" -NoNewline -ForegroundColor Green
    } else {
        Write-Host " [restart timeout]" -NoNewline -ForegroundColor Red
    }

    # Re-authenticate after restart
    try {
        $loginResp = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/login" -Method Post -ContentType "application/json" -Body $registerBody -ErrorAction Stop
        $token = $loginResp.accessToken
        $headers = @{ Authorization = "Bearer $token" }
    } catch {}

    Write-Host " OK" -ForegroundColor Green
}

# 3. Wait for all jobs to settle (poll instead of fixed wait)
Write-Host ""
Write-Host "3. Waiting for all jobs to reach terminal state..." -ForegroundColor Yellow
$maxWaitSeconds = 180
$elapsed = 0
$allTerminal = $false
while (-not $allTerminal -and $elapsed -lt $maxWaitSeconds) {
    Start-Sleep -Seconds 10
    $elapsed += 10
    $nonTerminalCount = 0
    foreach ($jid in $allJobIds) {
        $s = Get-JobStatus $jid $headers
        if ($s -and $terminalStates -notcontains $s) {
            $nonTerminalCount++
        }
    }
    Write-Host "  ${elapsed}s: $nonTerminalCount jobs remaining..." -NoNewline
    if ($nonTerminalCount -eq 0) {
        $allTerminal = $true
        Write-Host " ALL DONE" -ForegroundColor Green
    } else {
        Write-Host "" 
    }
}

# 4. Check final status of all jobs
Write-Host ""
Write-Host "4. Verifying job outcomes..." -ForegroundColor Yellow

$nonTerminal = @()
$completedCount = 0
$failedCount = 0
$dlqCount = 0

foreach ($jid in $allJobIds) {
    $retries = 0
    $found = $false
    while (-not $found -and $retries -lt 5) {
        try {
            $job = Invoke-RestMethod -Uri "$BaseUrl/api/v1/jobs/$jid" -Method Get -Headers $headers -ErrorAction Stop
            if ($terminalStates -contains $job.status) {
                switch ($job.status) {
                    "COMPLETED" { $completedCount++ }
                    "FAILED" { $failedCount++ }
                    "FAILED_PERMANENTLY" { $dlqCount++ }
                }
                $found = $true
            } else {
                $nonTerminal += [PSCustomObject]@{ Id = $jid; Status = $job.status; Attempts = $job.attemptCount }
                $found = $true
            }
        } catch {
            $retries++
            Start-Sleep -Seconds 2
        }
    }
    if (-not $found) {
        $nonTerminal += [PSCustomObject]@{ Id = $jid; Status = "UNKNOWN"; Attempts = 0 }
    }
}

# 5. Report
Write-Host ""
Write-Host "=== Chaos Test Results ===" -ForegroundColor Green
Write-Host "Total jobs submitted: $($allJobIds.Count)"
Write-Host "Iterations completed: $Iterations"
Write-Host ""
Write-Host "Job outcomes:"
Write-Host "  COMPLETED: $completedCount" -ForegroundColor Green
Write-Host "  FAILED: $failedCount" -ForegroundColor Yellow
Write-Host "  DLQ (FAILED_PERMANENTLY): $dlqCount" -ForegroundColor Yellow

if ($nonTerminal.Count -gt 0) {
    Write-Host "  NON-TERMINAL: $($nonTerminal.Count)" -ForegroundColor Red
    foreach ($nt in $nonTerminal) {
        Write-Host "    $($nt.Id) status=$($nt.Status) attempts=$($nt.Attempts)" -ForegroundColor Red
    }
} else {
    Write-Host "  NON-TERMINAL: 0" -ForegroundColor Green
}

# 6. Summary
Write-Host ""
Write-Host "=== Summary ===" -ForegroundColor Cyan
$lostCount = $nonTerminal.Count
$pass = ($lostCount -eq 0)

if ($pass) {
    Write-Host "CHAOS TEST PASSED" -ForegroundColor Green
    Write-Host "Zero jobs lost across $Iterations kill/restart iterations with $JobsPerIteration jobs each" -ForegroundColor Green
    Write-Host "Outbox pattern successfully recovered all jobs after backend restart" -ForegroundColor Green
} else {
    Write-Host "CHAOS TEST FAILED" -ForegroundColor Red
    Write-Host "$lostCount jobs stuck in non-terminal state" -ForegroundColor Red
}

Write-Host ""
Write-Host "All $Iterations chaos iterations completed." -ForegroundColor Cyan
