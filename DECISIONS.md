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

## 2026-08-18 — Source language comes from the keyboard subtype, not the pair store

`LanguagePairStore` stores only the **target** per package. The source is whatever the active
keyboard subtype is.

Rationale: HeliBoard already persists the subtype per app (`Settings.saveSubtypeForApp`), so storing
a source alongside it would create two sources of truth that can disagree. It also gives a nice
property for free: switching keyboard language switches the translation source.

Consequence for the chip's "tap swaps direction": a true swap changes the *source*, so it has to
switch subtype. `onTranslatePairKey` does that when an enabled subtype exists for the current target,
and otherwise falls back to cycling the target. Without the fallback the chip would silently do
nothing for anyone who has only one keyboard language enabled.

## 2026-08-18 — The field is written only after a successful translation

Spec section 8 requires that a timeout "restore the pre-translation state". Rather than snapshot and
roll back, `TranslationController` reads, translates, and only then performs the atomic replace. A
timeout, engine failure or cancellation therefore leaves the field byte-for-byte untouched, because
nothing was written. There is no half-edited state to recover from, by construction.

This is also why undo needs only one stored snapshot rather than a journal.

## 2026-08-18 — `isReturnDefaultValues` enabled for unit tests

`android.util.Log` throws "not mocked" in plain JVM unit tests, and `TranslationController` logs on
its normal success path. Without `testOptions.unitTests.isReturnDefaultValues = true`, every
controller test failed inside the `catch (Throwable)` that exists to keep translation failures
visible — reporting a translation failure rather than a logging failure, which was thoroughly
misleading.

Verified not to affect upstream's own tests: `./gradlew testRunTestsUnitTest` passes.

## 2026-08-18 — Phase 3 findings that changed the implementation

**ML Kit reports an English model like any other.** `getDownloadedModels()` listed English as already
on device, so `isPairReady` checks both endpoints of a pair with no special case for English. Every
model translates between its language and English, which is why the model screen tells the user to
keep English.

**ML Kit gives no download progress callback.** `downloadModelIfNeeded` reports only success or
failure. Spec section 7 asks for progress so the UI is "never a frozen spinner"; since there is no
honest number to show, the UI shows an indeterminate state and a toast rather than a fabricated
percentage. `PrepareState.Progress` stays in the interface for the Phase 7 engine, which can report
real decompression progress.

**Default target language was genuinely broken, and the fix is a hint, not a rule.** With ML Kit's
58 languages, an English keyboard on an all-English device skipped every informed rule (stored
choice, system locale, enabled subtypes were all either absent or English) and fell through to
"first supported language that is not the source" — which is Afrikaans, because `af` sorts first. The
chip really did read `EN → AF`.

The fix adds the languages the user has actually downloaded a model for as a preference hint, cached
by the model management screen. That is the strongest signal available: someone who downloads French
wants French. The arbitrary fallback is still there for a device with no models, where it is harmless
because translation reports `ModelMissing` and sends the user to the picker anyway.

**Translation is gated on the first-run disclosure, not just documented by it.** `onTranslateKey`
returns `NeedsDisclosure` until the screen has been accepted, so no model can be fetched and no
translation can run before the user has read what the INTERNET permission is for.

## 2026-08-18 — Live preview (Mode 2) shipped early, alongside Phase 3

Requested directly, so built now rather than in Phase 5. Behaviour follows spec section 5: 400ms
debounce, immediate trigger on sentence-terminal punctuation, rendered only in the candidates strip,
committed by tapping, single cancellable job.

Two things worth knowing:

- **It replaces word suggestions while it is showing.** There is one strip. That means losing
  autocorrect on the source text you are still writing, which is a real cost, not a detail. Off by
  default for that reason as much as for the inference cost.
- **A preview is refused if the field changed after it was computed.** Otherwise tapping a preview
  that landed just before another keystroke would overwrite newer text with a translation of older
  text. The controller compares against the snapshot it translated.

## 2026-08-18 — Spec 6.1's `IME_FLAG_NO_PERSONALIZED_LEARNING` rule is now user-overridable, default ON

**This deliberately departs from the spec**, so it is written down in full.

Spec 6.1 ends: "Also disable when `IME_FLAG_NO_PERSONALIZED_LEARNING` is set on `imeOptions`. If the
app is signalling that it does not want its content learned from, do not process it."

Implemented that way, PolyBoard is dead in exactly the apps it is most wanted in. Captured on device:

| App / field | inputType | imeOptions |
|---|---|---|
| Molly message box | `0x0002C001` `TEXT/NORMAL +CAP_SENTENCES +AUTO_CORRECT +MULTI_LINE` | `0x41000004` `SEND +NO_PERSONALIZED_LEARNING +NO_ENTER_ACTION` |
| Molly search | `0x00008001` `TEXT/NORMAL +AUTO_CORRECT` | `0x03000003` `SEARCH +NO_PERSONALIZED_LEARNING +NO_FULLSCREEN` |

