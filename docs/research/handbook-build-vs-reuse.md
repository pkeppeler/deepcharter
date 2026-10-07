# Deep Charter handbook: build vs. reuse

Research as of 2026-10-06, updated for the narrowed scope.

**Context.** Minecraft Java 26.3 came out on 2026-09-15. Fabric API for 26.3 exists (0.162.0+26.3, 2026-10-06), so the 26.3 mod ecosystem is only about three weeks old.

## Short answer

- **A quest mod is now overkill, and none is usable anyway.** The new scope (20–40 linear objectives, no rewards, plus a lore section) uses none of what quest mods are good at: rewards, reward tables, in-game quest-canvas editors, a large set of task types, party GUIs. Every serious quest mod is also blocked on version or on distribution.
- **Recommendation: build our own, but keep it thin.** Use hidden vanilla advancements to detect events, and write our own charter state and our own handbook screen. **Modonomicon is the runner-up and the fallback.** It is the only book mod with a Fabric 26.3 build. Run a 2–3 day spike on it first if shipping sooner matters more than full control of the look.

## Comparison table

| Option | Fabric 26.3 / 26.2 | Licence, and can we bundle it in a Modrinth pack? | Re-theme depth | API for our code | Team sharing | Data-defined | Maintenance | Fit for the new scope |
|---|---|---|---|---|---|---|---|---|
| **FTB Quests (+ FTB Library/Teams)** | No. Newest is 26.1.2 (26.1.2.8, 2026-09-15). The `main` branch targets 26.1.2, and its NeoForge dependency range stops below 26.2. | All Rights Reserved. **Not on Modrinth.** A Modrinth issue (2025-05-09) quotes FTB policy as forbidding embedding in packs outside FTB and CurseForge. The .mrpack format can only download from Modrinth CDN, GitHub and GitLab. **Effectively blocked.** | Strong for a quest mod. A resource-pack theme file sets textures, icons and colours per quest, chapter or tag. The layout is fixed. | Thin official API. Custom task and reward events exist. `TaskTypes.register` sits outside the stable API package, and the javadoc says those classes may change. | Native, through FTB Teams | Yes. JSON5, one file per chapter (SNBT dropped in 26.1.2.1) | Very active | Overkill and blocked |
| **Odyssey Quests (formerly Heracles, Terrarium)** | No. 2.0.0–2.0.2 **beta for 1.21.1 only** (2026-10-04/05). The repo has no 26.x branch. | MIT. Bundling is fine. Needs Resourceful Lib, which does have 26.3. | Theme JSON is **colours only** (verified in source). GUI sprites can be replaced with a resource pack (inferred from the asset layout). | `QuestTaskType`/`QuestRewardType` registries in its API package | `TeamProviders`. The team mod has to supply the provider (Odyssey Allies) | Yes. One JSON file per quest in config | About a two-year gap (1.1.13 in June 2024, then 2.0 beta). 66 open issues | Overkill. We would have to port it ourselves |
| **Better Questing** | No. Forge only (1.20.1 4.x preview, last update 2025-03-05; 1.12.2) | 4.x is All Rights Reserved. The GTNH 1.7.10 fork is MIT. | Expansion themes | Forge API | Parties | JSON | Mostly stalled | Out |
| **Small 26.x quest mods** (Questry, Ion Quests) | 26.2 only | All Rights Reserved, no source | Unknown | Unknown | Unknown | JSON in datapacks | 163 and 661 downloads | Out (too immature) |
| **Patchouli** | **26.1 beta only** (26.1-94-beta, 2026-07-10). No 26.2/26.3 branch. Last commit 2026-07-07. | CC-BY-NC-SA-3.0. Fine for a free pack served from the Modrinth CDN. | Three textures (book, filler, crafting) plus colours. Fixed layout. Templates for custom pages. | Global config flags (not per player). Can open a book or entry. Per-player gating only through advancements. | None. Our code would have to award advancements to every charter member. | Yes, JSON | Slow. 223 open issues | Weak. Blocked on 26.3. Read marks are stored **client-side** in `patchouli_data.json`. |
| **Lavender (owo-lib)** | No. Last release is for 1.21.4 (2025-01-20); repo last pushed 2025-09-10. owo-lib has 26.2 (2026-08-19) but no 26.3. | MIT | Probably very deep through owo-ui (not verified for current versions) | Advancement unlocks | None | Markdown/JSON | Dormant | Blocked |
| **Modonomicon** | **Yes.** 26.3-2.16.0 (2026-10-02). First 26.3 build was 2026-09-17, followed by 20 releases. | Code MIT. Assets: CC-BY-SA-4.0 per the README, but Modrinth lists "MIT AND CC-BY-4.0" (a mismatch). Bundling is fine. | Every chrome sprite can be replaced, plus palette, layout offsets and connection rendering. Custom `BookTheme` types. Custom page renderers (`PageRendererRegistry`). | A "research" system with facts, values, nodes and stages. Built-in hooks for item acquired/crafted, advancement and entry viewed. Custom trigger types via `TriggerTypeRegistry.register` with a login `replayAll`. `ResearchStateManager.grantFact/isNodeUnlocked`. A runtime API to add entries in code (useful for lore). | **Per player only.** The state services are hard-wired final statics, so charter sharing needs our own fan-out. | Yes: JSON in datapacks. The docs steer research authoring toward datagen. | One maintainer, commits daily, 7 open issues. **API churn:** 2.5 to 2.16 in about 15 days. | **Best reuse candidate** |
| **GuideME** | No. **NeoForge only** (26.3.1-alpha, 2026-10-02). Some third-party pages claim Fabric support, but Modrinth lists none. | LGPL-3.0 | Markdown plus 3D scenes | n/a | n/a | Markdown | Active | Out |
| **Booklet (Patbox)** | Yes (26.3) | LGPL-3.0 | Server-side; needs Polymer and a server resource pack | Data-driven | n/a | Yes | Small (890 downloads) | Wrong UI model for a custom client screen |
| **Build our own** | We control it | Ours | **Total** | Ours | **First-class charter state** | Ours (JSON in datapacks) | Ours | **Recommended** |

