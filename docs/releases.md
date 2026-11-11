# Android releases

[`.github/workflows/release.yml`](../.github/workflows/release.yml) runs when a
GitHub release is published, including prereleases. It checks out that release's
tag, runs policy tests and release lint, builds all four Android ABIs, verifies the
native ELF headers and APK contents, signs the APKs, and attaches all five APKs plus
`SHA256SUMS` to the release. Upload happens only after the entire build succeeds.

| Download | Devices |
| --- | --- |
| `ntroll-VERSION-armeabi-v7a.apk` | 32-bit ARM |
| `ntroll-VERSION-arm64-v8a.apk` | 64-bit ARM phones/tablets |
| `ntroll-VERSION-x86.apk` | 32-bit Intel Android/emulators |
| `ntroll-VERSION-x86_64.apk` | 64-bit Intel Android/emulators |
| `ntroll-VERSION-universal.apk` | All four ABIs in one APK |

All builds require Android 6.0/API 23 or newer. The architecture labels cover the
four supported ABIs of this project's pinned NDK; legacy ARMv5/MIPS and experimental
RISC-V are not included.

## One-time signing setup

Android requires signed APKs for installation. Create a release key **once**, retain
a secure backup of it and its passwords, and reuse it for every release so Android
can install updates. A key is not generated automatically on each workflow run.

If you already have a production signing key, use that existing key instead.
Otherwise, run locally and answer keytool's password/name prompts:

```sh
keytool -genkeypair -v -storetype JKS -keystore ntroll-release.jks \
  -alias ntroll-release -keyalg RSA -keysize 3072 -validity 10000
```

Add these four **repository Actions secrets** under Settings → Secrets and variables
→ Actions. The workflow fails early with the missing secret's name if setup is
incomplete.

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 contents of your release keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias, e.g. `ntroll-release` |
| `ANDROID_KEY_PASSWORD` | Private key password (often the same as the keystore password) |

With the GitHub CLI authenticated to the intended repository, base64 can be piped
directly into its secret store without printing it or creating another key file:

```sh
base64 -w 0 ntroll-release.jks | gh secret set ANDROID_KEYSTORE_BASE64
# These commands prompt for their values:
gh secret set ANDROID_KEYSTORE_PASSWORD
gh secret set ANDROID_KEY_ALIAS
gh secret set ANDROID_KEY_PASSWORD
```

`.jks` and `.keystore` files are ignored by Git. The workflow decodes the key into a
private file outside the checkout and removes it in an always-running cleanup
step. Passwords are passed through environment variables rather than command-line
arguments. Only signed APKs and checksums are uploaded or retained as artifacts.

## Publish a version

1. Update `versionName` and increment `versionCode` in `app/build.gradle`.
2. Commit the app, workflow, scripts, lockfile, and Gradle wrapper; push them to GitHub.
3. Create a tag matching `versionName`, such as `v0.7.0` for `0.7.0`, on that commit.
4. Publish a GitHub release using that tag. Draft releases do not start this workflow.
5. The **Build Android release** workflow attaches the signed APKs and checksums.

The version/tag match is checked before packaging. Existing assets with matching
names are replaced on a successful rerun. The build job has read-only repository
permissions; only the separate upload job receives `contents: write` through the
automatic `GITHUB_TOKEN`. A personal access token is not needed for asset uploads.
If a different workflow creates the release, note GitHub's rule that events created
with `GITHUB_TOKEN` generally do not trigger another workflow; publish manually or
use a supported triggering token for that separate release-creation automation.

This workflow attaches assets **after** publication. If immutable releases are
enabled in the repository, use a draft-build/upload-then-publish release process
instead; GitHub locks assets of immutable published releases.

You can also use Actions → Build Android release → Run workflow to build the
selected branch/tag and download the signed `android-release` workflow artifact.
Manual runs require the same signing secrets and do not publish or modify a release.
The workflow must exist on the default branch for this Run workflow option.

## Local builds and verification

CI pins JDK 17, Rust 1.90.0, cargo-ndk 4.1.2, SDK/build-tools 34, NDK
26.1.10909125, and action commit SHAs. Ordinary local debug builds keep the existing
ARM64/x86_64 defaults; request all ABIs explicitly for a release:

```sh
rustup target add armv7-linux-androideabi aarch64-linux-android \
  i686-linux-android x86_64-linux-android
./gradlew assembleRelease lintRelease \
  -PandroidAbis=armeabi-v7a,arm64-v8a,x86,x86_64 -PsplitApks
```

Unsigned APKs are generated in `app/build/outputs/apk/release/`. For a local
architecture/alignment/version check, without any signing credentials:

```sh
python3 scripts/package-release.py --unsigned --output app/build/unsigned-check
```

For installable signed release outputs, export `ANDROID_KEYSTORE_PATH`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` in your
local shell, then use an empty output directory:

```sh
python3 scripts/package-release.py --tag v0.7.0
(cd dist && sha256sum --check SHA256SUMS)
```

The packaging script requires four ABI APKs and one universal APK, rejects debug
builds, checks every APK's architecture using its bundled Rust ELF binary, checks
version consistency and ZIP alignment, signs every APK with the same key, verifies
each signature, and generates SHA-256 checksums. Python 3.9+ is required.

A debug-signed installation cannot be updated by a differently signed release APK.
Export the current profile's rules before switching from a debug build if you need
to uninstall it first. Per-profile rule exports do not include the profile library.

References: [GitHub release events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#release),
[uploading release assets](https://cli.github.com/manual/gh_release_upload),
[Android app signing](https://developer.android.com/studio/publish/app-signing), and
[Android ABIs](https://developer.android.com/ndk/guides/abis).

## Local validation of this workflow

The four ABI APKs and universal APK built successfully using the pinned Rust
1.90.0 toolchain. Release lint, workflow actionlint, Rust policy/format checks,
and Java policy/backup tests passed. A throwaway local signing key signed all
five APKs; every signature, shared signing certificate, and SHA-256 checksum was
verified. The test key was deleted and these test APKs were not installed or
published. Packaging also rejected a mismatched tag, a missing architecture, and
a native binary deliberately labeled with the wrong ABI. Switching back to the
normal local debug build retained exactly ARM64 and x86_64.

A hosted GitHub Actions run has not been performed from this workspace; the
repository must be pushed to GitHub and its signing secrets configured first.
