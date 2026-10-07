---
status: accepted
---

# Transmissions fire once per charter, and each member has a place in the story

The transmissions of the game are listed in `data/deepcharter/transmissions.json`: each has a sender, a framing (live, relay or unknown), a trigger (event, zone or breach), an optional bonus (B1, B2 or B3) and a `replay` flag. Each charter has a fired set, in order, and for each member a cursor into it. They are saved in one versioned `SavedData` (`transmission/TransmissionData`) of the world, keyed by `CharterId`. Features fire a transmission through `Transmissions.fire`.

- **The state lives in its own SavedData**: the charter code takes no change for M2 features, and [ADR 0007](0007-charters-are-one-versioned-saved-data.md) keeps `CharterData` to charter rules. `TransmissionData` follows the same rule: a `version` field, and data of another version kept and written back unchanged.
- **Unreadable data does nothing, and never throws from play.** `TransmissionData.isUsable()` is false for such data. `Transmissions` checks it at the top of `fire`, `deliver`, `replayTo` and the login path, logs once, and returns, so a tick, a login and a crossing never fail. An explicit read (`progress`, `replays`) still throws. The data file is read when the mod starts, so a broken file stops the game there.
- **Fired once is the one place that decides everything.** `TransmissionData.fire` returns true only the first time a charter fires a transmission. The bonus is credited only then, so a bonus is paid once, also across a restart. A bonus that a full account refuses is kept per charter and tried again on the next fire, login or zone poll.
- **Each member has a place.** A cursor says how many fired transmissions a member has been sent. A transmission is sent at once to the online members, and to each other member on their own login, from their own cursor. The cursor moves only after a send, so a failure loses nothing. A member who joins later starts at 0 and hears the charter's story so far. A member who leaves loses their cursor, so a return starts at the beginning. A shared queue that the first online member drains was rejected: it let one member's login take the story from the others.
- **A repair transmission replays for a charter founded later.** The world keeps the list of `replay` transmissions fired live, in order. A new charter gets them in its fired set when it is founded. A replay pays no bonus: the bonus is for the charter that did the work, and a charter founded later would otherwise be paid for founding.
- **Triggers.** An event is fired by the feature that owns it. A zone (a third of a layer, [ADR 0009](0009-layer-zones-are-thirds-and-biomes.md)) fires when a charter member stands in it, checked every `zonePollTicks`. A breach fires when a charter member descends into the layer, or climbs out into the surface ([ADR 0011](0011-the-surface-is-layer-0-with-an-open-floor.md)). A player on no charter fires nothing.
- **The client draws a HUD overlay, not a screen.** A transmission can arrive at any time, including mid-fall; a `CrtScreen` would take the mouse and the controls. The overlay reuses the CRT kit's `Typewriter` and drawing helpers. The packet handler drops a transmission it cannot show, with one log line, and never throws, because a throw disconnects the player.

## Consequences

- A change to the saved shape bumps `TransmissionData.VERSION` and makes the decode read the old one too.
- A transmission id that is saved but missing from the data file is skipped on delivery, with one log line.
- Bonus amounts are in `TransmissionTuning`.
