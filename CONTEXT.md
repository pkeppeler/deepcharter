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

**Depth**:
How far down a place is, on one axis that runs through every dimension of the campaign. A layer is defined by depth, not by dimension.
_Avoid_: Y (that is one dimension's own height)

**Layer**:
One link in the underground chain, with its own light, fog and sky. Its floor leads into the top of the next. A dimension can hold several layers, and one layer can span two dimensions.
_Avoid_: level, biome, dimension (that is the implementation)

**Campaign world**:
The overworld, the first dimension of the stack: the surface as its top band, then as many layer bands as fit, down to y -2032. Crossing a breach inside it has no teleport.

**Deep dimension**:
A tall dimension below the campaign world, holding the next depth. Not built yet.
_Avoid_: layer dimension, chain world

**Band**:
A layer's slice inside one dimension: a fixed range of Y. The surface and unbuilt depth are bands too.
_Avoid_: level, floor

**Unbuilt depth**:
The plain rock of the campaign world below the last built layer. Nothing is in it yet.
_Avoid_: void, empty space

**Seam**:
The boundary between two stacked dimensions on the depth axis, such as the campaign world and the first deep dimension, or the join to the uncharted worlds. It is a storage boundary, not a layer boundary: it can fall mid-layer, or coincide with a layer boundary and carry its crust, the seam crust (grained). How seamless it must be is open.
_Avoid_: portal, loading screen, dimension seam

**Arrival area**:
The radius-4 patch of the next dimension that the server loads under a vehicle approaching a seam, before it crosses. It is held by a timed `deepcharter:seam_arrival` ticket and lapses on its own once the approach ends.
_Avoid_: arrival pocket (the pocket is the carved air the fallback crossing lands in)

**Grained crust**:
A crust with a vertical grain that flexes on a slow pulse. Its rules are in the SPEC. Working name.
_Avoid_: no-build zone

**Decoy**:
A grained crust that is not on a seam, chosen from the world seed.
_Avoid_: fake seam

**Story layer**:
One of the roughly 8 hand-authored layers that make up the campaign.
_Avoid_: authored layer, main layer

**Uncharted layer**:
A layer in the uncharted worlds, below the ramp: a harder remix of a story-layer theme, with no limit on depth. The altimeter reads "UNCHARTED".
_Avoid_: endless layer, post-game layer

**Zone**:
A band within a layer where ore value, hazards and creatures step up.
_Avoid_: sub-layer

**Breach**:
The soft crust at a layer boundary, also called the breach crust. Any drill can pass, at a heavy cost. Crossing is a story event. A seam is not a breach.
_Avoid_: barrier, wall, gate

**Ramp**:
The stretch at the top of the first uncharted world, below the finale's seam, where drilling gets exponentially harder.
_Avoid_: floor, bedrock

**Splice**:
Adding a new story layer, as a new world at the seam above the Ramp, into the chain of existing worlds without losing anything built. A layer's content is fixed once any charter breaks into it.
_Avoid_: insert, migration

**Conduit**:
The undiggable Employer pipe that runs from the colony's ore processor straight down through every layer to the Furnace.
_Avoid_: pipe, pipeline, shaft

**Head Office**:
The Employer's factory complex in the Furnace. Its central bronze figure stands up as the final boss's second phase.
_Avoid_: factory, Refinery, Great Work

**Sleeper**:
The vast creature whose body is the deep. Player-facing text only hints at it: never a name, a gender or an explanation.
_Avoid_: Mother, god, Leviathan

**Moloch**:
The parasite in the Sleeper's heart cavity, in the shape of an idol, which cannot make anything and only takes. The Employer is its largest lure. Player-facing text names it only in layer 8.
_Avoid_: the devil, the boss (reserved for fights)

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

**Quest map**:
The handbook's view of its Directives: chapter tabs, and for each chapter a flowchart sheet you can drag and zoom.
_Avoid_: quest book, tech tree

**Pencil path**:
A route on the pencil sheet after the last chapter of the quest map, drawn by earlier miners when a charter finds a Note. It completes when the charter reaches the place the Note points to, and gives nothing but the place. It is not a Directive.
_Avoid_: side quest, Directive

**Parts catalogue**:
The Company viewer of items and recipes, beside inventory screens. It shows only the recipes the charter knows.
_Avoid_: recipe book, item viewer

**Known recipe**:
A recipe the charter has discovered, by a quest step that asks for its item becoming current, or by a member obtaining the item or an ingredient. Unknown recipes still craft.
_Avoid_: unlocked recipe

**Spec sheet**:
An item's page in the parts catalogue, such as a pad's power cost or a material's heat rating. It pops up the first time you craft or buy the item.
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

### Creatures

**Creature**:
Anything in the deep that moves of its own accord and is not a player or a pod. Every creature is a mob or a presence.
_Avoid_: monster, enemy (in player-facing text)

**Mob**:
A creature that costs a player something, in the pod and on foot, and gives a reason to avoid it.
_Avoid_: enemy, hostile

**Presence**:
A creature that never acts, such as THE EYES or the checkpoint wardens. It costs nothing but attention.
_Avoid_: ambient mob, NPC

**Family**:
One of the three kinds of creature: Moloch's, the Sleeper's defences, or the gnawers. A creature's family sets its draw and what it does to you.
_Avoid_: faction, type, class (means chassis)

**Draw**:
The one thing that brings a creature's family to you: the dark (Moloch's), the drill (the Sleeper's) or the haul (gnawers). In act 3 Moloch's machines are also drawn to the unauthorised.
_Avoid_: aggro, trigger

**Unauthorised**:
In act 3, a pod or person below the depth the Chairman allows the charter. It draws the Company's Recovery units.
_Avoid_: trespassing, out of bounds

**Alarm**:
A zone's rising response, for a few minutes, after one of the Sleeper's creatures is killed: more of them come to the drill, sooner.
_Avoid_: aggro, heat, wanted level

**Gnawer**:
A creature of the uncharted layers that comes to the haul and eats ore, in the rock, in a bay or in storage. Post-game only.
_Avoid_: eater, worm

**Layer boss**:
A fight in one of layers 3 to 7 that guards the layer's chassis wreck or catalyst. A charter can drill past it but fights it once to climb the upgrade ladder.
_Avoid_: miniboss, gatekeeper

**Shell**:
A building's exposed blocks taken together, closed doors included. A closed shell rated for the depth keeps everything inside it safe.
_Avoid_: wall, envelope, hull (a pod component)

**Exposed block**:
A placed block that touches open space a creature can reach from outside.
_Avoid_: unprotected block, outer block

**Damping block**:
A placed block that muffles the noise of what it encloses, so the Sleeper's defences are not drawn to it.
_Avoid_: soundproofing, insulation

### Story

**Night Shift**:
The night in Colony Year 23 when the Employer called every worker down and Prosperity emptied.
_Avoid_: the incident, the evacuation

**Retained**:
A miner rebuilt after death so many times that they went hollow and lampless, and still work in the deep. The word comes from the Employer's own records. In the game they are a normal mob of layers 1 and 2: they never attack, they come to a dark pod or a person in the dark, cling and drain (fuel, or hunger), and some carry a lamp (a lamp-carrier).
_Avoid_: zombie, undead, Long-Term Staff (the Employer's older euphemism)

**Dark pod**:
A pod with a pilot and power but no lights part that counts. A Retained comes to a dark pod; a lit pod is left alone.
_Avoid_: unlit pod, pod with the lamp off (a pod has no lamp switch)

**Continuity Event**:
The Employer's name for a death and the rebuild that follows it.
_Avoid_: respawn (in player-facing text)

**Relay**:
A transmission repeated once a day by a wreck's beacon, carrying a dead crew's last words.
_Avoid_: echo, recording

**Controlling Interest**:
The deed the final boss offers the charter. The Director alone can sign it; the whole online crew together can burn it. Either ends the charter's story.
_Avoid_: contract (means the Employment Contract)

**Renunciation**:
Burning the Controlling Interest in the Furnace, which takes it only when every online charter member lets go together. The true ending: it kills the final boss and costs the charter its whole account.
_Avoid_: good ending, sacrifice

**Unnumbered Lamp**:
The suit lamp every crew member receives at Renunciation. It never runs out.
_Avoid_: Hal's lamp, infinite lamp
