# 07 — Reconcile distribution/runtime acceptance evidence

Status: resolved — evidence reconciliation complete; retained FAIL/BLOCKED rows keep distribution/runtime not accepted.
Blocked by: no issue 06 workflow dispatch/final native candidates and the standalone Java 17 static dependency failure
Owner: distribution/runtime closeout owner

## Target

Evidence reconciliation only. This ticket owns no production, script, test, workflow, runner, POM, package, topology, or documentation implementation path. It does not rerun scenarios or repair defects.

## Change

1. Pin the accepted implementation and integration commits plus every workflow run/attempt, candidate/provenance hash, native host identity and evidence location.
2. Validate that every terminal record uses the issue 01 status/phase schema and that candidate hashes match the issue 06 handoff. Reject producer-host candidate paths, stale attempts, missing evidence, duplicate rows, or PASS with required null/missing outcomes.
3. Reconcile without aggregation that hides gaps:
   - Windows CPU portable/installed deep runs, three actual installer-upgrade identities, core update, no-engine install, and every retained portable variant;
   - Linux CPU deep run plus OpenCL/NVIDIA distribution rows;
   - native macOS arm64 and x86_64 installed-DMG deep runs and separate signature/notarization/quarantine statuses;
   - standalone Java 17 release-17 compile, effective class version/dependency evidence, provider child and full shaded application run;
   - offline/network-isolation, Unicode/space data, outside-write and cleanup results;
   - real GPU inference references as independent statuses, never inferred from launcher/package PASS.
4. For every deep engine row, confirm the record contains the accepted engine-sgf §2 oracle fields from engine-sgf issue 03. Variant launcher-only rows remain labeled narrower.
5. Preserve every FAIL/BLOCKED reason. Missing prerequisites for a mandatory distribution/runtime row prevent that group's acceptance. Intentional unsigned/not-notarized execution follows the specified quarantine path and may satisfy distribution without signing credentials. GPU inference outside this group's launcher-only rows remains an independent status; its BLOCKED result prevents overall GPU/E completion, not an otherwise complete distribution acceptance. No mandatory row may be waived or rewritten as NOT RUN/PASS.
6. Record actual follow-up defects or an explicit zero-follow-up verdict. New product defects require a separately approved bounded repair.

## Acceptance

- The final table has one unambiguous terminal status per required row and keeps distribution, analysis, inference, signing, notarization and quarantine meanings separate.
- Every PASS is tied to exact source/run/asset/local candidate/host/runtime identity and complete cleanup.
- Every BLOCKED has exact phase/prerequisite/reason plus truthful null/not-observed fields; every FAIL retains failing observations and diagnostics.
- No staging audit, app-image, generated MSI, source classpath, helper test, `katago version`, launcher survival, workflow build success or review approval is substituted for final-product behavior.
- No source/workflow changes or scenario reruns occur in this ticket.

## Evidence output

Return the reconciled table, commit/run/asset index, evidence links, blockers, follow-up list or zero-follow-up verdict, and the explicit group verdict. The ticket cannot declare full acceptance while any retained mandatory row is FAIL or BLOCKED.

## Reconciliation record — 2026-09-19

### Scope and availability

- This closeout changed only this evidence record. It did not change source, tests, workflows, packages, topology, launchers, or documentation, and it did not rerun any acceptance scenario.
- Reconciliation host availability was Linux `x86_64`, OpenJDK `21.0.12`, and `DISPLAY=:0`. This is not a Windows host, either required native macOS architecture, or the recorded Temurin 17 acceptance runtime, so it is not native-product acceptance evidence.
- Issue 06 commit `7f0e0453e7960c2e566ffbc4f79114085140e55f` was not pushed or dispatched. There is no workflow run ID/attempt/URL, final release asset, run-bound provenance, native-host `candidate.json`, Windows `run.json`, or platform-native `acceptance.json`; `dist/release` is empty.
- No credential value was requested or read. macOS signing is optional, but without a final candidate and native host there is no executed signed or intentional-unsigned branch. Signature, notarization, and quarantine therefore remain separate `BLOCKED` observations rather than inferred unsigned results.

### Commit, run, and evidence index

