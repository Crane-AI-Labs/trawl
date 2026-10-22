# The Windows Share pane for "Share" in a result's menu: a one-shot helper (Windows PowerShell 5.1, like hello-windows.ps1 and
# media-windows.ps1). Trawl starts it, reads ONE line from stdout and is done.
#
#   share-windows.ps1 check            -> "available" (the Share contract can be reached from this session), or "unavailable:<why>".
#                                         Shows NOTHING: no window, no pane.
#   share-windows.ps1 selfcheck <path> -> "ok:items=<n>" after turning the file into a Windows storage item and attaching to the
#                                         Share contract, or "error:<why>". Shows NOTHING.
#   share-windows.ps1 share <path>...  -> shows Windows' own Share pane (DataTransferManager) for those files, near the mouse, then
#                                         prints "ok" (a target took the files), "cancelled" (the pane was dismissed) or "error:<why>".
#                                         "error:nosheet" is a pane that never came: Windows said yes and nothing took the keyboard in 4 s
#                                         (ShellExperienceHost crashed, as on the Windows 10 image of the 2026-10-22 matrix).
#
# DataTransferManager needs a window to hang the pane on, so a 1x1 invisible form is made at the mouse and closed with the pane.
# Files are handed over as real files (StorageFile), so Mail, Nearby sharing and the rest get them. Nothing is stored or sent by Trawl.
# A sensitive file is asked about in Trawl's own "take it out of Trawl?" dialog before this helper is ever started.
$ErrorActionPreference = 'Stop'
$Mode = if ($args.Count -gt 0) { [string]$args[0] } else { '' }
$Paths = if ($args.Count -gt 1) { @($args[1..($args.Count - 1)]) } else { @() }
function Say([string]$s) { [Console]::Out.WriteLine($s); [Console]::Out.Flush() }

