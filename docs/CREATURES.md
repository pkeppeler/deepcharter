# Deep Charter: creatures and combat

Settled 2026-11-21 in the creatures and combat session ([#13](https://github.com/pkeppeler/deepcharter/issues/13)).

**Spoilers, team only.** Glossary terms are in [CONTEXT.md](../CONTEXT.md), the canon in [LORE.md](LORE.md) and the game's systems in [SPEC.md](SPEC.md). Numbers are proposals until playtests tune them. Each buildable step has an issue (section 12).

## 1. Settled with the user

| Decision | Settled |
|---|---|
| How often and how lethal | Rare, costly, seldom lethal (section 4). Every mob costs you in the pod and on foot, and gives you a reason to avoid it. |
| Pods | Damage follows the family: Moloch's take, the Sleeper's hurt, gnawers eat. |
| Outposts and blocks | Creatures may break placed blocks, but only ones they can reach that are not rated for the depth. A rated shell keeps everything inside safe. They act while nobody is near too, and the game says so plainly (section 9, [ADR 0072](adr/0072-creatures-break-exposed-unrated-blocks-even-while-nobody-is-near.md)). |
| Layer bosses | They gate the upgrade ladder, not the descent. Layers 3 to 7 only. Each charter fights each one once. |
| The finale | Phase 1 on foot: the Chairman can be hurt only in the light of your own lamps. Phase 2 in pods: only the drill and explosives hurt Head Office, its size comes from what the charter sold, and it eats the cargo you carry. |
| Crew size | Encounters scale by pods, not by the crew inside them, and a boss's health also rises with each person fighting it on foot. Solo play is tuned with robots in the empty seats. |
| The surface | Empty, for good: no monsters at all. This replaces the reskin plan from the 2026-11-21 playtest. |
| The Retained on foot | They cling to a person in the dark, slow them and drain their hunger until struck off. They still never deal damage. |
| Recovery units | They take cargo outright and bill you for it. Ordinary machines drop nothing worth hauling. |
| Super Motherload | A source of mechanics ideas only, under the same homage rules as the original. A tech-tree ideas pass follows this session. |

Everything else here is the session's creative liberty, open to the user's veto.

## 2. The three families

Every creature belongs to one of LORE's three families ([LORE.md section 14](LORE.md#14-hooks-for-the-creatures-session)). Each family has one **draw**: the one thing that brings it to you. Learn the draw and you can play every creature in the family. This is SPEC section 5's rule applied to creatures: the world is a mystery, the rules never are.

| Family | Draw | What it does | On sonar | Killing it | Placed blocks |
|---|---|---|---|---|---|
| **Moloch's**: the Retained, Tallies, masks, Company machines | the dark; in act 3, also being **unauthorised** | **takes**: fuel, cargo, hunger, lamp battery. Never damages a hull or a person | the hollow give **no echo**; machines echo as ore, because they are made of what was sold | possible; they go to ash or scrap. Pays nothing | never breaks them |
| **The Sleeper's defences** | the drill: noise, heat, resonance | **hurts**: hull and health. It is an immune response | real, so always an echo | possible, but each kill raises the zone's **alarm**. Pays nothing. Its bosses are driven off, never killed | breaks exposed blocks not rated for the depth |
| **Gnawers** (post-game) | the haul: ore in the rock, in a bay or in storage | **eats**: cargo, and the hull or wall between it and ore | rock that moves | hard. Drops the uncharted's rare ore | chews through anything to reach ore |

What follows from it:

- **Light keeps Moloch's things away; quiet keeps the Sleeper's away.** Mining is loud and light costs, so no pod is safe from both. That gap is where the dread lives.
- **Sonar shows what is real; light shows what is hollow.** A Retained carries a dim counterfeit lamp you can see from far off, but the navigator's screen shows nothing there. Reading sonar includes reading absence.
- **The economy carries the moral, without a word of text.** Wounding the good thing pays nothing and brings more of it. The Company's machines pay nothing either: you fight them only to keep what is yours.
- **Alarm.** Killing one of the Sleeper's creatures raises the zone's alarm for a few minutes: more of them come to the drill, sooner. The game shows it plainly (a deeper drone and a scanner line) so it never reads as random spawns.

**Presences** are creatures that never act: THE EYES, the checkpoint wardens, the Echoes, the kneeling shapes in the Quiet, Convoy Nine. They are not mobs and cost nothing but attention.

## 3. Principles

1. **Every mob is a problem the upgrade loop answers**, with a part, a seat or a consumable. The Retained answered by lights is the template ([mechanics.md](design/mechanics.md#the-retained-at-dark-pods-449-a)).
2. **Every mob costs you twice**: something in the pod and something on foot. It is unsettling to meet.
3. **Sensed before seen.** Every mob has a tell on sonar, sound or light at least 3 seconds before it can cost you anything. A player who bought the sensor and paid attention is never caught cold.
4. **Few, persistent, readable.** No packs, no farming, no experience. A mob's rule is stated plainly on first meeting: a one-time HUD or scanner line (SPEC section 5). Drifters are the one exception to "persistent": they are not saved, and one whose chunk unloads is gone and the zone places another ([mechanics.md](design/mechanics.md#drifters-and-the-alarm-973-a)).
5. **The worst an ordinary mob can do is cost you the run.** The worst a boss can do is leave a wreck to salvage (SPEC section 12).
6. **Fighting costs money.** Ammunition is bought consumables, so every fight feeds pillar 1.
7. **The dead keep their dignity** ([LORE guardrails](LORE.md#guardrails)). The Retained drop no loot and come apart in ash, never a body. Proposal: now and then one leaves a brass Continuity tag that becomes a Note with a name on it, and the records board counts names recovered.
8. **The lure never goes dark.** No creature blocks selling, buying or upgrading. Terminals and pads are never broken, and a bill never takes an account below zero.

## 4. How often and how lethal

- **Cadence.** A layer's ordinary creatures appear about once a run in its top zone, rising to every few minutes in its bottom zone. The Retained keep their tuning (common in layer 2, [mechanics.md](design/mechanics.md#the-retained-at-dark-pods-449-a)).
- **Lethality.**
  - Moloch's things never kill and never wreck. They take. The Retained's hunger drain stops at a floor, as their fuel drain stops at 5 per cent.
  - The Sleeper's things and gnawers can wreck a pod that ignores them, but never in one hit and never without a tell. On foot they can kill you (a Continuity Event).
  - A player who dies on foot leaves the pod where it stood.
  - Bosses can wreck pods.
- **Crew size: scale by pods, not people.**
  - Each pod is its own source of noise and darkness, so a group spread over more pods draws more encounters, capped per zone. Big groups are loud.
  - A full pod faces exactly what a solo pod with robots faces, and handles it better. That rewards crew over passengers (SPEC section 8) and keeps solo play viable.
  - Each person on foot in the dark is their own draw for Moloch's things.
  - A boss's health rises with each extra pod and each person on foot in its arena, up to 8 players.

## 5. Bestiary

Each entry gives the family, the draw, how it is sensed, what it costs in the pod and on foot, and the counters.

### Surface

Nothing, ever. The colony's emptiness is the horror, and the only life is the fungal stalks. Vanilla monsters do not spawn on the surface or anywhere else.

### Layer 1: The Claim

Act 1, satire. No boss: the layer teaches the loop and should feel merely abandoned.

#### The Retained

Moloch's, drawn to the dark. Built ([ADR 0046](adr/0046-the-retained-are-a-monster-that-never-attacks-and-the-lamp-carrier-has-its-own-files.md), [mechanics.md](design/mechanics.md#the-retained-at-dark-pods-449-a)); rare in layer 1.

- **Sensed:** a tiny dim lamp in the helmet, seen from far off in the dark. No echo on sonar. A low rumble as one sets off toward you.
- **Pod:** clings to a dark pod and drains fuel down to 5 per cent until it is killed.
- **On foot** (built, [#916](https://github.com/pkeppeler/deepcharter/issues/916), [mechanics.md](design/mechanics.md#the-retained-on-foot-916-a)): comes to a person in the dark (no light in either hand and block light under 3 where they stand; later, a suit lamp that is off). It clings, slows them to a crawl as if they carried ore, and drains hunger down to a floor until struck off. It never deals damage.
- **Counters:** lights (pod) and a light in hand (on foot) keep one away, but never free a pod or person already held. The cutter or any blow ends a grip.
- **Heard while it holds** (built, [#1450](https://github.com/pkeppeler/deepcharter/issues/1450), [ADR 0109](adr/0109-a-retaineds-grip-plays-a-dread-bed-to-those-held-and-ducks-the-drone-through-one-mixin.md)): a low dread bed rises and the layer's drone sinks under it, for everyone aboard the held pod or the person held, one bed however many hold on. It fades when the grip ends; a light does not end it, since a light frees no one.

#### Tallies

Moloch's: brass Company counting drones from the Boom. They ride the Conduit's casing wherever it runs and click over ore.

- **Sensed:** clicking; an echo as ore; a small amber eye. Its take has its own cue ([#1450](https://github.com/pkeppeler/deepcharter/issues/1450)): a counter ticks over, a receipt is stamped, a small bell rings, at the hatch or the pack, and anyone near hears it.
- **Pod:** one that trails a pod for about 30 seconds clamps to the cargo hatch and takes an ore as a "quality sample". The cutter bites it there.
- **On foot:** takes an ore you carry. One blow breaks it.
- **Act 3:** a Tally that sees an unauthorised pod calls the Recovery units. In act 3 the Conduit, your landmark all game, is watched.

### Layer 2: The Old Workings

No boss: onboarding ends here with the Prospector salvage, and a boss on the handbook's last chapter would fight the tutorial.

- **The Retained**, common, and the rail figure of Prospector's Run (built).
- **Tallies**, along the Conduit.

### Layer 3: Fungal Hollows

Act 2 opens. The navigator seat is new, and this is the first real on-foot exploration.

#### Drifters

The Sleeper's: pod-sized glowing spore bladders that drift toward drill noise and footsteps.

The mechanics are built before the layer is ([#973](https://github.com/pkeppeler/deepcharter/issues/973), numbers and decisions in [mechanics.md](design/mechanics.md#drifters-and-the-alarm-973-a)); they key on the biome tag `deepcharter:fungal_hollows`, which #972 fills.

- **Sensed:** a soft glow and a faint hiss. Mostly gas, so sonar reads one as **empty cave**: the classic ambiguous echo.
- **Pod:** touch one or drill beside it and it bursts into spore gas that eats at the hull while you sit in it.
- **On foot:** the gas poisons and blinds. They drift toward sprinting.
- **Counters:** stop drilling and they drift past; pop them at range (a charge, an arrow, the bolt driver) away from you; passive listening hears the hiss.

#### Overgrowth

A hazard of the Sleeper's growth, not a mob. A bore left unvisited grows over with pale soft growth: drillable at extra fuel, cut by hand on foot. Placed blocks never grow over, so a lined shaft stays open.

#### The kneeling shapes

A presence. Fungus-grown shapes kneel around the Badger shrine in the Quiet. They never move.

#### The Hush

The layer's boss (section 6).

### Layer 4: The Drowned Deep

The operator seat arrives with the Badger. Convoy Nine and Hal's Mole stay peaceful: nothing hunts near them.

#### Limpets

The Sleeper's: stone-shelled things that come to a drill's heat underwater and seal onto the hull.

- **Sensed:** each one **knocks** on the hull as it settles. On sonar they read as ore on the hull.
- **Pod:** they work at the seals: leaks first, then pressure damage, worse with each limpet. The cutter cannot reach the back of the hull.
- **On foot** (in water): one latches onto the suit and cracks its seal, so the suit timer runs fast. Pry it off by hand, or a crewmate does.
- **Counters:** the operator's arm pries them off; leave the water; a hot hull makes them let go (the radiator track). The first creature that needs the operator.

#### The Undertow

The layer's boss (section 6).

### Layer 5: The Lattice

#### Listeners

The Sleeper's, LORE's fixed beat: blind crystal things that stand among the geodes.

- **Sensed:** standing still, sonar cannot tell one from crystal (an ore echo). Moving, it is "something that moved". A faint ringing as it walks.
- **Draw: resonance.** Drilling crystal rings loudest; a sonar ping rings, and so does a charge. They freeze when it goes quiet and give up after about 10 seconds of silence.
- **Pod:** they cut the hull hard. **On foot:** they cut health.
- **Counters:** a damped drill head (a Lattice part: crystal rings less, the drill is slower); passive listening; decoy rounds and thrown decoys; the sonar burst; shattering them with charges (alarm).
- **The navigator's dilemma:** a ping is both how you see them and how you call them.

#### Echoes

A presence. The Lattice replays old contacts as false sonar returns: ghost pods, a crew that is not there. They never act. This is the SPEC's scanner interference.

#### The Great Listener

The layer's boss (section 6).

### Layer 6: The Magma Belt

Act 3: the Chairman takes over the charter's account. LORE's two wills become playable: Fell wants you to turn back, Moloch wants you down ([LORE.md section 4](LORE.md#4-the-employer-h-colom--co)).

#### Recovery units

Fell's, of Moloch's family. Company machines, including the siphon crawlers that tend the artery pipes.

- **Draw:** being unauthorised. They come more often the further a pod is below the authorised area (the altimeter's label in layer 6), and Tallies call them.
- **Sensed:** a polite recorded chime; amber work lamps; an echo as ore.
- **Pod:** a unit clamps the pod (no treads, no rotor) and siphons its cargo slot by slot. A receipt follows: *"Recovered: 6 Goldium. Credit: $0.00. Recovery fee: $40."* The fee never takes the account below zero.
- **On foot:** shackles a person in place and takes the ore they carry.
- **Counters:** the operator's arm or a person on foot cuts the clamp; the drill, charges and, from this layer, the Company turret break them.

#### Checkpoint wardens

A presence: Moloch's will. Tall recorded sentries at Checkpoint that refuse you and wave you through. They never act.

#### The figure with a radio

A presence ([LORE.md layer 6](LORE.md#6--the-magma-belt-blood)): a lampless figure whose radio crackles on your channel. It walks and never comes to a pod.

#### Scabs

The Sleeper's: slow lava-dwellers that seal wounds.

- **Draw:** drill heat near the arteries.
- **Pod:** they seal a fresh bore, behind or ahead, with hot crust: drillable, and hot on the hull. Your way home can be blocked, never erased.
- **On foot:** they burn on touch.
- **Counters:** rated lining keeps a shaft open; the radiator; killing them (alarm).

#### The Bailiff

The layer's boss (section 6).

### Layer 7: The Ossuary

#### THE EYES

A presence, the Sleeper's. They never attack ([LORE.md section 5](LORE.md#5-whats-at-the-bottom-the-sleeper)). One new rule: **while open eyes are on you, nothing of Moloch's comes near.** At the Watching Wall the dark is the safest place in the game, and the price is being watched. The Company's placard is its one true statement.

#### Masks

Moloch's: the heap of every Chairman's mask before Fell's, scuttling on bronze fingers out of the Old Furnaces.

- **Draw:** the dark. Hollow, so no echo; a faint scrape of metal on bone.
- **Pod:** one comes to a dark pod and covers the canopy: the pilot is blind until the operator's arm pries it off or someone on foot pulls it away.
- **On foot:** it comes to anyone whose suit lamp is off or dead and covers the visor. The view goes bronze, the lamp drains, and a voice makes offers. It never touches health. A crewmate pulls it off, or you wrench it off yourself.
- **Counters:** lit pods, a charged suit lamp, a crewmate, the Eyes. A rehearsal of the finale's offer.

#### The Old Idol

The layer's boss (section 6).

### Layer 8: The Furnace

The Retained work the lines under the Company's sign and never act (LORE section 12: they are not the finale's fight). Recovery units are Fell's last guard. Then the finale (section 7).

## 6. Layer bosses

- **They gate the ladder, not the depth.** Each sits on its layer's chassis wreck or catalyst. You can drill past one and go deeper, but the chassis cap brings you back: SPEC section 3's "you will regret it".
- **Act 2's bosses are the Sleeper's**, and you drive them off, never kill them. **Act 3's are Moloch's**, and you break them.
- **Once per charter.** A boss wakes for a charter that has not beaten it. Another charter's crew in the lair is pulled into the fight; that is co-op, not a bug.

#### The Hush

Layer 3, the Quiet. The Sleeper's. On foot, then a tow. **Guards:** the Badger wreck, grown into a shrine.

- A pale membrane spread across the cathedral roof: the reason sound dies there. Every noise shows as a ripple across it, and it lowers toward the loudest. What it reaches it holds and crushes slowly (health on foot, hull in a pod).
- Cut the Badger free of the growth on foot, hook the tow line, then tow it out under the Hush. The winch is loud, so someone throws decoys or sets fused charges far off to pull it away. A charge into its lowered body makes it recoil for a while.
- **Solo:** fused charges and thrown decoys on timers; slower, but it works.
- **Ends** when the Badger leaves the Quiet. The Hush stays. **Reward:** the Badger and its operator seat.

#### The Undertow

Layer 4, the Basin. The Sleeper's. In pods. **Guards:** the Lymphium.

- A shape the size of the cavern, seen only on sonar. It drags pods with current into the walls and coils around them: a grip the drill cuts, the Retained's rule at boss scale.
- The navigator marks it, the pilot flies blind by the marks, and the operator lands charges on the mark. The first true crew fight.
- The breach lies in the Basin too. Drilling it is loud enough to wake the Undertow, but it never follows through, so a pod can always cross: it is never a gate.
- **Ends:** driven down into the deep water. **Reward:** the Basin's Lymphium is safe for that charter.

#### The Great Listener

Layer 5, the Singing Halls. The Sleeper's. Both. **Guards:** the Nervium.

- A Listener the size of a cathedral, hung from the roof of the Halls. Pods are loud bait; the crew on foot plant charges on its resonant nodes.
- Every blast is also a call, so each charge is both a weapon and a lure. The sonar burst stuns it for a few seconds.
- **Solo:** set a decoy, plant, retreat.
- **Ends:** driven off; the Halls go silent. **Reward:** the Nervium.

#### The Bailiff

Layer 6, Checkpoint. Fell's. In pods, then on foot. **Guards:** Roz's Crawler, still wearing a 38-year-old Company clamp.

- A repossession rig on the arteries. It hooks pods with cables and drags them upward, toward the authorised area.
- Cut the cables with the drill or the arm, then break it with charges and the first Company turret, or break the crust under it so it falls into an artery.
- Then unclamp the Crawler on foot, against the heat and the suit timer.
- **Ends:** broken. **Reward:** the Crawler, and the one machine payout in the game: a heap of scrap the Company credits "for the return of Company property".

#### The Old Idol

Layer 7, the Old Furnaces. Moloch's. In pods. **Guards:** Survey One's Behemoth wreck on the Floor of Bones.

- An older, cold bronze figure built around an open hand, half sunk in bone. A drill's heat relights it.
- Only the drill and explosives hurt bronze, and its open hands pull cargo into its mouth to heal it. It teaches both of the finale's rules.
- Kept small, cold and slow, so it reads as a lesson, not a climax.
- **Ends:** broken.

## 7. The finale

The story's shape is settled in [LORE.md section 12](LORE.md#12-the-finale) and [ADR 0031](adr/0031-the-finale-is-won-by-renunciation.md). This is how it plays.

**Phase 1: the Chairman, on foot in the Boardroom.**
- Fell stands in the Company's light, and in counterfeit light nothing touches him. Put out the Boardroom's Company lamps and he stands in the light of your own suit lamps, where any blow hurts him.
- He fights with paperwork. A notice that hits you fines the account (never below zero) and knocks you toward the fire. Recovery units come in at the door.
- A few hits drop him. He talks the whole time and grows more ridiculous: mocking him is right.
- Template 0's "keep your lamp lit" becomes the literal mechanic.

**Phase 2: Head Office stands up, in pods.**
- It is built from what this charter sold. Its plating shows your ores, and its size and health come from the charter's lifetime sales, held between a floor and a ceiling, then scaled by pods present (section 4).
- Only the drill and explosives hurt it (charges break its joints, the drill its plating); turret rounds glance off.
- Its hands take a pod and pull its cargo into the chest furnace, and every ore it eats heals it. You win by going in empty-handed: the renunciation in rehearsal.
- It breaks but cannot die. The mask crawls out of the wreck, and the offer follows (LORE section 12).

## 8. Combat kit

Mining gear comes first; the turret comes late (SPEC section 13). Every round fired is bought.

### In the pod

| Tool | Seat | Arrives | What it does | Good against | Costs |
|---|---|---|---|---|---|
| Cutter | pilot | built | bites anything on the hull or in the bore while the pod drills; drill tiers bite harder | clingers: the Retained, Tallies, the Undertow's coils | the drill's fuel |
| Lights | a part | built | a lit pod draws no Moloch's thing | the Retained, masks | the part |
| Blasting charge | pilot | built | a blast under the pod | anything below | the charge |
| Active ping | navigator | the Prospector | reveals what is real in range and marks it on the pilot's HUD | the Sleeper's | power; it is loud |
| Passive listening | navigator | layer 3 part | silent; hears movement at short range | Drifters, Listeners, the Hush | the part |
| Sonar burst | navigator | layer 5 part | stuns the Sleeper's for a few seconds and wakes everything else near | Listeners, the Great Listener | power; very loud |
| Manipulator arm | operator | the Badger | pries off clingers, cuts clamps, grabs | Limpets, masks, Recovery clamps | nothing |
| Charge launcher | operator | the Badger | fires bought charges at range: dynamite, plastic, blasting and shaped charges | everything; the only ranged pod damage before layer 6 | the charges |
| Flare rounds | operator | layer 4 | a burst of light | Moloch's | the round |
| Decoy rounds | operator | layer 4 | a noise far off | the Sleeper's | the round |
| Damped drill head | a drill part | layer 5 | crystal rings less | Listeners | the part, drill speed |
| Company turret | operator | layer 6 | rivet fire | machines; poor on the Sleeper's; nothing on bronze | the part, rounds |

**Robots** fill empty seats with less judgment exactly where it matters: the navigator robot pings on a fixed timer and cannot stay quiet, and the operator robot fires late at marked targets and never uses decoys.

### On foot

| Tool | What it does |
|---|---|
| Vanilla melee | works on small mobs (it already kills the Retained) |
| A light in hand, later the suit lamp | keeps the Retained and masks off; makes the Chairman hurtable |
| Bolt driver | a mining roof-bolter: fires bolts at range, and sets roof bolts that hold a stretch of roof against cave-ins |
| Thrown charges, flares, decoys | the operator's rounds by hand |
| Tether | a line to the pod's winch: pulls you back, and tows in the Hush fight |
| Suit tracks | the suit timer is the clock on every fight on foot |

## 9. Creatures and placed blocks

SPEC section 10's rule stays: hazards never delete player-built blocks. Creatures have their own rule ([ADR 0072](adr/0072-creatures-break-exposed-unrated-blocks-even-while-nobody-is-near.md)).

### One rule per family

- **Moloch's things take; they never break.** The Retained come to a dark outpost and drain its stored fuel. In act 3, Recovery units carry off stored ore. Lights keep the Retained away. Moloch's things never break a block or open a door, so any closed shell keeps them out, rated or not.
- **The Sleeper's things break foreign blocks.** They come to an outpost's noise (pads, machines, a parked pod's engine) and break any **exposed** block not rated for that depth. Rated blocks they leave alone: the body accepts its own. The rated material that makes an outpost habitable (SPEC section 11) also makes it safe.
- **Gnawers chew through anything to reach ore.** Nothing is rated in the uncharted, so a camp there lasts only while it is defended.
- **Damping blocks** muffle noise: fungal felt from layer 3, damped crystal from layer 5. A pad walled in damping draws nothing, so the layout matters.

### The shell

A creature can break only what it can reach. A block is **exposed** when it touches open space a creature can reach from outside; the outer layer of a building is its **shell**. Unrated blocks inside a closed, rated shell are safe, so decoration and colony materials inside are fine. A gap in the shell, or an open door, exposes everything behind it. Doors count as shell when closed and rated.

Terminals and pads are never broken (principle 8). Pods and wrecks are never touched while nobody is near.

### While nobody is near

Creatures keep acting on an outpost nobody is near.

- **Visits are rolled on the world's clock** (game time, which stops while the server is off) for every outpost, from its draws: noise draws the Sleeper's, darkness the Retained, stored ore the Recovery units in act 3 and the gnawers in the uncharted.
- **A loaded outpost meets the visit in the world.** An unloaded one records it and applies it when it loads.
- **Each visit breaks or takes a bounded amount:** a few exposed unrated blocks, some stored fuel or ore. The loss grows with the time away until nothing exposed and unrated is left.

A few in-game weeks away from a camp built of colony materials is a ruin. A closed, rated, damped outpost comes back as you left it.

**Ruined, not erased.** A broken storage block spills its contents into a salvage heap that keeps them, as a wreck keeps its cargo.

**Said plainly, in five places:**
1. **The spec sheet** the first time a charter places an outpost bed or terminal below layer 2: *"Unattended structures are subject to local conditions. Rated materials hold. Unrated materials do not."*
2. **The placement readout** while you hold a block at depth: HOLDS HERE, SHELTERED or WILL NOT HOLD UNATTENDED.
3. **The outpost terminal's risk line:** exposed unrated blocks, gaps in the shell, dark corners, undamped machines.
4. **An alert when a visit is rolled,** to charter members online wherever they are: *"Disturbance reported at Outpost 2."* You know, and you cannot see.
5. **An incident report** at the outpost terminal when you return, listing what was lost.

## 10. Uncharted and after the finale

- **No Moloch's things below the Ramp**, under either ending: Moloch never broke the wall around the heart. The Retained, Tallies, masks and machines end at the Furnace.
- **Dreamed layers.** Each uncharted world dreams one story layer, and its Sleeper's creatures return with one rule warped, chosen by the world seed: Drifters drawn to light, Listeners drawn to silence. The first meeting states the warped rule plainly.
- **Gnawers** come to the haul and grow bigger and hungrier with depth. They are the records board's grind and drop the uncharted's rare ore.
- **After the finale** (presentation for that charter only, LORE guardrail 8):
  - Renounced: the Retained no longer come to that charter's pods or crew. They sit and rest.
  - Signed: they turn to face the Chairman wherever he goes, and never drain his pod. They are his staff.

## 11. Open questions

- **Numbers:** encounter cadence per zone, alarm strength and decay, boss health per pod and per person on foot, Head Office's floor and ceiling, the visit rate and how much each visit takes while nobody is near.
- **Dependencies:** the sonar signatures need the navigator's sonar; the on-foot costs of Limpets and masks need the suit timer (built, [#993](https://github.com/pkeppeler/deepcharter/issues/993)) and suit lamp; the shell rule needs outpost ratings (built, #989) and the shell's exposure (#926).
- **The Continuity tag** (principle 7): whether a Retained leaves one, and how often.
- **The smelter**, a Super Motherload idea (a Company terminal that trades ore for charges): parked for the tech-tree ideas pass, because bought charges already give blast shapes and make fighting cost money.
- **Homage check:** player-facing lines here (the receipt, the spec sheet, the alert) go through `tools/check-homage.py` against the original's reference ([homage-check.md](tooling/homage-check.md)).

## 12. Issues

**M3, into the dark (layers 3 and 4)**

- [#915](https://github.com/pkeppeler/deepcharter/issues/915) Empty surface
- [#916](https://github.com/pkeppeler/deepcharter/issues/916) The Retained on foot
- [#917](https://github.com/pkeppeler/deepcharter/issues/917) Tallies
- [#918](https://github.com/pkeppeler/deepcharter/issues/918) Drifters and the alarm
- [#919](https://github.com/pkeppeler/deepcharter/issues/919) Overgrowth
- [#920](https://github.com/pkeppeler/deepcharter/issues/920) The Hush
- [#921](https://github.com/pkeppeler/deepcharter/issues/921) Limpets
- [#922](https://github.com/pkeppeler/deepcharter/issues/922) The Undertow
- [#923](https://github.com/pkeppeler/deepcharter/issues/923) Operator combat kit
- [#924](https://github.com/pkeppeler/deepcharter/issues/924) Sonar signatures
- [#925](https://github.com/pkeppeler/deepcharter/issues/925) On-foot combat kit
- [#926](https://github.com/pkeppeler/deepcharter/issues/926) Creatures and placed blocks
- [#927](https://github.com/pkeppeler/deepcharter/issues/927) Settling visits while nobody is near

**M4, the pressure (layers 5 and 6)**

- [#928](https://github.com/pkeppeler/deepcharter/issues/928) Listeners, damped drill head, sonar burst
- [#929](https://github.com/pkeppeler/deepcharter/issues/929) The Great Listener
- [#930](https://github.com/pkeppeler/deepcharter/issues/930) Recovery units and checkpoint wardens
- [#931](https://github.com/pkeppeler/deepcharter/issues/931) Scabs
- [#932](https://github.com/pkeppeler/deepcharter/issues/932) The Bailiff and the Company turret

**M5, the bottom (layers 7 and 8)**

- [#933](https://github.com/pkeppeler/deepcharter/issues/933) THE EYES and masks
- [#934](https://github.com/pkeppeler/deepcharter/issues/934) The Old Idol
- [#935](https://github.com/pkeppeler/deepcharter/issues/935) The finale fight

**M6, uncharted**

- [#936](https://github.com/pkeppeler/deepcharter/issues/936) Gnawers and dreamed remixes

**No milestone**

- [#937](https://github.com/pkeppeler/deepcharter/issues/937) Tech-tree ideas pass (Super Motherload)