| Surface | Accepted identity | Run / asset / evidence identity | Reconciliation result |
| --- | --- | --- | --- |
| Issue 01 identity/schema | `b12e13c797babc925eeaede6f06c8af9b1a62f79` | Common `candidate.json` and phase-discriminated `acceptance.json` contract | Accepted implementation authority |
| Windows runner | `c620a449d697a72d5693f20325bbd204dca33144` | No final Windows asset, provenance, candidate, run, or acceptance record | Implementation accepted; native evidence absent |
| Linux runner | `d252a8a86133bcf649e2b23f05e4d98c022b95a3` | No final Linux asset, provenance, candidate, or acceptance record | Implementation accepted; native evidence absent |
| macOS runners | `1216fc11cc22a9d467958987ff1b1898f9fd7d1b` | No final arm64/amd64 DMG, provenance, candidate, or acceptance record | Implementation accepted; native evidence absent |
| Standalone Java 17 | `0d35185409f5cafb046085621c6eb78b2476285f` | Static record below is bound to its pre-commit base SHA; issue 05 reports provider-child proof but no exact provider run record | Implementation accepted; native group `FAIL` |
| Issue 06 integration | `7f0e0453e7960c2e566ffbc4f79114085140e55f` | No hosted run or attempt; no uploaded asset/provenance/evidence artifact | Accepted integration; never dispatched |
| Engine-SGF issue 03 oracle interface | `112e167690765d56c6bcd19179d9e70c368b5d7d` | Its D4 CPU proof passed, but no distribution deep row consumed that result | Interface accepted; not distribution evidence |
| TensorRT controlled implementation | `15c213f7d63f887f40b020c379baebfd7b52eb71` | Controlled review/proof is not final-product inference | Accepted narrow evidence only |
| TensorRT trusted preparation | Evidence-only Phase A record in TensorRT issue 02 | Windows 11 x64, RTX 5070 Ti Laptop, driver `591.66`; Phase A `PASS`, Phase B `BLOCKED`; no final candidate or `run.json` | Hardware/input preparation only |
| Java static terminal record | Expected `targetSha=1216fc11cc22a9d467958987ff1b1898f9fd7d1b`; accepted ticket-05 working changes were later committed as `0d35185409f5cafb046085621c6eb78b2476285f` | `/tmp/lizzie-java17-static-1216fc11-4/acceptance.json`; SHA-256 `64833c21d454f1cddaebed11df6f2190e8da9c461215f1f4cfaf579881559c41` | Issue 01 validator accepted row `standalone-java17/amd64/NOT_APPLICABLE/static`; terminal `FAIL` at `static` |

The Java record identifies the full shaded JAR as 23,225,811 bytes with SHA-256 `65744bee8e336625b1f86f8b121a78765a96eb64deee6574482cab6775bf7c9d`, built by Maven JAR Plugin 3.4.2 with JDK specification 21. Its runtime is Eclipse Adoptium Temurin `17.0.20.1+1` on `amd64`. The retained `jdeps-output.log` SHA-256 is `690274c8a4f2d0775fd982f032f92204dae329ffbc1f3742fbcb28899402d3ea`.

### Common BLOCKED observation contract

Every row carrying `NATIVE-IDENTITY-MISSING` or `TENSORRT-CANDIDATE-MISSING` inherits this contract, with any narrower prerequisite stated in its table cell. `NATIVE-IDENTITY-MISSING` means `status=BLOCKED`, `blockedPhase=identity/pre-transfer`, prerequisite “issue 06 trusted/manual producer run and exact final asset plus run-bound provenance, followed by native-host transfer and local candidate verification,” and reason “issue 06 was never pushed/dispatched and produced no run/asset/provenance.” `TENSORRT-CANDIDATE-MISSING` has the same blocked phase and null accounting, with the narrower prerequisite “TensorRT issue-02 Phase B local candidate plus distribution supervisor `run.json`,” and reason “Phase A passed, but Phase B and issue 04 did not run.”

For rows carrying either code, the following paths are `null` with the exact reason “not observed because execution was blocked at identity/pre-transfer before a final candidate existed”: `expected.workflowRunId`, `expected.workflowRunAttempt`, `expected.artifact.path`, `expected.artifact.sizeBytes`, `expected.artifact.sha256`, `observed.provenance`, `observed.candidate`, `observed.host`, `observed.extraction`, `observed.installation`, `observed.mount`, `observed.launcher`, `observed.runtime`, `observed.application`, `observed.dataRoot`, `observed.network`, `observed.outsideWrites`, `observed.backend`, `observed.analysis`, `observed.signatureStatus`, `observed.notarizationStatus`, and `observed.quarantineStatus`. No process, mount, firewall rule, installer state, or disposable scenario path was acquired, so reconciliation cleanup is `complete=true` with `remainingOwnedResources=[]`.

