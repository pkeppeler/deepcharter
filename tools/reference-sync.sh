#!/usr/bin/env bash
# Usage: tools/reference-sync.sh [PROPERTIES_FILE]
#
# Shallow-clones Fabric's source into the git-ignored reference/ folder, at the
# exact versions the build uses, so agents and humans can read the real code:
#
#   reference/fabric-api     github.com/FabricMC/fabric         fabric_api_version  tag "<v>"   (e.g. 0.162.0+26.3)
#   reference/fabric-loom    github.com/FabricMC/fabric-loom    loom_version        tag "v<v>"  (e.g. v1.18.2)
#   reference/fabric-loader  github.com/FabricMC/fabric-loader  loader_version      tag "<v>"   (e.g. 0.19.5)
#
# Versions are read from PROPERTIES_FILE (default: gradle.properties at the repo
# root). A missing file, missing/empty key, or missing upstream tag is a hard error.
#
# Idempotent: a re-run at the same versions only checks local state. A version
# bump re-fetches the new tag and moves the clone to it.
#
# Read-only reference material, never part of the build: nothing in the Gradle
# build may include reference/. Agent config files (CLAUDE.md, AGENTS.md,
# .claude/, .agents/, .mcp.json, .cursor/, .github/copilot-instructions.md) are
# excluded from the checkouts with a sparse-checkout, so they never load as
# instructions in a Claude Code session.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
props="${1:-$repo_root/gradle.properties}"
ref_dir="$repo_root/reference"

# name | upstream repo | gradle.properties key | tag prefix
specs=(
  "fabric-api|FabricMC/fabric|fabric_api_version|"
  "fabric-loom|FabricMC/fabric-loom|loom_version|v"
  "fabric-loader|FabricMC/fabric-loader|loader_version|"
)

# Non-cone sparse-checkout patterns: everything except agent config (at any depth).
sparse_patterns='/*
!CLAUDE.md
!AGENTS.md
!.claude/
!.agents/
!.mcp.json
!.cursor/
!/.github/copilot-instructions.md'

die() { echo "reference-sync: error: $*" >&2; exit 1; }

[[ -f "$props" ]] || die "properties file not found: $props"

# Prints the value of a key from the properties file; fails if absent or empty.
prop() {
  local key="$1" value
  value="$(awk -F= -v k="$key" '
    { sub(/\r$/, ""); gsub(/^[ \t]+|[ \t]+$/, "", $1) }
    $1 == k { v = $0; sub(/^[^=]*=/, "", v); gsub(/^[ \t]+|[ \t]+$/, "", v); print v; exit }
  ' "$props")"
  [[ -n "$value" ]] || die "key '$key' is missing or empty in $props"
  printf '%s\n' "$value"
}

# Fail on any bad key before touching the network or disk.
for spec in "${specs[@]}"; do
  IFS='|' read -r _ _ key _ <<<"$spec"
  prop "$key" >/dev/null
done

# Fails if agent config survived in a clone (guards the sparse-checkout).
assert_no_agent_config() {
  local dir="$1" hits
  hits="$(find "$dir" -path "$dir/.git" -prune -o \( \
    -name CLAUDE.md -o -name AGENTS.md -o -name .claude -o -name .agents \
    -o -name .mcp.json -o -name .cursor -o -path '*/.github/copilot-instructions.md' \
    \) -print)"
  [[ -z "$hits" ]] || die "agent config present in $dir:
$hits"
}

sync_one() {
  local name="$1" upstream="$2" key="$3" prefix="$4"
  local version tag dir url
  version="$(prop "$key")"
  tag="$prefix$version"
  dir="$ref_dir/$name"
  url="https://github.com/$upstream.git"

  # Quick check: clone already at this tag's commit, tree clean of agent config.
  if [[ -d "$dir/.git" ]]; then
    local want have
    want="$(git -C "$dir" rev-parse --verify --quiet "refs/tags/$tag^{commit}" || true)"
    have="$(git -C "$dir" rev-parse HEAD 2>/dev/null || true)"
    if [[ -n "$want" && "$want" == "$have" ]]; then
      assert_no_agent_config "$dir"
      echo "$name: already at $tag"
      return
    fi
  fi

  git ls-remote --exit-code --tags "$url" "refs/tags/$tag" >/dev/null 2>&1 \
    || die "$name: no tag '$tag' in $url (from $key=$version in $props)"

  mkdir -p "$dir"
  if [[ ! -d "$dir/.git" ]]; then
    git init --quiet "$dir"
    git -C "$dir" remote add origin "$url"
  fi
  git -C "$dir" config core.sparseCheckout true
  git -C "$dir" config core.sparseCheckoutCone false
  mkdir -p "$dir/.git/info"
  printf '%s\n' "$sparse_patterns" >"$dir/.git/info/sparse-checkout"

  git -C "$dir" fetch --quiet --depth 1 --no-tags origin "refs/tags/$tag:refs/tags/$tag"
  git -C "$dir" checkout --quiet --force --detach "refs/tags/$tag"
  # Drop tags from previous versions so the clone holds only the current one.
  local old
  while read -r old; do
    [[ "$old" == "$tag" ]] || git -C "$dir" tag --delete "$old" >/dev/null
  done < <(git -C "$dir" tag --list)

  assert_no_agent_config "$dir"
  echo "$name: synced to $tag"
}

mkdir -p "$ref_dir"
for spec in "${specs[@]}"; do
  IFS='|' read -r name upstream key prefix <<<"$spec"
  sync_one "$name" "$upstream" "$key" "$prefix"
done
