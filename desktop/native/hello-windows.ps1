# Windows Hello for the app lock: a one-shot helper (Windows PowerShell 5.1, like ocr-windows.ps1). Trawl starts it, reads ONE
# line from stdout and is done.
#
#   hello-windows.ps1 check            -> "available" (Hello or a PIN is set up), or "unavailable:<why>". NEVER shows a prompt.
#   hello-windows.ps1 auth "<reason>"  -> shows Windows' own sign-in sheet with <reason>, then prints "ok", "denied", "cancelled"
#                                         or "error:<code>".
#
# It uses UserConsentVerifier (Windows.Security.Credentials.UI): the same "verify it is you" sheet apps use. Windows keeps the
# secret; Trawl only learns yes or no. Nothing is stored or sent. Reasons for "unavailable": DeviceNotPresent (no Hello hardware or
# PIN), NotConfiguredForUser (nothing set up), DisabledByPolicy, DeviceBusy.
param([string]$Mode = '', [string]$Reason = 'Unlock Trawl')
$ErrorActionPreference = 'Stop'
try {
  Add-Type -AssemblyName System.Runtime.WindowsRuntime
  $null = [Windows.Security.Credentials.UI.UserConsentVerifier, Windows.Security.Credentials.UI, ContentType = WindowsRuntime]
  $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
  function Wait($op, [Type]$t) { $task = $asTask.MakeGenericMethod($t).Invoke($null, @($op)); $task.Wait(-1) | Out-Null; $task.Result }
  if ($Mode -eq 'check') {
    $a = Wait ([Windows.Security.Credentials.UI.UserConsentVerifier]::CheckAvailabilityAsync()) ([Windows.Security.Credentials.UI.UserConsentVerifierAvailability])
    if ("$a" -eq 'Available') { [Console]::Out.WriteLine('available') } else { [Console]::Out.WriteLine("unavailable:$a") }
  } elseif ($Mode -eq 'auth') {
    $r = Wait ([Windows.Security.Credentials.UI.UserConsentVerifier]::RequestVerificationAsync($Reason)) ([Windows.Security.Credentials.UI.UserConsentVerificationResult])
    switch ("$r") {
      'Verified'        { [Console]::Out.WriteLine('ok') }
      'Canceled'        { [Console]::Out.WriteLine('cancelled') }
      'RetriesExhausted' { [Console]::Out.WriteLine('denied') }
      default           { [Console]::Out.WriteLine("error:$r") }
    }
  } else {
    [Console]::Out.WriteLine('error:usage')
  }
} catch {
  # Any failure to reach the verifier means "this computer cannot do it": the passphrase takes over. Say which kind of failure, never a path.
  [Console]::Out.WriteLine('unavailable:' + $_.Exception.GetType().Name)
}
[Console]::Out.Flush()
