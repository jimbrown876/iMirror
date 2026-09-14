# Personal TCL Google TV build

This GPL-3.0 personal fork retains upstream attribution and is based on
`prat3ik/iMirror` main commit `9b52ce6d8bedb80ee557a5114d3cdbee4ab84f00`.
It is not an Apple-certified receiver. No DRM bypass or paid service is included.

## Current personal build: 1.0.3-personal (version code 4)

- Listen in a foreground service after leaving/removing the app task.
- Restore the listener after boot and package replacement; migrate the existing
  personal installation to start-on-boot once, preserving later user opt-outs.
- Open a temporary playback screen on an accepted session, not discovery probes.
  Return to the previous TV task on disconnect and release temporary audio focus.
- Remove the sender footer from music cards, retaining cover/title/artist/album.
- Wait boundedly for valid video output during automatic foreground handoff.

The version 1.0.2 repairs below are retained:

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

Open iMirror once after the first installation. It can then listen while another
TV app is visible. On these authorized Android 11 TCLs, automatic foregrounding
requires the app-specific `SYSTEM_ALERT_WINDOW` special-access grant. It does not
draw floating overlays. On the same trusted network,
choose that TV in Screen Mirroring for the display or the Music AirPlay speaker
menu for audio-only playback. After updating the receiver, disconnect and reconnect
the sender once so it establishes a new session. No Mac reboot is required.

PIN authorization is optional and off by default; this default is for a trusted
personal LAN, not an exposed/public network. Do not expose receiver or ADB ports
outside that LAN. Automatic connection interrupts the previous app using transient
audio focus; disconnect returns to its task, but that app decides whether to resume
playing automatically. The Stop notification action remains an explicit off switch.

Listening cannot be guaranteed while the TV is unplugged, force-stopped, or in a
vendor sleep mode that disables networking. Start-on-boot does not mean playing at
boot: it restores the listener only. Real reboot/standby and synchronized multiroom
audio are not claimed by local policy tests.

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
