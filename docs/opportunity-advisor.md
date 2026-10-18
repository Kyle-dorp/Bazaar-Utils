# Opportunity advisor: scoring model

The flip list has four modes (Bazaar flips/dips, fusions, forge) plus book crafts, each ranked on its own. The advisor puts
them on one scale and answers one question: **what should I do with my purse and forge slots right now?** The answer is a
plan, not a sorted list: "fill 7 forge slots with X, put the remaining 30M into flips Y and Z", with a plain-text reason for
every pick and for the strong options it leaves out.

It is deterministic optimisation. Ranking by profit per hour and splitting a purse and slots between options is arithmetic
the calculators already half-do; an LLM would only be guessing at it. If one is added later, it narrates numbers this
produces, it doesn't choose.

Code, pure (no Minecraft, no network): `features/Opportunity.java` (common units, one factory per calculator),
`features/MarketShare.java` (your share of each market from the order book), `features/OpportunityAdvisor.java` (the
allocation and the text), `features/AdvisorConfig.java` (the settings). In game: `features/OpportunityFeed.java` (gathers
all four calculators' rows), `features/BazaarFlipRows.java` (BazaarFlip's rows, by reflection, for any reader),
`features/AdvisorCommand.java` (`/advisor`). Tests: `OpportunityAdvisorTest`, `MarketShareTest`, `OpportunityFeedTest`,
`AdvisorConfigTest`.

## Common units

Every option becomes an `Opportunity` measured per **unit**: one item flipped, one book crafted, one fusion, one forge run
(the whole route from `ForgePlanner`).

| field | meaning | flips / dips | book crafts | fusions | forge |
|---|---|---|---|---|---|
| `unitProfit` | coins per unit after tax and fees | BazaarFlip `profit` | `Craft.profit` | `Fusion.profit` | `Route.profit` |
| `unitCost` | coins paid before it sells | buy price | `Craft.cost` | `Fusion.cost` | `Route.cost` |
| `share` | your share of the market | min of buy, sell side | min of base book, finished book | min of 2 inputs, output | output (AH: base) |
| `maxPerHour` | units/hour you get through | share x `totalProfit / profit` | share x `perHour / profit` | share x `perHour / profit` | see below |
| `labour` | anvil combines per unit | 0 | `units - 1` | 0 | 0 |
| `slotHours` | forge slot-hours per unit | 0 | 0 | 0 | `Route.slotSeconds / 3600` |
| `holdHours` | hours a coin spends in one unit | fill + sell | fill + sell | fill + sell | parts fill + forging + sale |
| passive | runs while you're away | no | no | no | yes |

The lists' rates are each item's whole 7-day volume (the slower side; for forges the least of market, part supply and all
your slots). `maxPerHour` is your share of that. For a forge run the share cuts the output's market and each bought part's
supply but not your slots: `min(Throughput.runsPerHour, share x market runs, part share x part supply / units per run)`.

**Coins tied up** follow Little's law: running `x` units an hour, each holding its coins for `holdHours`, keeps
`x * unitCost * holdHours` coins busy, never less than one unit's cost. A trade completes no sooner than you come back to it
and no sooner than you get one unit through: `wait = max(checkHours, 1 / your units per hour)`.

- Bazaar kinds: `holdHours = 2 * wait(orderCheckHours, maxPerHour)`, one wait for the buy order to fill, one for the sell
  offer. `orderCheckHours` is how often you tend your orders while playing (`/advisor checkhours`, default 1h). Since the
  wait is at least `1 / maxPerHour`, a Bazaar option always has at least two units in flight at its limit.
- Forge: `holdHours = wait(forgeCheckHours, your part runs/hour) + slotHours + wait(forgeCheckHours, your sale runs/hour)`:
  parts in by your next check-in (or as fast as your share of the scarcest part allows), the forging (slot time already
  counts a slot held until you collect), then the sale. `forgeCheckHours` is the existing /forgesettings value.

## Market share

Other players run orders on the same items, and only the order on top fills. The public Bazaar data (`sell_summary` = buy
orders, `buy_summary` = sell offers) lists the top 30 price levels per side with how many **orders** sit at each. Orders
priced within 1% of the best are the players you're fighting: with `n` of them you're on top about `1 / (n + 1)` of the
time. That is kept within the player's own range (`/advisor share <min> <max>`, default 5-10%, their estimate on
2026-10-17), and anything the order book doesn't cover (AH outputs and parts, no snapshot yet) gets the middle: 7.5%.

Live, 2026-10-17 20:01: a 0.1% band counts almost exactly what a 1% band does (undercut wars stack orders 0.1 coin apart).
Rejuvenate V's sell offers had 19 orders near the top (5%), Rejuvenate I's buy orders 10 (9.1%), Magma Cream Distillate's buy
orders 3 (10%). Across all 3658 sides with orders, 69% get 10% and 12% get 5%.

