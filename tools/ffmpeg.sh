#!/usr/bin/env bash
# Single source of truth for the CI ffmpeg pin. ci.yml and record-evidence.yml both reach it through
# .github/actions/install-ffmpeg.
# Usage: tools/ffmpeg.sh install DIR  download the pinned static build and put ffmpeg and ffprobe in DIR (CI only)
set -euo pipefail

# BtbN/FFmpeg-Builds (github.com/BtbN/FFmpeg-Builds) builds in public GitHub Actions and publishes the
# archives as release assets. The release tag is a monthly build: BtbN prunes daily tags but keeps one per month.
# The GPL build has libvorbis and libx264, which tools/build-audio-pack.sh and tools/record-evidence.sh use.
# The sha256 matches the digest GitHub lists for the asset.
FFMPEG_TAG=autobuild-2026-08-31-13-27
FFMPEG_ARCHIVE=ffmpeg-n8.1.2-50-g1a748fe2cd-linux64-gpl-8.1
FFMPEG_URL="https://github.com/BtbN/FFmpeg-Builds/releases/download/${FFMPEG_TAG}/${FFMPEG_ARCHIVE}.tar.xz"
FFMPEG_SHA256=c733b4b2951e5957e15505f788b2c65a7a41b6da4b289e295852cc38079b4d2b

if [[ ${1:-} != install ]]; then
  echo "usage: tools/ffmpeg.sh install DIR" >&2
  exit 2
fi
dest=${2:?usage: tools/ffmpeg.sh install DIR}

fail() {
  echo "::error::install ffmpeg: $1" >&2
  exit 1
}

mkdir -p "$dest"
archive="$dest/ffmpeg.tar.xz"
curl -fsSL --retry 3 --connect-timeout 20 --max-time 240 -o "$archive" "$FFMPEG_URL" \
  || fail "download of $FFMPEG_URL failed"
echo "$FFMPEG_SHA256  $archive" | sha256sum -c - \
  || fail "sha256 of $FFMPEG_URL does not match the pin in tools/ffmpeg.sh"
tar -xJf "$archive" -C "$dest" --strip-components=2 "$FFMPEG_ARCHIVE/bin/ffmpeg" "$FFMPEG_ARCHIVE/bin/ffprobe" \
  || fail "could not extract ffmpeg and ffprobe from the archive"
rm "$archive"
"$dest/ffmpeg" -hide_banner -encoders 2>/dev/null | grep -F libvorbis >/dev/null || fail "the pinned build has no libvorbis"
