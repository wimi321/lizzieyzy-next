# Pinned KataGo source build acceptance

This tooling is a prerequisite for the move-focus pre-release, not permission to publish it.
The production package builders and stable/R2 channels are unchanged until all release gates pass.

## Manual source-build acceptance

Run the three `katago-source-*.yml` workflows explicitly with `workflow_dispatch`
when changing the pinned KataGo source, toolchain, dependency locks or engine build
and packaging logic. Select the reviewed ref and the affected platform workflow;
each workflow runs its existing full platform matrix and retains build evidence.
Changes shared by all platforms require all three workflows.

Ordinary application PRs use application CI and existing identity-verified engine
artifacts for integration checks. The source-build workflows have no automatic PR
trigger. Their results certify the selected source-build inputs, not GPU hardware
inference or the final application package; those keep their separate acceptance
requirements.

## Windows CUDA evidence build

`windows-nvidia` builds the pinned source using the existing MSVC 14.44 toolset,
CUDA 12.8.0 components (NVCC/NVRTC 12.8.61) and cuDNN 9.8.0.87. The exact archives,
sizes and SHA-256 values are in
[katago_cuda_dependencies.json](../scripts/katago_cuda_dependencies.json).
These are the same runtime versions selected by the production NVIDIA packager;
this does not upgrade CUDA, cuDNN or the model. NVIDIA's compiler header explicitly
accepts MSVC 19.44; no unsupported-compiler override is used.

The common static SDK is extended in place only after input verification. Compiler,
headers and import libraries are constrained to that sealed prefix. Environment
overrides cannot select a different CUDA toolkit. The upstream CUDA architecture
selection remains unchanged, including the pre-RTX and RTX 50 targets.

The portable evidence artifact contains the 24 declared runtime DLLs, including
cuDNN graph/attention, NVRTC compiler/builtins and its alternate DLL, nvJitLink and the same MSVC DLLs as
the existing official package. It never copies the old engine or its unrelated
OpenSSL/zlib shared libraries. Every DLL is audited, and the relocated engine must
report its version with the developer SDK removed from `PATH`. NVIDIA's display
driver is a system prerequisite, not a bundled DLL. GPU inference, throughput and
Windows 10/11 final application acceptance remain **NOT_RUN** on the hosted runner.

```powershell
python scripts/build_katago_cuda_dependencies.py --output C:/build/cuda-sdk --jobs 3
python scripts/build_katago_source.py --source C:/src/KataGo --output C:/build/cuda-engine `
  --target windows-nvidia --windows-sdk C:/build/cuda-sdk/prefix --jobs 3
python scripts/package_katago_source_windows.py --build C:/build/cuda-engine `
  --sdk C:/build/cuda-sdk/prefix --output C:/build/cuda-evidence
```

## Windows OpenVINO evidence build

`windows-openvino` uses the same pinned KataGo source and MSVC/static protobuf
toolchain, with [its own dependency lock](../scripts/katago_openvino_dependencies.json).
It preserves the shipped OpenVINO 2026.2.1 runtime. Every OpenVINO and oneTBB DLL
must match both Intel's SHA-verified toolkit and the current official KataGo
1.18.1 bundle. ORT headers and notices use the exact upstream build revision
`7e76a52398ebf966bcbe4a10e552f438059edfce`, not floating latest headers.

The import library is generated only after verifying the actual ORT C exports.
The artifact contains all thirty-one required runtime DLLs, including GPU/NPU
plugins, along with ORT, OpenVINO and oneTBB notices. The old executable and
unrelated OpenSSL/shared protobuf DLLs are not copied. The Intel GPU plugin's
OpenCL loader comes from the pinned common SDK rather than an incidental driver
installation on the build host. Dependency closure and
relocated startup are audited just like the other Windows source targets.

CI runs upstream unit tests and real inference/focus protocol using the explicit
**ONNX CPU provider**. The packaged provider configuration remains `openvino`.
Intel GPU/NPU device selection, drivers and performance remain **NOT_RUN**;
CPU-provider CI evidence does not certify those devices. This is an artifact-only
build and does not change production downloads or publish a release.

## Windows DirectML evidence build

