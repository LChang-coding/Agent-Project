#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
VERSION=${VERSION:-v0.1.0}
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
