# Decisions

## 2026-08-18 — Translation engine (spec section 3)

**Decision: staged. `StubEngine` first, then ML Kit (Option A), with Option B (bundled
Opus-MT via ONNX Runtime) kept as a planned Phase 7 replacement.**

`android.permission.INTERNET` is accepted, for model download only.

Rationale: Option A validates the whole keyboard UX end to end with 58 languages for a
fraction of Option B's effort. Option B needs a SentencePiece tokenizer on device and a
hand-written greedy/beam decode loop, which is a large amount of work to sink before
knowing whether the commit-time translation UX is even pleasant to use. Building against
the `TranslationEngine` interface from the start keeps B a drop-in.

Cost accepted: HeliBoard's upstream identity is that it declares no INTERNET permission.
This fork breaks that property. The first-run disclosure (spec section 7) is what pays for
it, and the model download is logged so it stays auditable.

## 2026-08-18 — Language pairs (spec section 13.2)

**Decision: whatever the engine supports** — expose all 58 ML Kit languages rather than
prioritising a short list. Per-app pair memory decides what actually gets used.

**Unresolved tension, flagged rather than silently reconciled:** this is only compatible
with Option A. A bundled Opus-MT engine is realistic for roughly 4 directions, not 58, at
15-75MB per direction. So if Phase 7 ever happens, the supported language set necessarily
narrows to a handful of pairs. Phase 7 is therefore not a pure drop-in replacement at the
product level even though it is at the interface level. Revisit before starting Phase 7.

Consequence for quality: with all languages exposed, non-English pairs pivot through
English inside ML Kit (FR->EN->BG). That quality loss is accepted as the price of breadth.

## 2026-08-18 — Distribution (spec section 13.3)

**Decision: personal sideload only.**

Consequences: no GPL-3.0 source-publication obligation is triggered yet (see README), no
privacy policy is required, and the Play annual target-API deadline does not apply.
`targetSdk` stays at 36 regardless, because it is upstream's value and there is no reason
to regress it.

## 2026-08-18 — Minimum Android version (spec section 13.4)

**Decision: `minSdk` 26**, raised from upstream's 21.

ML Kit requires API 23 or higher, so 21 was not viable alongside the engine decision. 26
was chosen over the bare minimum of 23 to drop legacy storage/notification/threading code
paths. Device reach is irrelevant for a personal sideload on known hardware.

Follow-up available but not taken: upstream pins `androidx.core:core-ktx` to 1.17.0 and
`compose-bom` to 2025.11.01 specifically because newer versions require minSdk 23. Those
pins are now liftable. Left alone for now to keep the diff against upstream small.

## 2026-08-18 — Fork identity

`applicationId` is `it.hristov.polyboard`; the Kotlin/Java namespace stays
`helium314.keyboard.latin`. Renaming the namespace would touch thousands of files for no
benefit and would make every future rebase onto upstream painful. `applicationId` is what
governs co-installation, and it is separate from the namespace in AGP.

The two `FileProvider`/`ContentProvider` authorities had to be renamed too. They are
hardcoded string resources (`clipboard_provider_authority`, `gesture_data_provider_authority`)
rather than `${applicationId}`-derived, so without that change installing this fork
alongside real HeliBoard fails with `INSTALL_FAILED_CONFLICTING_PROVIDER`.
