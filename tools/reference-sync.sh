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
# root). A missing file, missing/empty key, or missing upstream tag is a hard
# error. With a duplicate key the last one wins, as in Gradle.
#
# Idempotent: a re-run at the same versions only checks local state (and
# re-applies the current checkout patterns, repairing an older clone). A version
# bump re-fetches the new tag and moves the clone to it.
#
# Read-only reference material, never part of the build: nothing in the Gradle
# build may include reference/. Only source and build files are checked out (a
# sparse-checkout allowlist), and every run verifies that no agent config,
# dot-directory or symlink is present, so a clone can never act as
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

# Allowlist of checked-out files (sparse-checkout globs, matched at any depth).
allowed=(
  '*.java' '*.kt' '*.kts' '*.gradle' '*.groovy' '*.json' '*.properties'
  '*.accesswidener' '*.classtweaker' '*.toml' '*.xml' '*.txt'
  'README.md' 'LICENSE*'
)
# Agent-config files, matched case-insensitively by name. Negated in the sparse
# patterns (an allowed type such as *.json can still be agent config) and
# checked for after every checkout, independent of the patterns. Agent-config
# directories are not listed: every dot-directory is excluded (see below).
agent_names=(
  'claude*.md' 'agents.md' 'gemini.md' '.cursorrules' '.windsurfrules'
  '.clinerules' '*.mdc' '.mcp.json' 'copilot-instructions.md'
)

die() { echo "reference-sync: error: $*" >&2; exit 1; }

[[ -f "$props" ]] || die "properties file not found: $props"

# Prints the value of a key from the properties file (last match wins); fails if
# absent or empty.
prop() {
  local key="$1" value
  value="$(awk -F= -v k="$key" '
    { sub(/\r$/, ""); gsub(/^[ \t]+|[ \t]+$/, "", $1) }
    $1 == k { v = $0; sub(/^[^=]*=/, "", v); gsub(/^[ \t]+|[ \t]+$/, "", v); last = v }
    END { print last }
  ' "$props")"
  [[ -n "$value" ]] || die "key '$key' is missing or empty in $props"
  printf '%s\n' "$value"
}

# Fail on any bad key before touching the network or disk.
for spec in "${specs[@]}"; do
  IFS='|' read -r _ _ key _ <<<"$spec"
  prop "$key" >/dev/null
done

# Writes the sparse-checkout patterns: the allowlist, then the negations of
# agent-config files and of every dot-directory at any depth.
write_sparse_patterns() {
  local dir="$1" p
  mkdir -p "$dir/.git/info"
  {
    printf '%s\n' "${allowed[@]}"
    for p in "${agent_names[@]}"; do printf '!%s\n' "$p"; done
    # `!.*/` alone leaves the files inside a dot-directory checked out.
    printf '%s\n' '!**/.*/**'
  } >"$dir/.git/info/sparse-checkout"
  git -C "$dir" config core.sparseCheckout true
  git -C "$dir" config core.sparseCheckoutCone false
}

# Fails if an agent-config file, any dot-directory (other than the clone's own
# .git) or any symlink is in a clone. Independent of the patterns.
assert_clean() {
  local dir="$1" hits p
  local args=(-type l -o \( -type d -name '.*' \))
  for p in "${agent_names[@]}"; do args+=(-o -iname "$p"); done
  hits="$(find "$dir" -mindepth 1 -path "$dir/.git" -prune -o \( "${args[@]}" \) -print)"
  [[ -z "$hits" ]] || die "agent config, dot-directory or symlink present in $dir:
$hits"
}

sync_one() {
  local name="$1" upstream="$2" key="$3" prefix="$4"
  local version tag dir url
  version="$(prop "$key")"
  tag="$prefix$version"
  dir="$ref_dir/$name"
  url="https://github.com/$upstream.git"

  # Quick check: clone already at this tag's commit.
  if [[ -d "$dir/.git" ]]; then
    local want have
    want="$(git -C "$dir" rev-parse --verify --quiet "refs/tags/$tag^{commit}" || true)"
    have="$(git -C "$dir" rev-parse HEAD 2>/dev/null || true)"
    if [[ -n "$want" && "$want" == "$have" ]]; then
      # Self-heal: re-apply the current patterns so a pattern change repairs this clone.
      write_sparse_patterns "$dir"
      git -C "$dir" sparse-checkout reapply
      assert_clean "$dir"
      echo "$name: already at $tag"
      return
    fi
  fi

  local rc=0 err
  err="$(git ls-remote --exit-code --tags "$url" "refs/tags/$tag" 2>&1 >/dev/null)" || rc=$?
  if [[ $rc -eq 2 ]]; then
    die "$name: no tag '$tag' in $url (from $key=$version in $props)"
  elif [[ $rc -ne 0 ]]; then
    die "$name: git ls-remote failed for $url (exit $rc): $err"
  fi

  mkdir -p "$dir"
  if [[ ! -d "$dir/.git" ]]; then
    git init --quiet "$dir"
    git -C "$dir" remote add origin "$url"
  fi
  write_sparse_patterns "$dir"

  git -C "$dir" fetch --quiet --depth 1 --no-tags origin "refs/tags/$tag:refs/tags/$tag"
  git -C "$dir" checkout --quiet --force --detach "refs/tags/$tag"
  # Drop tags from previous versions so the clone holds only the current one.
  local old
  while read -r old; do
    [[ "$old" == "$tag" ]] || git -C "$dir" tag --delete "$old" >/dev/null
  done < <(git -C "$dir" tag --list)

  assert_clean "$dir"
  echo "$name: synced to $tag"
}

mkdir -p "$ref_dir"
for spec in "${specs[@]}"; do
  IFS='|' read -r name upstream key prefix <<<"$spec"
  sync_one "$name" "$upstream" "$key" "$prefix"
done
