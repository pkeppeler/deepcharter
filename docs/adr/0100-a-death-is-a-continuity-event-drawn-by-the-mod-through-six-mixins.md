---
status: accepted
---

# A death is a Continuity Event, drawn by the mod through six mixins

Issue #1337. The user picked the death sequence from mock-ups: a hard cut to black and silence, the record skipping on the person's last sound, a laughing CRT skull made of the Company's words (C4) over the Okeh Laughing Record through a horn (S1), then RETURN TO WORK. Crewmates see the body go still and dissolve as a Retained does, and chat says `▌CONTINUITY EVENT — <name> (No. N) — <cause>, <depth>`. Presentation only: what drops and where the person wakes do not change ([SPEC section 12](../SPEC.md#12-death-and-failure)).

## Decision

- **A feature of its own, `continuity`**, with a per-person attachment `deepcharter:continuity` (`ContinuityRecord`, version 1, `Versioned.codec`, persistent, `copyOnDeath`, not synced). It counts deaths, once each, and an unreadable record neither throws nor counts: the death goes ahead and the line's number reads "—". The dying client is sent its number and its employee number in `ContinuityEventPayload`, before vanilla's combat-kill packet.
- **A wreck kills its crew with its own damage type, `deepcharter:hull_loss`**, tagged as vanilla's `generic_kill` is (armour, resistance, invulnerability, no knockback), so the line can say HULL LOSS. `CrewFate` calls `hurtServer` with it where it called `kill`; nothing else about the kill changes.
- **Six mixins, each where no Fabric event reaches:**

| Mixin | Target | Why no event |
|---|---|---|
| `continuity/mixin/ServerPlayerDieMixin` | `ServerPlayer.die(DamageSource)`: its head, and the value of its `CombatTracker.getDeathMessage()` call | `ServerLivingEntityEvents.AFTER_DEATH` runs after `die` has built, sent and broadcast vanilla's message, so neither the count nor the line could be in it; `ALLOW_DEATH` is a veto another listener can overrule after ours, so a count there could count a death that does not happen. Fabric's `ServerMessageEvents` can only observe a broadcast, not replace it. Replacing the message where vanilla reads it keeps vanilla's own `showDeathMessages` and team-visibility handling. |
| `continuity/mixin/PlayerDeathSoundMixin` | `Player.getDeathSound()` | No event for a mob's sounds. Null is what a silent mob returns, and vanilla's callers already skip it. The crew hear the lamp click out instead, played by `ContinuityEvents.died` to everyone but the person. |
| `client/continuity/mixin/ClientPacketListenerCombatKillMixin` | the `Gui.setScreen` call in `ClientPacketListener.handlePlayerCombatKill` | Fabric's screen events fire after a screen is chosen; none can swap it. A screen swapped a tick late would show vanilla's for a frame. Wrapping the one call keeps vanilla's branch on `doImmediateRespawn`. |
| `client/continuity/mixin/GameRendererMixin` | `GameRenderer.bobHurt` | No camera event. The red tilt is skipped only while the Continuity Event's screen is open. |
| `client/continuity/mixin/SoundEngineMixin` | the head of `SoundEngine.play(SoundInstance)` | No event can refuse a sound. Every sound passes through it (the manager's play, its delayed and ticking queues, music), so the silence holds for all of them, and only the event's own `SequenceSound`s play. A refused sound returns `NOT_STARTED`. |
| `client/continuity/mixin/AvatarRendererMixin` | the tail of `AvatarRenderer.extractRenderState` | No render-state event for vanilla's player renderer. For a dying player it clears the death time (no fall) and the red overlay, and draws the second half of the dying time see-through, as vanilla draws an invisible team-mate, before vanilla's own puff removes the body. |

- **The skip is live, the laugh is a file.** `RecentSounds`, a `SoundEventListener` on the sound manager (the subtitles' hook, no mixin), keeps the sound events the client started in the last seconds, with place, pitch and volume. The screen replays the latest loud one three times, each pass shorter and lower, each with the needle; with nothing to replay it goes straight to the laugh. The laugh is `continuity/laugh.ogg`, made by `tools/sounds/okeh_laugh.py` from the Great 78 Project's transfer, which also writes the loudness envelope (`continuity/laugh_envelope.json`) the skull's jaw and glitches follow, so nothing reads the mixer.
- **Art values are theme data** (`theme/continuity.json`, `ContinuityLook`, [ADR 0032](0032-the-ui-theme-is-resource-pack-data-merged-key-by-key.md)): the cut, the arrival, the skip's beat, the grid of letters, the tears, the band and the colours. The skull's geometry is the model, in `SkullShape`.

## Consequences

- A Minecraft update that renames a target stops the game at launch (`defaultRequire: 1`): fix the target.
- With `doImmediateRespawn` on there is no sequence, as there is no death screen in vanilla.
- The sequence runs on the client's clock from the screen's opening, so its sounds keep the beat at any frame rate; the button comes when the laugh's envelope ends.
- The client records no sound, so the evidence scenario `death-sequence` shows the picture only; `ContinuityClientTest` checks what is heard.