No producer-host path, staging audit, app-image, generated MSI, source classpath, helper test, `katago version`, launcher survival, workflow build result, or review verdict is substituted. This is a reconciliation status, not a fabricated platform `acceptance.json`: issue 01 validation cannot validate a record that was never produced, and this ticket is expressly forbidden to run the producer or native scenarios.

### Windows distribution rows

| Row | Exact final candidate / scenario | Scope kept separate | Status | Phase and reason |
| --- | --- | --- | --- | --- |
| W01 | `2026-09-19-windows64.with-katago.portable.zip` / `portable-offline-first-run` | CPU portable deep distribution + oracle | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING` |
| W02 | `2026-09-19-windows64.with-katago.installer.exe` / `installer-offline-first-run` | CPU installed deep distribution + oracle | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING` |
| W03 | prior base/CPU installer → `2026-09-19-windows64.with-katago.installer.exe` | actual CPU upgrade preservation | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING`; candidate and pinned prior installer/provenance absent |
| W04 | prior OpenCL installer → `2026-09-19-windows64.opencl.installer.exe` | actual OpenCL upgrade preservation | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING`; candidate and pinned prior installer/provenance absent |
| W05 | prior NVIDIA installer → `2026-09-19-windows64.nvidia.installer.exe` | actual NVIDIA upgrade preservation | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING`; candidate and pinned prior installer/provenance absent |
| W06 | `2026-09-19-windows64.core-update.zip` against a verified prior portable | core overlay preservation + launch | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING`; update and verified prior portable absent |
| W07 | `2026-09-19-windows64.opencl.portable.zip` / `variant-launch` | OpenCL launcher/content distribution | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING` |
| W08 | `2026-09-19-windows64.nvidia.portable.zip` / `variant-launch` | NVIDIA launcher/content distribution | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING` |
| W09 | `2026-09-19-windows64.without.engine.portable.zip` / `variant-launch` | no-engine visible repair state | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING` |
| W10 | `2026-09-19-windows64.experimental.directml.portable.zip` / `variant-launch` | DirectML launcher/content distribution | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING` |
| W11 | `2026-09-19-windows64.experimental.openvino.portable.zip` / `variant-launch` | OpenVINO launcher/content distribution | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING` |
| W12 | `2026-09-19-windows64.experimental.rocm.gfx103x.portable.zip` / `variant-launch` | ROCm gfx103x launcher/content distribution | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING` |
| W13 | `2026-09-19-windows64.experimental.rocm.gfx110x.portable.zip` / `variant-launch` | ROCm gfx110x launcher/content distribution | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING` |
| W14 | `2026-09-19-windows64.experimental.rocm.gfx1151.portable.zip` / `variant-launch` | ROCm gfx1151 launcher/content distribution | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING` |
| W15 | `2026-09-19-windows64.experimental.rocm.gfx120x.portable.zip` / `variant-launch` | ROCm gfx120x launcher/content distribution | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING` |
| W16 | `2026-09-19-windows64.without.engine.installer.exe` / clean install + launch | no-engine installer distribution | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING` |

`lizzieyzy-next-update-manifest.json` and the TensorRT split README/manifest/checksum are support/integrity metadata, not runnable distribution rows. The two TensorRT split files are consumed only through the separately blocked trusted TensorRT path; none is accepted as an ordinary portable candidate.

### Linux distribution rows

