# Personal TCL Google TV build

This GPL-3.0 personal fork retains upstream attribution and is based on
`prat3ik/iMirror` main commit `9b52ce6d8bedb80ee557a5114d3cdbee4ab84f00`.
It is not an Apple-certified receiver. No DRM bypass or paid service is included.

## Candidate personal build: 1.0.9-personal (version code 10)

- Parse raw and Annex-B-prefixed H.264 SPS configurations and remove emulation-prevention
  bytes before reading syntax. The mirroring caller supplies Annex-B; parsing it as raw SPS
  formerly selected incorrect dimensions or fell back to hints. Two regression tests failed
  before the repair and pass afterward. Framing was checked against AndroidX Media3
  `NalUnitUtil` and `ParsableNalUnitBitArray`.
- Physical playback validation is in progress. Mac Music on the previous build negotiated
  legacy FairPlay v2 and muted after only 3 of 24 ALAC frames decoded. This remains unresolved;
  successful control setup must not be reported as audio playback success.
- Bedroom was discoverable while TCL standby had frozen the app process. Normal TV wake
  thawed it and resumed RTSP handling. No always-available deep-standby claim is made.

The version 1.0.8 repairs below are retained:

- Answer supplemental mDNS queries over both IPv4 and IPv6, and refresh advertisements when a
  preferred IPv6 privacy address rotates. This removes stale endpoint selection from the phone's
  tap-to-connect path without restarting media sockets.
- Hold Android's high-performance Wi-Fi mode while the receiver is enabled. Music storage now
  honors the sender's negotiated latency maximum, so a recovered 2.4 GHz/Bluetooth contention
  burst is played in order instead of overflowing the short realtime queue and skipping ahead.
- Keep normal music startup at the sender's 250 ms minimum; the larger queue is bounded catch-up
  capacity, not a forced two-second prebuffer. Mirroring keeps its existing tight queue.

The version 1.0.7 recovery repair below is retained:

- Preserve every missing packet interval inside the bounded music recovery window after resend
  retries expire. This prevents a short unrecoverable Wi-Fi burst from deleting time and making
  otherwise valid playback sound like repeated skips, without enlarging the latency budget.

The version 1.0.6 discovery and weak-link audio repairs below are retained:

- Keep an Android multicast lock for the receiver's advertising lifetime, wait for a usable LAN,
  and automatically refresh only the mDNS advertisements after Wi-Fi loss/rejoin, address changes,
  registration failure, or an unexpected supplemental-responder exit.
- Preserve serialized RAOP/AirPlay registration and use generation checks plus bounded retry so
  duplicate or stale callbacks cannot create duplicate advertisements or interrupt media sockets.
- Send packet-loss recovery requests to the iPhone control port negotiated in type-96 SETUP,
  retry one outstanding gap at a bounded cadence, and distinguish delivered requests from failures.
- Give music-only ALAC a negotiated hard burst ceiling while priming only one hardware-minimum
  buffer before playback. Re-prime after a real underrun and insert one silent frame for an expired
  gap so packet loss does not delete time from the playback timeline.
- Leave the AAC-ELD screen-mirroring queue and its low-latency start behavior unchanged.

The version 1.0.5 pause, resume, and volume repairs below are retained:

- Treat iPhone's stream-scoped type-96 TEARDOWN as pause: stop that audio stream while retaining
  the authenticated control, event, timing and FairPlay session for immediate same-session resume.
- Flush pending PCM, RTP reorder/dedup state, decoder output and the hardware buffer on FLUSH or
  PAUSE so a restarted sequence cannot be mistaken for stale audio.
- Apply sender volume directly to decoded PCM16 samples on both audio paths. Android's track stays
  at unity, avoiding TCL vendor-mixer behavior without enlarging either low-latency queue.
- Keep a selected but quiet control socket alive through pause and media-app switching until
  socket/session teardown. An abandoned sender remains replaceable after the existing bounded
  handoff grace when another phone connects.
- Retain cover/title/artist/album across same-session audio removal so resume does not depend on an
  immediate metadata retransmission.

The version 1.0.4 connection-resilience repairs below are retained:

- Keep accepting control connections while a sender is active, reject probes during
  real media flow, and replace an abandoned Wi-Fi session after a bounded idle window.
- Reclaim a vanished sender automatically without imposing a fixed timeout on healthy
  audio/video traffic whose RTSP control channel is quiet.
- Run teardown once and keep the existing mDNS advertisements instead of repeatedly
  unregistering and re-registering them after discovery probes or duplicate teardown.
- Ignore late advertising callbacks while media is active so an older callback cannot
  release the new session's temporary audio focus or send the TV back to its prior app.
- Give audio-only music a modest weak-Wi-Fi burst cushion while leaving the interactive
  mirroring queue at its existing low-latency budget.

The version 1.0.3 background-listening repairs below are retained:

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
requires the app-specific `SYSTEM_ALERT_WINDOW` special-access grant, plus TCL's
`APP_AUTO_START` app-op for boot/package-replacement delivery. Both were verified
on these TVs; stock `AUTO_START` is not a recognized operation name. It does not
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

Living Room build 1.0.4 reproduced two additional receiver-side disconnects: a
quiet selected route was killed by the receiver's 30-second idle timer without a
sender teardown, and iPhone type-96 stream removal was incorrectly promoted to
full session cleanup. In both cases iOS still showed the TV selected and Apple
Music could skip tracks until the route was changed to iPhone Speaker and back.
Version 1.0.5 removes that autonomous established-session expiry and preserves
stream-scoped teardown state; both physical TVs still require the exact regression
flow before this result can be called verified.

Software audio queue budgets remain approximately 140 ms on the realtime mirroring
path. Music-only packet storage is capped by the sender's negotiated maximum and a two-second
receiver ceiling, with a 75 ms bounded loss-recovery window. The Living Room TCL's measured output
capacity is about 288 ms, and normal startup still primes to the sender's negotiated 250 ms minimum.
The larger software queue is catch-up storage after a Wi-Fi burst; it is not filled before normal
playback. On FLUSH/PAUSE the receiver
discards old UDP packets, resumes from the sender's RTP boundary, and ends a session cleanly if
the audio sink or decoder cannot recover. Sender buffering, network, decoding, and hardware
output still add delay, so this is a bounded receiver budget rather than an end-to-end guarantee.

Unsupported buffered-audio paths remain separate follow-up tests if they reproduce.
HEVC and protected-video
compatibility are not claimed. See [NOTICE](../NOTICE.md) for upstream components.
