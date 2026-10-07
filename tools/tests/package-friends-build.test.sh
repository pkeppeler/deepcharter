#!/usr/bin/env bash
# Tests tools/package-friends-build.sh with a fake mod jar, so it needs no Gradle and no network.
# Usage: tools/tests/package-friends-build.test.sh
set -euo pipefail

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
root=$(cd "$tools/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

failures=0
check() { # check <description> <condition-result: 0 = ok>
  if [[ $2 -eq 0 ]]; then echo "ok   $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}
ok() { # ok <description> <command...>: passes when the command succeeds
  local description=$1
  shift
  if "$@" >/dev/null 2>&1; then check "$description" 0; else check "$description" 1; fi
}
fails() { # fails <description> <command...>: passes when the command fails
  local description=$1
  shift
  if "$@" >/dev/null 2>&1; then check "$description" 1; else check "$description" 0; fi
}

# A fake mod jar: just a zip with a fabric.mod.json.
fake=$work/fake
mkdir -p "$fake"
echo '{"schemaVersion":1,"id":"deepcharter"}' >"$fake/fabric.mod.json"
(cd "$fake" && zip -q -X "$work/deepcharter-0.0.0.jar" fabric.mod.json)

# The script writes to <repo>/build/dist, so run a copy of the tools, docs and gradle.properties.
repo=$work/repo
mkdir -p "$repo/tools" "$repo/docs"
cp "$tools/package-friends-build.sh" "$repo/tools/"
cp "$root/docs/PLAYING.md" "$repo/docs/"
cp "$root/gradle.properties" "$repo/"
script=$repo/tools/package-friends-build.sh
dist=$repo/build/dist
mod_version=$(sed -n 's/^version=//p' "$root/gradle.properties")
label=$mod_version-test
build() { DEEPCHARTER_JAR=$work/deepcharter-0.0.0.jar "$script" "$@"; }

ok "builds with a version" build "$label"
mrpack=$dist/deepcharter-friends-$label.mrpack
serverzip=$dist/deepcharter-server-$label.zip
ok "writes the .mrpack" test -f "$mrpack"
ok "writes the server zip" test -f "$serverzip"
ok "writes PLAYING.md" test -f "$dist/PLAYING.md"
fails "PLAYING.md has no unfilled placeholder" grep -q '{{' "$dist/PLAYING.md"
ok "PLAYING.md stamps the version" grep -qF -- "$label" "$dist/PLAYING.md"
for needle in 'Prism' 'sneak' 'jump' 'sprint' '#57' 'Known issues' 'Report a bug'; do
  ok "PLAYING.md mentions $needle" grep -qi -- "$needle" "$dist/PLAYING.md"
done

# Index
index=$work/modrinth.index.json
unzip -p "$mrpack" modrinth.index.json >"$index"
audit=$root/docs/tooling/sodium-lithium-audit.md
validate_index() {
  python3 -I - "$index" "$audit" "$label" <<'PY'
import json, re, sys

index = json.load(open(sys.argv[1]))
audit = open(sys.argv[2]).read()
assert index["formatVersion"] == 1
assert index["game"] == "minecraft"
assert index["versionId"] == sys.argv[3]
assert index["name"]
assert index["dependencies"] == {"minecraft": "26.3", "fabric-loader": "0.19.5"}, index["dependencies"]
files = {f["path"]: f for f in index["files"]}
for prefix in ("mods/fabric-api-0.162.0+26.3", "mods/sodium-fabric-0.9.2+mc26.3", "mods/lithium-fabric-0.26.2+mc26.3"):
    assert any(p.startswith(prefix) and p.endswith(".jar") for p in files), prefix
for path, f in files.items():
    assert re.fullmatch(r"[0-9a-f]{40}", f["hashes"]["sha1"]), path
    assert re.fullmatch(r"[0-9a-f]{128}", f["hashes"]["sha512"]), path
    assert isinstance(f["fileSize"], int) and f["fileSize"] > 0, path
    assert f["env"]["client"] in ("required", "optional", "unsupported"), path
    assert f["env"]["server"] in ("required", "optional", "unsupported"), path
    assert f["downloads"] and all(u.startswith("https://cdn.modrinth.com/data/") for u in f["downloads"]), path
    # The pins must be the ones the audit page records.
    assert f["hashes"]["sha512"] in audit, path + " sha512 not in the audit page"
    assert f["hashes"]["sha1"] in audit, path + " sha1 not in the audit page"
assert files["mods/sodium-fabric-0.9.2+mc26.3.jar"]["env"]["server"] == "unsupported"
PY
}
ok "index has the required fields and pinned hashes" validate_index

mrpack_entries=$work/mrpack.list
serverzip_entries=$work/server.list
unzip -Z1 "$mrpack" >"$mrpack_entries"
unzip -Z1 "$serverzip" >"$serverzip_entries"
ok "our jar is in overrides/mods" grep -qx 'overrides/mods/deepcharter-0.0.0.jar' "$mrpack_entries"
fails ".mrpack bundles no third-party jar" grep -E '\.jar$' <(grep -vx 'overrides/mods/deepcharter-0.0.0.jar' "$mrpack_entries")

# Server zip
ok "server zip has our jar" grep -qx "deepcharter-server-$label/mods/deepcharter-0.0.0.jar" "$serverzip_entries"
fails "server zip bundles no other jar" grep -E '\.jar$' <(grep -vx "deepcharter-server-$label/mods/deepcharter-0.0.0.jar" "$serverzip_entries")
for f in start.sh server.properties eula.txt mods.lock; do
  ok "server zip has $f" grep -qx "deepcharter-server-$label/$f" "$serverzip_entries"
done
unzip -q "$serverzip" -d "$work/server"
srv=$work/server/deepcharter-server-$label
ok "eula.txt says eula=false" grep -qx 'eula=false' "$srv/eula.txt"
fails "eula.txt never says eula=true" grep -q 'eula=true' "$srv/eula.txt"
ok "start.sh is valid bash" bash -n "$srv/start.sh"
ok "start.sh is executable in the zip" test -x "$srv/start.sh"
ok "start.sh requires Java 25" grep -q -- '-lt 25' "$srv/start.sh"
ok "start.sh pins the launcher hash" grep -Eq '^launcher_sha256=[0-9a-f]{64}$' "$srv/start.sh"
ok "mods.lock lists Lithium and Fabric API only" test "$(cut -d' ' -f2 "$srv/mods.lock" | sort | tr '\n' ' ')" = 'fabric-api-0.162.0+26.3.jar lithium-fabric-0.26.2+mc26.3.jar '
# shellcheck disable=SC2016 # awk program, not shell
ok "mods.lock has sha512 and https URLs" awk '$1 !~ /^[0-9a-f]{128}$/ || $3 !~ /^https:\/\// { bad = 1 } END { exit bad }' "$srv/mods.lock"
ok "server.properties has a whitelist" grep -qx 'white-list=true' "$srv/server.properties"

# start.sh refuses to run until the EULA is accepted, before any download. A stub java stands in for Java 25.
stub=$work/stub
mkdir -p "$stub"
printf '#!/bin/sh\necho %s >&2\n' "'openjdk version \"25.0.1\" 2026-10-20'" >"$stub/java"
chmod +x "$stub/java"
eula_run() { (cd "$srv" && PATH="$stub:$PATH" ./start.sh); }
fails "start.sh refuses with eula=false" eula_run
eula_msg=$(eula_run 2>&1 || true)
ok "start.sh names the EULA when it refuses" grep -q eula <<<"$eula_msg"
printf '#!/bin/sh\necho %s >&2\n' "'openjdk version \"21.0.4\" 2026-07-16'" >"$stub/java"
fails "start.sh refuses Java 21" eula_run

# No XGen asset or private/ path in either archive.
forbidden='(^|/)(original_flash_game|private)(/|$)|\.swf$|(^|/)xgen'
fails ".mrpack has no XGen or private path" grep -Eiq "$forbidden" "$mrpack_entries"
fails "server zip has no XGen or private path" grep -Eiq "$forbidden" "$serverzip_entries"

# A jar that carries one is refused.
mkdir -p "$fake/private"
echo x >"$fake/private/pack.ogg"
(cd "$fake" && zip -q -X -r "$work/bad.jar" fabric.mod.json private)
fails "refuses a jar containing private/" env DEEPCHARTER_JAR="$work/bad.jar" "$script" "$label"
rm -rf "$fake/private"
mkdir -p "$fake/original_flash_game"
echo x >"$fake/original_flash_game/a.swf"
(cd "$fake" && zip -q -X -r "$work/bad2.jar" fabric.mod.json original_flash_game)
fails "refuses a jar containing original_flash_game/" env DEEPCHARTER_JAR="$work/bad2.jar" "$script" "$label"

# A large jar (listing over 64 KB, forbidden path first) must be refused: `unzip | grep -q`
# under pipefail used to let it through.
big=$work/big
mkdir -p "$big/private"
echo x >"$big/private/pack.ogg"
for i in $(seq 1 3000); do echo x >"$big/pad-entry-with-a-long-name-to-fill-the-listing-$i.txt"; done
(cd "$big" && zip -q -X -r "$work/big.jar" private ./*.txt)
ok "large jar listing exceeds 64 KB" test "$(unzip -Z1 "$work/big.jar" | wc -c)" -gt 65536
fails "refuses a large jar with private/ first" env DEEPCHARTER_JAR="$work/big.jar" "$script" "$label"

# start.sh with everything already "downloaded": a stray jar must stop it before java -jar runs.
# The pinned hashes are real, so this run uses a test-local fixture: tiny files, a test-local
# mods.lock carrying their real sha512, and the launcher_sha256 line of the extracted copy
# (not the shipped script) rewritten to the fixture's sha256. Stub curl fails if it is called.
run=$work/run
mkdir -p "$run"
unzip -q "$serverzip" -d "$run"
rsrv=$run/deepcharter-server-$label
sed -i.bak 's/^eula=false$/eula=true/' "$rsrv/eula.txt"
launcher_file=$(sed -n 's/^launcher=//p' "$rsrv/start.sh")
echo fixture-mod >"$rsrv/mods/fixture-mod.jar"
echo fixture-launcher >"$rsrv/$launcher_file"
mod_sha512=$(shasum -a 512 "$rsrv/mods/fixture-mod.jar" | cut -d' ' -f1)
fixture_launcher_sha256=$(shasum -a 256 "$rsrv/$launcher_file" | cut -d' ' -f1)
printf '%s fixture-mod.jar https://example.invalid/fixture-mod.jar\n' "$mod_sha512" >"$rsrv/mods.lock"
sed -i.bak "s/^launcher_sha256=.*/launcher_sha256=$fixture_launcher_sha256/" "$rsrv/start.sh"
rstub=$work/rstub
mkdir -p "$rstub"
cat >"$rstub/java" <<'STUB'
#!/bin/sh
if [ "$1" = "-version" ]; then echo 'openjdk version "25.0.1" 2026-10-20' >&2; exit 0; fi
echo "$@" >>"$JAVA_LOG"
STUB
printf '#!/bin/sh\necho "curl called: $*" >&2\nexit 99\n' >"$rstub/curl"
chmod +x "$rstub/java" "$rstub/curl"
export JAVA_LOG=$work/java.log
real_run() { (cd "$rsrv" && PATH="$rstub:$PATH" ./start.sh); }

: >"$JAVA_LOG"
ok "start.sh launches the launcher jar when mods/ is clean" real_run
ok "start.sh passed the launcher jar to java -jar" grep -qF -- "-jar $launcher_file" "$JAVA_LOG"

echo evil >"$rsrv/mods/evil.jar"
: >"$JAVA_LOG"
fails "start.sh refuses a stray jar in mods/" real_run
stray_msg=$(real_run 2>&1 || true)
ok "start.sh names the stray jar" grep -q 'unexpected jar in mods/: evil.jar' <<<"$stray_msg"
ok "start.sh never ran java -jar with a stray jar" test ! -s "$JAVA_LOG"

# Arguments
fails "refuses a label that does not start with the mod version" build "0.0.0-x"
fails "refuses no version" build
fails "refuses a version with a slash" build "$mod_version/../x"
fails "refuses a missing jar" env DEEPCHARTER_JAR="$work/none.jar" "$script" "$label"

if [[ $failures -ne 0 ]]; then
  echo "$failures failure(s)" >&2
  exit 1
fi
echo "all passed"
