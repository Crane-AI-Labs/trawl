<#
 Trawl's zero-network release gate, Windows side (see desktop/scripts/net-gate.py for the whole idea and NET-GATE.md for how to run it).

 Runs the INSTALLED app (Trawl.exe) through its scripted session (TRAWL_NETGATE_DIR: first run, models and Privacy pack from files, indexing a
 made-up folder, searches, privacy scoring, quick panel, Settings, Licences, diagnostics) for -Minutes while it polls, every 0.25 s, every TCP
 connection and UDP endpoint owned by the app's process tree (Get-NetTCPConnection / Get-NetUDPEndpoint, matched by owning process id).
 It also records the system's count of outbound TCP connections ("Active Opens" from netstat -s) before and after, as a cross-check for a
 connection too short for the polling to catch (informational: another program on the machine can move it).

 Output in -Out:  poll.csv (time,pid,process,proto,local,remote,state; one row per distinct connection seen), tree.txt, active-opens.txt,
 app-stderr.log, netgate-result.json, trawl.log. Judge it with:
     python net-gate.py analyse-poll poll.csv ; python net-gate.py check-result netgate-result.json --minutes 10
 (or on the Linux machine after copying the folder back). Exit code 1 when any row of the process tree was ever seen.

 A positive control runs first: powershell connects to a TEST-NET address and must show up in the same poll, or the gate reports a blind observer.
#>
param(
  [Parameter(Mandatory = $true)][string]$App,
  [Parameter(Mandatory = $true)][string]$Corpus,
  [Parameter(Mandatory = $true)][string]$Models,
  [Parameter(Mandatory = $true)][string]$Privacy,
  [Parameter(Mandatory = $true)][string]$ScratchHome,
  [Parameter(Mandatory = $true)][string]$Out,
  [double]$Minutes = 10
)
$ErrorActionPreference = 'Continue'
New-Item -ItemType Directory -Force -Path $Out | Out-Null
if (Test-Path $ScratchHome) { Remove-Item -Recurse -Force $ScratchHome }
$trawlHome = Join-Path $ScratchHome 'trawl'
New-Item -ItemType Directory -Force -Path $trawlHome | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $ScratchHome 'user') | Out-Null
Set-Content -Path (Join-Path $trawlHome 'shell.json') -Value '{"hotkeyOn": false, "keepRunning": false}'

function Get-Tree([int]$root) {
  $all = Get-CimInstance Win32_Process | Select-Object ProcessId, ParentProcessId, Name
  $ids = New-Object System.Collections.Generic.HashSet[int]
  [void]$ids.Add($root)
  $grew = $true
  while ($grew) {
    $grew = $false
    foreach ($p in $all) { if ($ids.Contains([int]$p.ParentProcessId) -and -not $ids.Contains([int]$p.ProcessId)) { [void]$ids.Add([int]$p.ProcessId); $grew = $true } }
  }
  return ,@($ids | ForEach-Object { $id = $_; ($all | Where-Object { $_.ProcessId -eq $id } | Select-Object -First 1) } | Where-Object { $_ })
}

function Get-ActiveOpens {
  $line = (netstat -s -p tcp | Select-String 'Active Opens').ToString()
  return [int64](($line -split '=')[-1].Trim())
}

$seen = @{}
function Poll-Once([int]$root, [string]$csv) {
  $tree = Get-Tree $root
  $ids = @($tree | ForEach-Object { [int]$_.ProcessId })
  if ($ids.Count -eq 0) { return 0 }
  $names = @{}; foreach ($p in $tree) { $names[[int]$p.ProcessId] = $p.Name }
  $now = (Get-Date).ToString('HH:mm:ss.fff')
  $rows = @()
  foreach ($c in (Get-NetTCPConnection -ErrorAction SilentlyContinue | Where-Object { $ids -contains [int]$_.OwningProcess })) {
    $rows += [pscustomobject]@{ time = $now; pid = $c.OwningProcess; process = $names[[int]$c.OwningProcess]; proto = 'TCP'; local = "$($c.LocalAddress):$($c.LocalPort)"; remote = "$($c.RemoteAddress):$($c.RemotePort)"; state = "$($c.State)" }
  }
  foreach ($u in (Get-NetUDPEndpoint -ErrorAction SilentlyContinue | Where-Object { $ids -contains [int]$_.OwningProcess })) {
    $rows += [pscustomobject]@{ time = $now; pid = $u.OwningProcess; process = $names[[int]$u.OwningProcess]; proto = 'UDP'; local = "$($u.LocalAddress):$($u.LocalPort)"; remote = ''; state = 'Bound' }
  }
  foreach ($r in $rows) {
    $key = "$($r.pid)|$($r.proto)|$($r.local)|$($r.remote)|$($r.state)"
    if (-not $seen.ContainsKey($key)) { $seen[$key] = 1; $r | Export-Csv -Path $csv -Append -NoTypeInformation }
  }
  return $ids.Count
}

