#!/usr/bin/env bash
# Builds the friends build into build/dist/:
#   deepcharter-friends-<version>.mrpack  Modrinth modpack (format v1): pinned CDN links + our jar
#   deepcharter-server-<version>.zip      our jar, start.sh, server.properties, eula.txt (eula=false)
#   PLAYING.md                            install and play notes (from docs/PLAYING.md)
# No third-party jar is bundled: the .mrpack links the Modrinth CDN and the server's
# start.sh fetches the same pinned files and checks sha512. Pins and licence notes:
# docs/tooling/sodium-lithium-audit.md. Nothing is uploaded; this script has no network
# access beyond `./gradlew build` (skipped when DEEPCHARTER_JAR is set).
#
# Usage: tools/package-friends-build.sh <version>
#   <version> is the release label. It must start with the `version=` in gradle.properties
#   (the mod's own version), e.g. 0.2.0 or 0.2.0-friends1.
# Env:   DEEPCHARTER_JAR  use this jar instead of running ./gradlew build (CI, tests)
# The leak guard below is best-effort: it checks the entry names of the mod jar only, not
# the contents of files or nested archives.
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <version>" >&2
  exit 2
fi
version=$1
if [[ ! $version =~ ^[0-9A-Za-z][0-9A-Za-z._+-]*$ ]]; then
  echo "error: version '$version' must match [0-9A-Za-z][0-9A-Za-z._+-]*" >&2
  exit 2
fi

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
dist=$root/build/dist

mod_version=$(sed -n 's/^version=//p' "$root/gradle.properties")
if [[ -z $mod_version || $mod_version == *$'\n'* ]]; then
  echo "error: need exactly one version= line in gradle.properties" >&2
  exit 1
fi
if [[ $version != "$mod_version"* ]]; then
  echo "error: version '$version' must start with the mod version $mod_version (gradle.properties)" >&2
  exit 2
fi

minecraft_version=26.3
loader_version=0.19.5

# Pins. Record: path | url | size | sha1 | sha512 | client env | server env.
# Hashes and sizes come from the Modrinth API (see the audit page); server env is
# "unsupported" for client-only mods, which also keeps them out of the server's mods.lock.
cdn=https://cdn.modrinth.com/data
pins=(
  "fabric-api-0.162.0+26.3.jar|$cdn/P7dR8mSH/versions/v2j28coa/fabric-api-0.162.0%2B26.3.jar|1772377|273cd2dcbd92d1559edcc91c9f23fee47f6ff93f|5ab70908952f1d2346d16b122ca31327f4055db59c279a1bc3c3c581ce359bb541cba15730f55b0727be7c7c4debc4a4412a183c228e0d4dea81c734f7b412ce|required|required"
  "sodium-fabric-0.9.2+mc26.3.jar|$cdn/AANobbMI/versions/bAZQdGpg/sodium-fabric-0.9.2%2Bmc26.3.jar|1908068|9acfe851e36f4fb27d7c5baa224e8bd4e0334283|f3f260f204b8ce5e8777c2c73f537956755ec61f3817b0b78b5e5568e8aa40f14891f146475075109747665c7c88a45ca0ca9a8df3c68d79c58ecdaa8d8c4b93|required|unsupported"
  "lithium-fabric-0.26.2+mc26.3.jar|$cdn/gvQqBUqZ/versions/xS0Q8LSi/lithium-fabric-0.26.2%2Bmc26.3.jar|914543|dd5a4ec2c68d7601d8bce9076f789c55a5f8bef3|4d7fee66132eedc71feab9390b92c95d7058edbdad0fecfac1d836a2950b97a7ca463afbede61c7ef361ce65e1f927ab9e89dde5bf2e0e0ce486afb5c5dbee40|required|required"
)

# Fabric server launcher for the pinned Minecraft and loader (installer 1.1.2). It
# downloads Mojang's server jar on first run; we ship neither. sha256 recorded 2026-10-07.
launcher_name=fabric-server-mc.$minecraft_version-loader.$loader_version-launcher.1.1.2.jar
launcher_url=https://meta.fabricmc.net/v2/versions/loader/$minecraft_version/$loader_version/1.1.2/server/jar
launcher_sha256=0b56ad54d762172e8b8748e467f584f071e4dedecd93dc336cf2c68837e790be

# Our jar.
if [[ -n ${DEEPCHARTER_JAR:-} ]]; then
  jar=$DEEPCHARTER_JAR
else
  (cd "$root" && ./gradlew build -q)
  jar=$root/build/libs/deepcharter-$mod_version.jar
fi
if [[ ! -f $jar ]]; then
  echo "error: mod jar $jar not found (set DEEPCHARTER_JAR or run ./gradlew build)" >&2
  exit 1
fi
jar_name=$(basename "$jar")

# Never ship Flash-game assets or private files, whatever the jar or tree holds.
forbidden='(^|/)(original_flash_game|private)(/|$)|\.swf$|(^|/)xgen'
# Capture the listing first: `unzip | grep -q` under pipefail fails open on a large jar
# (grep exits at the first match, unzip dies of SIGPIPE, the pipeline reads as "no match").
if ! entries=$(unzip -Z1 "$jar"); then
  echo "error: cannot list $jar" >&2
  exit 1
fi
if grep -Eiq "$forbidden" <<<"$entries"; then
  echo "error: $jar contains a forbidden path:" >&2
  grep -Ei "$forbidden" <<<"$entries" | head -5 >&2
  exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$dist"
rm -f "$dist/deepcharter-friends-$version.mrpack" "$dist/deepcharter-server-$version.zip" "$dist/PLAYING.md"

