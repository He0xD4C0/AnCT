# AnCT

AnCT is an experimental Android 10+ arm64 manager for two deliberately
different kinds of local Linux workloads:

- **Linux environments** use PRoot and need no root. They provide a terminal,
  package installation and one user-configured foreground process. They do not
  guarantee systemd, OpenRC, namespace isolation, network isolation or hard
  resource limits.
- **System containers** reuse the pinned Droidspaces runtime. They require
  existing root access and a compatible kernel. This backend is experimental
  until it has been validated on the target physical device.

The application is released under GPL-3.0-only. It is distributed through
community channels and does not target Google Play.

## Requirements

- macOS or Linux development host
- JDK 21
- Android SDK 36
- Docker for reproducible runtime assets
- Android 10+ arm64 device for execution

Boot-time and native-container testing requires a rooted arm64 device with a
kernel that passes `droidspaces --json check`.

## Build

```sh
./gradlew :core:test :app:assembleDebug
```

A debug APK can be built without runtime payloads so UI and pure logic can be
developed offline. Such an APK reports both runtimes as unavailable.

To build the pinned PRoot bootstrap and Droidspaces binary, then assemble a
release candidate:

```sh
scripts/build-runtimes.sh
./gradlew :app:assembleRelease
```

Release builds fail if the generated runtime assets are missing.

Local release builds use the Android debug signing key unless
`ANCT_KEYSTORE`, `ANCT_KEYSTORE_PASSWORD`, `ANCT_KEY_ALIAS` and
`ANCT_KEY_PASSWORD` Gradle properties are supplied. The generated
`outputs/AnCT-0.1.0-arm64.apk` is a local candidate, not a production-signed
release.

Catalog updates are signed with ECDSA P-256:

```sh
ANCT_CATALOG_KEY=/secure/path/catalog-key.pem scripts/sign-catalog.sh
```

The repository stores the public key and signature; the private key stays out
of the source tree.

## Runtime boundary

- Application ID: `dev.anct`
- `minSdk`: 29
- `compileSdk`: 36
- `targetSdk`: 28 by design, because Android 10+ blocks execution of ELF files
  from an application's writable home when target SDK is 29 or higher.
- Supported ABI: arm64-v8a
- Backups contain only instance-internal data. External bind-mount contents are
  never copied.
- No encryption, firewall, tunnel, public ingress, Docker orchestration, desktop,
  SSH portal, snapshots or kernel installation is included.

## Native backend warning

The native backend uses a real privileged container runtime. Guest root can
affect Android. Install the root module only on a device you control, and only
run images you trust.

Uninstalling the APK does not execute cleanup callbacks. PRoot data is removed
by Android. The native module and `/data/local/Droidspaces` data can remain,
matching the underlying runtime's behavior.

## Verification

```sh
./gradlew :core:test
./gradlew :app:assembleDebug
```

Runtime and GUI acceptance require an arm64 Android system image. Native
backend physical-device acceptance is currently pending.

