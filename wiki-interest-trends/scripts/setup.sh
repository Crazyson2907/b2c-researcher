#!/usr/bin/env bash
# Builds the wikitrend CLI from source (tool/) into tool/build/install/wikitrend.
#
# Requirements: JDK 17+ and network access to Maven Central on first build.
# Gradle is taken from $GRADLE, then `gradle` on PATH (9.x), then an existing Gradle 9.x
# wrapper distribution in ~/.gradle, and otherwise Gradle 9.6.0 is downloaded and verified
# against a pinned SHA-256. No prebuilt binaries are shipped with the skill.
set -euo pipefail

SKILL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOL_DIR="$SKILL_DIR/tool"
GRADLE_VERSION="9.6.0"
GRADLE_SHA256="bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01"
CACHE_DIR="${WIKITREND_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/wikitrend}"

die() { echo "setup: ERROR: $*" >&2; exit 1; }
log() { echo "setup: $*" >&2; }

# --- 1. JDK 17+ -------------------------------------------------------------------------------
command -v java >/dev/null 2>&1 || die "Java not found. Install JDK 17+ (macOS: brew install openjdk@17; Debian/Ubuntu: apt install openjdk-17-jdk)."
java_major="$(java -version 2>&1 | awk -F'"' '/version/ {print $2; exit}' | awk -F. '{ if ($1 == "1") print $2; else print $1 }')"
[[ "$java_major" =~ ^[0-9]+$ ]] || die "Could not parse Java version from 'java -version'."
(( java_major >= 17 )) || die "Java $java_major found, JDK 17+ required."

# --- 2. Locate or fetch Gradle ------------------------------------------------------------------
gradle_major() { "$1" --version 2>/dev/null | awk '/^Gradle /{split($2, v, "."); print v[1]; exit}'; }

find_gradle() {
  if [[ -n "${GRADLE:-}" ]]; then echo "$GRADLE"; return; fi
  if command -v gradle >/dev/null 2>&1 && [[ "$(gradle_major gradle)" -ge 9 ]] 2>/dev/null; then
    command -v gradle; return
  fi
  local candidate
  for candidate in "$HOME"/.gradle/wrapper/dists/gradle-9.*-bin/*/gradle-9.*/bin/gradle "$CACHE_DIR/gradle-$GRADLE_VERSION/bin/gradle"; do
    [[ -x "$candidate" ]] && { echo "$candidate"; return; }
  done
  log "downloading Gradle $GRADLE_VERSION (one-time, ~140 MB)..."
  mkdir -p "$CACHE_DIR"
  local zip="$CACHE_DIR/gradle-$GRADLE_VERSION-bin.zip"
  curl -fsSL -o "$zip" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" || die "Gradle download failed."
  local actual
  if command -v sha256sum >/dev/null 2>&1; then actual="$(sha256sum "$zip" | awk '{print $1}')"; else actual="$(shasum -a 256 "$zip" | awk '{print $1}')"; fi
  [[ "$actual" == "$GRADLE_SHA256" ]] || { rm -f "$zip"; die "Gradle checksum mismatch (got $actual)."; }
  unzip -q -o "$zip" -d "$CACHE_DIR" && rm -f "$zip"
  echo "$CACHE_DIR/gradle-$GRADLE_VERSION/bin/gradle"
}

GRADLE_BIN="$(find_gradle)"
log "building wikitrend with $GRADLE_BIN (first build takes ~1 min)..."

# --- 3. Build ------------------------------------------------------------------------------------
"$GRADLE_BIN" -p "$TOOL_DIR" --no-daemon --quiet --console=plain installDist >&2 \
  || die "Build failed. Re-run with: $GRADLE_BIN -p $TOOL_DIR installDist"
touch "$TOOL_DIR/build/install/.built"
log "done: $TOOL_DIR/build/install/wikitrend/bin/wikitrend"