| Row | Exact final candidate / scenario | Distribution status | Inference status | Phase and reason |
| --- | --- | --- | --- | --- |
| L01 | `2026-09-19-linux64.with-katago.zip` / `cpu-offline-first-run` | `BLOCKED` | `NOT_APPLICABLE` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING` |
| L02 | `2026-09-19-linux64.opencl.zip` / `variant-launch` | `BLOCKED` | `BLOCKED` separately as G09 | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING` |
| L03 | `2026-09-19-linux64.nvidia.zip` / `variant-launch` | `BLOCKED` | `BLOCKED` separately as G10 | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING` |

The current reconciliation host could in principle host a Linux run, but no exact issue 06 asset/provenance/candidate exists. Host capability cannot replace candidate identity.

### macOS distribution and trust rows

| Row | Architecture / meaning | Exact final candidate | Status | Phase and reason |
| --- | --- | --- | --- | --- |
| M01 | native arm64 installed-DMG deep distribution + oracle | `2026-09-19-mac-apple-silicon.with-katago.dmg` | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING`, native Apple Silicon host absent |
| M02 | native x86_64 installed-DMG deep distribution + oracle | `2026-09-19-mac-intel.with-katago.dmg` | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING`, native Intel host absent |
| M03 | arm64 `signatureStatus` | same candidate as M01 | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING`; `SIGNED_VALID` versus `UNSIGNED_INTENTIONAL` was not executed |
| M04 | arm64 `notarizationStatus` | same candidate as M01 | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING`; stapling/notarization was not inspected |
| M05 | arm64 `quarantineStatus` | same candidate as M01 | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING`; neither signed Gatekeeper nor unsigned Open Anyway path ran |
| M06 | x86_64 `signatureStatus` | same candidate as M02 | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING`; `SIGNED_VALID` versus `UNSIGNED_INTENTIONAL` was not executed |
| M07 | x86_64 `notarizationStatus` | same candidate as M02 | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING`; stapling/notarization was not inspected |
| M08 | x86_64 `quarantineStatus` | same candidate as M02 | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING`; neither signed Gatekeeper nor unsigned Open Anyway path ran |

Optional signing credentials do not waive M03–M08. All-absent credentials could select the intentional-unsigned flow only during a real native execution; it cannot be inferred during reconciliation.

### Standalone Java 17 rows

| Row | Proof layer | Status | Exact observation |
| --- | --- | --- | --- |
| J01 | JDK 21 build with compiler release 17 and full shaded JAR | `BLOCKED` | phase `identity/source-binding`; issue-05 implementation is accepted at `0d351854…`, but the retained build record names base SHA `1216fc11…` plus then-uncommitted working changes and has no immutable tree/diff binding to the accepted commit |
| J02 | Java-17-effective classfile gate | `BLOCKED` | phase `identity/source-binding`; the retained record observed 8,440 effective classes, maximum major 61, and zero later-only classes, but its shaded artifact is not exactly source-bound to `0d351854…` |
| J03 | JDK 17 `jdeps --multi-release 17 --recursive` | `FAIL` | 20 unresolved packages; assertion failed at phase `static`; retained diagnostics and hashes above |
| J04 | full shaded logging-provider child on explicit Temurin 17 | `BLOCKED` | phase `evidence/provider-child`; issue 05 reports the child proof passed, but supplies no exact run/log location, run identity, host binding, or cleanup record required for a reconciled `PASS` |
| J05 | exact shaded `java -jar` application smoke under display/offline/Unicode-space isolation | `BLOCKED` | phase `application-smoke/pre-launch`; mandatory J03 static prerequisite failed, so application PID/process/command/data root/display/window/log/outside-write/network observations are null with “not observed before terminal phase static” reasons |

Java BLOCKED accounting is narrower than the native contract. J01/J02 have prerequisite “immutable source binding from the retained artifact/static record to accepted commit `0d351854…`”; `acceptedSourceBinding` is null with reason “the record identifies `1216fc11…` plus then-uncommitted working changes, not an immutable tree/diff for `0d351854…`.” Their recorded artifact, Temurin runtime, classfile observations, and static-run cleanup remain known; the static record has `cleanup.complete=true` and `remainingOwnedResources=[]`. J04 has prerequisite “exact provider-child run record”; `providerRunId`, `providerEvidencePath`, `providerHost`, `providerRuntimeBinding`, and `providerCleanup` are null with reason “issue 05 reports the outcome but supplies no exact provider execution record.” J05 inherits the terminal static record's `cleanup.complete=true`; only its later application/network/data observations are null for the reason stated in the table.

The terminal Java record has `assertions.identity=true`, `assertions.static=false`, launch/verification/cleanup assertions null, diagnostic `static-failure.txt`, and terminal cleanup `complete=true` with no remaining owned resources. Its 20 unresolved packages are `android.net.ssl`, `android.os`, `android.util`, `com.beust.jcommander`, `com.github.luben.zstd`, `com.jogamp.nativewindow`, `com.jogamp.opengl`, `com.jogamp.opengl.awt`, `com.jogamp.opengl.util`, `jakarta.mail`, `jakarta.mail.internet`, `jakarta.servlet`, `jakarta.servlet.http`, `javax.annotation`, `javax.annotation.meta`, `org.brotli.dec`, `org.conscrypt`, `org.jline.jansi`, `org.objectweb.asm`, and `org.tukaani.xz`.

J01, J02, and J04 retain their narrower observed facts but cannot be terminal `PASS` without exact accepted-source/run/host/cleanup identities. They do not aggregate away J03 or substitute for J05. The standalone Java 17 group verdict is `FAIL`, never `PASS`, `NOT RUN`, or an environmental skip.

### Deep engine-oracle accounting

The accepted authority is engine-SGF issue 03 commit `112e167690765d56c6bcd19179d9e70c368b5d7d`. A deep row must record production ownership; frozen fixture semantic path/node kind/board/stones/side-to-play/komi/rules target; confirmed rules and peer position; structurally parsed positive visits at that node; the 400 ms quiet-stop invariant; and bounded PID/reader/staged-file cleanup.

| Deep distribution row | Oracle status | Reason |
| --- | --- | --- |
| W01 CPU portable | `BLOCKED` | no final candidate/native run; every oracle field is unobserved |
| W02 CPU installed | `BLOCKED` | no final candidate/native run; every oracle field is unobserved |
| L01 Linux CPU | `BLOCKED` | no final candidate/native run; every oracle field is unobserved |
| M01 macOS arm64 | `BLOCKED` | no final candidate/native run; every oracle field is unobserved |
| M02 macOS x86_64 | `BLOCKED` | no final candidate/native run; every oracle field is unobserved |

The engine-SGF D4 CPU run passed its own interface proof, but no distribution row consumed that oracle. It therefore cannot satisfy any row above.

### Independent GPU inference references

All rows below are inference-only. Their distribution launcher status is recorded separately above.

| Row | Inference surface | Status | Phase and reason |
| --- | --- | --- | --- |
| G01 | Windows OpenCL portable | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING`; no matching hardware/inference record |
| G02 | Windows NVIDIA portable | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING`; no matching hardware/inference record |
| G03 | Windows DirectML portable | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING`; no matching hardware/inference record |
| G04 | Windows OpenVINO portable | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING`; no matching hardware/inference record |
| G05 | Windows ROCm gfx103x portable | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING`; no matching hardware/inference record |
| G06 | Windows ROCm gfx110x portable | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING`; no matching hardware/inference record |
| G07 | Windows ROCm gfx1151 portable | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING`; no matching hardware/inference record |
| G08 | Windows ROCm gfx120x portable | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING`; no matching hardware/inference record |
| G09 | Linux OpenCL archive | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING`; no matching hardware/inference record |
| G10 | Linux NVIDIA archive | `BLOCKED` | `identity/pre-transfer`; `NATIVE-IDENTITY-MISSING`; no matching hardware/inference record |
| G11 | Trusted Windows TensorRT final-product inference | `BLOCKED` | `identity/pre-transfer`; `TENSORRT-CANDIDATE-MISSING`; issue-02 Phase A hardware/input preparation passed, but issue 04 never ran |

