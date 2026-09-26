# b2c-researcher

A collection of Claude Code agent skills for consumer (B2C) market research. Each skill is a
self-contained directory with a `SKILL.md` definition and any supporting tools, so Claude can use
it as part of a conversation without writing ad-hoc scraping or analysis code.

## Skills

### wiki-interest-trends

Measures how interest in a topic changes over time and across Wikipedia language editions, using
Wikimedia pageview data. It checks whether an observed trend is statistically trustworthy (not
noise, a traffic spike, or a platform-wide artifact) and produces monthly charts, raw data, and an
optional one-page PDF report.

Typical questions it answers:

- Is there an interest in a topic, product category, or technology growing or declining?
- Which language editions or markets show the strongest relative interest?
- Is a "growing" trend real, or does it just track a platform-wide traffic change?

The skill is implemented as a small Kotlin CLI (`wikitrend`) wrapped by a shell entry point, plus a
`SKILL.md` that tells Claude how to translate a research question into CLI calls and how to read
the output. See [`wiki-interest-trends/SKILL.md`](wiki-interest-trends/SKILL.md) for the full
workflow and [`wiki-interest-trends/references/methodology.md`](wiki-interest-trends/references/methodology.md)
for the metrics, trust checks, and known data caveats.

## Requirements

- bash
- JDK 17 or later
- Internet access (Wikimedia APIs at runtime; Maven Central on the first build)

No prebuilt binaries are shipped. The CLI is built from source on first use.

## Setup

### As a Claude Code skill

Copy or symlink the skill directory into a location where Claude Code looks for skills, for
example a project's `.claude/skills/`:

```bash
mkdir -p .claude/skills
ln -s /path/to/b2c-researcher/wiki-interest-trends .claude/skills/wiki-interest-trends
```

Once loaded, Claude reads `SKILL.md` and calls `scripts/wikitrend` on your behalf when you ask a
relevant research question; you do not need to invoke the CLI yourself.

Wikimedia allows only about 10 requests/minute without contact details, and 200/minute with them.
Configure a contact once per machine:

```bash
mkdir -p ~/.config/wikitrend
echo 'you@example.com' > ~/.config/wikitrend/contact
```

### Building or running the CLI directly

The wrapper script builds the tool automatically on first run (and after source changes):

```bash
wiki-interest-trends/scripts/wikitrend --help
```

To build explicitly instead:

```bash
wiki-interest-trends/scripts/setup.sh
```

This resolves Gradle (a local `gradle` on `PATH`, an existing wrapper distribution, or a
checksum-verified download) and runs `installDist`, producing
`wiki-interest-trends/tool/build/install/wikitrend/bin/wikitrend`.

## Usage

The CLI has three commands: `resolve`, `analyze`, and `report`.

1. Resolve a topic to a Wikidata item:

   ```bash
   scripts/wikitrend resolve "intermittent fasting" --langs pl,cs
   ```

2. Analyze pageview trends for that item across language editions:

   ```bash
   scripts/wikitrend analyze --item Q1666254 --langs pl,cs
   ```

   This downloads daily pageviews, computes growth and trust checks, writes charts/CSV/JSON to a
   run directory, and prints a summary table.

3. Render a shareable one-page PDF from a run:

   ```bash
   scripts/wikitrend report --run wikitrend-runs/<run-dir> --ui-lang en \
     --title "Interest in intermittent fasting, PL/CS 2024-2026" \
     --question "Should we localize this content into Polish or Czech?" \
     --finding "PL views per million grew 24% year over year (HIGH confidence)" \
     --recommendation "Prioritize Polish; validate with a landing page test."
   ```

Run any command with `--help` for the full option list, including multi-topic and multi-article
comparisons (up to 8 series per run).

### Reading the output

| Column | Meaning | Use it for |
|---|---|---|
| Views/day (recent) | Average over the last 12 months | Audience size |
| Per 1M edition views | Topic views per million views of that edition | Interest intensity, comparable across languages |
| Change | Last 12 months vs. previous 12 | Raw growth |
| Change vs edition | Change in the topic's share of the edition's traffic | Fair comparison that removes platform-wide decline |
| Months up | Months higher than the same month a year earlier | Consistency |
| Verdict | Direction (growing, declining, flat) plus confidence | How much to trust the result |

Always check the confidence level and any failed trust checks before acting on a trend; low
confidence or a very small audience means the result should not drive a decision on its own. Full
definitions and thresholds are in
[`references/methodology.md`](wiki-interest-trends/references/methodology.md).

## Repository structure

```
wiki-interest-trends/
  SKILL.md                  Skill definition and workflow Claude follows
  references/methodology.md Metrics, trust checks, and data caveats
  scripts/
    wikitrend                Entry point (builds on first use, then runs the CLI)
    setup.sh                 Explicit build script
  tool/                       Kotlin CLI source (Gradle project)
    src/main/kotlin/wikitrend/
    src/test/kotlin/wikitrend/
```

## Troubleshooting

- `ERROR: ... HTTP 429`: rate limited. Configure a contact as described in Setup and retry.
- `No Wikidata items match`: try an English term, a synonym, or pass `--search-lang` matching the
  language of the query.
- Build fails: confirm `java -version` reports 17 or later, then retry
  `wiki-interest-trends/scripts/setup.sh` directly to see the full Gradle output.
