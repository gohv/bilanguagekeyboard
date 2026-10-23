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

## 2026-10-23 — Opt-in cloud LLM engine with a user-supplied API key

**Decision: add `CloudLlmEngine` as a third, explicitly selected engine.** It speaks the Anthropic
Messages API and the OpenAI chat-completions shape, which together cover nearly every hosted
provider. Presets (Mistral, the default, then Anthropic, OpenAI, OpenRouter, Gemini, custom) only fill in the base URL and a
default model; both stay editable.

**This ends "no typed text leaves the device" as an unconditional property.** It remains true for
the default ML Kit engine and for Local LLM, and the disclosure, README, manifest comment and
`TranslationEngine` contract now say so with this one exception named. What keeps the exception
narrow:

- Never the default, and does nothing until the user saves a key (`isPairReady` is false without one,
  so the strip says "not set up" instead of sending anything).
- **Live preview is refused for this engine** (`BiPrefs.livePreviewActive`) regardless of the
  checkbox: preview translates partial drafts on every pause, and sending text the user never asked to
  translate is a different thing from sending what they tapped translate on.
- `FieldGate` is unchanged, so password, email, URL, phone and number fields are still never sent.
- The request contains the text, the two language names and a fixed instruction, nothing else.
- Logs record host, model, HTTP status, latency and redacted length/hash, never text, response or key.
- HTTPS only. Plain-HTTP LAN servers (LM Studio, Ollama) are refused by design rather than by
  adding a cleartext network-security exception for the whole keyboard process.

**The key is encrypted with an AES-GCM key in the Android Keystore** and only the ciphertext goes to
SharedPreferences. `EncryptedSharedPreferences` was not used because it is deprecated. A restored
backup carries undecryptable ciphertext and the user re-enters the key.

Language identification for the 6.2 idempotence check still uses on-device ML Kit, so it adds no
network use. No temperature is sent and OpenAI-compatible requests carry no token limit, because
several current models reject non-default values or `max_tokens`; the controller timeout bounds them.

## 2026-10-23 — Translate box (type in the keyboard, translation appears in the app)

Requested after using the cloud engine: tapping translate per message is too slow. Modelled on
Samsung Keyboard's translation bar. With "Translate key opens a translation box" on (the default),
the translate key opens a box above the strip; what is typed there is translated after a 500 ms pause
(immediately on `.`, `!`, `?`) and the translation replaces whatever the box last wrote into the app.
When the app empties its field (a sent message), the box clears for the next one.

**Redirect by overriding `LatinIME.getCurrentInputConnection()`.** `RichInputConnection` fetches the
connection from it before every operation, so pointing it at the box's own `EditText` connection
gives the box all of upstream's typing behaviour (composing, autocorrect, suggestions, gestures) with
no changes to `InputLogic`. The box reports its own selection changes in place of the system's
`onUpdateSelection`, posted and coalesced so they are never re-entrant, and the system's reports for
the app's field are diverted while the box is open. Opening and closing re-run the same
`startInput` / cache reset that `onStartInputView` does.

**Privacy, with Cloud LLM: the box sends drafts on each pause.** That is the thing live preview is
refused for. The difference is intent: the box is opened explicitly for live translation and only its
contents are sent, never the app's field. The settings hint says so plainly.

## 2026-10-23 — Casual / Polite switch for French

A chip in the translate box switches between casual (tu) and polite (vous) French. It is shown only
with Cloud LLM and a French target, because only an instruction-following engine can honour it; ML
Kit and the fine-tuned local model have no such control. The choice is stored (`blk_register`,
default casual) and read by `CloudLlmEngine` on every request, so it applies to one-tap translation
too. It replaces the prompt's "keep the register" line with an explicit tu/vous instruction.
Switching re-translates the box immediately.

## 2026-10-23 — Local LLM: point at any GGUF, no hardcoded model

Supersedes the import half of the 2026-09-05 entry. The importer used to accept exactly one file
(size + SHA-256) and copy it into private storage. Now the document picker grants persistent read
access to a GGUF where it already is, nothing is copied, any number can be listed, and one is active.
The `:local_llm` process opens the content URI itself and hands llama.cpp `/proc/self/fd/N`; if
mapping that fails it retries with plain reads.

No model is special-cased. At add time a minimal GGUF metadata reader records `general.name`,
`general.architecture` and whether `tokenizer.chat_template` exists. With a template, the model gets
the cloud engine's instruction (including Casual / Polite) wrapped in its own turn format — Gemma 4
`<|turn>`, Gemma 2/3, Llama 3, otherwise ChatML — with thinking off, a 1536-token context and
decoding to end-of-turn. Without one, it gets the fine-tuned model's raw `FR:`/`EN:` prompt, greedy,
one line. llama.cpp's own `llama_chat_apply_template` was not used because its built-in list
recognises Gemma by `<start_of_turn>` and would mis-format Gemma 4.

Trade-off: if the user moves or deletes the file, the model stops loading and the error says to add
it again. That is preferred over duplicating multi-gigabyte files per model.

## 2026-10-23 — Local LLM speed: CPU features, thread split, prefix snapshot, real cancel

None of these change the model or the maths; output is identical.

**Built for the phone's CPU.** The native library was compiled for baseline armv8-a, so llama.cpp
could not use `sdot` / `smmla`. It is now built with
`GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+i8mm+fp16`, which also lets the runtime Q4_K repack use i8mm.
`LocalLlmModelStore.isSupportedAbi` reads `/proc/cpuinfo` and refuses ARM CPUs without
`asimddp`/`i8mm`/`asimdhp` rather than letting the model process die with SIGILL.

Measured with llama-bench on a Snapdragon 8 Elite (Galaxy Z Fold7), Index-Translate-2B Q4_K_M,
160-token prompt, 32 generated tokens:

| build | threads | prompt t/s | generation t/s |
|---|---|---|---|
| baseline armv8-a | 6 (old setting) | 37 | 24 |
| dotprod+i8mm+fp16 | 6 | 106 | 39 |
| dotprod+i8mm+fp16 | 8 | 191 | 17 |

Hence **8 threads for prompt processing, 6 for generation** (`n_threads_batch` / `n_threads`).

**One context per loaded model, plus a snapshot of the instruction prefix.** The prompt is sent as
(prefix, rest), split where the user's text begins. After the prefix is processed once, sequence
0's state is saved with `llama_state_seq_get_data` and restored on later calls. A snapshot rather
than trimming the KV cache, because hybrid/recurrent models (Qwen3.5, the Index-Translate base)
cannot remove part of a sequence.

**Cancellation reaches the model.** A newer keystroke used to cancel only the Kotlin coroutine while
generation ran to completion and the next request queued behind it. The engine now sends a
`oneway cancel(requestId)`, checked by an atomic flag in the decode loop and llama.cpp's abort
callback; the id ensures a late cancel can never abort a newer request.

**Warm-up.** Opening the translate box loads the active model in the background.
