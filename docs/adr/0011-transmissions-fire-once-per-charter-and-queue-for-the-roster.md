---
status: accepted
---

# Transmissions fire once per charter and queue until a member is online

The transmissions of the game are listed in `data/deepcharter/transmissions.json`: each has a sender, a framing (live, relay or unknown), a trigger (event, zone or breach), an optional bonus (B1, B2 or B3) and a `replay` flag. Each charter has a fired set and an ordered queue of the transmissions it has not yet been sent, saved in one versioned `SavedData` (`transmission/TransmissionData`) of the world, keyed by `CharterId`. Features fire a transmission through `Transmissions.fire`.

- **The state lives in its own SavedData**: the charter code takes no change for M2 features, and [ADR 0007](0007-charters-are-one-versioned-saved-data.md) keeps `CharterData` to charter rules. `TransmissionData` follows the same rule: a `version` field, data of another version kept and written back unchanged, and every use throws.
- **Fired once is the one place that decides everything.** `TransmissionData.fire` returns true only the first time a charter fires a transmission. The bonus is credited, and the transmission is queued, only when it returns true, so a bonus is paid once, also across a restart. A transmission in the queue is part of the fired set.
- **The queue belongs to the charter, not to a player.** It is sent to every online member, in order, as soon as one is online: at once when a transmission fires, otherwise when a member logs in or joins. A member who is offline when it is sent does not get it later. Per-player queues were rejected: they would grow with every player who ever joined and need pruning, for a story the whole charter is meant to hear together.
- **A repair transmission replays for a charter founded later.** The world keeps the list of `replay` transmissions fired live, in order. A new charter gets them in its queue, and in its fired set, when it is founded. A replay pays no bonus: the bonus is for the charter that did the work, and a charter founded later would otherwise be paid for founding.
- **Triggers.** An event is fired by the feature that owns it. A zone (a third of a layer, [ADR 0009](0009-layer-zones-are-thirds-and-biomes.md)) fires when a charter member stands in it, checked every `zonePollTicks`. A breach fires when a charter member descends into the layer, from `BreachEvents.CROSSED`. A player on no charter fires nothing.
- **The client draws a HUD overlay, not a screen.** A transmission can arrive at any time, including mid-fall; a `CrtScreen` would take the mouse and the controls. The overlay reuses the CRT kit's `Typewriter` and drawing helpers.

## Consequences

- A change to the saved shape bumps `TransmissionData.VERSION` and makes the decode read the old one too.
- A transmission id that is saved or fired but missing from the data file throws on delivery: remove a transmission only with a version bump that drops it from saved data.
- Bonus amounts are in `TransmissionTuning`.
