# Opportunity advisor: scoring model

The flip list has four modes (Bazaar flips/dips, fusions, forge) plus book crafts, each ranked on its own. The advisor puts
them on one scale and answers one question: **what should I do with my purse and forge slots right now?** The answer is a
plan, not a sorted list: "fill 7 forge slots with X, put the remaining 30M into flips Y and Z", with a plain-text reason for
every pick and for the strong options it leaves out.

It is deterministic optimisation. Ranking by profit per hour and splitting a purse and slots between options is arithmetic
the calculators already half-do; an LLM would only be guessing at it. If one is added later, it narrates numbers this
produces, it doesn't choose.

Code: `features/Opportunity.java` (common units, one factory per calculator), `features/OpportunityAdvisor.java` (the
allocation and the text). Both are pure: no Minecraft, no network. Tests: `OpportunityAdvisorTest`.

## Common units

Every option becomes an `Opportunity` measured per **unit**: one item flipped, one book crafted, one fusion, one forge run
(the whole route from `ForgePlanner`).

| field | meaning | flips / dips | book crafts | fusions | forge |
|---|---|---|---|---|---|
| `unitProfit` | coins per unit after tax and fees | BazaarFlip `profit` | `Craft.profit` | `Fusion.profit` | `Route.profit` |
| `unitCost` | coins paid before it sells | buy price | `Craft.cost` | `Fusion.cost` | `Route.cost` |
| `maxPerHour` | most units/hour the market allows | `totalProfit / profit` | `perHour / profit` | `perHour / profit` | `Throughput.runsPerHour` |
| `slotHours` | forge slot-hours per unit | 0 | 0 | 0 | `Route.slotSeconds / 3600` |
| `holdHours` | hours a coin spends in one unit | fill + sell | fill + sell | fill + sell | parts fill + forging + sale |
| passive | runs while you're away | no | no | no | yes |

`maxPerHour` comes straight from each calculator's own volume logic (the slower side of 7-day volume; for forges the least of
market, part supply and all your slots), so the advisor can't plan past what the lists already consider possible.

**Coins tied up** follow Little's law: running `x` units an hour, each holding its coins for `holdHours`, keeps
`x * unitCost * holdHours` coins busy, never less than one unit's cost. A trade completes no sooner than you come back to it
and no sooner than the market trades one unit: `wait = max(checkHours, 1 / unitsPerHour)`.

- Bazaar kinds: `holdHours = 2 * wait(orderCheckHours, maxPerHour)`, one wait for the buy order to fill, one for the sell
  offer. `orderCheckHours` is how often you tend your orders while playing (default 1h; no setting exists yet).
- Forge: `holdHours = forgeCheckHours + slotHours + wait(forgeCheckHours, market runs/hour)`: parts filled by your next
  check-in, the forging (slot time already counts a slot held until you collect), then the sale. `forgeCheckHours` is the
  existing /forgesettings value.

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
3. Bazaar items run at once <= `maxActive` (default 7: a buy order and a sell offer each),
4. in AFK mode only passive options.

Forges are passive and run alongside flipping, so they compete with the Bazaar for **coins only**, not for your time.

**Forge side, exhaustive over a shortlist.** The shortlist is the best 5 routes per slot plus the best 5 per coin (at most
10). Every whole-slot assignment of those routes that fits the purse is scored: at most C(10 + 7, 7) = 19,448 for 7 slots,
however long the forge list is. A route is never given more slots than its market keeps busy.

**Bazaar side, for the coins each assignment leaves.** For a given set of options the best use of a budget is to fill them
in order of return per coin (`expected profit / (unitCost * holdHours)`), each up to its market's limit; that is the
fractional knapsack and exact. Which set: the top `maxActive` by `(profit - price * capital)` per hour for up to 32 capital
prices, from 0 ("most profit per hour", right when coins are plentiful) up through the options' own returns per coin
(right when coins are tight). The sets don't depend on the budget, so they're built once.

- With no binding item limit, the plan matches exhaustive search exactly (400 random cases in the tests).
- With a binding item limit it's a heuristic: worst case 98.2% of exhaustive search over 300 random cases.

## Settings it reuses

- Purse: `ForgeCalc.budget(BaseProfit.isForgePurseFilter(), ForgeCalc.lastPurse())`, the same rule as /forgesettings: the
  scoreboard purse when the filter is on and readable, otherwise no limit (the plan then says so).
- Unlocks: the forge rows come from `ForgeCalc`, which already applies the HotM unlock filter and the purse cap per route.
- Slots and check-in hours: `BaseProfit.getForgeSlots()`, `getForgeCheckHours()`.

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
together", "needs you online to keep its orders on top". Coins left over say why too: the item limit, full forge slots,
every pick at its market's limit, or too little left for one more unit.

## Known simplifications

- **Full market volume, free labour.** Like the lists, a flip's rate cap is the item's whole 7-day volume; real capture is
  a share of it. Book crafts also cost anvil time and XP levels that nothing counts. The live run on 2026-10-17 shows what
  that does: with a 250M purse the plan put 7 Bazaar items (5 book crafts) at ~174M/hr, e.g. Rejuvenate V at 1694 crafts a
  day (each 15 anvil combines). The **ranking** of options is still meaningful; the **absolute** Bazaar numbers are not.
  The fix needs two inputs from the player, so it's phase 2: a market share (what fraction of a market's volume you
  capture) and an anvil-labour cap for crafts.
- Two options on the same market (a book flipped and also crafted, a forge output also flipped) are planned independently.
- Not modelled: Hypixel's daily Bazaar order coin limit (`OrderLimit`), AH listing slots, your own orders moving the price.
- BazaarFlip's list holds flips **or** dips depending on its mode, so the advisor sees whichever is active.

## Phases

1. **This one:** the pure ranker, the scoring model, tests (each guard seen failing against a deliberately broken copy).
2. Glue: a public accessor for BazaarFlip's rows (FlipHud reads them privately by reflection), gather all four calculators,
   settings for `orderCheckHours`, `maxActive`, AFK, market share and the anvil-labour cap.
   `OpportunityAdvisorLiveProbe` (skipped unless `ADVISOR_LIVE=1`) already runs the advisor on live data with your
   .minecraft/config settings and writes the plans for several purses to `ADVISOR_REPORT`.
3. Login toast: one line on joining SkyBlock ("Fill 7 slots with X, put 30M into Y and Z: ~2.1M/hr").
4. Advisor screen: the plan, each pick's reason, the left-out options; replaces most of the per-list config.
5. AH flips from sbflip (D:\projects\skyblock-flipper, lowest-BIN finder) as another `Opportunity` kind.
6. Optional: an LLM that narrates the computed plan in friendlier words. It never picks.
