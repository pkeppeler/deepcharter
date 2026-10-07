# Deep Charter

A co-op Minecraft total conversion in the spirit of Motherload: drill ever deeper in a pod, sell ore, upgrade, and find out what the employer wants down there.

## Language

### Organisation

**Charter**:
A player organisation issued by the employer, with its own account, pods, components, outposts, pads and story progress. Charters run in parallel and are friendly.
_Avoid_: company, team, guild, faction, outfit

**Director**:
The player who founded a charter and approves who joins it.
_Avoid_: owner, leader, admin

**Crew**:
Players signed on to a charter, other than its Director.
_Avoid_: members, team

**Employment Contract**:
The agreement a player signs to join a charter as crew, approved by the Director. Its fine print matters to the story.
_Avoid_: membership, contract (alone, it is ambiguous)

**Employer**:
The in-fiction company, H. Colom & Co., that issues charters, sets work orders and sends transmissions. It is the lure of the parasite in the Furnace.
_Avoid_: boss (reserved for fights), Natas (the original's character)

**Chairman**:
The Employer's head, "H. Colom": a title worn by whoever last signed a Controlling Interest. The current one is the final boss's first phase.
_Avoid_: CEO, Mr. Colom, Director (a charter's founder)

### World

**Surface**:
The vanilla-style frontier world above the layers, with no settlements besides the colony.
_Avoid_: overworld

**Colony**:
Prosperity, the one derelict mining colony per world, at spawn. Its terminals are repaired once for every charter.
_Avoid_: town, base, hub

**Terminal**:
A repairable machine where you sell ore, buy fuel and upgrades, repair, or sign contracts.
_Avoid_: shop, store, vendor

**Layer**:
One link in the underground chain, with its own light, fog and sky. Its floor leads into the top of the next.
_Avoid_: level, biome, dimension (that is the implementation)

**Story layer**:
One of the roughly 8 hand-authored layers that make up the campaign.
_Avoid_: authored layer, main layer

**Uncharted layer**:
A layer below the ramp: a harder remix of a story-layer theme, with no limit on depth. The altimeter reads "UNCHARTED".
_Avoid_: endless layer, post-game layer

**Zone**:
A band within a layer where ore value, hazards and creatures step up.
_Avoid_: sub-layer

**Breach**:
The soft crust at a layer boundary. Any drill can pass, at a heavy cost. Crossing is a story event.
_Avoid_: barrier, wall, gate

**Ramp**:
The stretch at the bottom of the last story layer where drilling gets exponentially harder.
_Avoid_: floor, bedrock

**Splice**:
Adding a new story layer into the chain of existing worlds without losing anything built. A layer's content is fixed once any charter breaks into it.
_Avoid_: insert, migration

**Conduit**:
The undiggable Employer pipe that runs from the colony's ore processor straight down through every layer to the Furnace.
_Avoid_: pipe, pipeline, shaft

**Head Office**:
The Employer's factory complex in the Furnace. Its central bronze figure stands up as the final boss's second phase.
_Avoid_: factory, Refinery, Great Work

**Sleeper**:
The vast creature whose body is the deep. Never named, gendered or explained in player-facing text.
_Avoid_: Mother, god, Leviathan

**Motherload**:
The Sleeper's heart, below the Ramp. The miners' word for the strike of a lifetime.
_Avoid_: mother lode, core

### Pods

**Pod**:
A vehicle with seats that drills, flies and hauls ore. It has no walkable interior while moving.
_Avoid_: digger, vehicle, mech, ship (a drill is a component)

**Chassis**:
The pod's body, one of six: Mole, Prospector, Badger, Hauler, Crawler or Behemoth. It sets seat count and caps component tiers.
_Avoid_: hull (a component), model, frame

**Component**:
A tiered part installed in a chassis, such as the drill, hull or scanner. Bound to its charter.
_Avoid_: module, part

**Founding pod**:
The derelict Mole in the colony hangar that the first charter repairs.
_Avoid_: starter pod

**Robot**:
A component that fills the navigator or operator seat with less skill than a player.
_Avoid_: AI crew, bot

**Role**:
A seat's job: Pilot (movement), Navigator (sonar and target marking), or Operator (arm and charge launcher).
_Avoid_: station (ambiguous), job, class (means chassis)

**Anchor mode**:
The Behemoth parked and unfolded into a small walkable temporary outpost.
_Avoid_: deploy, camp

**Stranded**:
A pod that has run out of fuel: powered off and dark until rescued or given a reserve tank.
_Avoid_: dead, out of fuel

**Wreck**:
A destroyed or derelict pod at depth, with its cargo.
_Avoid_: corpse

**Salvage**:
Recovering a wreck's cargo, or towing the wreck home and paying to restore it.
_Avoid_: loot

**Tender run**:
One pod carrying fuel and empty cargo capacity down to a pod that keeps drilling.
_Avoid_: supply run

### Economy

**Ore**:
A physical, heavy, non-stacking item. The pod hauls it; carrying it on foot slows you sharply.
_Avoid_: mineral, resource

**Catalyst**:
A rare material from one specific layer, needed on top of money for every chassis and every couple of component tiers.
_Avoid_: key item, rare ore

**Work order**:
An employer request to deliver goods for a bonus, which can carry story beats.
_Avoid_: quest, bounty

**Transmission**:
A story message sent to the whole charter when its deepest point reaches a trigger, or on a story event.
_Avoid_: message, radio call

**Records board**:
The colony board that ranks each charter on deepest breach, wealth hauled, wrecks salvaged and so on. No single score.
_Avoid_: leaderboard

**Bootstrap**:
The opening phase of normal Minecraft crafting, ending when the colony's terminals and the founding pod are repaired.
_Avoid_: early game, tutorial

### Depth infrastructure

**Outpost**:
A charter-built station at depth with terminals, pads, beds and storage. It sells at worse rates than the colony.
_Avoid_: base, station, checkpoint

**Pad**:
A teleporter you build, paired with another pad. It carries players and pods with empty cargo; nothing mined teleports.
_Avoid_: teleporter station, warp

**Rating**:
A depth's environment requirement (heat, pressure, flooding, quake bracing). An outpost built below it is uninhabitable but never destroyed.
_Avoid_: resistance, tier

### On foot

**Suit**:
The wearable gear for leaving a pod, upgraded separately from it.
_Avoid_: armour

**Suit timer**:
How long you can stay on foot outside a pod or habitable outpost.
_Avoid_: oxygen meter, stamina

### Guidance

**Employee Handbook**:
The company-issued onboarding manual, bound to the player when they sign their Employment Contract. It teaches the bootstrap and layers 1-2, then fills with Notes.
_Avoid_: questbook, guidebook, tutorial

**Directive**:
One of the handbook's objectives, completed automatically from game events. It gives no reward.
_Avoid_: quest, task, objective

**Spec sheet**:
A short explanation shown the first time you buy or craft something, such as a pad's power cost or a material's heat rating.
_Avoid_: tooltip, wiki

**Note**:
A found piece of lore added to the handbook's back section, shared with every member of the charter.
_Avoid_: lore page, collectible

**Margin note**:
A previous miner's handwritten line printed beside the Employee Handbook's text, the same in every copy.
_Avoid_: annotation, comment

**Revision**:
A story-driven change to the Employee Handbook's tone or clauses. It never changes a rule.
_Avoid_: update, patch, version

### Story

**Night Shift**:
The night in Colony Year 23 when the Employer called every worker down and Prosperity emptied.
_Avoid_: the incident, the evacuation

**Retained**:
A miner rebuilt after death so many times that they went hollow and lampless, and still work in the deep.
_Avoid_: zombie, undead, Long-Term Staff (the Employer's euphemism)

**Continuity Event**:
The Employer's name for a death and the rebuild that follows it.
_Avoid_: respawn (in player-facing text)

**Relay**:
A transmission repeated once a day by a wreck's beacon, carrying a dead crew's last words.
_Avoid_: echo, recording

**Controlling Interest**:
The deed the final boss offers the charter. Signing it or burning it ends the charter's story.
_Avoid_: deed, contract

**Renunciation**:
Throwing the Controlling Interest into the Furnace. The true ending: it kills the final boss and costs the charter its whole account.
_Avoid_: good ending, sacrifice

**Unnumbered Lamp**:
The suit lamp every crew member receives at Renunciation. It never runs out.
_Avoid_: Hal's lamp, infinite lamp