# ---- positive control ----------------------------------------------------------------------------------------------------
$canaryCsv = Join-Path $Out 'canary-poll.csv'
Remove-Item -Force -ErrorAction SilentlyContinue $canaryCsv
# Warm the cmdlets up first: the first Get-NetTCPConnection call takes seconds, long enough for a short-lived canary to come and go unseen.
[void](Poll-Once $PID (Join-Path $Out 'warmup.csv')); $seen = @{}
$canary = Start-Process -FilePath 'powershell.exe' -ArgumentList '-NoProfile', '-Command', "`$c = New-Object System.Net.Sockets.TcpClient; try { `$c.ConnectAsync('203.0.113.7', 443).Wait(10000) | Out-Null } catch {}; Start-Sleep -Milliseconds 1500" -PassThru -WindowStyle Hidden
$caught = $false
while (-not $canary.HasExited) { [void](Poll-Once $canary.Id $canaryCsv); Start-Sleep -Milliseconds 100 }
$seen = @{}
if (Test-Path $canaryCsv) { $caught = (@(Import-Csv $canaryCsv | Where-Object { $_.remote -like '203.0.113.7:443' }).Count -gt 0) }   # @() because one row has no .Count in Windows PowerShell 5.1
"canary caught=$caught" | Tee-Object -FilePath (Join-Path $Out 'canary.txt')

# ---- the run -------------------------------------------------------------------------------------------------------------
$opensBefore = Get-ActiveOpens
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $App
$psi.UseShellExecute = $false
$psi.RedirectStandardError = $true
$psi.EnvironmentVariables['TRAWL_HOME'] = $trawlHome
$psi.EnvironmentVariables['USERPROFILE'] = (Join-Path $ScratchHome 'user')
# The JVM's user.home does not follow the environment: pin it, and the app's gate driver refuses to run unless it is this folder (made-up files only).
$psi.EnvironmentVariables['TRAWL_NETGATE_HOME'] = (Join-Path $ScratchHome 'user')
$psi.EnvironmentVariables['JAVA_TOOL_OPTIONS'] = "-Duser.home=$(Join-Path $ScratchHome 'user')"
$psi.EnvironmentVariables['TRAWL_NETGATE_DIR'] = $Corpus
$psi.EnvironmentVariables['TRAWL_NETGATE_MODELS'] = $Models
$psi.EnvironmentVariables['TRAWL_NETGATE_PRIVACY'] = $Privacy
$psi.EnvironmentVariables['TRAWL_NETGATE_MINUTES'] = "$Minutes"
[void]$psi.EnvironmentVariables.Remove('ORT_DISABLE_TELEMETRY')   # the app must make itself safe
$proc = [System.Diagnostics.Process]::Start($psi)
$errTask = $proc.StandardError.ReadToEndAsync()
$csv = Join-Path $Out 'poll.csv'
Remove-Item -Force -ErrorAction SilentlyContinue $csv
$started = Get-Date
$polls = 0; $maxTree = 0
$limit = ($Minutes + 15) * 60
while (-not $proc.HasExited) {
  $n = Poll-Once $proc.Id $csv
  $polls++; if ($n -gt $maxTree) { $maxTree = $n }
  if (((Get-Date) - $started).TotalSeconds -gt $limit) { Stop-Process -Id $proc.Id -Force; break }   # by PID
  Start-Sleep -Milliseconds 250
}
$opensAfter = Get-ActiveOpens
Set-Content -Path (Join-Path $Out 'app-stderr.log') -Value $errTask.Result
foreach ($f in 'netgate-result.json', 'logs\trawl.log') { $p = Join-Path $trawlHome $f; if (Test-Path $p) { Copy-Item $p $Out } }
$hadTelemetryDir = @(Get-ChildItem -Path (Join-Path $ScratchHome 'user') -Recurse -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -eq 'DeveloperTools' -or $_.Name -eq '.onnxruntime' }).Count
$rowsSeen = if (Test-Path $csv) { @(Import-Csv $csv).Count } else { 0 }
@{ canary_caught = $caught; polls = $polls; max_tree = $maxTree; wall_s = [int]((Get-Date) - $started).TotalSeconds; exit_code = $proc.ExitCode; rows_seen = $rowsSeen
   active_opens_before = $opensBefore; active_opens_after = $opensAfter; telemetry_dirs = $hadTelemetryDir } | ConvertTo-Json | Set-Content (Join-Path $Out 'windows-run.json')
Get-Content (Join-Path $Out 'windows-run.json')
Remove-Item -Recurse -Force $ScratchHome -ErrorAction SilentlyContinue   # made-up data in a scratch folder
if ($rowsSeen -gt 0 -or -not $caught) { exit 1 } else { exit 0 }
