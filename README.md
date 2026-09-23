# Portal Voice Compat

A tiny **server-side-only** Fabric mod for Minecraft 1.21.1 that makes
[Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat) proximity voice
audible **through [Immersive Portals](https://modrinth.com/mod/immersive-portals) portals**.

**The problem:** SimpleVC routes proximity voice per-dimension. Walk through a
portal and you're in a different `ServerLevel`, so friends on the other side
can't hear you — even though Immersive Portals lets them *see* you.

**The fix:** this addon listens to SimpleVC's `MicrophonePacketEvent` (without
cancelling it, so normal voice is untouched), computes a
*portal-aware distance* for listeners who are out of direct range (other
dimensions, or far away in the same dimension)
(`speaker → portal → portal exit → listener`, mirroring how Immersive Portals
itself does cross-portal sounds), and sends those listeners a
`LocationalSoundPacket` placed at a *virtual* position along the
listener→portal-exit ray at the true path distance — so volume falloff is
correct and the sound pans from the portal opening.

No client mod needed — the receiving client just plays positional audio at the
given coordinates.

## Requirements

- Minecraft **1.21.1**, **Fabric Loader ≥ 0.16**
- Simple Voice Chat **1.21.1-2.6.24** (Fabric) — on the server and clients
- Immersive Portals **6.0.6-mc1.21.1** (Fabric) — on the server and clients
- This mod: **server only** is enough. It also loads harmlessly on a client, so
  singleplayer / LAN works.

## Install

Drop the jar in the server's `mods/` folder alongside Simple Voice Chat and
Immersive Portals. No config file: voice ranges are read live from SimpleVC's
server config (`max_voice_distance`, and `whisper_distance` for whispers).

Set the `portal-voice-compat` logger to `DEBUG` to see each portal delivery.

## Building

Needs JDK 21. Dependencies are fetched from Modrinth's maven and Simple Voice
Chat's maven; nothing is vendored.

```bash
JAVA_HOME=<jdk-21> ./gradlew build
```

The jar lands in `build/libs/`.

## Behavior notes

- **Volume is path-correct.** Loudness reflects the full path (speaker → portal →
  you), not just your distance to the portal. The *direction* always points at the
  portal opening, which is where the sound arrives from.
- **Same-dimension portals work too.** If a portal links two distant places in one
  dimension, listeners out of direct range but within range through the portal hear
  the speaker. Listeners already in direct range are left to SimpleVC, so nobody
  hears a voice twice.
- **Portals must be usable.** The speaker has to be in front of a valid, visible
  portal. Voice doesn't pass through a portal from behind or through hidden ones.
- **Other plugins are respected.** Cancelled mic events, disabled voice connections
  (sender or listener) and spectator speakers are skipped.
- **Whispering** uses the shorter whisper range, same as vanilla.
- **Group chat stays private:** speakers in a voice group are never forwarded.
- Single hop only: voice goes through one portal, not chains of them. The shortest portal path wins.
- Threading: SimpleVC fires mic events on its own thread; the mod hops to the
  server thread before touching the world, and does nothing if the speaker has no
  portal nearby.

## Future work

- Client mixin option: exact speaker positioning and the talking indicator, at the
  cost of requiring the mod on clients too.

## License

MIT. See [LICENSE](LICENSE).