Molly's message box is an ordinary multiline text field — byte-identical `inputType` to WhatsApp's —
and the *only* thing blocking translation was that flag. Signal and its forks set it because they
offer an "incognito keyboard" option.

**The distinction that makes the override defensible:** the flag asks the keyboard not to *learn*
from the content — not to fold it into dictionaries, prediction or personalisation. PolyBoard never
persists typed text at all (6.4: no history, no on-disk cache, and the log carries lengths and short
hashes rather than text). So the flag's actual request is honoured unconditionally, whether or not
translation runs. Translating is processing, not learning.

A password field is a different kind of thing: there the *content* is a secret, so translating it
would move a secret through an engine and into a message box. **No setting unblocks that**, and the
gate is ordered so the hard content rules are evaluated first — a password field with the flag set
reports `TEXT_VARIATION_PASSWORD`, not the flag, and stays blocked with the override on. Four unit
tests pin this down.

Default is **on** (translate in these apps), because the user asked for translation to work in every
app and named Molly specifically. Turn it off in the translation screen: "Translate in
private-keyboard apps (Signal, Molly)". Anyone who prefers the strict spec reading flips that one
switch.

## 2026-08-18 — Fallback field read, so "every app" is closer to true

`getExtractedText` is optional and some `InputConnection` implementations return null. Previously that
meant `read()` returned null and translation was simply impossible in those apps.

There is now a fallback using the cached before/after-cursor reads. It **refuses** when either side
comes back at `EDITOR_CONTENTS_CACHE_SIZE` (1024), because at the cap a full read and a truncated one
are indistinguishable, and translating half a message while computing the replace from the wrong
length is the worst failure this feature has available. Short messages, which is nearly all of them,
now work in apps that would previously have failed outright.

## 2026-08-18 — Distribution changed: public GitHub repo and APK releases

Supersedes the earlier "personal sideload only" decision.

**The GPL-3.0 obligation is now live, and is satisfied by the publication itself.** The repository
contains the complete corresponding source of the fork, the APK releases are built from it, upstream's
README and licences are preserved, and `CHANGES-FROM-UPSTREAM.md` states what was modified as GPL-3.0
requires of modified versions.

Consequences accepted:

- No privacy policy is required for GitHub distribution. One would be required for Google Play.
- The Play annual target-API requirement does not apply. `targetSdk` stays 36 regardless.
- F-Droid would be a problem, not because of the licence but because ML Kit is a proprietary Google
  binary. F-Droid would reject or heavily flag it. That is another argument for the Phase 7 ONNX
  engine, which would remove both the blob and the `INTERNET` permission.

**Signing.** A self-signed 4096-bit RSA key was generated for releases. The keystore lives *outside*
the repository and `keystore.properties`, `*.keystore`, `*.jks` and `*.p12` are gitignored. A signing
key committed to a public repo would let anyone publish an "update" that installs over this app. The
certificate SHA-256 is published in the release notes so users can verify continuity between updates.
Losing the keystore means future releases cannot upgrade existing installs in place.

**Personal information.** The engineering docs originally named the specific apps used for testing,
including a banking app and a mail provider, plus the exact device model. Those were generalised
before publishing, because a public repository under a personal account should not double as an
inventory of the maintainer's phone. Molly is still named: supporting it is a documented feature, not
an incidental detail. Git author identity is inherently public in commit metadata.

## 2026-08-18 — Renamed to BiLanguage Keyboard

`applicationId` `it.hristov.bilanguagekeyboard`, display name "BiLanguage Keyboard", log tag
`BiLangKb`, internal package `helium314.keyboard.bilanguage`, preference keys prefixed `blk_`.

The preference-key rename is safe specifically because the `applicationId` changed at the same time:
the new package cannot see the old app's preferences anyway, so there was no migration to preserve.

## 2026-09-05 — Selectable private GGUF engine via llama.cpp

**Decision: retain Google ML Kit and add the fine-tuned Q4_K_M model as an explicitly selectable
English ↔ French engine.** The app never downloads the private model or embeds a Hugging Face token.
The user imports the exact GGUF through Android's document picker; the app streams it into
app-private storage while checking its known size and SHA-256, and refuses access before unlock.

llama.cpp is pinned as a Git submodule and called through a deliberately small JNI surface. Inference
uses the training prompt verbatim (`FR:\n…` / `EN:\n…`), greedy decoding, and a newline/EOS stop, so
no chat template can add assistant prose. The model lives in a non-exported `:local_llm` process:
loading roughly 398 MB cannot permanently inflate or destabilise the latency-sensitive IME process.
The service and native runtime serialize inference. Provider changes recreate the controller on the
next input view, and the model remains lazy until the first translation.

Only arm64-v8a phones and x86-64 emulators expose the Local LLM choice. Existing 32-bit installs keep
ML Kit support. The controller timeout is 30 seconds because first-load latency is device-dependent;
field contents are still not modified until successful completion, so failure remains atomic.
