# Surge XT, Dexed and vorbis-tools audit

Read-only audit, 2026-10-20, of the offline sound tools: Surge XT and Dexed as VST3 plugins in pedalboard, and `oggenc` for mono Ogg Vorbis. Nothing was installed, built or run. **[V]** = verified (how), **[I]** = inferred.

## Verdict: safe offline, with conditions

- **vorbis-tools 1.4.3: safe.** No root needed; `oggenc` has no network code.
- **Dexed 1.0.1: safe.** No network code. One determinism trap.
- **Surge XT 1.3.4: safe if we load only our own patches.**
  - **Patch code:** a hostile patch can run shell commands through its Lua (Runtime behaviour, condition 3).
  - **Footprint:** a root installer writes about 780 MB with no way to deselect parts ([What the installers write](#what-the-installers-write)).
  - **Determinism:** clock-seeded, no exposed seed ([Determinism](#determinism), condition 5).
- **Needs the user:** approve two `sudo` installs, or pick the no-root route (condition 2).

## Versions, hashes, signing

| | Surge XT | Dexed | vorbis-tools |
|---|---|---|---|
| Version | 1.3.4, newest stable | 1.0.1, newest | 1.4.3 |
| URL | `github.com/surge-synthesizer/releases-xt/releases/download/1.3.4/surge-xt-macOS-1.3.4.dmg` | `github.com/asb2m10/dexed/releases/download/v1.0.1/Dexed-1.0.1-macOS.dmg` | ghcr bottle `arm64_golden_gate` |
| Size | 416715686 | 17183630 | 139751 |
| sha256 | `b56e8d51a8dc2382ae668c9ff4e96bae38dbd452af4e584d5a5ef50ab2d09b07` | `a6505103869d15400e7bed1d988d9dd155d6d8204a3d532f5301966ae7507ad8` | `6a9654152cfe33a92cb44ba2e95d2397a29dba5803bd4489847059d57e2a7007` |
| Signed | Developer ID, Paul Walker `547U7G7MZC` | Developer ID, Pascal Gauthier `PZ6T894W8E` | ad hoc (normal for bottles) |
| Notarised | yes, stapled | yes, stapled | n/a |

**[V]** Each sha256 computed with `shasum` equals its cask or formula and the project's own checksum list. I checked signing with `codesign`, `pkgutil --check-signature`, `spctl` and `stapler`. Both VST3 bundles pass `codesign --verify --deep --strict`, use the hardened runtime, have no entitlements and link only Apple frameworks. The source tarball (`a1fe3ddc…0dde`) equals the formula and Xiph's `SHA256SUMS`; there is no detached signature.

**Built by CI from the tag:**

- **Surge, [V] indirect.** The tag `release_xt_1.3.4` is GPG-verified and points at `f7b97c6`. The `releases-xt` Azure pipeline builds, signs and notarises in CI, then publishes as `surge-rackupdater`. The release notes it writes start at `f7b97c6`. I cannot see the run logs.
- **Dexed, [V].** The tag `v1.0.1` points at `bce5dee`. Actions run 19787982313 uploaded the DMG as `github-actions[bot]`, and the pkg was signed during that run's macOS job.
- **vorbis-tools, [V].** `gh attestation verify` passes: homebrew-core `dispatch-build-bottle.yml`.

## What the installers write

The Surge XT cask runs a root installer that writes about 780 MB, including apps and AU/CLAP plugins, with no way to deselect them.

**[V]** from `pkgutil --expand`, `lsbom`, the Distribution and Scripts files, and Homebrew's `pkg.rb`.

**Surge.** The cask installs every component:

- `Surge XT` and `Surge XT Effects` bundles in `/Library/Audio/Plug-Ins/{VST3,Components,CLAP}/`
- two apps in `/Applications`
- `/Library/Application Support/Surge XT/`: 377 MB of patches, wavetables and skins

A root `postinstall` script moves the apps and the data out of `/tmp/SXT` and runs `chown -R $USER:staff`. Homebrew sets `USER` to you. **[I]** `brew uninstall` leaves the data folder behind, because the package receipt does not list it.

**Dexed.** `Dexed.{vst3,component,clap}` go in the same folders, and `/Applications/Dexed.app` goes in through `/tmp/dsgb-install` the same way.

Neither installer adds a launch agent or daemon, a kernel extension, a login item or a helper tool.

**vorbis-tools.** It installs into `/opt/homebrew/Cellar` without `sudo`, with libvorbis 1.3.7, libogg 1.3.6, flac 1.5.0 and libao 1.2.2. None of these is installed yet. `oggenc` links only libvorbis, libogg and libFLAC. libao and libcurl serve `ogg123`.

## Licence

| | Licence | Bundled content |
|---|---|---|
| Surge | GPL-3.0 | 78 of 2753 patches say CC0; the rest fall under the repo's GPL-3 **[I]** |
| Dexed | GPL-3.0 (engine Apache-2.0) | 33 built-in cartridges, no licence file |
| vorbis-tools | GPL-2.0 | libvorbis and libogg are BSD-3 |

- **We write our own patches and voices.** Never load factory or third-party patches, presets, cartridges or wavetables: a wavetable's samples end up in the audio **[I]**.
- **We ship only our rendered `.ogg` files.** Licences on a program do not cover its output **[I]**.

## Runtime behaviour

**Network.** **[V]** I grepped the source at each tag and ran `strings` on the binaries.

- Surge and Dexed have no update check and no telemetry.
- Every Surge URL opens in the browser on a click. Dexed has two About-panel links.
- Surge's OSC (UDP) stays off unless a saved DAW state turns it on.
- Neither binary contains a JUCE usage-reporting endpoint.
- JUCE's core modules pull in the socket and `NSURLSession` imports. **[I]** Neither plugin phones home at load. Nothing was traced at runtime.

**File writes.** **[V]** from the source.

- **Surge, at load:** creates `~/Documents/Surge XT/`. It creates `SurgePatches.db` only when the patch browser opens or a patch is saved. It reads factory data from `~/Library/Application Support/Surge XT`, else `/Library/...`; `SURGE_DATA_HOME` overrides the path.
- **Dexed, at load:** creates `~/Library/Application Support/DigitalSuburban/Dexed/` and unzips its factory cartridges there on first run.

**Code loading:**

- **MTS-ESP, [V].** Both plugins `dlopen` `/Library/Application Support/MTS-ESP/libMTS.dylib` if it exists. The folder is absent and writing it needs admin rights.
- **Surge Lua, [V].** Top-level Lua in a patch runs with LuaJIT's `os` and `io`, so a hostile patch could run shell commands. `LuaSupport.cpp` runs a patch's Lua chunk with `lua_pcall` after `luaL_openlibs`, and sandboxes only the function it defines. Upstream `main` is unchanged. I decoded all 43 shipped formula scripts: none uses `os`, `io`, `ffi`, `require` or `load`.

## Determinism

- **Surge, [V].** The engine RNG is seeded from `system_clock::now()`, and its `seed_rand` is commented out. Drift uses `srand(time)`. Random modulators and the chowdsp Tape and Spring Reverb effects use `std::random_device`. No seed option exists.

  **[I]** A patch renders repeatably only if it avoids every random source:
  - oscillator Retrigger on
  - drift, noise and unison off
  - no Random or S&H LFO, and no random MSEG
  - no Alias, String or S&H oscillator
  - no chowdsp Tape or Spring Reverb
- **Dexed, [V].** The engine is fixed-point, but `Lfo::phase_` and the S&H `randstate_` are never initialised. Use LFO Key Sync on and avoid the S&H wave.
- **oggenc, [V] then [I].** Without `-s`, the stream serial is `rand()` seeded with `time ^ pid`. No ENCODER or date tag is added, and libvorbis makes no time or `rand` calls. So `oggenc -s <n>` should give identical bytes for identical input on the same build. Don't byte-compare across machines: float encoding can differ by CPU.

## Conditions

1. **Pin** the hashes above, and re-audit before any update.
2. **Install route.** Either approve the `sudo` casks, or use the no-root route: copy `Surge XT.vst3`, `Dexed.vst3` and Surge's data from the verified pkgs into a user folder and set `SURGE_DATA_HOME` **[I]**, untested.
3. **Load only patches we wrote.** Never factory, third-party or downloaded `.fxp`, `.vstpreset` or `.syx` files.
4. **Keep `/Library/Application Support/MTS-ESP/` absent.**
5. **Follow the determinism rules.** Render twice and compare hashes before trusting a patch.
6. **Never ship, commit or bundle the tools or their content.**
7. **On removal,** also delete `/Library/Application Support/Surge XT`, `~/Documents/Surge XT` and `~/Library/Application Support/DigitalSuburban`.

## Not verified

- Runtime: no network capture, no render test, and no pedalboard hosting test.
- No rebuild from source.
- Submodule sources (JUCE, LuaJIT, MTS-ESP) only through `strings`.

## Method

- **Metadata:** Homebrew API, `gh api`, `gh release view`.
- **Installers:** the DMGs were mounted with `hdiutil attach -readonly -nobrowse`, then detached. The payloads were unpacked with `tar` into empty directories.
- **Source:** shallow clones at the tags and the Xiph tarballs, read with `grep`. Formula scripts were decoded with `python3 -I`.
