# Trawl's video and sound reader for Windows: Media Foundation, through the WinRT classes every Windows already has (nothing is bundled).
# Windows PowerShell 5.1, long-lived: one request per line on stdin, tab-separated, the same protocol as native/media-apple.swift:
#   probe  PATH
#   frame  PATH  TIME_MS  MAX_SIDE
#   audio  PATH  START_MS  SPAN_MS
# Every answer starts with one JSON line on stdout. A picture (JPEG) or a stretch of sound (16 kHz, mono, 16-bit little-endian PCM)
# follows it as length-prefixed chunks: 4 bytes big-endian length, then that many bytes; a length of 0 ends the answer.
# Error text is a fixed short phrase and never repeats the file's path or name. Nothing is sent anywhere.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Storage.StorageFile, Windows.Foundation, ContentType = WindowsRuntime]
$null = [Windows.Storage.StorageFolder, Windows.Foundation, ContentType = WindowsRuntime]
$null = [Windows.Media.MediaProperties.MediaEncodingProfile, Windows.Foundation, ContentType = WindowsRuntime]
$null = [Windows.Media.Editing.MediaClip, Windows.Foundation, ContentType = WindowsRuntime]
$null = [Windows.Media.Editing.MediaComposition, Windows.Foundation, ContentType = WindowsRuntime]
$null = [Windows.Media.Transcoding.MediaTranscoder, Windows.Foundation, ContentType = WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType = WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapEncoder, Windows.Foundation, ContentType = WindowsRuntime]
$null = [Windows.Storage.Streams.InMemoryRandomAccessStream, Windows.Foundation, ContentType = WindowsRuntime]

