# The zero-network release gate (desktop)

Trawl's promise is that nothing leaves the computer. This gate runs the PACKAGED app (the installer's own copy: its bundled Java runtime, its
native libraries, its helper programs) through a scripted ten-minute session and FAILS if the app, or any program it starts, ever opens an
internet socket or connects to a non-local address. Run it on all three installers before every desktop release.

Why it exists: ONNX Runtime 1.29-1.31 shipped Microsoft's telemetry client, on by default, and an app built on it phoned `mobile.events.data.microsoft.com`
about ten seconds after start. No unit test could see that. The gate is the test that would have.

## What the session does (`TRAWL_NETGATE_DIR`, `desktop/.../ui/NetGate.kt`)

It drives the controller the way the buttons do (no clicks, no pointer): first run, installs the signed model bundle and the Privacy pack from
files, indexes a made-up folder (documents, PDFs, photos, a screenshot with words, a spoken audio note, a short video with speech) so every
native library and helper loads, waits for the privacy-blur scoring pass, searches (words, meaning, every filter, the quick panel's own search),
shows and hides the quick panel, opens Settings and Licences, builds the Copy-diagnostics report, runs the share helper's no-window self-check,
then keeps rescanning and searching until the time is up (default 10 minutes), and writes `netgate-result.json`.

The gate refuses to run on real data: the JVM's `user.home` must be the scratch folder (the runner sets `-Duser.home` through
`JAVA_TOOL_OPTIONS`; a JVM does not follow `$HOME`). The global shortcut is switched Off in the scratch settings.

## It fails when

- the app or any child process opens any AF_INET/AF_INET6 socket (Linux: any use, loopback included; macOS and Windows: any socket row owned by the process tree);
- the scripted session did not finish every step, or ran shorter than the required minutes;
- the app is not on its packaged runtime, the runtime is older than the minimum safe Java (21.0.8), or Copy diagnostics lacks the "Java runtime:" line;
- a Microsoft `DeveloperTools` / `.onnxruntime` folder appears under the scratch home;
- the positive control is not seen: before every run a canary connects to a TEST-NET address (203.0.113.7:443) and the SAME observer must catch it, so a blind observer cannot pass.

The JVM itself opens one to three never-used probe sockets at start (libnet); on Linux they appear as `socket()` only and are listed, not counted.

## How to run

Inputs: the installed or unpacked app, a made-up corpus folder, `trawl-models.zip`, `findr-private-models.zip`, a scratch home folder (deleted at the end).

Corpus: `scripts/make_demo_docs.py <docs-dir> <pics-dir>`, a handful of photos, and a spoken note made with `say`/`espeak` plus ffmpeg
(`ffmpeg -loop 1 -i photo.jpg -i note.wav -t 8 -c:v libx264 -c:a aac clip.mp4`).

macOS (attach the dmg, run against the copy inside it; no Gradle needed):

    hdiutil attach -readonly -nobrowse -mountpoint /tmp/trawl-mnt Trawl-1.0.0.dmg
    codesign --verify --deep --strict /tmp/trawl-mnt/Trawl.app
    python3 desktop/scripts/net-gate.py run --app /tmp/trawl-mnt/Trawl.app/Contents/MacOS/Trawl \
      --corpus <corpus> --models trawl-models.zip --privacy findr-private-models.zip --home <scratch>/home --out <scratch>/out --minutes 10

It polls `lsof` over the whole process tree every 0.25 s and `nettop -t external` once a second. Quit is by PID.

Linux (strace needs no root; Xvfb gives the app a display):

    dpkg-deb -x trawl_1.0.0_amd64.deb root
    xvfb-run -a python3 desktop/scripts/net-gate.py run --app root/opt/trawl/bin/Trawl \
      --corpus <corpus> --models trawl-models.zip --privacy findr-private-models.zip --home <scratch>/home --out <scratch>/out --minutes 10

It runs the app under `strace -f -yy -e trace=%network,close`, so even a connection too short for any poller is in the log.

Windows (on a throw-away VM in a console session; install the msi first, then):

    powershell -ExecutionPolicy Bypass -File desktop\scripts\net-gate-windows.ps1 -App "$env:LOCALAPPDATA\Trawl\Trawl.exe" `
      -Corpus C:\gate\corpus -Models C:\gate\trawl-models.zip -Privacy C:\gate\findr-private-models.zip -ScratchHome C:\gate\home -Out C:\gate\out -Minutes 10
    (copy C:\gate\out back to a machine with Python 3, then)
    python3 desktop/scripts/net-gate.py analyse-poll out/poll.csv
    python3 desktop/scripts/net-gate.py check-result out/netgate-result.json --minutes 10

It polls `Get-NetTCPConnection` and `Get-NetUDPEndpoint` by owning process id over the process tree every 0.25 s, and records the system's TCP
"Active Opens" counter before and after (informational: a connection shorter than the poll interval moves it; another program can too).
Create the VM only from a Linux build machine, in its own resource group (`rg-trawl-desktop-N`, westeurope, tags `company=crane-ai-labs project=trawl`, SSH only from that machine,
auto-shutdown), and delete the group afterwards (`az group exists` must print false). Cost for one gate run is well under 1 USD.

Exit code 0 = pass, 1 = fail. `verdict.json` (macOS, Linux) and `windows-run.json` plus `poll.csv` (Windows) are the evidence to keep.

## Honest limits

- It proves the scripted session opened no connection, not that no code path ever could. It covers every native library, helper and model the app ships, but not a feature nobody triggered.
- The Windows poll is a sampler (4 per second). A connection shorter than 250 ms that closes between samples would be missed by it; that is why the build also scans every native
  library for the telemetry client's strings (`checkNoTelemetryInNatives`) and pins ONNX Runtime to a version without it. The Linux strace has no such gap.
- `nettop` shows byte counts only for traffic that left on a real interface, so on its own it would miss a connection that moved no bytes; `lsof` covers that.
- Share sheets, the mail client and "Open with" hand files to other programs; those programs are not Trawl and are not watched.