The source matrix also builds `windows-directml` at the same pinned KataGo commit.
It uses the common MSVC 14.44 SDK plus static protobuf 3.21.12. The additional
lock is [katago_directml_dependencies.json](../scripts/katago_directml_dependencies.json).
ONNX Runtime 1.24.4 and DirectML 1.15.4 come from Microsoft's NuGet packages;
their DLLs must be byte-identical to those in the already-shipped official
KataGo 1.18.1 DirectML asset. Only the existing MSVC redistributables are copied
from that SHA-verified asset, never its old `katago.exe` or unrelated DLLs.

The sealed SDK covers headers, import libraries, static libraries, notices and
every runtime file. Packaging requires all eleven runtime DLLs, audits x64 PE
imports (including delay imports), and runs the relocated engine with developer
SDK paths removed. The example provider configuration remains `directml`.

Windows CI runs upstream unit tests, tiny real ONNX inference, and the same-tree
focus protocol using the explicitly recorded **CPU execution provider**. This
is protocol/model execution evidence, **not DirectML GPU acceptance**. DirectML
adapter selection, actual GPU throughput and Windows 10 hardware checks remain
pending; this artifact-only workflow neither publishes a release nor upgrades
the production package catalog.

## Source identity

- Repository: <https://github.com/lightvector/KataGo>
- Merged commit: `47aadc08518b3e121f22539796c911002f699584`
- Upstream change: <https://github.com/lightvector/KataGo/pull/1252>
- This source reports **KataGo v1.18.2**. Preserve its real version output; a version string alone
cannot identify focus support.

Both macOS targets explicitly use deployment target `15.0`, matching the current release's
Mach-O load command. The builder checks the actual executable, rather than relying on the CMake
argument. Building on macOS 26 must not silently raise the release's minimum system version.
The bundle auditor checks every non-system dylib too. A library requiring macOS 15.1 or 26
is rejected even when the executable itself supports 15.0.

The builder rejects dirty or different source checkouts, implicit dependency auto-fetching,
cross-host claims and reused output directories. It records the real compiler, CMake options,
binary size/hash and source revision. A build receipt deliberately leaves packaging, dependency
closure and hardware acceptance as `NOT_RUN`. It does not certify packaged DLLs.

## Build

Prepare the exact clean upstream checkout and separately verified SDK/dependency prefixes first.
The builder does not install packages, update GPU drivers or modify the source checkout.

```sh
python3 scripts/build_katago_macos_dependencies.py \
  --output /path/to/new/sdk-build \
  --arch arm64

python3 scripts/build_katago_source.py \
  --source /path/to/clean/KataGo \
  --output /path/to/new/build-directory \
  --target macos-arm64 \
  --macos-sdk /path/to/new/sdk-build/prefix
```

Use Python 3.12 or later for dependency extraction. Every invocation needs a new output directory; a failed configure or
compile must never leave a previous executable looking like a successful new build.

`katago_macos_dependencies.json` pins the source archives and SHA-256 for protobuf, abseil,
libzip, xz and zstd. All are built for macOS 15.0 into an isolated prefix, without Homebrew
library discovery. Redistributable licenses and a verified file inventory accompany the SDK.
KataGo builds refuse a changed SDK, a different architecture, or an older lock receipt.
The system SDK supplies zlib and platform frameworks. Build tools may come from Homebrew;
its runtime libraries must not leak into the artifact.

The dependency lock keeps the protobuf/abseil/libzip versions found in the reference package.
It additionally pins previously floating optional compression dependencies. This does not
upgrade CUDA, cuDNN, TensorRT or the bundled model. Intel builds use `--arch x86_64` and
`--target macos-amd64` on a native Intel host.

Package a build with its pinned SDK and upstream/third-party licenses:

```sh
python3 scripts/package_katago_source_macos.py \
  --build /path/to/new/build-directory \
  --sdk /path/to/new/sdk-build/prefix \
  --output /path/to/new/portable-engine
```

This verifies the compiled executable's hash before copying, rewrites and verifies the entire
dylib closure, checks minimum macOS versions and records the final rewritten file hashes.
Linkers reserve Mach-O header space for portable dependency paths. Both architectures build in
`katago-source-macos.yml`; these are CI artifacts, not public releases. Hardware acceptance
remains `NOT_RUN` until separately tested. Developer ID signing, notarization and all 15 final
application packages remain separate release gates.

## Linux CPU and OpenCL evidence builds

