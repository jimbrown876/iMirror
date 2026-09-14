# Personal TCL Google TV build

This GPL-3.0 personal fork retains upstream attribution and is based on
`prat3ik/iMirror` main commit `9b52ce6d8bedb80ee557a5114d3cdbee4ab84f00`.
It is not an Apple-certified receiver. No DRM bypass or paid service is included.

## Current repair: 1.0.2-personal (version code 3)

- Preserve room-specific names and distinct stable receiver identities.
- Repair mDNS registration lifecycle and bounded local discovery responses.
- Require a trusted controller signature when optional PIN authorization is enabled.
- Keep accepted, quiet media sessions from inheriting a handshake timeout.
- Enable audio by default, preserve partial PCM writes and bound software queues.
- Preserve sender volume before output creation and across both audio paths.
- Accept JPEG/PNG album art up to 5 MiB on SET_PARAMETER without applying the
  smaller 64 KiB control-body cap. Keep request framing aligned for subsequent
  volume, metadata and feedback commands.
- Sample artwork off the main thread and cap decoded image memory.
- Replace the inherited wrapper with an official Gradle 8.7 wrapper and pin the
  verified Gradle distribution checksum.

## Build and use

Use JDK 17, Android SDK 35, NDK 28.2.13676358 and CMake 3.22.1.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The two physical test TVs use 32-bit ARM (`armeabi-v7a`), Android 11 / API 30.
Their APK is `app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk`.
It is a personal **debug build**, not a production/store release. Preserve the
signing key for updates. The pre-existing files in `apk/` do not include these repairs.

Open iMirror on the destination TV and leave it open. On the same trusted network,
choose that TV in Screen Mirroring for the display or the Music AirPlay speaker
menu for audio-only playback. After updating the receiver, disconnect and reconnect
the sender once so it establishes a new session. No Mac reboot is required.

PIN authorization is optional and off by default; this default is for a trusted
personal LAN, not an exposed/public network. Automatic background foregrounding,
start-on-boot playback and multiroom synchronized audio are not claimed.

## Evidence and limits

The earlier personal build rendered an actual Mac screen on the physical Bedroom
TV. Both TVs accepted iPhone ALAC audio and decoded PCM. A 95,330-byte incoming
artwork request then exceeded the inherited general message cap and closed the
audio session; this is the reproduced failure repaired in version 1.0.2.

Regression tests reproduce that exact request size, fragmented reads, artwork
followed by volume, repeated controls, bounded decoding and UDP packet reuse.
**Installed-build verification of sustained sound, artwork and track changes is
still required; unit tests do not prove audible playback.** Compatibility varies
by sender OS, codec and content provider.

Software audio queue budgets are approximately 140 ms on the realtime path, not
measured end-to-end latency. The TCL hardware reports a roughly 144 ms minimum
AudioTrack buffer and declines Android's FAST flag. Sender buffering, network,
decoding, and hardware output add delay; no total-latency guarantee is made.

FLUSH/seek behavior and unsupported buffered-audio paths remain separate follow-up
tests if they reproduce after the artwork repair. HEVC and protected-video
compatibility are not claimed. See [NOTICE](../NOTICE.md) for upstream components.