## Why build our own now

1. **The look is the product.** Modonomicon's theme surface stops at sprites, palette and layout numbers. The `BookTheme` interface only returns sprites and integers. The two-page spread and its fixed buttons (search, bookmarks, read-all) stay. Margin notes and redaction would need custom page renderers, and a CRT effect would likely need mixins into its screen. That is the same screen work, done against someone else's internals.
2. **Charter-shared state is native in our model.** In Modonomicon it is a fan-out workaround, because state is per player and the services can't be swapped.
3. **The narrowed scope no longer needs a book mod's heavy features**, such as recipe or multiblock pages and search.
4. **Most of the trigger work is free.** Hidden vanilla advancements already define data-driven criteria in JSON: `inventory_changed`, `placed_block`, `item_used_on_block` (for repairs), `changed_dimension`, `recipe_crafted`, plus `impossible` or our own criterion for code triggers. They can be hidden and toast-free.
5. **Fewer upgrade risks.** We avoid depending on Modonomicon's non-API classes (`research.state.*`, `client.render.page.*`) while they churn.

**What would flip the decision to Modonomicon:** a 2–3 day spike that shows all three of these:

- (a) a custom `BookTheme` gives a convincing paper-handbook or terminal look;
- (b) a custom page renderer can draw margin notes and redaction;
- (c) a custom trigger type whose `replayAll` reads our charter save data keeps charter members in sync on login and when someone joins.

If all three pass, reuse saves about 2–4 weeks. In that design, objectives are entries in index (list) mode, and conditions swap a "PENDING" page for a "COMPLETED" page. Lore notes use an `item_acquired` hook (matching on an item data component) or our code, which unlocks entries in a back "Lore" category. Read marks are already per player and server-side.

## Likely effort

One experienced Fabric developer; my judgement, not measured.

- **Build our own:** about 5–8 weeks.
  - Data model, codecs and reload listener: 3–4 days.
  - Advancement-backed triggers plus custom criteria: 3–5 days.
  - Charter save data, per-player read marks and network sync: 4–6 days.
  - Handbook screen (pagination, table of contents, stamps, margin notes, redaction, CLASSIFIED locks, CRT mode): 2–4 weeks. This is the time sink.
  - Dedicated-server testing and commands: 3–5 days.
  - Then each game drop costs us a port.
- **Modonomicon plus our glue layer:** about 3–4 weeks of development plus art.
  - Theme sprites and palette: about 1 week.
  - Custom page renderers: 3–5 days.
  - Charter fan-out and replay trigger: 3–5 days.
  - Custom triggers: 2–3 days.
  - A real CRT shader would add 1–2 weeks and is risky.
  - After that, we track its API churn.