# .mrpack
pack=$work/pack
mkdir -p "$pack/overrides/mods"
cp "$jar" "$pack/overrides/mods/$jar_name"
{
  printf '{\n'
  printf '  "formatVersion": 1,\n'
  printf '  "game": "minecraft",\n'
  printf '  "versionId": "%s",\n' "$version"
  printf '  "name": "Deep Charter friends build",\n'
  printf '  "summary": "Deep Charter with Sodium and Lithium for Minecraft %s.",\n' "$minecraft_version"
  printf '  "files": [\n'
  sep=
  for pin in "${pins[@]}"; do
    IFS='|' read -r path url size sha1 sha512 client_env server_env <<<"$pin"
    printf '%s    {\n' "$sep"
    printf '      "path": "mods/%s",\n' "$path"
    printf '      "hashes": { "sha1": "%s", "sha512": "%s" },\n' "$sha1" "$sha512"
    printf '      "env": { "client": "%s", "server": "%s" },\n' "$client_env" "$server_env"
    printf '      "downloads": ["%s"],\n' "$url"
    printf '      "fileSize": %s\n' "$size"
    printf '    }'
    sep=$',\n'
  done
  printf '\n  ],\n'
  printf '  "dependencies": { "minecraft": "%s", "fabric-loader": "%s" }\n' "$minecraft_version" "$loader_version"
  printf '}\n'
} >"$pack/modrinth.index.json"
python3 -I -m json.tool "$pack/modrinth.index.json" >/dev/null
(cd "$pack" && zip -q -X -r "$dist/deepcharter-friends-$version.mrpack" modrinth.index.json overrides)

# Server zip
srv=$work/deepcharter-server-$version
mkdir -p "$srv/mods"
cp "$jar" "$srv/mods/$jar_name"
for pin in "${pins[@]}"; do
  IFS='|' read -r path url _ _ sha512 _ server_env <<<"$pin"
  [[ $server_env == unsupported ]] && continue
  printf '%s %s %s\n' "$sha512" "$path" "$url" >>"$srv/mods.lock"
done
printf 'eula=false\n' >"$srv/eula.txt"
cat >"$srv/server.properties" <<'PROPS'
# Deep Charter friends server. Whitelist is on: `whitelist add <name>` from the console.
motd=Deep Charter
online-mode=true
white-list=true
enforce-whitelist=true
max-players=8
difficulty=normal
gamemode=survival
server-port=25565
view-distance=10
simulation-distance=8
spawn-protection=0
enable-command-block=false
PROPS
cat >"$srv/start.sh" <<START
#!/usr/bin/env bash
# Deep Charter friends server. Needs Java 25 and internet on first run.
# 1. Set eula=true in eula.txt after reading https://aka.ms/MinecraftEULA (your call, not ours).
# 2. Run ./start.sh. It fetches the pinned mods in mods.lock and the Fabric server
#    launcher (sha-checked), then starts the server. The launcher downloads Mojang's
#    server jar itself; this zip contains no Minecraft code.
set -euo pipefail
cd "\$(dirname "\$0")"

launcher=$launcher_name
launcher_url=$launcher_url
launcher_sha256=$launcher_sha256

java_major=\$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
if [[ -z \$java_major || \$java_major -lt 25 ]]; then
  echo "error: Java 25 or newer is required (found: \${java_major:-none})" >&2
  exit 1
fi

if ! grep -qx 'eula=true' eula.txt; then
  echo "error: set eula=true in eula.txt first (https://aka.ms/MinecraftEULA)" >&2
  exit 1
fi

hash_of() { # hash_of <algo> <file>
  if command -v shasum >/dev/null 2>&1; then shasum -a "\$1" "\$2" | cut -d' ' -f1; else "sha\$1sum" "\$2" | cut -d' ' -f1; fi
}

fetch() { # fetch <url> <file> <algo> <expected hash>
  if [[ -f \$2 && \$(hash_of "\$3" "\$2") == "\$4" ]]; then return 0; fi
  echo "fetching \$2"
  curl -fL --proto '=https' --tlsv1.2 -o "\$2.part" "\$1"
  if [[ \$(hash_of "\$3" "\$2.part") != "\$4" ]]; then
    rm -f "\$2.part"
    echo "error: \$2 does not match its pinned \$3" >&2
    exit 1
  fi
  mv "\$2.part" "\$2"
}

mkdir -p mods
while read -r sha512 file url; do
  fetch "\$url" "mods/\$file" 512 "\$sha512"
done <mods.lock
fetch "\$launcher_url" "\$launcher" 256 "\$launcher_sha256"

# Nothing unverified may load: every jar in mods/ is ours or a lock entry (hash-checked above).
for jar in mods/*.jar; do
  [[ -e \$jar ]] || continue
  name=\${jar#mods/}
  if grep -qF " \$name " mods.lock || [[ \$name == "$jar_name" ]]; then continue; fi
  echo "error: unexpected jar in mods/: \$name (remove it)" >&2
  exit 1
done

exec java -Xms2G -Xmx4G -jar "\$launcher" nogui
START
chmod +x "$srv/start.sh"
(cd "$work" && zip -q -X -r "$dist/deepcharter-server-$version.zip" "deepcharter-server-$version")

# PLAYING.md
sed "s/{{VERSION}}/$version/g" "$root/docs/PLAYING.md" >"$dist/PLAYING.md"

echo "built:"
ls -1 "$dist"/deepcharter-friends-"$version".mrpack "$dist"/deepcharter-server-"$version".zip "$dist"/PLAYING.md