The RTX 5070 Ti Phase A record proves hardware/input preparation only. `katago version`, controlled TensorRT proof, reviewed UI behavior, package layout, repair-state readiness, and launcher survival are not real final-product inference.

### Group verdicts

| Group | Terminal verdict | Acceptance consequence |
| --- | --- | --- |
| Windows distribution | `BLOCKED` | All W01–W16 mandatory behaviors lack exact candidates and native execution |
| Linux distribution | `BLOCKED` | L01–L03 lack exact candidates and native execution |
| macOS distribution/trust | `BLOCKED` | M01–M08 lack exact candidates, both native hosts, and executed trust flows |
| Standalone Java 17 | `FAIL` | J03 failed with 20 unresolved dependencies; J05 did not run after the failing gate |
| GPU inference | `BLOCKED` | G01–G11 have no matching final-product inference record |
| Overall distribution/runtime | **`NOT ACCEPTED`** | Mandatory `FAIL` and `BLOCKED` rows remain; no row is waived or rewritten |

### Blocker and defect ledger

| ID | Kind | Affected rows | Exact blocker / defect | Required follow-up outside this evidence-only ticket |
| --- | --- | --- | --- | --- |
| DR07-B01 | execution blocker | W01–W16, L01–L03, M01–M08, G01–G10 | Issue 06 was not pushed/dispatched; no run attempt, final assets, provenance, native candidates, or terminal native records exist | Produce and transfer exact candidates through the accepted issue-06 handoff, then execute each owning native scenario |
| DR07-B02 | execution blocker | M01–M08 | Neither native Apple Silicon nor native Intel execution/trust-flow evidence exists | Run each exact DMG on its matching native architecture and record distribution/signature/notarization/quarantine separately |
| DR07-F01 | product/runtime defect | J03; blocks J05 and Java-group acceptance | JDK 17 `jdeps` found the 20 unresolved packages listed above | A separately approved bounded dependency/support-policy repair is required; this ticket grants no repair authority |
| DR07-B03 | inference blocker | G11 | TensorRT Phase B/final-product candidate supervision and issue-04 trusted inference never ran | Complete candidate handoff and supervisor `run.json`, then execute trusted real TensorRT acceptance |
| DR07-B04 | evidence-identity blocker | J01, J02, J04 | Retained Java build/static/provider narratives are not immutably bound to accepted commit `0d351854…`; provider run/host/cleanup evidence location is absent | Capture terminal evidence against an exact accepted source identity in separately authorized execution; do not promote narrower observations to `PASS` here |