$ext = [System.WindowsRuntimeSystemExtensions]
$asTaskOp = ($ext.GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
$asTaskAction = ($ext.GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncAction' })[0]
$asTaskProgress = ($ext.GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncActionWithProgress`1' })[0]

# Waits for a WinRT operation and returns its result. PowerShell 5.1 sees these as bare COM objects, so the result type is passed in.
function Await($op, [Type]$t) {
  $task = $asTaskOp.MakeGenericMethod($t).Invoke($null, @($op))
  $task.Wait(-1) | Out-Null
  $task.Result
}
function AwaitAction($op) { $asTaskAction.Invoke($null, @($op)).Wait(-1) | Out-Null }
function AwaitProgress($op, [Type]$progress) {
  $asTaskProgress.MakeGenericMethod($progress).Invoke($null, @($op)).Wait(-1) | Out-Null
}

$in = New-Object System.IO.StreamReader([Console]::OpenStandardInput(), (New-Object System.Text.UTF8Encoding($false)))   # paths arrive as UTF-8
$out = [Console]::OpenStandardOutput()

function Send($h) {
  $b = [System.Text.Encoding]::UTF8.GetBytes(($h | ConvertTo-Json -Compress) + "`n")
  $out.Write($b, 0, $b.Length); $out.Flush()
}
function Fail($why) { Send @{ ok = $false; error = $why; chunked = $false } }
function Chunk([byte[]]$b, [int]$off, [int]$n) {
  $len = [byte[]]@((($n -shr 24) -band 255), (($n -shr 16) -band 255), (($n -shr 8) -band 255), ($n -band 255))
  $out.Write($len, 0, 4)
  if ($n -gt 0) { $out.Write($b, $off, $n) }
}
function EndChunks { Chunk ([byte[]]@()) 0 0; $out.Flush() }

function OpenFile($path) {
  if (-not [System.IO.File]::Exists($path)) { return $null }
  Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($path)) ([Windows.Storage.StorageFile])
}

function Probe($path) {
  $file = OpenFile $path
  if ($null -eq $file) { return Fail 'no such file' }
  try { $profile = Await ([Windows.Media.MediaProperties.MediaEncodingProfile]::CreateFromFileAsync($file)) ([Windows.Media.MediaProperties.MediaEncodingProfile]) } catch { return Fail 'not a media file' }
  $video = ($null -ne $profile.Video) -and ($profile.Video.Width -gt 0)
  $audio = ($null -ne $profile.Audio) -and ($profile.Audio.SampleRate -gt 0)
  if (-not $video -and -not $audio) { return Fail 'no picture or sound in it' }
  $ms = 0
  try {
    if ($video) { $ms = [int64](Await ($file.Properties.GetVideoPropertiesAsync()) ([Windows.Storage.FileProperties.VideoProperties])).Duration.TotalMilliseconds }
    else { $ms = [int64](Await ($file.Properties.GetMusicPropertiesAsync()) ([Windows.Storage.FileProperties.MusicProperties])).Duration.TotalMilliseconds }
  } catch { $ms = 0 }
  $h = @{ ok = $true; hasVideo = $video; hasAudio = $audio; chunked = $false }
  if ($ms -gt 0) { $h.durationMs = $ms }
  Send $h
}

function Frame($path, [int64]$ms, [int]$maxSide) {
  $file = OpenFile $path
  if ($null -eq $file) { return Fail 'no such file' }
  try { $clip = Await ([Windows.Media.Editing.MediaClip]::CreateFromFileAsync($file)) ([Windows.Media.Editing.MediaClip]) } catch { return Fail 'no picture in it' }
  try { $vp = $clip.GetVideoEncodingProperties() } catch { return Fail 'no picture in it' }   # a sound-only file is refused here
  $w = [int]$vp.Width; $h = [int]$vp.Height
  if ($w -le 0 -or $h -le 0) { return Fail 'no picture in it' }
  $longest = [Math]::Max($w, $h)
  if ($longest -gt $maxSide) { $f = $maxSide / $longest; $w = [Math]::Max(2, [int][Math]::Round($w * $f)); $h = [Math]::Max(2, [int][Math]::Round($h * $f)) }
  $at = [double]$ms
  $total = $clip.OriginalDuration.TotalMilliseconds
  if ($total -gt 0 -and $at -ge $total) { $at = [Math]::Max(0, $total - 100) }
  $comp = New-Object Windows.Media.Editing.MediaComposition
  [System.Collections.Generic.ICollection[Windows.Media.Editing.MediaClip]].GetMethod('Add').Invoke($comp.Clips, @($clip))   # PowerShell 5.1 cannot cast the WinRT list itself; the runtime can
  # The nearest keyframe, not the exact frame: much faster, and the phone does the same.
  $stream = Await ($comp.GetThumbnailAsync([TimeSpan]::FromMilliseconds($at), $w, $h, [Windows.Media.Editing.VideoFramePrecision]::NearestKeyFrame)) ([Windows.Graphics.Imaging.ImageStream])
  $net = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($stream)
  $ms2 = New-Object System.IO.MemoryStream
  $net.CopyTo($ms2)
  $bytes = $ms2.ToArray()
  if ($bytes.Length -lt 4) { return Fail 'no picture there' }
  if (-not ($bytes[0] -eq 0xFF -and $bytes[1] -eq 0xD8)) {
    # Not a JPEG already: decode it and write a JPEG.
    $mem = New-Object Windows.Storage.Streams.InMemoryRandomAccessStream
    $dec = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream.CloneStream())) ([Windows.Graphics.Imaging.BitmapDecoder])
    $bmp = Await ($dec.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
    $enc = Await ([Windows.Graphics.Imaging.BitmapEncoder]::CreateAsync([Windows.Graphics.Imaging.BitmapEncoder]::JpegEncoderId, $mem)) ([Windows.Graphics.Imaging.BitmapEncoder])
    $enc.SetSoftwareBitmap($bmp)
    AwaitAction ($enc.FlushAsync())
    $r = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($mem.GetInputStreamAt(0))
    $ms3 = New-Object System.IO.MemoryStream
    $r.CopyTo($ms3)
    $bytes = $ms3.ToArray()
  }
  Send @{ ok = $true; chunked = $true; width = $w; height = $h }
  Chunk $bytes 0 $bytes.Length
  EndChunks
}