`katago-source-linux.yml` builds three native x86_64 evidence targets on Ubuntu 22.04.
It pins and verifies Eigen 3.4.0, zlib 1.3.1, libzip 1.11.4 and the Khronos
2024.10.24 OpenCL headers/ICD loader. The loader is not a GPU driver; no vendor
driver or CUDA runtime is installed or changed. OpenCL GPU execution remains unverified.

```sh
python3 scripts/build_katago_linux_dependencies.py --output /path/to/new/linux-sdk
python3 scripts/build_katago_source.py \
  --source /path/to/clean/KataGo --output /path/to/new/linux-build \
  --target linux-cpu --linux-sdk /path/to/new/linux-sdk/prefix
python3 scripts/package_katago_source_linux.py \
  --build /path/to/new/linux-build --sdk /path/to/new/linux-sdk/prefix \
  --output /path/to/new/linux-package
```

Use `linux-opencl` for the second target. Build receipts must match the SDK inventory
and lock digest. Library discovery is restricted to that prefix; compiler flags and
loader environment from the caller cannot select unrelated host libraries. Both
targets avoid AVX2-only instructions. The OpenCL artifact includes its verified ICD
loader and resolves it relative to the executable, not to the build machine.

The ELF audit checks architecture, all direct dependencies, runtime paths and a
glibc ceiling of 2.35. **This build-host check is not production ABI approval.** The
existing official Linux binary is an AppImage; its extracted payload and supported
distribution behavior must be compared before replacing it. Therefore Linux receipts
carry `productionAbiAcceptanceStatus=NOT_RUN`, and the full-release gate rejects them
until that separate compatibility check passes. This change does not increase the
minimum requirements of any released package.

The CPU job must run ordinary/single/multiple/remove/clear focus with the pinned
upstream b6 test model. It preserves raw GTP evidence and fails on timeout, rejection
or tree reset. A green compile-only job is not CPU acceptance.

### Linux CUDA evidence

The `linux-nvidia` target separately locks CUDA 12.1.1 (NVCC 12.1.105) and cuDNN
9.8.0.87 in `scripts/katago_linux_cuda_dependencies.json`. NVIDIA's seven archives
are checked for both byte size and SHA-256 before installation into an isolated SDK.
The existing CUDA 12.1 architecture range is unchanged; no driver is installed.

```sh
python3 scripts/build_katago_linux_cuda_dependencies.py --output /path/to/new/cuda-sdk
python3 scripts/build_katago_source.py \
  --source /path/to/clean/KataGo --output /path/to/new/cuda-build \
  --target linux-nvidia --linux-sdk /path/to/new/cuda-sdk/prefix
python3 scripts/package_katago_source_linux_cuda.py \
  --build /path/to/new/cuda-build --sdk /path/to/new/cuda-sdk/prefix \
  --output /path/to/new/cuda-package
```

Like the existing official Linux CUDA AppImage, this evidence package requires external
CUDA/cuDNN libraries. It does not silently add vendor runtime gigabytes to user packages.
The receipt records their hashes, ELF dependencies and the real relocated engine's
`version` output using the locked external SDK. It rejects build-host RPATHs, missing
runtime components, and requirements above GLIBC 2.34 / GLIBCXX 3.4.30 / CXXABI 1.3.13,
the ceilings observed in the previous official CUDA payload. This is not GPU inference
or final distribution certification: both hardware and production ABI acceptance remain
`NOT_RUN`, and the final release gate still refuses incomplete acceptance.

## Real protocol acceptance

```sh
python3 scripts/probe_katago_focus.py \
  --engine /path/to/self-built/katago \
  --model /path/to/model.bin.gz \
  --evidence /path/to/new/evidence-directory
```

The probe uses one real process, numbered acknowledgements and the engine's **root** visit count.
It checks ordinary analysis, one focused point, two points, removal, clearing and clean shutdown.
Focused points use equal weights and probability `0.5`. Candidate visit totals are not substituted
for root visits. Evidence directories cannot be overwritten. Rejection, exit, timeout and a reset
search tree fail the probe; none are reclassified as unsupported hardware.

`--timeout` bounds each version/GTP operation (default 120 seconds, allowed 1-600), with no
automatic retries. Evidence records the version-check elapsed time, including OS startup security
scanning. Downloaded ad-hoc-signed CI artifacts can incur a macOS first-launch scan; they are not
the final Developer ID signed/notarized application. Preserve any failed attempt and use a new
evidence directory for retests. A warm successful run does not replace final-package cold-start
acceptance.

## Required package matrix

