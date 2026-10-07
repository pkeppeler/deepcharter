# Transmission script

The full script, settled 2026-10-07. Canon and context: [LORE.md](../LORE.md). Text inside quote blocks is **player-facing**; everything else is for the team.

## Rules

- **Depth triggers** fire once per charter, the first time any member reaches that zone (the charter's deepest point). Breach triggers fire on a charter's first crossing.
- **Event triggers:**
  - Terminal repairs are world-wide, so a charter founded after a repair receives that repair's transmission when it is founded, queued in order.
  - Finale events are per charter.
- **Delivery:** every transmission goes to the whole charter. When several fire at once, they queue in ID order.
- **Template fields** are filled from the charter: `[CHARTER]`, `[DIRECTOR]`, `[CREW]` (a member's name; the Lattice cycles through all of them), `[DEATHS]` (the charter's Continuity Events).
- **Bonuses** are tiers B1–B4, priced in the numbers pass. Only Personnel pays bonuses, and a misfiring template still pays. The Chairman's receipts are flavour text, not money.
- **The echo rule:** in layer 5, every Company header gets a second line with the sender reversed: `▌ECHO — .OC & MOLOC .H`.
- **Framing:**
  - Live: `▌INCOMING — <sender>` … `▌END OF MESSAGE`
  - Relays, a wreck's beacon repeating a dead crew's last words: `▌AUTOMATIC RELAY — POD <serial> — LOOP <n>` … `▌RELAY RESTARTS`. Relays repeat once a day from the moment they were recorded, so the loop counter is the number of days since.
- **Senders:** `PERSONNEL, H. COLOM & CO.` (Joy), `POD MOLE-0117` (Hal, live), `[SOURCE UNKNOWN]` (fragments and Roz), `[MANY SOURCES]` (the Lattice), `THE CHAIRMAN`, `H. COLOM & CO.` (system), `CHECKPOINT 6, H. COLOM & CO.`, `HEAD OFFICE`.
- **Milestones:** T01–T18 ship with the vertical slice (M2). The rest arrive with their layers.

## Act 1: the Claim and the Old Workings

**T01 · Event: charter founded · Personnel**
> Welcome to H. Colom & Co., and congratulations on founding [CHARTER]! You'll find Prosperity a little quiet. Our terminals have been offline since the Night Shift, and we're counting on you to bring them back. Your Employee Handbook has been issued and bound to you. It can't be lost, so don't try! Chapter 1 will get you started.
>
> We're so glad you're here. Have a productive shift!
>
> — Joy, Personnel, on behalf of Mr. H. Colom, Chairman

**T02 · Event: fuel pump repaired · Personnel · B1**
> The fuel pump is back online, which means YOU are officially in business! A signing bonus has been credited to your charter. Fuel is the lifeblood of every Colom pod, so fill up often. Have a productive shift!

**T03 · Event: ore processor repaired · Personnel**
> The ore processor is working again! Every ore you sell now travels by Conduit to Head Office for refining. You may hear it from time to time. That's just the sound of Prosperity!

**T04 · Event: upgrade terminal repaired · Personnel · B1**
> The upgrade terminal is open for business! Better parts mean deeper work, and deeper work means better pay. Remember: all components remain the property of H. Colom & Co., licensed to your charter for its exclusive use. Please don't scratch off the serial numbers.

**T05 · Event: each further terminal repaired · Personnel** (rotate)
> Another terminal back online! Prosperity thanks you.

> Prosperity is getting brighter every day!

> That's one less thing in the dark!

**T06 · Event: Mole repaired (first charter) or bought (later charters) · Personnel · B1**
> The Mole is flying again! She was the first pod Prosperity ever put to work, and she's served more crews than we can count. Take good care of her. The ground beneath the colony is full of opportunity, and it's all yours to dig.

**T07 · Layer 1 entered · Personnel · B1**
> Your first descent! Personnel is proud of you. A first-descent bonus has been credited to your account. Every foot down is a foot closer to retirement!

**T08 · L1 Stone Benches · Hal** (`▌INCOMING — POD MOLE-0117`)
> Well, I'll be. Another signal down here. Thought I was the last one working this rock. Name's Hal. Hal Brennan. Twenty-two years on the Claim, and Friday I'm done. If you need anything, sing out. I'm usually somewhere below you.

**T09 · L1 Stone Benches, lower half · Personnel · B2**
> Look at you go! [CHARTER] is already outpacing projections. A productivity bonus has been credited. Personnel would like to remind you that unexplained sounds in the Claim are almost always settling rock.

**T10 · L1 Deep Claim · source unknown**
> `▌INCOMING — [SOURCE UNKNOWN]`
>
> …don't look at the walls when it goes quiet… ▒▒▒ …keep the lamp on, keep the…
>
> `▌SIGNAL LOST`

**T11 · Breach 1→2 · Personnel · B2**
> Congratulations on your first breach! You're now working the Old Workings, Prosperity's original mine levels. Our sensors report some seismic activity at this depth, along with a little radio interference. Any garbled transmissions you receive are atmospheric. Please disregard them!

**T12 · L2 Upper Levels · relay** (`POD MOLE-0388 — LOOP 14,002`)
> …all hands, they said. All hands to Head Office, triple pay. Nobody's ever been to Head Office. Benny's gone ahead without his lamp. Why would he leave his lamp. Why are we all going down.

**T13 · L2 Upper Levels · Hal**
> Kid, piece of advice. If you stop to rest down here, don't leave her dark. Keep the lights on. Things come to dark pods. …Listen to me, an old man fussing. Friday I'm done.

**T14 · L2 Shift Change · Personnel · B2**
> You've found Shift Change, our historic underground rail hub! It's a little dusty, but it's a proud part of Prosperity's heritage. Please do not punch any time cards. All shifts are currently accounted for.

**T15 · L2 Shift Change · relay** (`POD PROSPECTOR-0002 — LOOP 15,048`)
> Tomas, if you can hear this, turn your lamp on. Please. I've left the pod at the end of the rail line with the lights on for you. Turn your lamp on and walk toward them.

*(When the crew finds the Prospector, one of its lights is still burning.)*

**T16 · L2 Prospector's Run · Hal**
> There's somebody walking the old rail line, kid. No lamp. I've seen him for years. He's never hurt anybody. But don't follow him. He's not going anywhere you want to go.

**T17 · Event: Prospector salvaged · Personnel · B3**
> Wonderful work recovering Company property! The Prospector has been re-registered to [CHARTER]. With a navigator's seat, she'll take you deeper than the Mole ever could. Your onboarding is now complete. Further documentation is restricted to Senior Personnel, and we just know you'll get there!

**T18 · Breach 2→3 · Personnel · B3**
> Welcome to the Fungal Hollows! You're the first crew to work this deep in a very long time. Your Employee Handbook has been updated (Rev. 2). Please take a moment to review the changes. Remember: the Continuity Plan returns you to work at no cost to you!

## Act 2: the Hollows, the Drowned Deep, the Lattice

**T19 · L3 the Garden · Personnel · B3** (pays despite the misfire: the lure never goes dark)
> Congratulations, [CREW_NAME], on reaching [DEPTH_MILESTONE]! As a token of our appreciation, [BONUS_AMOUNT] has been credited to [ACCOUNT]. Have a productive [SHIFT_TYPE]!

*(The bracketed fields are printed literally. This is the misfire.)*

**T20 · L3 the Garden · Hal**
> Big night Friday, kid. They're throwing me a send-off at the Lamp & Pick. Whole shift's coming, and old Benny's buying. You should come up. …You know the Lamp & Pick? By the bandstand?

*(The Lamp & Pick is a burned-out shell. Only the bandstand's footings remain.)*

**T21 · L3 Spore Fields · relay** (`POD BADGER-0031 — LOOP 14,720`)
> We're not coming up. You can tell Personnel we quit. It's quiet down here, and nobody's counting anything. It doesn't hurt. It doesn't hurt at all.

**T22 · L3 the Quiet · no sender**
> `▌INCOMING —`
>
> `▌END OF MESSAGE`

**T23 · Breach 3→4 · Personnel · B3**
> You've reached the Drowned Deep! Our records show some flooding at this depth, but pressure readings are within acceptable limits. If water enters your pod, please report it to your Director.

**T24 · L4 the Seep · Personnel**
> A reminder from Personnel: Company altimeters are certified to Layer 5. Readings below Layer 5 are provided for entertainment purposes only.

**T25 · L4 the Drowned Road · relay** (`CONVOY NINE — ALL PODS — LOOP ▒▒,▒▒▒`; many voices, slightly off-key)
> *Low and low the long road goes,*
> *lamp in hand and none to see.*
> *Deep the water, dark the stone —*
> *hold the light, and hold to me.*

**T26 · L4 the Drowned Road · relay** (`POD HAULER-0009 — LOOP 14,002`)
> Convoy Nine to Prosperity. Shaft's flooding behind us and ahead. We can't go back, and we won't go on. All pods sealed. We're going to sing for a while. If anybody comes looking, tell them we sang.

**T27 · L4 Black Water · Hal**
> Kid? You're getting deep. Me too. Funny thing. Don't remember coming down this far. Can't see my own lamp. Must've left it on the pod. …Friday, though. Friday I'm done.

*(His Mole lies nearby. Its log, Note N18, ends on the Friday of the Night Shift.)*

**T28 · L4 the Basin · Personnel**
> Your charter's Continuity Events to date: [DEATHS]. Thank you for your continued service! Remember, Continuity Events are recorded for training purposes. Recorded for training— recorded for training— recorded for training purposes.

**T29 · Breach 4→5 · Personnel · B4**
> Welcome to the Lattice! Staff who reach Layer 5 are eligible for our Senior Personnel—
>
> `▌TEMPLATE ENDS`

**T30 · L5 Geode Fields · Personnel · B4** (the first echo)
> `▌INCOMING — PERSONNEL, H. COLOM & CO.`
> `▌ECHO — .OC & MOLOC .H ,LENNOSREP`
>
> Beautiful, isn't it? A friendly reminder that drilling noise carries a long way in the Lattice. Please work quietly. The geology appreciates it!

**T31 · L5 Singing Halls · many sources**
> …please disregard… …turn your lamp on… …all hands to Head Office… …hold the light… …Friday… …please disregard…

**T32 · L5 the Switchboard · many sources, in Hal's voice**
> [CREW]? [CREW]? That you, kids? I can hear you all the way down here. Everybody can hear you down here.

**T33 · L5 the Switchboard · Personnel, Template 0**
> This is Joy. This one isn't a template. The Chairman says after Friday I'll be unavailable, so I've spent the week recording the rest: the welcomes, the bonuses, the congratulations. I'm sorry about all of them. Every bonus is bait. Whatever he offers you at the bottom, don't sign it. Keep your lamp lit. Oh, it's still recording, isn't it. I'm—
>
> `▌END OF TEMPLATE`

**T34 · Breach 5→6 · H. Colom & Co.**
> Your account has been escalated. Personnel will no longer be handling [CHARTER]. The Chairman will handle your charter personally.

## Act 3: the Magma Belt and the Ossuary

**T35 · L6 Cinder Crust · the Chairman** (his first notice)
> Good evening. I am H. Colom. I have taken a personal interest in your charter. You have done excellent work, and you have done it a great deal deeper than anyone authorised. I am a patient man. I would ask you now to return to the surface, where the work is plentiful and the air, for now, is free.
>
> Your latest delivery has been credited to your account.

**T36 · L6 the Arteries · source unknown (Roz)**
> You found my notes. Good. My name's Roz. I was shift steward, back when there was a shift. He'll be polite for a while. He's always polite until he isn't. Keep your lamp lit, and don't take anything he hands you. —R.

**T37 · L6 the Arteries · the Chairman** (second notice)
> You are below your authorised depth. Return to the surface at once, or your employment will be consumed. Concluded. Your employment will be *concluded*.
>
> Your latest delivery has been credited to your account.

**T38 · L6 Checkpoint · Checkpoint 6**
> HALT. YOU ARE ENTERING A RESTRICTED AREA. PRESENT YOUR AUTHORISATION. … Authorisation not found. … Authorisation not found. … Please proceed. Have a productive shift.

*(The Chairman orders you home and his own checkpoint waves you through: two wills. The third notice is the Notice of Disciplinary Action in handbook Rev. 3.)*

**T39 · Breach 6→7 · the Chairman**
> I have sent you three notices. You have ignored them all. The last man to hold your position ignored his notices, too. I have him still. Turn back.
>
> Your latest delivery has been credited to your account.

**T40 · L7 Rib Vaults · the Chairman**
> I built this Company out of men like you. Men with good hands and no patience. I know exactly what you want, because I wanted it. Turn back while you still want it.
>
> Your latest delivery has been credited to your account.

**T41 · L7 Old Furnaces · source unknown (Roz)**
> Count the masks. Every one of them was a man who came down here, same as you. Same as him. It isn't an initial, you know. It's the end of his name. —R.

**T42 · L7 the Watching Wall · relay** (`POD BEHEMOTH-0001, SURVEY ONE — LOOP 22,281`)
> Lights are down, we're dark, we're— they're open. THEY'RE OPEN. ALL OF THEM. THE EYES ARE OPEN. Sir, put the lights back on. Sir, please. Sir, they're looking at—

**T43 · L7 the Watching Wall · the Chairman**
> Reports of eyes in the Ossuary are a morale matter and will be noted in your file. There are no eyes in the Ossuary.
>
> Your latest delivery has been credited to your account.

**T44 · L7 Floor of Bones · relay** (`POD BEHEMOTH-0001, SURVEY ONE — LOOP 22,280`)
> Survey One to Head Office. We've reached the bottom of the bones. Sir, the floor is warm. Sir, it's beating. This is the motherload. This is—

**T45 · L7 Floor of Bones · the Chairman**
> FINAL NOTICE. You are hereby relieved of— You are— Do you know how long I have sat in that chair? Sixty-one years. Sixty-one years, and you think you can simply walk in and— Turn back. TURN BACK.
>
> Your latest delivery has been credited to your account.

**T46 · Breach 7→8 · the Chairman**
> Very well. Come down, then. I'll see you in the Boardroom.

## Finale: the Furnace

**T47 · L8 Intake · Personnel** (Joy's last template, played automatically)
> Welcome to Head Office! Smile, you're on Company premises.
>
> — Joy, Personnel

**T48 · L8 Intake · Hal**
> Kid? That you? Well, pull up a pick. Shift's nearly over. Feels like it's been nearly over a long time.

**T49 · L8 the Works · source unknown (Roz)**
> I'm here. Third line from the fire. Mine's the lamp that's still lit. Whatever happens in that Boardroom, don't sign. —R.

**T50 · Event: Boardroom entered · the Chairman** (the fight starts when the message is closed)
> `▌INCOMING — H. COLOM` *(the sender's letters slide, one at a time, into)* `▌INCOMING — MOLOCH`
>
> So. You came anyway. Do sit down. No? Then let me tell you what you are standing in. Everything you ever sold me is here. Every ore you hauled up those shafts came down that pipe to me. I made it into a body, and I made that body into a Company, and I made that Company into you. You are standing in your own wages. Now. Let us discuss your termination.

**T51 · Event: phase 1 ends, Head Office stands · Head Office**
> THERE IS NO ONE IN THE CHAIR. THERE IS ONLY THE CHAIR.

**T52 · Event: Head Office broken; the Controlling Interest drops · the Chairman**
> …Every notice said turn back. You came anyway. That is what we look for in a Chairman. The position is yours. Take it. All of it. The money, the pods, the men in the lines. Sign, and none of it will ever stop.

**T53a · Event: Controlling Interest signed**
> `▌INCOMING — H. COLOM & CO.`
> Welcome to the Board, Chairman [DIRECTOR]. Your first dividend has been credited. Production must increase.

> `▌INCOMING — POD MOLE-0117`
> Is it Friday?

> `▌INCOMING — H. COLOM & CO.`
> No.

**T53b · Event: Controlling Interest burned**
> `▌INCOMING — POD MOLE-0117`
> Kid? Floor's gone quiet. …Is it Friday? Feels like Friday.
>
> `▌CHANNEL CLOSED — POD MOLE-0117 — 38 YEARS, 4 MONTHS`

*(Nothing covers the heartbeat or the eye. The silence is the point.)*

## Post-game

**T54 · Event: first time below the Ramp**

- Renounced, source unknown (Roz):
  > You came after me. Good. There's more of them down here, and they don't bother with Companies. They just eat. Keep your lamp lit. —R.
- Signed, H. Colom & Co.:
  > QUOTA NOTICE: production at this depth is below target. Descend.

**T55 · Event: each dividend, signed charters only · H. Colom & Co.** (rotate)
> Dividend credited. Production must increase.

> Dividend credited. Head Office is hungry.

> Dividend credited. It is not Friday.

**T56 · Event: splice ("the depths moved") · system, every charter**
> `▌SEISMIC EVENT — CHARTS INVALID`
>
> The depths have moved.

Renounced charters then receive, from source unknown (Roz):
> It turned over in its sleep. Something new has opened below the Furnace. —R.
