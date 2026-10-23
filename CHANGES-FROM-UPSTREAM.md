# Changes from upstream HeliBoard

GPL-3.0 requires modified versions to carry prominent notices stating what was changed. This is that
statement. Upstream is [HeliBoard](https://github.com/Helium314/HeliBoard); the fork point is recorded
in the git history of this repository.

## Identity

| | HeliBoard | BiLanguage Keyboard |
|---|---|---|
| `applicationId` | `helium314.keyboard` | `it.hristov.bilanguagekeyboard` |
| `minSdk` | 21 | 26 |
| Display name | HeliBoard | BiLanguage Keyboard |
| `INTERNET` permission | not declared | declared, for model download and the opt-in Cloud LLM engine |

The Java/Kotlin namespace is deliberately left as `helium314.keyboard.*`. Renaming it would touch
thousands of files, make every future rebase onto upstream painful, and gain nothing:
`applicationId` is what governs installation identity.

The two `ContentProvider` authorities were renamed, because upstream hardcodes them as string
resources rather than deriving them from `${applicationId}`. Without that change this fork cannot be
installed alongside HeliBoard.

## Added

All new code lives in `helium314.keyboard.bilanguage`, kept separate so it stays reviewable and so
rebasing onto upstream is tractable:

- `engine/TranslationEngine`, `LanguageIdentifier` — the interfaces translation is written against.
- `engine/MlKitEngine`, `MlKitLanguageIdentifier` — on-device translation and language detection.
- `engine/LocalLlmEngine` and `llm/*` — selectable English ↔ French GGUF inference through llama.cpp,
  with checksum-verified user import and a separate model process.
- `engine/CloudLlmEngine` and `cloud/*` — opt-in translation through a hosted LLM with the user's own
  API key (Anthropic Messages or any OpenAI-compatible chat endpoint), key encrypted with the Android
  Keystore. The only code that sends typed text off the device.
- `engine/StubEngine` — a fake engine used by the tests and available in settings, so the whole UX
  can be exercised with no model present.
- `TranslationController` — orchestration: lazy engine init, one in-flight job, 30s timeout, one undo
  step, and the Mode 2 debounce.
- `FieldGate` — decides which fields may be translated at all.
- `FieldIo` / `RichInputConnectionFieldIo` — field access behind an interface, so the controller is
  unit-testable.
- `LanguagePairStore` — the translation target, remembered per app.
- `BiTranslationActivity` — first-run disclosure and model management.
- `TranslateBox` — the translate-as-you-type box above the strip.
- `TranslateStatusView`, `PairChipDrawable`, `FieldDebugView` — the strip UI, the language chip, and a
  debug overlay.

## Modified upstream files

Kept as small as possible:

- `KeyCode.kt` — three new key codes at `-30000` and below, chosen far from upstream's ranges so
  rebases do not collide, plus the `checkAndConvertCode` allow-list.
- `ToolbarUtils.kt` — two new `ToolbarKey` entries, their code mappings, and the default toolbar.
- `KeyboardIconsSet.kt` — icons for those keys in all three icon styles.
- `InputLogic.java` — dispatch for the new key codes.
- `LatinIME.java` — controller lifecycle, the field gate on input start, the live-preview hook,
  engine release in the existing `onTrimMemory` arm and in `onDestroy`, and the translate box: a
  `getCurrentInputConnection()` override, selection routing in `onUpdateSelection`, and its height
  in `onComputeInsets`.
- `RichInputConnection.java` — two additions: `deleteSurroundingText(before, after)`, which upstream
  lacks, and `getFullFieldText()`, which reads the whole field instead of the 1024-character cached
  window.
- `SuggestionStripView.kt` — enable/disable the translate key, and set the language chip label.
- `DebugSettings.java`, `Defaults.kt`, `DebugScreen.kt` — one debug preference.
- `build.gradle.kts` — ML Kit and local llama.cpp modules, release signing read from a gitignored file, and
  `isReturnDefaultValues` for unit tests.
- Manifest — `INTERNET`, `ACCESS_NETWORK_STATE`, the translation activity, and a private LLM service.

## Not changed

No upstream behaviour was removed or altered. With translation unused, this keyboard behaves as
HeliBoard does.
