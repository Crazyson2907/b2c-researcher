# Methodology

## Data

- Source: Wikimedia Pageviews API (`/metrics/pageviews/per-article` and `/aggregate`), daily granularity, from July 2015.
- Defaults: `agent=user` (known spiders and automated traffic excluded) and `access=all-access` (desktop + mobile web + app).
- A **series** = one topic in one language edition. A topic's views are the sum of its articles plus up to 25
  redirects per article (`--max-redirects`). Old titles become redirects after a rename, so this keeps renamed
  articles continuous.
- Edition totals (`aggregate`) use the same agent and access filters. They are used to normalize.
- Only whole calendar months are used, and the window ends at the last complete month.

## Metrics

| Metric | Definition |
|---|---|
| Change | Average daily views in the last 12 months ÷ average daily views in the previous 12 − 1 (per-day, so leap years don't bias it). With a window under 24 months: last N/2 vs previous N/2 months (not seasonally aligned). |
| Change vs edition (share growth) | (topic ÷ edition views, recent) ÷ (topic ÷ edition views, prior) − 1 |
| Per 1M edition views | recent topic views × 10⁶ ÷ recent edition views |
| Months up | Month pairs (month *i* of the recent period vs month *i* of the prior period, compared per day) where the recent month is higher |
| Trend/yr | Theil–Sen slope of ln(monthly views + 1) over the whole window, annualized (robust to outlier months) |
| Momentum | Last 3 months vs the same 3 months a year earlier |
| Spike day | A day with ≥4× the median of the surrounding ±14 days and ≥30 views above it |
| Spike share | Excess views on spike days in the recent period ÷ all recent views |

## Direction and confidence

Direction: `growing` if Change ≥ +10%, `declining` if ≤ −10%, otherwise `flat`.

Checks (for `growing`/`declining`):

| Check | Passes when | Failure means |
|---|---|---|
| consistency | Majority of month pairs move in the stated direction and the two-sided sign test gives p < 0.05 | Direction may be chance or driven by a few months |
| noise | \|ln(recent/prior)\| ÷ √(1/recent + 1/prior) ≥ 3 (Poisson standard errors) | The change is within random fluctuation |
| spikes | Spike share < 15% and the median day moves in the same direction | Growth comes from news events, external links or bots |
| platform | Change vs edition has the same sign | Raw change mirrors the whole edition (e.g. platform-wide traffic decline) |
| coverage | Views exist in the first month and no month is zero afterwards | Article is new or was renamed without a redirect, so the change is an artifact |
| volume (cap) | ≥ 20 views/day | Very small audience; a few readers can move it |
| seasonality (cap) | Window ≥ 24 months | Periods compare different calendar months |

Level: all core checks (consistency, noise, spikes, platform, coverage) pass → HIGH. Exactly one fails and consistency
holds → MEDIUM. Otherwise → LOW. Volume < 20/day and window < 24 months cap the level at MEDIUM. Failed coverage
forces LOW. For `flat` series only spikes and coverage are core.

## Known data caveats

- **Platform-wide decline.** Human pageviews fell in many editions in 2025–2026 (AI answers in search, better bot
  detection reclassifying traffic from `user` to `automated`). This is why "Change vs edition" exists. Reclassification
  can also produce a step change for a single article.
- **School seasonality.** Educational topics peak in September and October and dip in summer. Year-over-year comparison
  cancels this out; momentum over short windows does not.
- **Language ≠ market.** Speakers of smaller languages often read English Wikipedia; Ukrainian speakers may read the
  Russian edition. Absolute sizes understate interest in those markets.
- **Topic proxies.** Wikipedia has articles about subjects, not intents: "learning English" is best proxied by the
  "English language" article (Q1860) plus "English as a second or foreign language" (Q130192) where it exists. State
  proxies explicitly.
- **Missing articles.** No article in an edition usually means low editor interest, and often low reader interest. Report it rather than
  substituting a loosely related article.

## Output files (per run directory)

`analysis.json` (all metrics, checks, monthly arrays, parameters), `summary.md` (what `analyze` printed),
`monthly.csv`, `daily.csv`, `views.png` (monthly views, log scale when series differ >20×), `growth.png` (change vs
share change per series), `report.pdf` (after `report`).

The HTTP cache is in `~/.cache/wikitrend/http` (override with `WIKITREND_CACHE`). Closed months never change, so they
are cached permanently; metadata is cached for 7 days.
