# HeliBoard architecture notes

Required reading from spec section 2.4, done against `v4.0-30-g50d13c1`. These are the notes
that matter for mounting translation, not a general tour. Line numbers are from that commit.

## 1. Toolbar key definition and dispatch

This is the natural mounting point, as the spec predicted, and it is cleaner than expected.

**Definition** — `latin/utils/ToolbarUtils.kt`:

- `enum class ToolbarKey` (line 121) is the complete list of toolbar keys.
- `getCodeForToolbarKey(key)` (line 66) maps each entry to a `KeyCode` Int.
- `getCodeForToolbarKeyLongClick(key)` (line 100) does the same for long press.
- `createToolbarKey(context, key)` (line 28) builds the `ImageButton`, sets `button.tag = key`,
  and pulls both the icon and the content description **from the enum name**:
  - icon: `KeyboardIconsSet.instance.getNewDrawable(key.name, context)`
  - content description: `key.name.lowercase().getStringResourceOrName("", context)`

  So a `TRANSLATE` entry needs an icon registered under the name `TRANSLATE` and a string
  resource named `translate`. Miss either and you get a blank button, not a crash.

**Dispatch** — `latin/suggestions/SuggestionStripView.kt`:

- `onClick` (line 331): if `view.tag is ToolbarKey`, calls
  `onClickToolbarKey(view) { listener.onCodeInput(it, SUGGESTION_STRIP_COORDINATE, ...) }`.
- `onLongClick` (line 352) → `onLongClickToolbarKey` (line 367).

So a toolbar tap becomes an ordinary `onCodeInput` and lands in `InputLogic` exactly like a
physical key. No separate dispatch path to build.

**Terminal** — `inputlogic/InputLogic.java`, the `switch` in `handleFunctionalEvent`
(`case KeyCode.UNDO:` is at line 853). This is where a `KeyCode.TRANSLATE` case goes.

> Upstream bug seen in passing, do not copy the pattern: the
> `case KeyCode.SEND_INTENT_ONE, TWO, THREE:` arm (line 872) has **no `break;`** and falls
> through into `KeyCode.IME_HIDE_UI`, so sending an intent also hides the keyboard.

**Removability comes free.** Spec section 7 requires both new toolbar elements be removable
through existing customization. `upgradeToolbarPrefs` (line 152) appends any enum entry
missing from the stored pref, so new keys automatically appear in the existing customizer.
To be *enabled* by default a key must be added to `defaultToolbarPref` (line 133).

### KeyCode number space

`keyboard/internal/keyboard_parser/floris/KeyCode.kt`. Occupied ranges:

| Range | Use |
|---|---|
| `-1` .. `-400` ish | FlorisBoard-compatible codes |
| `-10001` .. `-10054` | HeliBoard-specific codes |
| `-11000` | `KEY_REPEAT`, popup/long-press only |
| `-20000` .. `-20002` | `SEND_INTENT_ONE/TWO/THREE` |

**PolyBoard should use `-30000` and below.** That leaves upstream room to grow without
colliding on every rebase.

`Int.checkAndConvertCode()` (line 199) **throws `IllegalStateException` on any code not in its
allow-list.** New codes must be added there, or referencing them from a JSON layout crashes.

## 2. RichInputConnection

`latin/RichInputConnection.java`. Wrapper over `InputConnection` that maintains a local
mirror of the text around the cursor plus an expected selection. Two things it gives us and
two it does not.

Has: `beginBatchEdit` / `endBatchEdit` (191/207), `commitText` (324), `getTextBeforeCursor`
(443), `getTextAfterCursor` (533), `finishComposingText` (301), `setSelection` (730),
`getExpectedSelectionStart` / `End` (1145/1149), `hasSelection` (1156).

### Missing 1: there is no `deleteSurroundingText(before, after)`

Only `deleteTextBeforeCursor(beforeLength)` (line 566). The spec's atomic replace needs both
directions, so **a `deleteSurroundingText` must be added to `RichInputConnection`**, not
called around it.

This matters more than it looks. The class keeps `mCommittedTextBeforeComposingText`,
`mExpectedSelStart` and `mExpectedSelEnd` in sync by hand on every mutation. Reaching past
the wrapper to the raw `InputConnection` leaves those stale, and the keyboard then
mispredicts the cursor — wrong autocaps, wrong backspace behaviour, wrong suggestions. The
new method has to update the cache the same way `deleteTextBeforeCursor` does.

### Missing 2: cached reads are capped at 1024 characters

`Constants.EDITOR_CONTENTS_CACHE_SIZE = 1024` (`common/Constants.java:146`). That is the
budget the cursor-context cache is built around.

**This directly collides with Phase 6**, which requires correct behaviour on fields over
2000 characters. Reading "the whole field" via `getTextBeforeCursor` will silently truncate,
and truncation here does not throw — it produces a translation of part of the message and an
atomic replace computed from the wrong length. That is the worst failure mode in the whole
project: silent, plausible, and destructive.