The doubt for a thin market still uses the **market's** pace, not your share of it: it's about whether the price is real.

## Anvil time

Book crafts need anvil combines: a tier V from tier I books is 16 books into one, 15 combines. You do only so many an hour
(`/advisor combines <n>`, default 150, the player's answer on 2026-10-17). All book crafts share that: `sum(x * labour) <=
combinesPerHour`. Without it the 2026-10-17 run planned Rejuvenate V alone at 1694 crafts a day, 1059 combines an hour.

## Live check, 2026-10-17 20:37

`OpportunityAdvisorLiveProbe` on live data with the player's settings (7 slots, 7 items, 5-10% share, 150 combines/hr):

| purse | phase 1 model | now | of which forges |
|---|---|---|---|
| 250M | 196.59M/hr | 23.42M/hr | 3.62M |
| unlimited | | 26.81M/hr | 7.01M |
| 1B | | 25.83M/hr | 6.03M |
| 50M | | 19.61M/hr | 0.49M |
| 15M | | 14.95M/hr | 0.49M |
| 50M, AFK | | 2.29M/hr | 2.29M |

Rejuvenate V now runs 130 crafts a day (7.7% of its market, 81 combines/hr) instead of 1692. With 15M and 50M the anvil
takes 135-136 of the 150 combines; with more coins the 7-item limit binds first (90 combines). The Titanium Drill DR-X655
gets 1 slot at ~2.1 runs a day (7.5% of its AH sales) instead of 5 slots at 29. Plans took 80-170 ms (phase 1: 16-97 ms;
the extra is the labour prices in the shortlists and the anvil search), off the client thread in game.

## Confidence

The plan maximises **expected** profit: `unitProfit * confidence`. Confidence only drops for what the rows can show:

- **Thin market**: `trades/h / (trades/h + 1/24)`. An item trading once a day gets 0.5 (even odds yours is the one that
  trades today); anything trading several times an hour is effectively 1.
- **Prices that disagree**: `1 - 0.5 * (1 - min/max)` for two prices of the same thing. Forge AH outputs: lowest BIN vs
  3-day average (the SX-R326 at 16.8M vs 9.4M gets 0.78). Bazaar flips: buy price vs its 7-day normal
  (`BaseProfit.typicalBuy`). Dips are exempt: a cheap buy price is the point of a dip.

Each factor below 0.95 becomes a "doubt" line in the explanation, with the planned (after-haircut) profit.

## Allocation

Maximise total expected profit per hour subject to:

1. coins tied up <= purse (when the purse is known and the purse filter is on),
2. forge slots used <= your slots, whole slots, one route per slot,
3. Bazaar items run at once <= `maxActive` (`/advisor maxactive`, default 7: a buy order and a sell offer each),
4. anvil combines per hour <= `combinesPerHour`, shared by every book craft,
5. in AFK mode (`/advisor afk`) only passive options.

Forges are passive and run alongside flipping, so they compete with the Bazaar for **coins only**, not for your time.

**Forge side, exhaustive over a shortlist.** The shortlist is the best 5 routes per slot plus the best 5 per coin (at most
10). Every whole-slot assignment of those routes that fits the purse is scored: at most C(10 + 7, 7) = 19,448 for 7 slots,
however long the forge list is. A route is never given more slots than its market keeps busy.

**Bazaar side, for the coins each assignment leaves.** For a given set of options the best use of a budget is to fill them
in order of return per coin (`expected profit / (unitCost * holdHours)`), each up to its market's limit; that is the
fractional knapsack and exact. Which set: the top `maxActive` by `(profit - price * capital)` per hour for up to 32 capital
prices, from 0 ("most profit per hour", right when coins are plentiful) up through the options' own returns per coin
(right when coins are tight). The sets don't depend on the budget, so they're built once.

**Anvil time** makes the Bazaar side a linear programme with two shared resources (coins and combines). When a set's fill
needs more combines than you have, combines get a price `mu`: each option's return per coin becomes
`(expected profit - mu * labour) / (unitCost * holdHours)`, and a craft drops out once `mu` passes its profit per combine.
The fill only changes where two options' adjusted returns cross or a craft drops out, so those break points (at most
`k + k(k-1)/2` for `k` options) are bisected for the first interval whose fill fits your combines; that fill and the one
just before it are both optimal at the break price between them, and the mix of the two that uses exactly all your combines
is optimal overall. Cost: `2 + ceil(log2(breaks))` fills per set and budget instead of one. With no purse limit every forge
assignment leaves the same budget, so the Bazaar side is worked out once. With an item limit the shortlists also try up to
6 labour prices per capital price (the crafts' profits per combine), so a set can favour what needs no anvil.

- With no binding item limit, the plan matches exhaustive search exactly (400 random cases), and with anvil time it matches
  the linear programme solved by brute force (every vertex) exactly (400 random cases, more than 50 with purse and anvil both
  binding).
- With a binding item limit it's a heuristic: worst case 98.2% of exhaustive search over 300 random cases; with anvil time
  too, 93.8% of the best subset's linear programme.
- Mixing two fills can leave an option running below one unit; if paying for that whole unit would break the purse, it's
  dropped from the mix.

## Settings

The advisor's own, in `config/bazaarutils-advisor.json` (`/advisor settings` shows them):

| setting | command | default |
|---|---|---|
| order check-in | `/advisor checkhours <h>` | 1h |
| Bazaar items at once | `/advisor maxactive <n>` | 7 (0-20) |
| AFK (forges only) | `/advisor afk` (toggles) | off |
| market share range | `/advisor share <min%> <max%>` | 5-10%, 7.5% without an order book |
| anvil combines per hour | `/advisor combines <n>` | 150 |

Reused:

- Purse: `ForgeCalc.budget(BaseProfit.isForgePurseFilter(), ForgeCalc.lastPurse())`, the same rule as /forgesettings: the
  scoreboard purse when the filter is on and readable, otherwise no limit (the plan then says so).
- Unlocks: the forge rows come from `ForgeCalc`, which already applies the HotM unlock filter and the purse cap per route.
- Slots and check-in hours: `BaseProfit.getForgeSlots()`, `getForgeCheckHours()`.

`/advisor` gathers every list's current rows (`OpportunityFeed.gather`: BazaarFlip's flips or dips via `BazaarFlipRows`,
`BookCraft`, `FusionCalc`, `ForgeCalc`, each refreshed if stale, never waiting on the network), plans off the client
thread and prints the summary and each pick in chat, with how many rows each list gave (a list at 0 may still be loading).

## Explanations

Template text with the plan's own numbers, no LLM. For example:

```
Fill 7 forge slots with 7x A: 700.00K/hr, running while you're AFK.
Put 21.25M into Y and Z: 1.11M/hr while you're online.
Expected 1.81M/hr: 700.00K passive + 1.11M active.

7 slots: forge A. 400.00K/run x 42 runs/day = 700.00K/hr. Ties up 8.75M (5h from buying parts to sale). Keeps running while you're AFK.
flip Y. 30.00K each x 480/day = 600.00K/hr on 10.00M of your purse (back in ~1h), 60.00K/hr per 1M tied up. That's all its market trades.
```

Left-out options get a reason: "one run costs 50.00M, more than your purse", "a slot ties up 584.14M for 5.93M/hr:
10.15K/hr per 1M, less than your picks earn" (the Titanium Drill DR-X655 with a 1B purse, live 2026-10-17), "your slots earn more on A", "you're already running 7 Bazaar items that earn more
together", "needs you online to keep its orders on top", "needs 15 anvil combines each (20.00K per combine); your 150
combines/hr earn more on the picked crafts". Coins left over say why too: the item limit, full forge slots, every pick at
its market's limit, anvil time used up, or too little left for one more unit. A pick on a shared market says so ("That's
your ~5% of what its market trades."), and a craft says how many combines it takes.

## Known simplifications

- **Market share is an estimate.** Orders near the top count as competitors whether or not anyone is tending them, one
  player with several orders counts several times, your own order (if you already run one) counts against you, and only
  the top 30 price levels are visible. The player's range (5-10%) bounds the damage.
- **Anvil XP** isn't counted separately; the combines-per-hour setting is meant to include it.
- Two options on the same market (a book flipped and also crafted, a forge output also flipped) are planned independently.
- Not modelled: Hypixel's daily Bazaar order coin limit (`OrderLimit`), AH listing slots, your own orders moving the price.
- BazaarFlip's list holds flips **or** dips depending on its mode, so the advisor sees whichever is active.

## Phases

1. Done: the pure ranker, the scoring model, tests (each guard seen failing against a deliberately broken copy).
2. Done (2026-10-18): market share from the order book, the anvil-time cap, `BazaarFlipRows` (public accessor for
   BazaarFlip's rows), `OpportunityFeed` (all four calculators), settings and `/advisor`. FlipHud still reads BazaarFlip by
   its own reflection; the Forge session will switch it to `BazaarFlipRows` once its search work is committed.
   `OpportunityAdvisorLiveProbe` (skipped unless `ADVISOR_LIVE=1`) runs the advisor on live data with your .minecraft/config
   settings and writes the plans for several purses (and the phase 1 model for comparison) to `ADVISOR_REPORT`.
3. Login toast: one line on joining SkyBlock ("Fill 7 slots with X, put 30M into Y and Z: ~2.1M/hr").
4. Advisor screen: the plan, each pick's reason, the left-out options; replaces most of the per-list config.
5. AH flips from sbflip (D:\projects\skyblock-flipper, lowest-BIN finder) as another `Opportunity` kind.
6. Optional: an LLM that narrates the computed plan in friendlier words. It never picks.
