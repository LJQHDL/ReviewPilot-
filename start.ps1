param(
    [ValidateSet('Toggle', 'Start', 'Stop')]
    [string]$Action = 'Toggle'
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$backendDir = Join-Path $root 'backend'
$frontendDir = Join-Path $root 'frontend'
$jar = Join-Path $backendDir 'target\reviewpilot-backend-0.1.0-SNAPSHOT.jar'
$logDir = Join-Path $root 'logs'
$stateFile = Join-Path $logDir 'processes.json'
$configFile = Join-Path $root '.reviewpilot.env'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null

function Import-LocalConfig {
    if (-not (Test-Path $configFile)) { return }
    foreach ($line in Get-Content $configFile) {
        $text = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($text) -or $text.StartsWith('#')) { continue }
        $parts = $text -split '=', 2
        if ($parts.Count -ne 2) { continue }
        $name = $parts[0].Trim()
        $value = $parts[1].Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        if ($name -in @('GITHUB_TOKEN', 'DEEPSEEK_API_KEY') -and -not [string]::IsNullOrWhiteSpace($value)) {
            Set-Item -Path "Env:$name" -Value $value
        }
    }
}

function Get-Java17Path {
    function Get-JavaMajor([string]$path) {
        $version = (& $path --version 2>$null | Select-Object -First 1).ToString()
        if ($version -match '(?:version\s+"|openjdk\s+)(\d+)') { return [int]$Matches[1] }
        return 0
    }
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += Join-Path $env:JAVA_HOME 'bin\java.exe' }
    $candidates += @('C:\Program Files\Java\jdk-17\bin\java.exe', 'C:\Program Files\Eclipse Adoptium\jdk-17\bin\java.exe', 'F:\Liberica JDK\bin\java.exe')
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if ((Test-Path $candidate) -and ((Get-JavaMajor $candidate) -ge 17)) { return $candidate }
    }
    $java = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($java -and ((Get-JavaMajor $java.Source) -ge 17)) { return $java.Source }
    return $null
}

function Get-PortPid([int]$port) {
    $line = netstat.exe -ano | Select-String -Pattern (':\s*' + $port + '\s+.*LISTENING') | Select-Object -First 1
    if ($line -and $line.Line -match '\s(\d+)\s*$') { return [int]$Matches[1] }
    return $null
}

function Read-State {
    if (-not (Test-Path $stateFile)) { return @() }
    try {
        $state = Get-Content $stateFile -Raw | ConvertFrom-Json
        if ($state -is [array]) {
            foreach ($entry in $state) { Write-Output $entry }
        } elseif ($null -ne $state) {
            Write-Output $state
        }
    } catch { return @() }
}

function Write-State($entries) {
    if ($entries.Count -eq 0) { Remove-Item $stateFile -Force -ErrorAction SilentlyContinue }
    else { $entries | ConvertTo-Json | Set-Content -Path $stateFile -Encoding UTF8 }
}

function Stop-TrackedProcesses {
    $entries = Read-State
    if ($entries.Count -eq 0) {
        Write-Host 'No processes started by ReviewPilot were found; existing services were left untouched.'
        return
    }
    foreach ($entry in $entries) {
        $process = Get-Process -Id ([int]$entry.pid) -ErrorAction SilentlyContinue
        $portPid = if ($entry.port) { Get-PortPid ([int]$entry.port) } else { $null }
        if ($process -and (($null -eq $portPid) -or ($portPid -eq [int]$entry.pid))) {
            Stop-Process -Id $process.Id -Force
            Write-Host "Stopped $($entry.name) (PID $($process.Id))."
        } elseif ($process) {
            Write-Warning "Skipped PID $($entry.pid): it no longer owns port $($entry.port)."
        }
    }
    Write-State @()
    Write-Host 'ReviewPilot processes stopped.'
}

if ($Action -eq 'Stop') { Stop-TrackedProcesses; exit 0 }
if ($Action -eq 'Toggle') {
    $tracked = @(Read-State | Where-Object { Get-Process -Id ([int]$_.pid) -ErrorAction SilentlyContinue })
    if ($tracked.Count -gt 0) { Stop-TrackedProcesses; exit 0 }
    Write-State @()
}

Import-LocalConfig
Write-Host 'Starting ReviewPilot'
Write-Host "GITHUB_TOKEN:     $(if ([string]::IsNullOrWhiteSpace($env:GITHUB_TOKEN)) { 'missing' } else { 'set' })"
Write-Host "DEEPSEEK_API_KEY: $(if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) { 'missing' } else { 'set' })"
if ([string]::IsNullOrWhiteSpace($env:GITHUB_TOKEN)) { Write-Warning 'GITHUB_TOKEN is missing; GitHub requests will use anonymous rate limits.' }
if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) { Write-Warning 'DEEPSEEK_API_KEY is missing; /api/review will return 401 until it is set.' }

$java = Get-Java17Path
if (-not $java) { throw 'Java 17 or newer was not found. Set JAVA_HOME to a JDK 17+ installation.' }
if (-not (Test-Path $jar)) { throw "Backend jar not found: $jar. Build it first with Maven/JDK 17." }

$started = @()
if (Get-PortPid 8080) { Write-Host 'Backend port 8080 is already in use; keeping the existing process.' }
else {
    $p = Start-Process -FilePath $java -ArgumentList @('-jar', $jar) -WorkingDirectory $backendDir -RedirectStandardOutput (Join-Path $logDir 'backend.out.log') -RedirectStandardError (Join-Path $logDir 'backend.err.log') -WindowStyle Hidden -PassThru
    $started += [pscustomobject]@{ name = 'backend'; pid = $p.Id; port = 8080 }
    Write-Host 'Backend started on http://127.0.0.1:8080/'
}

if (Get-PortPid 5173) { Write-Host 'Frontend port 5173 is already in use; keeping the existing process.' }
else {
    $p = Start-Process -FilePath 'npm.cmd' -ArgumentList @('run', 'dev', '--', '--host', '127.0.0.1') -WorkingDirectory $frontendDir -RedirectStandardOutput (Join-Path $logDir 'frontend.out.log') -RedirectStandardError (Join-Path $logDir 'frontend.err.log') -WindowStyle Hidden -PassThru
    Start-Sleep -Seconds 2
    $frontendPid = Get-PortPid 5173
    if (-not $frontendPid) { $frontendPid = $p.Id }
    $started += [pscustomobject]@{ name = 'frontend'; pid = $frontendPid; port = 5173 }
    Write-Host 'Frontend started on http://127.0.0.1:5173/'
}

Write-State $started
Write-Host "Logs: $logDir"
