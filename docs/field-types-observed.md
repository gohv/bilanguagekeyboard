# Observed field types

Real `EditorInfo` values captured on the target device (Galaxy Z Fold 7, SM-F966B, Android 16)
via the phase 1 instrumentation. This is the input for phase 2's spec 6.1 gating predicate, and
the fixture set for its unit tests.

Capture command:

```bash
adb logcat -s PolyBoard:V
```

## Results

| Where | inputType | Decoded | imeOptions | 6.1 verdict |
|---|---|---|---|---|
| WhatsApp message box | `0x0002C001` | `TEXT/NORMAL +CAP_SENTENCES +AUTO_CORRECT +MULTI_LINE` | `DONE +NO_ENTER_ACTION` | **translate** |
| Proton Mail recipient | `0x00000021` | `TEXT/EMAIL_ADDRESS` | `NEXT +NO_FULLSCREEN` | exclude |
| Brave omnibox | `0x00080011` | `TEXT/URI +NO_SUGGESTIONS` | `GO +NO_FULLSCREEN +NO_EXTRACT_UI` | exclude |
| web `input[type=password]` | `0x000000E1` | `TEXT/WEB_PASSWORD` | `NEXT +NO_FULLSCREEN +NO_EXTRACT_UI` | exclude |
| web `input[type=email]` | `0x000000D1` | `TEXT/WEB_EMAIL_ADDRESS` | same | exclude |
| web `input[type=url]` | `0x00000011` | `TEXT/URI` | same | exclude |
| web `input[type=tel]` | `0x00004003` | `PHONE` | same | exclude |
| web `input[type=number]` | `0x00006002` | `NUMBER/NORMAL +DECIMAL` | same | exclude |
| web `input[type=date]` | `0x00000000` | `NULL` | `UNSPECIFIED +...` | n/a, no text input |
| web `input[type=text]` | `0x0000C0A1` | `TEXT/WEB_EDIT_TEXT +CAP_SENTENCES +AUTO_CORRECT` | `NEXT +...` | **translate** |
| Revolut (banking) | — | no text field at all | — | see below |

Reproduce the web rows with [fixtures/fieldtest.html](fixtures/fieldtest.html). `file://` is blocked
by scoped storage and Chromium blocks top-level `data:` navigation, so serve it over the USB
bridge instead — no internet involved:

```bash
cd docs/fixtures && python3 -m http.server 8099 --bind 127.0.0.1
```

```bash
adb reverse tcp:8099 tcp:8099 && adb shell am start -a android.intent.action.VIEW -d "http://localhost:8099/fieldtest.html" -p com.brave.browser
```

## Findings that affect the design

### 1. Banking PIN entry never reaches the keyboard

Revolut authenticates with a **biometric prompt** (`BiometricPromptRoot`), and the only field it
ever handed the IME was `TYPE_NULL`. Many banking apps are like this: biometrics, or a custom
in-app digit pad built from ordinary buttons rather than an `EditText`.

So the spec's phase 3 / phase 10 "banking app PIN field" check is **safe by construction in this
case, but it does not exercise the password predicate**. Do not treat a clean run against a
banking app as evidence that 6.1 works. The web `input[type=password]` fixture above is the real
test, because it produces an actual `WEB_PASSWORD` field, and `NUMBER/PASSWORD` still needs a
synthetic unit test since nothing on this device produced one naturally.

### 2. Autofill and clipboard suggestions outrank the debug overlay

The overlay mounts through `setExternalSuggestionView`, and so do autofill inline suggestions
(`LatinIME:1327`) and clipboard suggestions (`LatinIME:1554`). Those run *after*
`onStartInputViewInternal`, so on a field where autofill fires — a password field with saved
credentials, most notably — **autofill wins and the overlay never appears**.

This is correct behaviour, not a bug to fix: autofill is more useful to the user than a debug
readout. It does mean the overlay cannot be the only instrumentation channel, which is why the log
line is emitted unconditionally. When the overlay is missing, read the log.

The same contention will apply to the phase 5 live-preview view. Worth deciding then whether a
translation preview should lose to a clipboard suggestion (probably yes) or to autofill
(certainly yes).

### 3. Browsers set meaningless flag bits for non-text classes

Brave sets bit `0x4000` on its `tel` and `number` fields. In `TYPE_CLASS_TEXT` that bit is
`TYPE_TEXT_FLAG_CAP_SENTENCES`; for `TYPE_CLASS_PHONE` and `TYPE_CLASS_NUMBER` it is undefined.
The decoder deliberately does not name flags for classes that define none, so those bits are
dropped from the readable form. Nothing is hidden, because the raw hex is always printed next to
the decode. Do not "fix" this by printing text flag names for phone fields.

### 4. Upstream already goes incognito on password fields

Opening the web password field visibly switched the keyboard into incognito mode (the icon appears
in the strip). Upstream already suppresses learning for these fields. The 6.1 gate is a separate,
stricter thing — no translation at all — but it is good to know the existing behaviour is aligned
rather than fighting us.
