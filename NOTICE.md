# Third-Party Notices

iMirror is a derivative work. This file records what it is built from and under
what terms, as required by the licenses of the components involved.

The project as a whole is distributed under the **GNU General Public License
v3.0** (see [LICENSE](LICENSE)). GPL-3.0 was not an arbitrary pick — it is the
strongest license present in the dependency graph, and the FairPlay component
below makes it mandatory for the combined work.

---

## 1. playfair — GNU GPL

**Location:** `app/src/main/cpp/playfair/`
**Files:** `playfair.c`, `omg_hax.c`, `hand_garble.c`, `modified_md5.c`, `sap_hash.c`, and headers
**Origin:** <https://github.com/EstebanKubata/playfair>, as bundled by
[RPiPlay](https://github.com/FD-/RPiPlay) in its `lib/playfair` directory
**License:** GNU GPL

This implements the FairPlay SAP handshake, without which no iOS or macOS
device will hand over a decryptable mirroring stream.

**This component is why iMirror is GPL-3.0 and cannot be MIT or Apache-2.0.**
The source files carry no license headers, which makes the obligation easy to
miss — RPiPlay's own documentation is what identifies it as GPL. Anyone forking
this project should understand that removing the GPL notice is not an option
while these files remain.

Note further that RPiPlay describes the legal status of this library as
unclear, because it derives from Apple's FairPlay implementation. That
uncertainty is inherited here and is not resolved by any license choice. It is
the same position every third-party AirPlay receiver occupies.

## 2. Apple ALAC decoder — Apache License 2.0

**Location:** `app/src/main/cpp/alac/`
**Origin:** <https://github.com/macosforge/alac>
**Copyright:** Copyright (c) 2011 Apple Inc. All rights reserved.
**License:** Apache License, Version 2.0

Apple's open-source Apple Lossless decoder, used for AirPlay audio streams.
The original copyright headers are retained in each source file. One local
modification was made to `EndianPortable.c` to add ARM/ARM64 detection, which
upstream did not handle; it is marked in-place with an `iMirror:` comment.

## 3. PhairPlay — Apache License 2.0

**Origin:** <https://github.com/mazer666/PhairPlay>
**License:** Apache License, Version 2.0

iMirror began as a fork of PhairPlay and retains a substantial portion of its
AirPlay 2 receiver implementation: the RTSP handshake, HomeKit-style pairing,
mDNS advertising, mirror stream handling, and audio pipeline.

Apache-2.0 is compatible with GPL-3.0 in this direction, so the combined work
is redistributable under GPL-3.0 with this attribution preserved.

**Changes made from upstream:**

- Removed the Miracast and Google Cast receiver stacks entirely, along with the
  Google Cast SDK dependency, Wi-Fi P2P permission, and both location permissions.
- Collapsed the `googletv` / `firetv` product flavors into a single universal variant.
- Fixed an mDNS registration bug: `_airplay._tcp` and `_raop._tcp` were registered
  concurrently, and Android's `NsdManager` silently dropped one of them with no
  callback, leaving the receiver invisible to iOS Screen Mirroring. The two
  registrations are now serialized.
- Added low-latency decoder configuration (`KEY_LOW_LATENCY`, realtime
  `KEY_PRIORITY`, `KEY_OPERATING_RATE`).
- Made the app installable on phones and tablets as well as TV devices.
- Renamed the package namespace to `dev.imirror.receiver`.

## 4. Protocol documentation

No code was taken from these, but they documented the protocol:

- [openairplay/airplay-spec](https://github.com/openairplay/airplay-spec)
- [Unofficial AirPlay Protocol Specification](https://nto.github.io/AirPlay.html)
- [UxPlay](https://github.com/FDH2/UxPlay) — GPL-3.0, consulted as a reference implementation

## 5. Runtime dependencies

Retrieved via Gradle, not vendored into this repository:

| Dependency | License |
|---|---|
| AndroidX (appcompat, core-ktx, constraintlayout, leanback, datastore) | Apache-2.0 |
| Kotlin stdlib, kotlinx-coroutines | Apache-2.0 |
| Bouncy Castle (`bcprov-jdk18on`) | MIT-style Bouncy Castle License |
| dd-plist | MIT |
| Timber | Apache-2.0 |

---

## Trademarks

AirPlay, Apple TV, iPhone, iPad, and macOS are trademarks of Apple Inc.
iMirror is not affiliated with, authorized by, or endorsed by Apple Inc.
The protocol is implemented for interoperability only.