These are acceptance blockers/defects, not deferred code-review findings. No repair, workflow dispatch, publication, signing operation, credential access, or native rerun is authorized by this ticket.

### Evidence links

- [Distribution/runtime specification](../spec.md)
- [Issue 01 identity and acceptance schema](01-artifact-identity-contract.md)
- [Issue 02 Windows implementation and native prerequisites](02-windows-final-products.md)
- [Issue 03 Linux implementation and native prerequisites](03-linux-final-archives.md)
- [Issue 04 macOS implementation and native prerequisites](04-macos-native-dmgs.md)
- [Issue 05 standalone Java 17 terminal failure](05-standalone-java17.md)
- [Issue 06 integration completion and undispatched state](06-integration.md)
- [Engine-SGF issue 03 accepted oracle interface](../../engine-sgf/issues/03-runner-integration-gate.md)
- [TensorRT issue 02 Phase A/Phase B record](../../tensorrt-gpu/issues/02-trusted-gpu-preparation.md)
- [TensorRT issue 04 trusted real-GPU acceptance](../../tensorrt-gpu/issues/04-trusted-real-gpu-acceptance.md)

### Review follow-up verdict

- Frozen review root `/home/dev/dev/weiqi/worktrees/lizzieyzy-next/distribution-runtime`; review base and expected HEAD `7f0e0453e7960c2e566ffbc4f79114085140e55f`. The final reviewed reconciliation body, before this administrative review record was appended, has SHA-256 `7fb872b4735f9c74c375eb3157be60d8d7333806a950cf37386d5e20b23f7a76`.
- `FULL_REVIEW`: Standards `CLEAN`; Spec admitted `SPEC-01` (incomplete BLOCKED accounting) and `SPEC-02` (unbound Java PASS evidence) as in-scope blockers.
- Repair and `VERIFICATION`: native/TensorRT BLOCKED rows gained exact phase/prerequisite/null/cleanup contracts; J01/J02/J04 became evidence-accurate `BLOCKED`; Standards returned `SUCCESS`; Spec resolved `SPEC-01` and reopened `SPEC-02` when the first repair over-applied native null/cleanup semantics to Java rows.
- Convergence repair scoped the common contract only to native/TensorRT codes and added Java-specific prerequisites, known observations, null reasons, and cleanup states.
- `FINAL_REVIEW`: Standards `PASS` with no findings; Spec `PASS`, with `SPEC-01` and `SPEC-02` resolved. Open in-scope findings: 0. Deferred findings: 0. **Review follow-up items: 0.**
- Review success does not change acceptance: the group remains **`NOT ACCEPTED`** because the blocker/defect ledger above is external acceptance work, not a review ledger.
