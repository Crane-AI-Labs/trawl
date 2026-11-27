# Windows Distribution Checklist (WinGet MSI & Microsoft Store MSIX)

## 1. Build MSI Installer

On Windows x64 with Visual C++ 2015–2022 Redistributable installed (needed so `stageWindowsVcRuntime` stages `msvcp140_1.dll` for ONNX Runtime):

```powershell
cd desktop
..\gradlew -Dorg.gradle.jvmargs=-Xmx2g -Dkotlin.daemon.jvmargs=-Xmx2g `
  packageMsi checkLauncherHeap checkPackagedRuntime `
  checkJvmClassfileVersion checkNoTelemetryInNatives --no-daemon
```

Output: `desktop\build\compose\binaries\main\msi\Trawl-1.1.0.msi` (per-user install, UpgradeCode `6f1d1f7e-3c0e-4a5e-9d57-2b1f5e0c7a11`).

## 2. WinGet Submission

1. Compute SHA-256:
   ```powershell
   (Get-FileHash desktop\build\compose\binaries\main\msi\Trawl-1.1.0.msi -Algorithm SHA256).Hash
   ```
2. Update `InstallerSha256` in `packaging/winget/CraneAILabs.Trawl.installer.yaml`.
3. Validate locally with `winget validate --manifest packaging\winget`.
4. Submit PR to `microsoft/winget-pkgs` under `manifests/c/CraneAILabs/Trawl/1.1.0/`.

## 3. Microsoft Store MSIX Packaging Checklist

- [ ] Build `createDistributable` (`desktop\build\compose\binaries\main\app\Trawl\`) with pinned Temurin 21 runtime + staged `msvcp140_1.dll`.
- [ ] Package with `MakeAppx.exe` using Partner Center `Identity Name="CraneAILabs.Trawl"` and `Publisher="CN=..."`.
- [ ] **Capabilities (Zero-Network D43):** Declare **NO** `internetClient` or `privateNetworkClientServer` capability in `AppxManifest.xml` (only `runFullTrust` for user-chosen folder indexing).
- [ ] **Assets:** Include Square44x44Logo, Square150x150Logo, StoreLogo, and Wide310x150Logo generated from `desktop/packaging/trawl.png`.
- [ ] **Signing:** Sign with EV / Trusted Signing certificate for sideloaded MSIX, or upload unsigned MSIX for Microsoft Store signing.
- [ ] **Validation:** Pass Windows App Certification Kit (WACK) and `desktop/scripts/net-gate-windows.ps1` (zero outbound sockets).
- [ ] **Store Privacy Questionnaire & URL:** Declare 0 data collected, 0 data shared, offline-only operation; set Privacy Policy URL to `https://trawlsearch.com/privacy.html` and Support/Homepage URL to `https://trawlsearch.com/`.
