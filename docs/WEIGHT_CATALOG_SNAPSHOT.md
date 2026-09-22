# Weight catalog snapshot

The setup dialog previously opened/decompressed model headers while rebuilding rows,
matching installed official weights, drawing recommendations and checking compatibility.
HumanSL installation status could also verify the entire model again during rendering.

The dialog now publishes an immutable `WeightCatalogSnapshot` with each directory scan.
Each normalized candidate has one bounded identity read per scan; entries record the
internal name, display fallback, file presence and modification date. A model-name index
resolves official downloads without repeatedly traversing and opening every local file.
The active path wins when several installed files contain the same model.

Initial discovery, selected-engine refresh, imports and downloads load metadata on
background workers. Discovery-only results from other setup actions are also completed
on a worker before rendering. Request generations and source-snapshot identity reject
late results after refresh, engine selection or closing the dialog. Completing metadata
does not change the engine-switch token, active engine or pending state.

Refresh explicitly rereads identities. There is no persistent size/mtime identity cache:
replacing a model under the same name with the same length and timestamp becomes visible
on the next refresh. A displayed snapshot remains internally consistent until then.
Corrupt or unavailable headers retain filename fallbacks; the engine still validates
actual model contents, and applying a profile checks that the model still exists.

## Validation

- A 500-model fixture supplies duplicate normalized paths and verifies exactly 500
  identity reads. After deleting the files, four rounds of all catalog lookups on the
  Swing event thread still produce the recorded identities without additional reads.
- Same-name, same-size and same-mtime replacement changes only the refreshed snapshot.
- Gzip identities, damaged/missing files and duplicate-model active-path priority are
  covered, as is metadata reuse when selecting an already scanned weight.
- Delayed worker completion cannot overwrite a later refresh or selection. Metadata
  completion preserves pending and committed engine-switch state.
- Existing identity helpers retain their fresh-read behavior for other consumers.

Windows headless verification on 2026-09-22: 206 tests, zero failures/errors and three
existing platform skips. The suite includes catalog, weight identity, setup helper,
dialog layout/sidebar, runtime helper and experimental backend installer tests.
Repository line endings, local Markdown links and `git diff --check` also passed.

This reduces application-side metadata I/O. It does not establish a change in KataGo
search throughput; GPU performance must be evaluated with the separate fixed-budget
benchmark protocol. Native desktop acceptance is recorded separately from headless tests.

This change depends on the local-candidate retention fix in PR #537.