The independent `Pinned KataGo Windows Source` workflow covers CPU and OpenCL evidence builds
on Windows Server 2022 using the MSVC 14.44 toolset. Every dependency archive is hash-locked in
`scripts/katago_windows_dependencies.json`; compiler/SDK versions and file identities are recorded.
It uses a static C runtime, disables AVX2, and rejects unknown PE imports. Only the verified
Khronos OpenCL loader is bundled for OpenCL, not a GPU vendor driver. Relocated binaries must
report the same source identity with the developer SDK removed from `PATH`. CPU additionally
executes the real same-tree focus probe. No unsigned evidence executable is published as a user
download. Windows 10/11 final application and GPU acceptance remain separate requirements.

```powershell
python scripts/build_katago_windows_dependencies.py --output C:/build/locked-sdk --jobs 3
python scripts/build_katago_source.py --source C:/src/KataGo --output C:/build/katago `
  --target windows-cpu --windows-sdk C:/build/locked-sdk/prefix --jobs 3
python scripts/package_katago_source_windows.py --build C:/build/katago `
  --sdk C:/build/locked-sdk/prefix --output C:/build/portable-evidence
```

Run these commands from the selected x64 developer environment; an unverified SDK, wrong compiler
toolset, stale output, missing DLL, or failed engine process is a hard failure, not a fallback to
an older KataGo executable. DirectML and OpenVINO use the separately locked SDK extensions
described above, as do Windows CUDA and TensorRT. Linux CUDA has a separate sealed SDK
and external-runtime audit. The four experimental ROCm targets use the sealed SDK below.

| Platform | Backends |
| --- | --- |
| Windows x64 | Eigen, OpenCL, CUDA, TensorRT, DirectML, OpenVINO |
| Windows x64 experimental ROCm | gfx103x, gfx110x, gfx1151, gfx120x |
| Linux x64 | Eigen, OpenCL, CUDA |
| macOS | Apple Silicon Metal, Intel Metal |

All **15** targets require compilation, packaging and dependency-closure audit. Hardware acceptance
may be `PENDING_HARDWARE` only when corresponding hardware is unavailable, never after an actual
test failure, and must include an explicit reason. Eigen CPU targets must execute, not claim a
missing GPU. Receipts must identify the correct backend and executable size/digest. A receipt
completeness check does not replace checking the final package bytes,
signed macOS bundles or public download hashes.

## Windows ROCm source evidence

The four `windows-rocm-*` targets keep the existing ROCm 7.13 per-family runtime bytes
from the official KataGo 1.18.1 distributions. Only `katago.exe` is rebuilt from the pinned
source; the downloaded older executable is never copied into the SDK or output package.
Both `rocblas/library` and `hipblaslt/library` are mandatory, with full size/digest inventory.

`scripts/katago_rocm_dependencies.json` pins the AMD compiler/headers archive and four
runtime archives. AMD's public `7.13.0` archive has an internal `7.13.0rc2` directory name;
the embedded `.info/version` is `7.13.0`. The lock records this without relabeling the
archive, and its SHA-256 was calculated from the official AMD download. The seven key
SDK DLLs were independently compared with the existing gfx110X distribution and matched.

The native Windows workflow compiles all four targets, retaining the broad upstream GPU
architecture range. Upstream still probes MSVC compatibility; packaging rejects selection
of a different toolset instead of silently changing the locked build. The HIP SDK and
Windows developer environment are not needed on end-user machines.
The build process supplies upstream's required `HIP_PLATFORM`, `HIP_DEVICE_LIB_PATH`
and `LLVM_PATH` alongside `HIP_PATH`, all scoped to the sealed SDK rather than changing
machine-wide settings. A real compiler/cmath preflight preserves stderr before upstream's
otherwise-silent toolset probe, so missing device libraries are diagnosable.

```powershell
python scripts/build_katago_rocm_dependencies.py --output C:/build/locked-sdk `
  --target windows-rocm-gfx120x --jobs 3
python scripts/build_katago_source.py --source C:/source/KataGo --output C:/build/katago `
  --target windows-rocm-gfx120x --windows-sdk C:/build/locked-sdk/prefix --jobs 3
python scripts/package_katago_source_windows.py --build C:/build/katago `
  --sdk C:/build/locked-sdk/prefix --output C:/build/portable-evidence