- **Patchouli or Lavender:** about 2–3 weeks once 26.3 builds exist. Today they would need a port of the mod (and of owo-lib for Lavender), and theming stays weaker.
- **A quest mod:**
  - FTB Quests is blocked on Modrinth no matter the effort.
  - Porting Odyssey (1.21.1 to 26.3, plus its UI library) is 3–6+ weeks and leaves us with a fork to maintain.

## Unverified

- **Version plans:** whether and when FTB, Odyssey, Patchouli or Lavender will ship 26.2/26.3 builds. No branches exist as of 2026-10-06.
- **FTB policy:** the wording comes from a third-party issue, not from an FTB policy page.
- **Modonomicon licence:** the asset licence mismatch noted in the table.
- **Modonomicon theming:** whether a custom `BookTheme` can render beyond sprites. That is inferred from the interface; the spike would settle it.
- **Odyssey details:** whether Odyssey Allies supports 1.21.1, and that Odyssey's GUI sprites can be overridden with a resource pack.
- **Lavender:** how far owo-ui theming goes.
- **GuideME:** its Fabric support claims.
- **Effort:** all figures are estimates.
- **Open design decision for us:** are lore unlocks per player or per charter?

## Sources

Accessed 2026-10-06; publication dates where known.

- Minecraft 26.3 release on 2026-09-15: https://space-node.net/blog/minecraft-26-3-server-update-guide-2026 ; Fabric blog posts for 26.3 (2026-09-15) and 26.2 (2026-06-15): https://fabricmc.net/blog/
- FTB Quests Fabric file list and licence (latest 2026-09-15): https://www.curseforge.com/minecraft/mc-mods/ftb-quests-fabric/files/all ; repo `gradle.properties`, theme and API files, CHANGELOG (JSON5 switch in 26.1.2.1): https://github.com/FTBTeam/FTB-Quests
- FTB and Modrinth restriction: https://github.com/modrinth/code/issues/3631 (2025-05-09) ; https://modrinth.com/mod/ftbchecker ; .mrpack allowed domains: https://support.modrinth.com/en/articles/8802351-modrinth-modpack-format-mrpack
- Third-party FTB Quests fixes: https://modrinth.com/mod/ftb-quests-freeze-fix (2024-10-02) ; https://modrinth.com/mod/ftb-quests-optimizer (2026-04-14) ; https://modrinth.com/mod/quests-teams-fixes (2026-09-09)
- Odyssey Quests versions (2.0 beta, 2026-10-04/05): https://api.modrinth.com/v2/project/odyssey-quests/version ; source: https://github.com/terrarium-earth/Heracles (branch 1.21.1) ; https://modrinth.com/mod/odyssey-allies ; https://modrinth.com/mod/resourceful-lib
- Better Questing: https://www.curseforge.com/minecraft/mc-mods/better-questing (2025-03-05) ; GTNH quest workflow: https://github.com/GTNewHorizons/GT-New-Horizons-Modpack/blob/master/config/betterquesting/Readme.md
- Patchouli: https://modrinth.com/mod/patchouli (2026-07-10) ; https://github.com/VazkiiMods/Patchouli (branch 26.1) ; https://vazkiimods.github.io/Patchouli/docs/reference/book-json ; https://vazkiimods.github.io/Patchouli/docs/reference/entry-json
- Lavender: https://modrinth.com/mod/lavender (2025-01-20) ; owo-lib: https://modrinth.com/mod/owo-lib (2026-08-19)
- Modonomicon: https://modrinth.com/mod/modonomicon (2026-10-02) ; docs on the `documentation` branch (research, custom-hooks, theme, runtime-extending) and source on branch `version/26.3`: https://github.com/klikli-dev/modonomicon
- GuideME: https://modrinth.com/mod/guideme (2026-10-02) ; Booklet: https://modrinth.com/mod/booklet (2026-09-11) ; Questry and Ion Quests on Modrinth (2026-08 and 2026-06) ; OPAC Fabric 26.3 (2026-10-05, LGPL-3.0): https://modrinth.com/mod/open-parties-and-claims
- Vanilla advancement triggers and hidden or no-toast display: https://minecraft.wiki/w/Advancement_definition
