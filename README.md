# BiLanguage Keyboard

An Android keyboard that translates what you type, on the device. You write in one language, tap one
key, and the other language replaces your text in whatever app you are in.

It is a fork of [HeliBoard](https://github.com/Helium314/HeliBoard), so it is a complete, ordinary
keyboard first: layouts for dozens of languages, dictionaries, autocorrect, clipboard history,
one-handed and split modes. The translation is added on top of that, not bolted onto something
minimal.

## Licence, and where the source is

**GPL-3.0.** HeliBoard is GPL-3.0, so this fork is too.

Publishing this APK is distribution, which under the GPL means the complete corresponding source must
be available under the same licence. **This repository is that source** — the entire fork, not a
subset. Every release is built from the tagged commit it is published against.

Changes made to HeliBoard are described in [CHANGES-FROM-UPSTREAM.md](CHANGES-FROM-UPSTREAM.md), and
upstream's own README is preserved in [UPSTREAM-README.md](UPSTREAM-README.md).

Not affiliated with, endorsed by, or connected to Google, Signal, HeliBoard's maintainers, or any app
mentioned in the docs.

## The honest privacy summary

A keyboard sees everything you type, so vague claims are worthless. Specifically:

- **Translation runs on this device by default.** With ML Kit or Local LLM, your text is not sent
  anywhere to be translated.
- **Except with Cloud LLM, which you have to opt into.** If you choose it and save your own API key,
  the text of each translate tap is sent to the provider you configured (Mistral, Anthropic, OpenAI,
  OpenRouter, Gemini, or any OpenAI-compatible endpoint), under that provider's terms. Live preview
  is disabled for this engine, so unfinished drafts are never sent. Each request is logged as
  `CLOUD REQUEST to <host>` with a length and hash, never the text or the key.
- **The app declares `INTERNET`.** It is used by the Google ML Kit option to download a translation
  model when you tap Download, and by Cloud LLM if you enable it. Local LLM mode does not use the
  network: its GGUF is user-imported.
- **That download is logged**, so the claim above is checkable rather than trusted:

  ```
  BiLangKb: MODEL DOWNLOAD starting for en<->fr (requireWifi=true) - this is the only network use
  BiLangKb: MODEL DOWNLOAD complete for en<->fr
  ```

  Watch everything the keyboard does with `adb logcat -s BiLangKb:V`. Translations are logged as
  lengths and short hashes, never as text.
- **Nothing you type is written to disk.** No translation history, no on-disk cache.
- **No analytics, no crash reporting service, no ads.**
- Once a model is downloaded, translation works with the network off. Verified in aeroplane mode.

Upstream HeliBoard declares no `INTERNET` permission at all. If that property matters more to you
than translating, use upstream — that is a completely reasonable choice, and it is why the difference
is stated plainly here rather than buried.

## Install

Grab the APK from [Releases](../../releases), install it, then enable it as a keyboard:
Settings → General management → Keyboard list and default.

It installs alongside HeliBoard and any other keyboard; nothing is replaced.

Signed with a self-signed key, as any sideloaded app is. The certificate SHA-256 is published in each
release's notes, so you can check that an update came from the same source:

```bash
apksigner verify --print-certs BiLanguageKeyboard-*.apk
```

## Using it

**First run.** Long-press the translate key (the toolbar key that looks like 文A) to open the
translation screen. Choose Google ML Kit, Local LLM, or Cloud LLM. Nothing downloads until you ask, and
ML Kit downloads use Wi-Fi by default.

**Cloud LLM.** Choose **Cloud LLM** in the translation screen, pick a provider, check the model
id, paste your API key, and tap **Save**. **Test** sends the fixed phrase "Good morning", nothing you
typed. The key is encrypted with an Android Keystore key and only its last four characters are ever
shown again. The URL must be `https://`.

**Local LLM.** Put any GGUF on the phone (Downloads is fine), choose **Local LLM**, and tap **Add
GGUF model…**. Nothing is copied: the app keeps read access to the file where it is. Add as many as
you like and tap one to make it active. **Remove** forgets a model without deleting the file. The
app reads the GGUF header: models with a chat template (Gemma, Qwen, Llama instruct builds) are
prompted with a translation instruction in their own turn format and can translate every language,
including Casual / Polite French. Models without one get the raw `FR:` / `EN:` prompt of the
fine-tuned English ↔ French model. llama.cpp loads the active model in a separate app process on
first translation.

Download the languages you type in **and English** — every model translates between its language and
English, so English is needed even for, say, French to Bulgarian.

**Translating.** Type normally, then tap the translate key. Your text is replaced by the translation
in one atomic edit. An Undo button appears in the suggestion strip; it restores your original text
exactly.

**The language chip** next to it reads e.g. `EN → FR`. Tap it to swap direction, long-press for the
language screen. The chosen target is remembered per app, because in practice one app or one contact
means one language.

**Live translation (optional).** Turn on "Show translation instead of word suggestions" and the
translation appears in the suggestion strip as you type; tap it to use it. Be aware of the tradeoff:
while it is showing you do not get word suggestions or autocorrect for the text you are still
writing, and it runs many more translations than pressing the key does. Off by default.

**Both toolbar keys are removable** through HeliBoard's normal toolbar customisation if you do not
want them.

## Where translation is switched off on purpose

Translation is disabled, and the key greyed out, in:

- password fields, including web password fields
- email address fields
- URL and web-address fields, including browser address bars
- phone number, numeric and date/time fields

This is not configurable. Translating a password would move a secret through a translation engine and
into a message box.

Apps that request an "incognito keyboard" (Signal, and forks such as Molly) are a separate case, and
**are** translated by default. That flag asks keyboards not to *learn* from your text; this keyboard
never stores your text at all, so the request is honoured either way. If you would rather it stayed
out of those apps entirely, there is a switch for it in the translation screen.

## Known limitations, stated up front

- ML Kit is fine for ordinary messages but weaker than an online translator. Language pairs that do
  not include English are routed through English internally, which costs accuracy.
- Local LLM quality and speed depend entirely on the GGUF. It requires a 64-bit ARM device (x86-64
  for emulators) and much more working memory than ML Kit; the first translation is slower because
  the model must be loaded.
- Each language model is roughly 30 MB. The app cannot report exact size on disk, because ML Kit does
  not expose it.
- There is no download progress bar, because ML Kit reports only success or failure. A fabricated
  percentage would be worse than none.
- In a small number of apps whose text field does not support `getExtractedText`, very long text
  (over about 1000 characters) will refuse to translate rather than risk translating only part of it.
- URLs, @mentions and code spans are **not yet** protected from being translated. That work is
  planned; until then, check the result before sending if your message contains them.
- Glide typing is absent, as in upstream: it needs a closed-source library that is not bundled.

## Build

macOS or Linux, no Android Studio required. See [docs/toolchain-setup.md](docs/toolchain-setup.md)
for the exact setup this was built with. Clone with submodules, or initialise them after cloning:

```bash
git submodule update --init --recursive
```

```bash
./gradlew assembleDebug
```

For a smaller APK targeting modern physical devices only:

```bash
./gradlew assembleDebug -PtargetAbi=arm64-v8a
```

```bash
./gradlew testRunTestsUnitTest
```

A release build is unsigned unless you provide your own `keystore.properties`; that file is
gitignored and no signing key is in this repository.

## Credits

- [HeliBoard](https://github.com/Helium314/HeliBoard) by Helium314 and contributors, GPL-3.0
- [OpenBoard](https://github.com/openboard-team/openboard), and AOSP LatinIME before it, Apache-2.0
- [llama.cpp](https://github.com/ggml-org/llama.cpp), MIT; pinned as a Git submodule
- Translation by Google ML Kit or llama.cpp, running on device
