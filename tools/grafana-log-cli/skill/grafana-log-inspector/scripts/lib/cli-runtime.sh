#!/bin/sh

# This file is sourced by Skill entrypoints. The caller must set SKILL_ROOT.

cli_fail() {
  printf 'grafana-log: %s\n' "$*" >&2
  return 2
}

cli_checksum() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
    return
  fi
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
    return
  fi
  cli_fail "缺少 sha256sum 或 shasum，无法验证内置 CLI"
}

cli_detect_platform() {
  case "$(uname -s)" in
    Darwin) CLI_OS=darwin ;;
    Linux) CLI_OS=linux ;;
    *) cli_fail "不支持的操作系统: $(uname -s)" || return ;;
  esac

  case "$(uname -m)" in
    x86_64|amd64) CLI_ARCH=amd64 ;;
    arm64|aarch64) CLI_ARCH=arm64 ;;
    *) cli_fail "不支持的处理器架构: $(uname -m)" || return ;;
  esac
}

cli_load_asset() {
  CLI_ASSETS="$SKILL_ROOT/assets/bin"
  [ -f "$CLI_ASSETS/VERSION" ] || cli_fail "Skill 缺少 CLI 版本文件" || return
  [ -f "$CLI_ASSETS/SHA256SUMS" ] || cli_fail "Skill 缺少 CLI 哈希清单" || return
  [ -f "$CLI_ASSETS/RELEASE.json" ] || cli_fail "Skill 缺少 CLI 发布清单" || return

  CLI_VERSION=$(tr -d '\r\n' < "$CLI_ASSETS/VERSION")
  [ -n "$CLI_VERSION" ] || cli_fail "CLI 版本为空" || return
  grep -Fq "\"version\": \"$CLI_VERSION\"" "$CLI_ASSETS/RELEASE.json" ||
    cli_fail "CLI 版本与发布清单不一致" || return
  CLI_ARCHIVE="grafana-log-$CLI_OS-$CLI_ARCH.gz"
  CLI_EXPECTED=$(awk -v file="$CLI_ARCHIVE" '$2 == file {print $1}' "$CLI_ASSETS/SHA256SUMS")
  [ -n "$CLI_EXPECTED" ] || cli_fail "构建清单缺少 $CLI_ARCHIVE" || return
  [ -f "$CLI_ASSETS/$CLI_ARCHIVE" ] || cli_fail "Skill 缺少 $CLI_ARCHIVE" || return
}

cli_verify_archive() {
  actual=$(cli_checksum "$CLI_ASSETS/$CLI_ARCHIVE") || return
  [ "$actual" = "$CLI_EXPECTED" ] || cli_fail "Skill 内置 CLI 哈希校验失败" || return
}

cli_prepare_binary() {
  cli_detect_platform || return
  cli_load_asset || return
  cli_verify_archive || return

  cache_root=${XDG_CACHE_HOME:-"$HOME/.cache"}
  cache_key="$CLI_VERSION-$(printf '%s' "$CLI_EXPECTED" | cut -c1-16)"
  CLI_CACHE_DIR="$cache_root/grafana-log-inspector/$cache_key"
  CLI_BINARY="$CLI_CACHE_DIR/grafana-log"
  if [ -x "$CLI_BINARY" ]; then
    return
  fi

  mkdir -p "$CLI_CACHE_DIR"
  temporary="$CLI_CACHE_DIR/.grafana-log.$$"
  trap 'rm -f "$temporary"' EXIT HUP INT TERM
  gzip -dc "$CLI_ASSETS/$CLI_ARCHIVE" > "$temporary" ||
    cli_fail "无法解压内置 CLI" || return
  chmod 700 "$temporary"
  mv -f "$temporary" "$CLI_BINARY"
  trap - EXIT HUP INT TERM
}

cli_verify_all_archives() {
  manifest="$SKILL_ROOT/assets/bin/SHA256SUMS"
  [ -f "$manifest" ] || cli_fail "Skill 缺少 CLI 哈希清单" || return
  count=0
  while read -r expected archive; do
    [ -n "$expected" ] || continue
    case "$archive" in
      grafana-log-*.gz) ;;
      *) cli_fail "哈希清单包含非法资源名: $archive" || return ;;
    esac
    [ -f "$SKILL_ROOT/assets/bin/$archive" ] ||
      cli_fail "Skill 缺少 $archive" || return
    actual=$(cli_checksum "$SKILL_ROOT/assets/bin/$archive") || return
    [ "$actual" = "$expected" ] ||
      cli_fail "$archive 哈希校验失败" || return
    grep -Fq "\"archive\":\"$archive\",\"sha256\":\"$expected\"" \
      "$SKILL_ROOT/assets/bin/RELEASE.json" ||
      cli_fail "$archive 与发布清单不一致" || return
    count=$((count + 1))
  done < "$manifest"
  [ "$count" -eq 4 ] || cli_fail "CLI 平台资源数量应为 4，实际为 $count" || return
}
