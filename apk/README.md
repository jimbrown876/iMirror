# Prebuilt iMirror APKs — AirPlay Screen Mirroring for Android TV

Ready-to-install, release-signed builds of **iMirror v1.0.0**. Download one and
sideload it onto your Android TV to mirror an iPhone, iPad, or MacBook. No build
step, no Android Studio, no account.

## Which file do I need?

Android APKs contain compiled native code for a specific CPU architecture (ABI).
Picking the matching one gives you a smaller download.

| Folder | CPU architecture | Typical devices | Size |
|---|---|---|---|
| [`armeabi-v7a/`](armeabi-v7a/) | 32-bit ARM | Most Android TV boxes and sticks, Fire TV Stick, budget and regional smart TVs | 5.8 MB |
| [`arm64-v8a/`](arm64-v8a/) | 64-bit ARM | Newer 4K Android TV / Google TV, Nvidia Shield, Fire TV Cube | 6.3 MB |
| [`x86/`](x86/) | 32-bit Intel | Intel-based TVs, some emulators | 5.8 MB |
| [`x86_64/`](x86_64/) | 64-bit Intel | ChromeOS, Android emulators, Intel TV boxes | 6.2 MB |
| [`universal/`](universal/) | **All of the above** | **Use this if you are unsure — works everywhere** | 11 MB |

**When in doubt, take [`universal/`](universal/).** It carries every architecture,
so it installs on any device. The only cost is a larger file.

## How do I check my TV's architecture?

With `adb` connected to the TV:

```sh
adb shell getprop ro.product.cpu.abi
```

That prints exactly one of `armeabi-v7a`, `arm64-v8a`, `x86`, or `x86_64`.

Note that many 4K Android TVs still report `armeabi-v7a`, because the TV runs a
32-bit userspace even on 64-bit silicon. Trust the command, not the marketing.

## Install

```sh
adb connect <your-tv-ip>
adb install -r iMirror-1.0.0-universal.apk
```

If `adb install` fails with `INSTALL_FAILED_NO_MATCHING_ABIS`, you picked the
wrong architecture — use the universal build.

No computer to hand? Use a sideloading app such as *Downloader* or
*Send Files to TV* to get the APK onto the TV, then open it from a file manager.

## Verifying the download

Every APK here is signed with the same release key. You can confirm a file has
not been tampered with:

```sh
apksigner verify --print-certs iMirror-1.0.0-universal.apk
```

It should report `CN=iMirror`. An APK signed with any other certificate did not
come from this repository.

## Then what?

Open iMirror on the TV, leave it on the waiting screen, and pick your TV from
**Control Centre → Screen Mirroring** on your iPhone, iPad, or Mac. Both devices
must be on the same Wi-Fi network.

Full instructions, features, and known limitations are in the
[main README](../README.md).
