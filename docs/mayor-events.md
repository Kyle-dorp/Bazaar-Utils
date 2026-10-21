# Mayor events: 24-hour shifts and perk badges

The lists used to assume "this week trades like last week": every price check used the 7-day median from Coflnet, and every
profit/hr used Hypixel's 7-day volume ÷ 168. A mayor perk, an event or an update moves a market for hours or days. The 7-day
numbers lag behind it, so dip mode calls the new level a dip, the manipulation filter hides it, and profit/hr misses the extra volume.

## What changed

**MarketShift** (built from the Coflnet week history BaseProfit already downloads, so it costs no extra request):

- *Price.* It takes the median of the newest 24 hours of 2-hour buckets and compares it with the median of the days before.
  A median only moves once at least half the buckets have, so a level counts as new after it has held about 12 of the
  24 hours. A shift is **confirmed** only when the buy-order price and the sell-offer price both moved at least 10% the same
  way, and the spread between them changed by under 25%. A buyout or a dump moves one side, or one side much more than the other.
- *Dip mode.* The "normal" buy price is the lower of the 7-day and 24-hour medians, so a price the market has sat at all day
  is not a dip. A fresh drop (newer than the history, which runs about 4 to 6 hours behind) is still a dip.
- *Manipulation band* (flips, book crafts, fusions, forge parts). Prices within the tolerance of the 7-day medians pass as
  before. Once a shift is confirmed, prices within the tolerance of the 24-hour medians also pass. With no confirmed shift
  the band is exactly the old one (a test pins this).
- *Volume.* Hypixel only publishes 7-day moving totals. M(now) − M(24 h ago) equals the last 24 hours minus the day that just
  left the window, so it is a lower bound on the last 24 hours. Each side's rate is max(weekly ÷ 168, that bound ÷ 24): it rises
  when a surge is provable and never overstates the last day. It never goes *down*, because a slump can't be told apart from
  a busy day leaving the window with this data. All profit/hr sources use it: BazaarFlip rows, book crafts, fusions, forge
  parts and the advisor.

**Mayor perks** (MayorPerks, MayorWatch, `/flipmayor`):

- The mayor, the minister and their perks come from `api.hypixel.net/v2/resources/skyblock/election`. It needs no key, is
  about 4 KB and is fetched every 10 minutes, a minute after a term ends, or 2 minutes after a failure.
- Jerry's **Perkpocalypse** activates all perks of one of Aatrox, Cole, Diana, Finnegan, Marina and Paul (never Grand Feast)
  for 6 hours at a time, starting at the election end. The order is random each Jerry term and then repeats every 36 hours.
  The election API names Jerry's own perks only. The rotation mayor comes from SkyHanni's reading after you open the Calendar
  (read by reflection, ignored if SkyHanni is missing or changes), from the mayor already seen in the same slot of the cycle this
  term (kept in `config/bazaarutils-perkpocalypse.json`), or from `/flipmayor now <mayor>` or `/flipmayor order <6 mayors>`
  (the wiki's Perkpocalypse table, slot A first).
- Items each perk moves: `assets/bazaarutils/mayor-perks.json`. Put a copy at `config/bazaarutils-mayor-perks.json` to
  replace it without a rebuild. Tagged rows get a badge such as `[Mining Fiesta]`, and confirmed shifts or faster fills get
  `[24h -22%]` or `[24h vol x4.5]`.

## Turning it off

The **Mayor** checkbox in `/bazaarflip`'s flipper row (Flippers.MAYOR, last in the enum) turns all of this off. No history
is kept, every check uses the 7-day rules and weekly ÷ 168, no badges are drawn, and the election is never downloaded. The
Advisor checkbox label became "Advise" so 8 checkboxes fit.

## Cost

- No extra Coflnet requests: the 24-hour signal comes from the week history already fetched (one request per item per
  20 minutes, ~17 KB).
- One election request (~4 KB) per 10 minutes. Badge lookups are a map read, safe every frame. At most one refresh runs at a
  time. A test drives 10,000 frames of lookups and counts 1 download.
- `perHour` is a map lookup and a binary search over ~82 buckets.

## Live check, 2026-10-21 (Jerry's term, day 1)

| Item | Buy orders / sell offers, 24 h vs before | Confirmed | Flip fills vs weekly |
|---|---|---|---|
| Jerry Box (green / blue / purple / golden) | ~0% / ~0% (purple +31% ask, golden +20% bid) | no | x4.5 / x5.7 / x5.5 / x5.9 |
| Fine Flour | −21% / −28% | yes | x1.0 |
| Shark Fin | +32% / +542% | no (buyout) | x1.0 |
| Refined Mineral, Griffin Feather, Booster Cookie, ... | within ±8% | no | x1.0 |

20 week-history requests, 345 KB in total. Without the volume bound, Jerry Box flips were ranked on a week in which hidden
Jerries didn't exist yet.

## Limits

- The 24-hour price window is too slow for a single 6-hour Perkpocalypse slot. The perk badge is what marks those.
- A slump in volume is not detected (see Volume).
- The forge list does not yet shorten forge times for Molten Forge. The data file tags it (`lists: forge`) for when it does.
