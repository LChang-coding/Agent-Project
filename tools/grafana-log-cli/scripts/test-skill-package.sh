#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SKILL="$ROOT/skill/grafana-log-inspector"
TEMP=$(mktemp -d "${TMPDIR:-/tmp}/grafana-log-skill-test.XXXXXX")
trap 'rm -rf "$TEMP"' EXIT HUP INT TERM

cp -R "$SKILL" "$TEMP/skill"
HOME="$TEMP/home" XDG_CACHE_HOME="$TEMP/cache" "$TEMP/skill/scripts/self-test" \
  > "$TEMP/self-test.out"
grep -q '^status=ok$' "$TEMP/self-test.out"
grep -q '^assets=4$' "$TEMP/self-test.out"

mkdir -p "$TEMP/bin"
HOME="$TEMP/home" XDG_CACHE_HOME="$TEMP/cache" "$TEMP/skill/scripts/install-cli" \
  --bin-dir "$TEMP/bin" > "$TEMP/install.out"
"$TEMP/bin/grafana-log" --version | grep -q 'grafana-log version'

HOME="$TEMP/home" XDG_CACHE_HOME="$TEMP/cache" "$TEMP/skill/scripts/install-cli" \
  --bin-dir "$TEMP/bin" > "$TEMP/reinstall.out"
grep -q '已是' "$TEMP/reinstall.out"

printf '# unrelated\n' > "$TEMP/bin/grafana-log"
chmod 700 "$TEMP/bin/grafana-log"
if HOME="$TEMP/home" XDG_CACHE_HOME="$TEMP/cache" "$TEMP/skill/scripts/install-cli" \
  --bin-dir "$TEMP/bin" > "$TEMP/refuse.out" 2>&1; then
  printf 'install-cli unexpectedly overwrote a different file\n' >&2
  exit 1
fi
grep -q '使用 --force' "$TEMP/refuse.out"

HOME="$TEMP/home" XDG_CACHE_HOME="$TEMP/cache" "$TEMP/skill/scripts/install-cli" \
  --bin-dir "$TEMP/bin" --force > "$TEMP/force.out"
"$TEMP/bin/grafana-log" --version | grep -q 'grafana-log version'

cp -R "$SKILL" "$TEMP/tampered-skill"
printf 'tampered' >> "$TEMP/tampered-skill/assets/bin/grafana-log-darwin-arm64.gz"
if HOME="$TEMP/home" XDG_CACHE_HOME="$TEMP/tampered-cache" \
  "$TEMP/tampered-skill/scripts/self-test" > "$TEMP/tampered.out" 2>&1; then
  printf 'self-test unexpectedly accepted a tampered archive\n' >&2
  exit 1
fi
grep -q '哈希校验失败' "$TEMP/tampered.out"

printf 'skill_package_test=ok\n'
