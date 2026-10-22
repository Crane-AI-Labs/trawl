# Slim ONNX Runtime

The app has two users of ONNX Runtime (ORT):

| User | Models | Runtime |
|---|---|---|
| Quick look (`engine/ClipEncoder.kt`) | TinyCLIP-61M/32 `image.dyn8pc.onnx`, `text.wo8.onnx` | the slim ORT 1.31.0 built here |
| Blur private-looking previews (`engine/PrivateScorer.kt`) | Marqo nsfw-384 `nsfw-384.onnx` | the same slim ORT (one process, one `OrtEnvironment`: `ClipEncoder.environment()`) |
| Speech, VAD, sound labels (`extract/Sherpa*.kt`) | sherpa-onnx's models | sherpa-onnx's own ORT 1.28.2, linked statically and privately inside `libsherpa-onnx-jni.so` (arm64). Not affected by this build. |

The stock `onnxruntime-android` AAR costs about 12.9 MB compressed in the APK (every operator kernel, ML ops, a telemetry
client). The slim build keeps only the operators the models above need, so it is about 4 MB. It is arm64-v8a only.

## Fresh checkout

`app/libs/onnxruntime-slim.aar` is gitignored (like sherpa's AAR), so a fresh checkout builds it once:

    scripts/fetch-asr.sh                 # sherpa-onnx AAR into app/libs
    TRAWL_BUILD_SSH=user@host scripts/ort-slim/build.sh   # ORT 1.31.0 built on a Linux machine you name, AAR into app/libs

`build.sh` needs ssh access to a Linux x86-64 machine (`TRAWL_BUILD_SSH=user@host`; there is no default) with the Android NDK 27.1
under `~/Android/Sdk`, python3 and git; the work goes under `TRAWL_BUILD_ROOT` (default `~/trawl-ortslim` there). It clones ORT v1.31.0, makes a Python
venv, builds with `--include_ops_by_config scripts/ort-slim/ops.config --disable_ml_ops --no_telemetry --enable_lto`
(MinSizeRel, NDK 27.1, API 30), strips the libraries and `make_aar.py` packs them with ORT's own Java classes. A first build
takes about 40 minutes on 8 vCPUs; `BUILD=<dir>` reuses a build directory incrementally. The build is not bit-reproducible, but
`ops.config` and the ORT tag are fixed, so the result is functionally the same.

## When a model changes: regenerate `ops.config`

`ops.config` is generated from the `.onnx` graphs, never by hand:

    GEN=1 scripts/ort-slim/build.sh

`GEN=1` copies the shipped models (the quick-look pack and the private pack in the git-ignored `bench-data/` folder, named from `QuickLookPack.kt` and
`PrivatePack.kt`) to the box, runs `gen_ops_config.py` there with the same `onnxruntime` version as the build, and writes
`ops.config`. The script unions, over every model: the operators in the graph, the operators after ORT's optimiser at each
level (it fuses nodes into `com.microsoft` ops such as `MatMulNBits`, `MatMulIntegerToFloat`, `Gelu`), and a short list of
operators the optimiser creates and drops on the way (`Gemm`, from MatMul+Add: with no CPU kernel for it session creation fails
with "Could not find an implementation for MatMulNBits(1)", although that op is compiled in) plus the Arm-only convolution
fusions (`NhwcFusedConv`) the x86 box cannot show.

**A new ORT model (or a changed graph) must be added to the model list in `build.sh` and the config regenerated.** A model whose
operator is missing fails at session creation with "Could not find an implementation for X" - the pass logs it and stops, so
the symptom is a feature that quietly does nothing. The proof is always a real phone: index the same photos with a build on the
stock ORT and one on the slim ORT, then compare what each stored (the quick-look vectors bit by bit, and the privacy scores) with
`scripts/ort-slim/ort_compare.py`. Compare arms only when both indexed the same number of files.

## Not done, on purpose

- ORT-format models with a minimal build: the `.ort` files are ~16% larger (+17 MB in the pack).
- Putting the ORT library in a downloadable pack: Play forbids loading native code from downloaded files.
- XNNPACK / NNAPI: not needed, speed is at parity with the stock build.
