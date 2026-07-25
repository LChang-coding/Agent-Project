#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
VERSION=${VERSION:-v0.2.0}
DIST="$ROOT/dist/$VERSION"
ASSETS="$ROOT/skill/grafana-log-inspector/assets/bin"
ASSETS_TMP="$ROOT/skill/grafana-log-inspector/assets/.bin.tmp.$$"
PACKAGE="github.com/codeliu/ai-agent-scaffold/tools/grafana-log-cli/internal/cli.Version"

cd "$ROOT"
rm -rf "$DIST"
rm -rf "$ASSETS_TMP"
mkdir -p "$DIST" "$ASSETS_TMP"
trap 'rm -rf "$ASSETS_TMP"' EXIT HUP INT TERM

for target in darwin/amd64 darwin/arm64 linux/amd64 linux/arm64; do
  os=${target%/*}
  arch=${target#*/}
  name="grafana-log-$os-$arch"
  CGO_ENABLED=0 GOOS="$os" GOARCH="$arch" \
    go build -trimpath -buildvcs=false -mod=readonly \
      -ldflags "-s -w -X $PACKAGE=$VERSION" \
      -o "$DIST/$name" "$ROOT/cmd/grafana-log"
  gzip -n -9 -c "$DIST/$name" > "$ASSETS_TMP/$name.gz"
done

(
  cd "$ASSETS_TMP"
  for archive in grafana-log-*.gz; do
    shasum -a 256 "$archive"
  done > SHA256SUMS
  printf '%s\n' "$VERSION" > VERSION
  darwin_amd64=$(awk '$2 == "grafana-log-darwin-amd64.gz" {print $1}' SHA256SUMS)
  darwin_arm64=$(awk '$2 == "grafana-log-darwin-arm64.gz" {print $1}' SHA256SUMS)
  linux_amd64=$(awk '$2 == "grafana-log-linux-amd64.gz" {print $1}' SHA256SUMS)
  linux_arm64=$(awk '$2 == "grafana-log-linux-arm64.gz" {print $1}' SHA256SUMS)
  printf '%s\n' \
    '{' \
    '  "schemaVersion": 1,' \
    '  "name": "grafana-log",' \
    "  \"version\": \"$VERSION\"," \
    '  "entrypoint": "scripts/grafana-log",' \
    '  "installEntrypoint": "scripts/install-cli",' \
    '  "platforms": [' \
    "    {\"os\":\"darwin\",\"arch\":\"amd64\",\"archive\":\"grafana-log-darwin-amd64.gz\",\"sha256\":\"$darwin_amd64\"}," \
    "    {\"os\":\"darwin\",\"arch\":\"arm64\",\"archive\":\"grafana-log-darwin-arm64.gz\",\"sha256\":\"$darwin_arm64\"}," \
    "    {\"os\":\"linux\",\"arch\":\"amd64\",\"archive\":\"grafana-log-linux-amd64.gz\",\"sha256\":\"$linux_amd64\"}," \
    "    {\"os\":\"linux\",\"arch\":\"arm64\",\"archive\":\"grafana-log-linux-arm64.gz\",\"sha256\":\"$linux_arm64\"}" \
    '  ]' \
    '}' > RELEASE.json
)

(
  cd "$DIST"
  for binary in grafana-log-*; do
    shasum -a 256 "$binary"
  done > SHA256SUMS
)

rm -rf "$ASSETS"
mv "$ASSETS_TMP" "$ASSETS"
trap - EXIT HUP INT TERM

printf 'release=%s\nassets=%s\n' "$DIST" "$ASSETS"