```

A source build needs at least 30 GB free temporary space. PE closure and execution with
a system-only PATH are mandatory. A successful `version` invocation is not GPU inference;
each family's hardware acceptance remains `NOT_RUN` until tested on the corresponding GPU.
These targets remain experimental and do not change the stable channel or default backend.

## Windows TensorRT source evidence

`windows-tensorrt` uses the same sealed Windows CUDA 12.8/cuDNN 9.8 SDK plus the existing
TensorRT 10.9.0.34 redistribution. `scripts/katago_tensorrt_dependencies.json` locks the
official archive's size, SHA-256 and seven runtime DLLs. Headers, import libraries,
acknowledgements and the parser runtime are retained; unlisted DLLs fail the build.
The same lock pins Protobuf 3.21.12 source for the upstream ONNX emitter. It is compiled
statically with the matching MSVC runtime, so no new protobuf DLL is needed and no
symbols are exchanged with the private protobuf inside NVIDIA's parser.

```powershell
python scripts/build_katago_tensorrt_dependencies.py --output "$env:TEMP/katago-trt-sdk"
python scripts/build_katago_source.py --source upstream-katago --output "$env:TEMP/katago-trt-build" --target windows-tensorrt --windows-sdk "$env:TEMP/katago-trt-sdk/prefix"
python scripts/package_katago_source_windows.py --build "$env:TEMP/katago-trt-build" --sdk "$env:TEMP/katago-trt-sdk/prefix" --output "$env:TEMP/katago-trt-package"
```

The normal x64 MSVC 14.44 environment is required. Packaging verifies every PE import,
runtime closure and the relocated executable with a system-only PATH. The source SHA
and compiler are recorded without claiming a new official KataGo release. A CPU-hosted
`version` check is not TensorRT inference or hardware acceptance: those remain `NOT_RUN`.
This workflow neither uploads release assets nor updates stable downloads.

## Integration gates still required

### Sealed source release staging

`scripts/stage_katago_source_release.py` consumes one audited package per target and
separate, executable-bound acceptance evidence. It checks every byte in the original
inventory, including licenses and nested ROCm runtime data. Missing targets, changed
files, path traversal, case collisions, symlinks, failed audits and old source identities
all fail before a catalog becomes visible. It does not upload or publish anything.

```sh
python3 scripts/stage_katago_source_release.py \
  --packages /path/to/packages-by-target \
  --acceptance /path/to/acceptance-by-target \
  --base-catalog src/main/resources/katago-assets.json \
  --source /path/to/clean/pinned/KataGo \
  --output /path/to/new/release-staging \
  --tag next-YYYY-MM-DD.N