The raw connection (`private InputConnection mIC`, line 128) has **no accessor**, though the
class itself calls `mIC.getExtractedText(r, 0)` internally at line 165. So implementing the
spec's "or `getExtractedText` where available" requires a new method on `RichInputConnection`
too. Treat long-field correctness as a Phase 2 design constraint, not a Phase 6 bug hunt.

### `hasSlowInputConnection()` (line 150)

Upstream already tracks apps whose `InputConnection` is slow and degrades behaviour for them.
Relevant to the section 8 latency budget and to the section 10 app matrix — worth consulting
before blaming our own code for latency in a specific app.

## 3. Composing text lifecycle

`InputLogic` holds `mWordComposer` (the in-progress word) and `mLastComposedWord` (the
previous one, retained for revert). Relevant calls: `mWordComposer.isComposingWord()`,
`commitTyped(settingsValues, separator)`, `mConnection.finishComposingText()`.

Consequence for translation: **the composing region must be settled before any read or
replace.** Translating while a word is still composing means the text in the field, the text
in `mWordComposer`, and the text we read can disagree. Call `finishComposingText()` (or
`commitTyped`) first, then read, then replace.

This is also why spec rule 5 (never stream partial translations) is enforced by the
architecture and not just by policy: partial commits would fight the composing region.

## 4. Suggestion strip / candidates view

`latin/suggestions/SuggestionStripView.kt`.

The important discovery: **`setExternalSuggestionView(view: View?, addCloseButton: Boolean)`**
(line 255). It clears the strip, sets `isExternalSuggestionVisible`, adds an arbitrary `View`,
and when `addCloseButton` is true also adds a close button wired to
`listener.removeExternalSuggestions()`. Emoji search already uses this.

This single API covers three separate spec requirements without touching the suggestion
renderer:

- the Mode 2 live-preview view (section 5)
- the undo affordance after a commit (section 5, Mode 1 step 6)
- the Phase 1 debug overlay showing `packageName` / decoded `inputType` / `imeOptions`

`Listener` (line 78) is the interface to extend for taps on our own views:
`pickSuggestionManually`, `onCodeInput`, `removeSuggestion`, `removeExternalSuggestions`,
`onSwipeDownOnToolbar`.

Note `setExternalSuggestionView` calls `setToolbarVisibility(false)` when `mAutoHideToolbar`
is set — so showing a preview can hide the translate key that produced it. Check that
interaction when building the Mode 2 UI.

## 5. Subtype and language settings storage, and per-app memory

`latin/settings/Settings.java` plus `latin/utils/SubtypeUtils.kt`. Prefs are plain
`SharedPreferences`.

**Prior art for spec section 7's per-app language pair memory already exists:**

- `saveSubtypeForApp(subtype, packageName)` — `Settings.java:580`
- `getSubtypeForApp(packageName)` — `Settings.java:587`
- key scheme: `PREF_SAVED_APP_SUBTYPE_PREFIX + packageName`
- gated by `PREF_SAVE_SUBTYPE_PER_APP`
- called from `LatinIME.java:831` on input start, read at `LatinIME.java:842`

`LanguagePairStore` should mirror this exactly: `PREF_POLY_TARGET_PREFIX + packageName`.

**Simplification this enables:** because HeliBoard already remembers the *subtype* per app,
the **source** language can be derived from the active subtype rather than stored. The store
only needs the **target** per package. Fewer things to keep consistent, and it means
switching keyboard language switches the translation source for free.

Default target when no entry exists: system locale, per spec section 7.

## 6. Field-type gating, existing infrastructure

Do not hand-roll the `inputType` decoding for spec section 6.1. Upstream already has:

- `latin/InputAttributes.java` — decodes an `EditorInfo` into behaviour flags and already
  references `TYPE_TEXT_VARIATION_PASSWORD` and `IME_FLAG_NO_PERSONALIZED_LEARNING`
- `latin/utils/InputTypeUtils.java`

The section 6.1 predicate belongs next to these, and `InputAttributes` also already holds the
`toString()`-style decoding useful for the Phase 1 debug overlay.

Entry points for the gate: `LatinIME.onStartInputView` (line 787) →
`onStartInputViewInternal` (line 853). `getCurrentInputEditorInfo()` is available throughout.

## 7. Lifecycle hooks for the engine

- `LatinIME.onDestroy` (line 694) — `engine.close()` goes here. Upstream already chains
  `onDestroy` into its managers, so follow that pattern.
- `onTrimMemory` — **not currently overridden in `LatinIME`.** Section 8 requires it, so it
  has to be added.
- `App` (`helium314.keyboard.latin.App`) is the `Application` subclass, an alternative home
  for `onTrimMemory`.

Note `android:defaultToDeviceProtectedStorage="true"` and `android:directBootAware="true"` in
the manifest. Anything the engine writes (ML Kit model files) lands in device-protected
storage by default, which has different semantics before first unlock. Worth checking when
model management is built.

## 8. Glide typing

Confirmed as spec section 2.3 says: gesture typing needs a closed-source library that is not
bundled, and there is a whole `runTests` / `nouserlib` build-variant structure around user-
provided libraries. Its absence is not a bug and not ours to fix.
