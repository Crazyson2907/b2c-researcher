---
name: wiki-interest-trends
description: Measures how interest in a topic changes across Wikipedia language editions using Wikimedia pageview data, checks whether the trend is trustworthy, and produces charts and a one-page PDF report. Use when a founder or product team asks which topic, course or feature to build next, which languages or markets to localize into, whether interest in a subject is growing, or wants Wikipedia pageview trends compared across languages or topics.
compatibility: Requires bash, JDK 17+ and internet access (Wikimedia APIs; Maven Central on first build). The Kotlin CLI is built from source on first run (~1 min).
metadata:
  version: "0.1.0"
---

# Wikipedia interest trends

All data work is done by `scripts/wikitrend` (run it from this skill's directory or by full path).
Do not write your own API or statistics code: run the commands and read their output.

## Setup (once per machine)

Wikimedia allows 10 requests/min without contact details and 200/min with them. If a command prints
`NOTE: no contact configured`, ask the user for an email or URL and save it:
`mkdir -p ~/.config/wikitrend && echo 'their@email' > ~/.config/wikitrend/contact`

## Workflow

1. **Translate the question** into topics (what), language editions (where) and a window (when).
   - Language editions are Wikipedia subdomain codes: `uk` Ukrainian, `pl` Polish, `cs` Czech, `es` Spanish, `tr` Turkish, `vi` Vietnamese, `id` Indonesian, `pt` Portuguese, `de` German...
   - Default window: `--months 24` (last 24 complete months). "Last N years" = `--months 12*N`. Keep ≥24 so the comparison is seasonally aligned.
2. **Resolve topics to Wikidata items**: `scripts/wikitrend resolve "intermittent fasting" --langs pl,cs`
   - Pick the item whose *description* matches the user's meaning (search can rank a wrong item first, e.g. "planet" → asteroid).
   - For a non-English query add `--search-lang uk` (the language of the words).
   - If a language has "(no article)", read the search suggestions. Use one only if it is an article *about* the topic.
     Otherwise report that the edition has no article (low coverage is itself a signal) and continue with the other languages.
3. **Analyze**: `scripts/wikitrend analyze --item Q1666254 --langs pl,cs`
   - Several topics in one language: `--item Q333 --item Q544 --langs uk`
   - One topic made of several articles (broader interest): `--topic "Astronomy=Q333|Q544|Q4213|Q589" --langs uk`
   - Add a local article found with `search`: `--topic "Intermittent fasting=Q1666254|pl:<exact Polish title>"`
   - At most 8 series (topics × languages) per run.
4. **Interpret** the printed table and trust checks (rules below), then answer the user.
5. **Report** (when the user wants something shareable, or asks for a report/PDF/one-pager):
   ```
   scripts/wikitrend report --run "<Run dir printed by analyze>" --ui-lang en \
     --title "Interest in astronomy, Ukrainian Wikipedia 2024-2026" \
     --question "Should we add an astronomy course?" \
     --finding "<statement with a number> (<HIGH/MEDIUM/LOW> confidence)" --finding "..." \
     --recommendation "..."
   ```
   Use `--ui-lang uk` when the user writes Ukrainian. Write 2-4 findings, each with a number and its confidence.
   Share the PDF path, and `views.png` / `growth.png` from the run dir as charts.

## Reading the output

| Column | Meaning | Use it for |
|---|---|---|
| Views/day (recent) | average over the last 12 months | audience size |
| Per 1M edition views | topic views per million views of that edition | interest intensity, comparable across languages |
| Change | last 12 months vs previous 12 | raw growth |
| Change vs edition | change of the topic's share of the edition's traffic | **fair comparison**: removes the platform-wide decline |
| Months up | months higher than the same month a year earlier | consistency |
| Verdict | direction (growing ≥+10%, declining ≤−10%, flat) + confidence | how much to trust it |

Rules:
- Always report the confidence and name the failed checks (`[!!]` lines) in plain words.
- Many editions lost 10-30% of all traffic recently (AI answers, bot filtering). When comparing languages,
  lead with **Change vs edition**, then size (Views/day) and intensity (Per 1M).
- `platform` failed: the raw change mostly mirrors the whole edition.
  `spikes` failed: growth comes from a few event days (see top spike dates).
  `coverage` failed: the article is new or renamed, so treat the change as an artifact.
- LOW confidence or a very small audience means you should not recommend acting on it. Suggest a broader topic basket or another edition.
- Pageviews measure attention, not willingness to pay. Recommend what to **validate next**, not what will sell.
- Language edition ≠ country (many people read English Wikipedia).

Details on every metric and threshold: [references/methodology.md](references/methodology.md).

## Follow-up questions

- Changed languages, period or topics: re-run `analyze` with new arguments. Downloaded data is cached, so repeats are fast.
- Keep the QIDs you already resolved (they are in `<run dir>/analysis.json` → `params.topics`); do not resolve again.
- A new report from the same run needs no new analysis: run `report` again with different text.
- Raw numbers for custom questions: `<run dir>/monthly.csv` (month, series, views, edition_views, views_per_million).

## Answer shape

1. One-sentence answer to the question.
2. Numbers per language/topic: change, change vs edition, views/day, confidence (and why).
3. Recommendation: what to explore or validate next, and why.
4. Caveats: at most 2 relevant ones.

## Troubleshooting

- `ERROR: ... HTTP 429`: rate limited. Configure a contact (Setup) and retry.
- First run prints `setup: building...`: this is the one-time build. If Java is missing, tell the user to install JDK 17+.
- `No Wikidata items match`: try an English term, a synonym, or `--search-lang` of the query's language.
