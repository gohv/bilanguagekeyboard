# Toolchain setup, macOS on Apple Silicon

Recorded from an actual clean setup on macOS 27 (Darwin 27.0.0, arm64), no Android Studio.
Where this differs from the project spec, the deviation and its reason are called out.

## What was already present

`brew`, `git`, and the `android-platform-tools` cask (so `adb` was on `PATH`). Nothing else.
No JDK at all — `/usr/libexec/java_home` reported "Unable to locate a Java Runtime".

## Deviation 1: JDK via formula, not the Temurin cask

The spec says:

```bash
brew install --cask temurin@21
```

**This could not be used.** The `temurin@21` cask installs a `.pkg`, which requires an
administrator password. Installed instead:

```bash
brew install openjdk@21
```

The formula unpacks into the Homebrew prefix and needs no sudo. Result is the same JDK
version (21.0.12). The only difference: it is keg-only, so `/usr/libexec/java_home` does not
see it and `JAVA_HOME` must be set explicitly to the path below.

If you would rather have the JDK registered system-wide, run this yourself (it needs your
password):

```bash
sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-21.jdk
```

## Deviation 2: the shell is zsh, not fish

The spec's snippet writes to `~/.zshrc`, which is correct for this setup. Noting only that
if you drive this from fish interactively, `export` is not a fish builtin — use `set -x`.

## Install

```bash
brew install --cask android-commandlinetools   # no sudo needed, unlike the JDK cask
```

## Environment

```bash
# ~/.zshrc
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Note `JAVA_HOME` is the explicit keg path, not `$(/usr/libexec/java_home -v21)` as the spec
has it. That command fails here for the reason in Deviation 1.

## SDK packages

```bash
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" \
  "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;28.0.13004108"
```

Two things the spec does not mention:

1. **The NDK is required, and the spec omits it.** HeliBoard has an `externalNativeBuild`
   `ndkBuild` block pinned to `ndkVersion = "28.0.13004108"`. Without it the build fails.
   It is also the bulk of the download.
2. **`sdkmanager` now prints a deprecation warning** telling you to use a new `android sdk`
   CLI instead. It still works; the warning is noise, not a failure.

`--licenses` is interactive, hence `yes |`.

## local.properties

```
sdk.dir=/opt/homebrew/share/android-commandlinetools
```

Already covered by upstream's `.gitignore`.

## Build and install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/PolyBoard_4.0-dev1-debug.apk
adb shell ime list -s
adb logcat -s PolyBoard:V
```

**The spec's install path is wrong.** It says `app/build/outputs/apk/debug/app-debug.apk`,
but `app/build.gradle.kts` overrides `output.outputFileName`, so the artifact is named
`PolyBoard_<versionName>-<buildType>.apk` (upstream: `HeliBoard_...`). Use the path above, or
glob it:

```bash
adb install -r app/build/outputs/apk/debug/*.apk
```

## Faster dev loop

`assembleDebug` runs R8 minification, because upstream keeps the debug APK small enough for
GitHub's 25MB attachment limit. For iteration use the variant upstream provides for exactly
this:

```bash
./gradlew assembleDebugNoMinify
```

## Timings on this machine

| | |
|---|---|
| Baseline `assembleDebug`, cold, unmodified upstream | 1m 54s |
| Incremental after `minSdk` + rename change | 42s |
| Debug APK size | ~27MB |

## Gatekeeper

The spec warns that a manually downloaded SDK zip picks up quarantine attributes. Using the
Homebrew cask as instructed, no `xattr -d com.apple.quarantine` was needed on anything.

## Gradle

Do not install Gradle. `./gradlew` provisioned 8.14 itself. The build reports "Deprecated
Gradle features were used ... incompatible with Gradle 9.0" — upstream's problem, harmless
here.