```

Each acceptance directory contains `hardware.json`: either a real focus probe result
bound to that exact executable, or an explicit `PENDING_HARDWARE` record with a reason,
source commit and executable digest. CPU execution cannot be deferred. ONNX CPU-provider
evidence does not certify DirectML or Intel GPU/NPU hardware. Linux additionally requires
`linux-compatibility.json`, recording the reference executable digest, symbol ceilings
and passing distribution checks. Do not synthesize these records from a green compile.

All 15 deterministic archives and the catalog are staged atomically. CUDA and TensorRT
repair archives keep the compiled engine and notices, reusing the existing separately
locked runtime installers instead of duplicating gigabytes. Other targets retain their
full audited dependency closure. `source-release.json` describes the exact archive
inventory and runtime policy; the original source receipt remains included as evidence.
Default GTP and analysis templates are included from the same clean pinned source
checkout. Archives are reopened and every stored file is hashed before staging completes.

The shared catalog distinguishes official releases from `project-source-build`. Only
the reviewed `wimi321/lizzieyzy-next` immutable release URL is accepted for project
engines. Model URLs remain official and unchanged. Runtime repair, CUDA companions,
experimental installation and TensorRT packaging use the same source-aware URL resolver.
Catalog schema 2 records `zlibLinkage: static` only for the pinned Windows CUDA and
TensorRT project builds. Installed engine manifest schema 2 binds the catalog origin,
asset ID/name/archive digest, executable digest, backend, source commit and zlib linkage
to the final executable. Runtime readiness omits the dynamic zlib DLL group only when
that manifest and the executable still match the catalog exactly; official, external,
unknown or modified engines remain subject to their dynamic dependency contract.
The TensorRT HumanSL companion has no manifest of its own. It omits the zlib group only
when the TensorRT engine beside it passes that check, the same strict manifest lists
the companion, the companion matches the pinned CUDA executable digest, and the CUDA
catalog asset is a static-zlib project build. Its other CUDA 12.8/cuDNN 9 requirements
still apply, and TensorRT component status and repair use the companion's result.
The checked-in production catalog is **not switched by staging**. Final archive upload,
application package integration, signing and publication remain separate gates.

Linux CUDA packages also carry the hash-locked SDK's `libz.so.1`, required dynamically by
cuDNN. CUDA/cuDNN remain external; zlib must not be silently resolved from the build host.

- Lock and fetch build SDKs and dependency archives by version and digest without changing the
  existing CUDA/cuDNN, TensorRT, ROCm and ONNX execution-provider runtime choices.
- Build and audit every target in CI, then feed those exact verified artifacts into full packages.
- Publish trusted self-built engine catalogs for repair and on-demand installation; do not let a
  repair silently replace the new engine with an old official release.
- Complete #449 runtime probing and GUI/SGF regression. The approved release scope removes the
  legacy `allow` fallback: unsupported engines retain ordinary analysis, with an upgrade hint
  for point evaluation. A main-program update does not replace an external or old engine.
- Collect all final assets in Draft and audit them before any pre-release publication.

The current stable release, official download catalog and R2 assets must remain untouched.

## Installing reviewed source archives into full packages

The release build reads `origin` from `katago-assets.json`. A `project-source-build`
catalog selects `prepare_katago_source_assets.py` for the native platform. It verifies
the archive length, SHA-256, executable and complete inventory before copying files,
preserving runtime directories, licenses and the source receipt. The old official
download/source-builder path is not a fallback for a failed self-built archive.

Windows TensorRT uses the same verified archives and adds the hash-locked CUDA HumanSL
companion without replacing the engine provenance fields. Existing NVIDIA runtime
preparation is unchanged. Before macOS signing or Windows/Linux publication,
`audit_katago_source_bundle.py` checks the strict engine manifest, exact executable,
source identity and every original file in the installed bundle against its receipt;
when the optional TensorRT companion is present, the audit verifies its catalog digest
as well. Final platform signing may legitimately change macOS executable hashes afterwards;
the final signed assets are bound to their workflow by the existing release provenance.

The reviewed source catalog must point to the exact forthcoming application release
tag, not another release. The publisher first creates its normal immutable tag/Draft,
then waits up to 20 minutes for the 15 previously staged source archives to be uploaded.
Every asset must match the committed catalog's size and GitHub SHA-256 digest before
application packaging is dispatched. Release jobs use the short-lived workflow token
to read those Draft assets; repair downloads use the same public URLs after publication.
Missing archives leave the Draft unpublished. Unexpected, duplicate or modified assets
fail verification, including the final public recheck. No old engine is substituted.

For the first source-catalog activation, the reviewed archives may be uploaded to an
unpublished Draft before the activation PR is merged, so its Linux CPU acceptance job
can verify the exact new binary. Use the short-lived read-only workflow token; never
publish the Draft to make a failing test pass. Before starting the publisher, bind that
still-unpublished Draft to the final reviewed commit and ensure no conflicting tag exists.
The publisher continues to enforce the final immutable tag and commit identity.
CPU acceptance uses the same trusted project URL and archive hash, and additionally
checks the extracted executable hash and actual reported upstream revision. A failed
Draft download or verification must not fall back to the old official engine.

### Production Linux compatibility evidence

The Linux source workflow runs `audit_katago_linux_compatibility.py` after relocation.
It verifies the SHA-locked previous official 1.18.1 archive, extracts its AppImage
payload without FUSE, and compares GLIBC, GLIBCXX and CXXABI symbol ceilings against
every new bundled ELF file. It then runs both old and new executables inside pinned
native amd64 Ubuntu 22.04 and 24.04 containers. The CPU target additionally performs
a real GTP move with the pinned upstream model in each distribution.

The new engine is tested with only the system C++ runtime installed; the old bundle's
SSL/OpenCL system dependencies are added afterward for the baseline comparison. Engine packages,
reference payloads and SDKs are mounted read-only, CUDA/cuDNN remain separately verified
external dependencies, and no driver is installed on the runner or user's system.
The log records actual libc/libstdc++ package versions. A failed loader or symbol check
produces a retained FAIL report, never a missing-hardware waiver. GPU inference is not
performed or claimed by this compatibility check. Final app packaging remains separate.