function Audio($path, [int64]$startMs, [int64]$spanMs) {
  $file = OpenFile $path
  if ($null -eq $file) { return Fail 'no such file' }
  try { $src = Await ([Windows.Media.MediaProperties.MediaEncodingProfile]::CreateFromFileAsync($file)) ([Windows.Media.MediaProperties.MediaEncodingProfile]) } catch { return Fail 'not a media file' }
  if ($null -eq $src.Audio) { Send @{ ok = $true; chunked = $true }; EndChunks; return }   # no sound is not an error: it is silence
  $dir = [System.IO.Path]::GetTempPath()
  $name = 'trawl-' + [guid]::NewGuid().ToString('N') + '.wav'
  $tmp = [System.IO.Path]::Combine($dir, $name)
  try {
    $folder = Await ([Windows.Storage.StorageFolder]::GetFolderFromPathAsync($dir)) ([Windows.Storage.StorageFolder])
    $dest = Await ($folder.CreateFileAsync($name, [Windows.Storage.CreationCollisionOption]::ReplaceExisting)) ([Windows.Storage.StorageFile])
    $profile = [Windows.Media.MediaProperties.MediaEncodingProfile]::CreateWav([Windows.Media.MediaProperties.AudioEncodingQuality]::Medium)
    $profile.Audio.SampleRate = 16000; $profile.Audio.ChannelCount = 1; $profile.Audio.BitsPerSample = 16; $profile.Audio.Bitrate = 256000
    $tc = New-Object Windows.Media.Transcoding.MediaTranscoder
    $tc.TrimStartTime = [TimeSpan]::FromMilliseconds([double]$startMs)
    $tc.TrimStopTime = [TimeSpan]::FromMilliseconds([double]($startMs + $spanMs))
    $prep = Await ($tc.PrepareFileTranscodeAsync($file, $dest, $profile)) ([Windows.Media.Transcoding.PrepareTranscodeResult])
    if (-not $prep.CanTranscode) { return Fail 'could not read the sound' }
    AwaitProgress ($prep.TranscodeAsync()) ([double])
    $wav = [System.IO.File]::ReadAllBytes($tmp)
  } catch { return Fail 'could not read the sound' }
  finally { try { [System.IO.File]::Delete($tmp) } catch { } }
  # Find the "data" chunk of the RIFF file; its size can be unset (0 or -1) in a streamed file, then it runs to the end.
  $pos = 12; $dataAt = -1; $dataLen = 0
  while ($pos + 8 -le $wav.Length) {
    $id = [System.Text.Encoding]::ASCII.GetString($wav, $pos, 4)
    $size = [BitConverter]::ToUInt32($wav, $pos + 4)
    if ($id -eq 'data') { $dataAt = $pos + 8; $dataLen = [int64]$size; break }
    $pos += 8 + $size + ($size % 2)
  }
  if ($dataAt -lt 0) { return Fail 'could not read the sound' }
  if ($dataLen -le 0 -or $dataAt + $dataLen -gt $wav.Length) { $dataLen = $wav.Length - $dataAt }
  Send @{ ok = $true; chunked = $true }
  $step = 65536; $o = $dataAt; $end = $dataAt + [int]$dataLen
  while ($o -lt $end) { $n = [Math]::Min($step, $end - $o); Chunk $wav $o $n; $o += $n }
  EndChunks
}

while ($null -ne ($line = $in.ReadLine())) {
  if ($line.Length -eq 0) { continue }
  $f = $line.Split("`t")
  try {
    switch ($f[0]) {
      'probe' { Probe $f[1] }
      'frame' { Frame $f[1] ([int64]$f[2]) ([int]$f[3]) }
      'audio' { Audio $f[1] ([int64]$f[2]) ([int64]$f[3]) }
      'quit' { exit 0 }
      default { Fail 'bad request' }
    }
  } catch {
    # The answer to the app stays a fixed phrase; the real reason goes to standard error only when a developer asks for it.
    if ($env:TRAWL_MEDIA_DEBUG) { [Console]::Error.WriteLine($_.Exception.ToString()) }
    Fail 'could not read it'
  }
}