try {
  Add-Type -AssemblyName System.Runtime.WindowsRuntime
  Add-Type -AssemblyName System.Windows.Forms
  $null = [Windows.ApplicationModel.DataTransfer.DataTransferManager, Windows.ApplicationModel.DataTransfer, ContentType = WindowsRuntime]
  $null = [Windows.ApplicationModel.DataTransfer.DataRequestedEventArgs, Windows.ApplicationModel.DataTransfer, ContentType = WindowsRuntime]
  $null = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
  $null = [Windows.Storage.IStorageItem, Windows.Storage, ContentType = WindowsRuntime]

  Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class TrawlShareInterop {
  [ComImport, Guid("3A3DCD6C-3EAB-43DC-BCDE-45671CE800C8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  public interface IDataTransferManagerInterop {
    IntPtr GetForWindow(IntPtr appWindow, [In] ref Guid riid);
    void ShowShareUIForWindow(IntPtr appWindow);
  }
  [DllImport("combase.dll", PreserveSig = false)]
  static extern void RoGetActivationFactory([MarshalAs(UnmanagedType.HString)] string classId, [In] ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object factory);
  static IDataTransferManagerInterop Factory() {
    Guid iid = new Guid("3A3DCD6C-3EAB-43DC-BCDE-45671CE800C8");
    object f;
    RoGetActivationFactory("Windows.ApplicationModel.DataTransfer.DataTransferManager", ref iid, out f);
    return (IDataTransferManagerInterop)f;
  }
  /// The DataTransferManager of a window, as an object the caller casts to the WinRT type.
  public static object ManagerFor(IntPtr hwnd) {
    Guid iid = new Guid("a5caee9b-8708-49d1-8d36-67d25a8da00c");
    IntPtr p = Factory().GetForWindow(hwnd, ref iid);
    try { return Marshal.GetObjectForIUnknown(p); } finally { Marshal.Release(p); }
  }
  public static void Show(IntPtr hwnd) { Factory().ShowShareUIForWindow(hwnd); }
  [DllImport("user32.dll")]
  static extern IntPtr GetForegroundWindow();
  /// The window that has the keyboard now: still the anchor form means no pane took it.
  public static IntPtr Foreground() { return GetForegroundWindow(); }
}
'@

  $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
  function Wait($op, [Type]$t) { $task = $asTask.MakeGenericMethod($t).Invoke($null, @($op)); $task.Wait(-1) | Out-Null; $task.Result }

  function StorageItems([string[]]$paths) {
    $list = New-Object 'System.Collections.Generic.List[Windows.Storage.IStorageItem]'
    foreach ($p in $paths) {
      if (-not (Test-Path -LiteralPath $p -PathType Leaf)) { continue }
      $full = (Resolve-Path -LiteralPath $p).ProviderPath
      $list.Add((Wait ([Windows.Storage.StorageFile]::GetFileFromPathAsync($full)) ([Windows.Storage.StorageFile])))
    }
    return , $list
  }

  # A hidden 1x1 form at the mouse: the window the pane is attached to.
  function NewAnchor {
    $f = New-Object System.Windows.Forms.Form
    $f.FormBorderStyle = 'None'; $f.ShowInTaskbar = $false; $f.TopMost = $true; $f.Opacity = 0.01
    $f.StartPosition = 'Manual'; $f.Size = New-Object System.Drawing.Size(1, 1)
    $f.Location = [System.Windows.Forms.Cursor]::Position
    return $f
  }

  if ($Mode -eq 'check') {
    $f = NewAnchor
    $h = $f.Handle   # makes the window handle without showing the form
    $m = [TrawlShareInterop]::ManagerFor($h)
    if ($m -ne $null) { Say 'available' } else { Say 'unavailable:NoManager' }
    $f.Dispose()
  } elseif ($Mode -eq 'selfcheck') {
    $items = StorageItems $Paths
    $f = NewAnchor
    $m = [Windows.ApplicationModel.DataTransfer.DataTransferManager][TrawlShareInterop]::ManagerFor($f.Handle)
    $handler = [Windows.Foundation.TypedEventHandler[Windows.ApplicationModel.DataTransfer.DataTransferManager, Windows.ApplicationModel.DataTransfer.DataRequestedEventArgs]] { param($s, $e) }
    $token = $m.add_DataRequested($handler)
    $m.remove_DataRequested($token)
    $f.Dispose()
    Say ('ok:items=' + $items.Count)
  } elseif ($Mode -eq 'share') {
    $items = StorageItems $Paths
    if ($items.Count -eq 0) { Say 'error:nofiles'; exit 0 }
    $script:chosen = $false
    $script:shown = $false
    $form = NewAnchor
    $m = [Windows.ApplicationModel.DataTransfer.DataTransferManager][TrawlShareInterop]::ManagerFor($form.Handle)

    $onRequested = [Windows.Foundation.TypedEventHandler[Windows.ApplicationModel.DataTransfer.DataTransferManager, Windows.ApplicationModel.DataTransfer.DataRequestedEventArgs]] {
      param($s, $e)
      $e.Request.Data.Properties.Title = if ($items.Count -eq 1) { 'Trawl: 1 file' } else { 'Trawl: ' + $items.Count + ' files' }
      $e.Request.Data.SetStorageItems($items, $true)
    }
    $null = $m.add_DataRequested($onRequested)

    $done = New-Object System.Windows.Forms.Timer
    $done.Interval = 3000
    $done.add_Tick({ $done.Stop(); Say 'ok'; $form.Close() })
    $null = $m.add_TargetApplicationChosen([Windows.Foundation.TypedEventHandler[Windows.ApplicationModel.DataTransfer.DataTransferManager, Windows.ApplicationModel.DataTransfer.TargetApplicationChosenEventArgs]] {
      param($s, $e) $script:chosen = $true; $done.Start() })

    # When the pane goes away without a target, focus comes back to this window: that is "cancelled". The 1.5 s wait lets a chosen target
    # report first.
    $cancel = New-Object System.Windows.Forms.Timer
    $cancel.Interval = 1500
    $cancel.add_Tick({ $cancel.Stop(); if (-not $script:chosen) { Say 'cancelled'; $form.Close() } })
    $form.add_Activated({ if ($script:shown -and -not $script:chosen) { $cancel.Stop(); $cancel.Start() } })
    # A pane that opens takes the keyboard from this form. If, 4 s after the call, nothing has taken it (no deactivation, this form still the
    # foreground window, no target chosen), no pane came: say so, so Trawl can tell the person instead of leaving them waiting.
    $script:paneSeen = $false
    $form.add_Deactivate({ if ($script:shown) { $script:paneSeen = $true } })
    $watch = New-Object System.Windows.Forms.Timer
    $watch.Interval = 4000
    $watch.add_Tick({
      $watch.Stop()
      if (-not $script:paneSeen -and -not $script:chosen -and ([TrawlShareInterop]::Foreground() -eq $form.Handle)) { Say 'error:nosheet'; $form.Close() }
    })
    $form.add_Shown({
      try { [TrawlShareInterop]::Show($form.Handle); $script:shown = $true; $watch.Start() }
      catch { Say ('error:' + $_.Exception.GetType().Name); $form.Close() }
    })
    # Nobody should be left waiting on a pane for ever.
    $limit = New-Object System.Windows.Forms.Timer
    $limit.Interval = 600000
    $limit.add_Tick({ $limit.Stop(); Say 'cancelled'; $form.Close() })
    $limit.Start()
    [System.Windows.Forms.Application]::Run($form)
  } else {
    Say 'error:usage'
  }
} catch {
  # Any failure to reach the Share contract means "this computer cannot do it": say which kind, never a path.
  if ($Mode -eq 'check') { Say ('unavailable:' + $_.Exception.GetType().Name) } else { Say ('error:' + $_.Exception.GetType().Name) }
}
