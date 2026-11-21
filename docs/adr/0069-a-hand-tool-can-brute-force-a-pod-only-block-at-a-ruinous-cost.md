---
status: accepted
---

# A hand tool can brute-force a pod-only block, at a ruinous cost

Amends SPEC section 9 (Hardness), [ADR 0050](0050-company-rock-can-be-blasted-and-the-drill-still-refuses-it.md) and [ADR 0052](0052-the-prospectors-best-drill-bites-company-rock-and-a-shaped-charge-clears-a-column.md). Issue #858. A player with a strong pickaxe broke breach crust and it "came back": the server cancelled the break, the client was not told, so it ghost-broke, and nothing said why. The user's rule: one intended way to play, but no hard block. A determined player can brute-force it, at a cost that makes it futile, and the game says what they are doing wrong.

## Decision

- **One rule, one place.** `HandBreak.isPodOnly(level, pos, state)` is true for the breach crust, Company rock (`deepcharter:undiggable`) and `deepcharter:deep_rock` where the block's layer is above 1. It replaces the three separate refusals (`BreachService`, `LayerRock`, `CompanyRock`). It lives in `layer/` because the layer owns the crust and the bands, and it reads only the level, so it works on the client. Creative players are exempt. Pod drills and charges remove blocks directly and never meet it.
- **Break time is constant.** For a non-creative player the destroy progress of a pod-only block is `1 / breakTicks` a tick (`breakTicks` 1200, 60 s) when the held item bites, and 0 otherwise. Efficiency, Haste, Mining Fatigue, water and footing are ignored.
- **The ghost-break fix.** A mixin on `BlockBehaviour.BlockStateBase.getDestroyProgress(Player, BlockGetter, BlockPos)`, at its head, returns that progress. The client's prediction (`MultiPlayerGameMode.continueDestroyBlock`) and the server's check (`ServerPlayerGameMode`) both read this method and it runs the same code on both sides, so the client never predicts a break the server refuses. A swing with a tool that cannot bite is refused on the client by an `AttackBlockCallback`, so no crack starts; the server's copy is the backstop.
- **Which tool bites, by layer.** The block's layer is its band's layer at its Y; the crust counts as the layer whose floor it is, unbuilt depth of the campaign world as the layer below the last built one, and the surface as layer 1. Layer 1: the item tag `deepcharter:hand_break/layer_1` (diamond and netherite pickaxes, the pickaxes correct for diamond-tier blocks). Layer 2 and deeper: `deepcharter:hand_break/layer_2_and_deeper` (netherite). Vanilla has no tool-material item tag for pickaxes, so the tags list the items, and a pack extends them.
- **Cost.** A block costs `round(share(layer) * maxDamage)` durability: 0.75 in layer 1, 0.9 in layer 2, 1.0 from layer 3, so the first breach costs most of a diamond pick and a deep block a whole netherite pick. It is spent evenly over the first `breakTicks - 1` ticks of mining, and the break pays the rest, so stopping early wastes what was spent. A pick that cannot pay shatters on the way, or at the break, and the block stays; a pick with exactly the cost is used up and the block goes.
- **Unbreaking cannot reduce it.** The tool is damaged with `ItemStack.setDamageValue`, never `hurtAndBreak`, the one path that runs the item-damage enchantment effects. Mending repairs from experience and never changes the cost. A test pins it with Unbreaking III and Mending on the pick.
- **Nothing drops.** The server takes over `ServerPlayerGameMode.destroyBlock` for a pod-only block and removes it with no drop, so brute force is never an ore exploit. Breaking the crust opens the way down as a bore does.
- **It suffers visibly.** While the player mines, the server sends electric-spark particles at the face and a grindstone sound on a cadence, and an action bar line, "Pod drill only: your pick is burning out". The first swing at a pod-only block per session (not saved) says in chat that a pod drill is the way and a pick burns itself out on it; with a tool that cannot bite it says the tool cannot bite it at all.
- **Tuning** is one record, `HandBreakTuning` in `LayerTuning`: `breakTicks`, the shares, the tool tags and the effect cadences.
- **Mixin targets** (the M2 rule): `BlockBehaviour.BlockStateBase.getDestroyProgress` at head, and `ServerPlayerGameMode` `tick` at tail (the cost and effects of each mining tick; no Fabric event reaches it), `handleBlockBreakAction` at head (a new swing restarts the count) and `destroyBlock(BlockPos)` at head (the paid break, no drop).

## Consequences

- The default build gets marginally easier: a diamond pick can pass Company rock in layer 1. That is fine: it costs 75% of a diamond pick a block, far more than a blasting charge or a drill bite.
- A client that sends its break early still pays the whole cost: the break settles what mining did not. The 0.7 leeway of vanilla's check can shorten the 60 s by 30% at most.
- The three by-hand refusals are gone, and with them `LayerRock` and `CompanyRock`. `deepcharter:deep_rock` moved to `HandBreak.DEEP_ROCK`.
