# DawDreamer and pedalboard audit

Read-only audit, done 2026-10-20, of [DawDreamer](https://github.com/DBraun/DawDreamer) 0.9.0 and Spotify's [pedalboard](https://github.com/spotify/pedalboard) 0.9.26, with numpy and mido. They are the Python side of the offline sound pipeline (uv, Python 3.14, M2 Mac). The VST3 plugins have their own audit. Nothing was installed, imported, built or run. **V** = verified (how is stated); **I** = inferred.

## Verdict: safe for local offline rendering, pinned; never a mod dependency

- Neither package has telemetry, an update check or a default network call. **V**
- **pedalboard is small and clean.** The only foreign code it runs is the plugins you load.
- **DawDreamer is a 123 MB native blob**: JUCE plus a prebuilt libfaust with **LLVM 20.1.8 MCJIT**. It compiles Faust source to native code in-process. **V**
  - Risks: a plain-HTTP Faust fetcher and an LLVM JIT (see [What each does at runtime](#what-each-does-at-runtime) and condition 3).
- Neither project publishes attestations. Both upload to PyPI with API tokens. **V**
- Start with pedalboard. Add DawDreamer only if Faust synthesis is wanted.

### Conditions

1. **Pin with hashes.** Use a dev-only `uv` project under `tools/`, with the hashes below and `uv sync --locked`. Keep it out of Gradle, the mod and the `.mrpack`.
2. **Declare numpy and mido ourselves.** DawDreamer declares no dependencies (`install_requires=[]`), but it returns numpy arrays. Neither package depends on mido. **V**
3. **Local Faust only.**
   - The render script refuses Faust source that contains `http://` or `https://`.
   - Never compile third-party Faust code or bitcode (`compile_from_bitcode`).
   - Never unpickle DawDreamer state from elsewhere.
4. **Load plugins by explicit, audited path.** Plugins run unsandboxed in the Python process.
5. **One host per process.** DawDreamer bundles JUCE 7.0.8 and pedalboard bundles JUCE 6.1.4, so render with separate scripts. (**I**: a precaution)
6. **Never commit or ship the wheels or the venv.** Commit only the rendered audio and our scripts.
7. **Keep any clone outside the repo.** DawDreamer's tag ships a `CLAUDE.md`. **V**

## Versions and hashes

Each sha256 was recomputed and equals PyPI's digest. Each wheel is **byte-identical to its CI artifact**, downloaded and compared. **V**

| | dawdreamer | pedalboard |
|---|---|---|
| Wheel (macOS arm64, cp314) | `dawdreamer-0.9.0-cp314-cp314-macosx_11_0_arm64.whl`, 39,748,006 B | `pedalboard-0.9.26-cp314-cp314-macosx_11_0_arm64.whl`, 2,470,554 B |
| sha256 | `1ca73180447622d65b310181d23fe13a5571061f974c78aa56de1d2049cb5db1` | `b231c6dfb56bfdc96de12c7d54cfe1848065153f1b1b2d1e44842fbb11cfac1c` |
| Tag | `v0.9.0` = `25a84b2` (unsigned) | `v0.9.26` = `05e8f0b` (signed merge) |
| CI build | run 31604359316, artifact 9144756410 | run 37677224829, artifact 11507895446 |
| Upload | The CI upload job **failed** at 14:47. This wheel reached PyPI at 16:05 from outside CI (**I**: a manual upload of that artifact). | The CI job uploaded at 21:20, matching PyPI's times. |
| Attestations | none (PyPI integrity API 404) | none (404) |

The dependencies:

- numpy 2.5.4, `cp314-macosx_14_0_arm64`: `956555e0603a4d38019ae6925711cb9dc43195c076a928accf7ea5d50bddfe53`. Attested by `numpy/numpy-release`. BSD-3 and others.
- mido 1.3.3: `01033c9b10b049e4436fca2762194ca839b09a4334091dd3c34e7f4ae674fd8a`. Pure Python, MIT, released 2024-10. No attestation.
  - Its ports, sockets and `amidi` subprocess load only when used. `MidiFile` needs none of them. **V**
- packaging 26.3 (mido's dependency): `d7193f7c8e4e93f444fde0262bf90af30e16fa0ad0ad44cb553c87339b23cd1c`. Attested.

## Licence

Both are **GPL-3.0** (each wheel's `LICENSE`; pedalboard's `NOTICE`). **V**

- **DawDreamer bundles:**
  - JUCE 7.0.8: GPLv3 option.
  - Faust 2.85.9: GPL-2.0+.
  - LLVM: Apache-2.0 with the LLVM exception.
  - Rubber Band: GPL-2.0+ or commercial; the GPL option is used here.
  - libsamplerate (BSD-2), nanobind (BSD-3), LV2 libs (ISC).
  - Its `licenses/` folder lacks the LLVM, nanobind and LV2 texts. That matters only to redistributors.
- **pedalboard bundles:**
  - JUCE 6.1.4 with the VST3 SDK: GPLv3.
  - Rubber Band: GPL-2.0+.
  - LAME/mpglib (LGPL-2), libgsm, libFLAC (BSD-3).
  - GPL FFTW is built for Linux only. The macOS wheel uses Accelerate (CMakeLists; no `fftw` symbols).
- **Neither bundles libsndfile.** **V**

What this means for us (our reading, not a lawyer's):

- The GPL binds on conveying the program; we only run it locally. **Rendered audio is program output, which the tools' licence does not cover** (GPL FAQ). Neither wheel ships samples or impulse responses. **V**
- Our render scripts stay MIT, which is GPL-compatible.

## What each does at runtime

| | DawDreamer | pedalboard |
|---|---|---|
| Native code | one Mach-O, `dawdreamer.so`, 122.7 MB, ad-hoc signed | one Mach-O, 5.0 MB |
| Network | Faust's `http_fetch`: a raw socket on port 80, no TLS, "HTTP Fetcher" User-Agent. It runs only when Faust source imports a URL (`sourcereader.cpp` @2.85.9). The bundled `.lib` files import none. **V** | JUCE's NSURLSession and socket code is linked in, but no pedalboard C++ or Python calls it. **V** (grep) |
| Analytics / update check | none in strings or sources **V** | none **V** |
| Spawn | `fork`/`posix_spawn`/`popen` imported by JUCE and LLVM. Not called from DawDreamer's sources (**V**); dormant (**I**). | `fork`/`execvp`/`system` from JUCE, not called (**V**/**I**) |
| Dynamic code | **LLVM JIT** (`mmap`, `mprotect`, `sys_icache_invalidate`); VST2, VST3, AU and LV2 hosting via `dlopen`/`CFBundle` **V** | VST3 and AU via `dlopen`/`CFBundle`, in-process **V** |
| File writes | only caller paths (`save_state`, `save_midi`) **V** | only `AudioFile(…, "w")` **V** |
| Browser | The JUCE splash screen opens juce.com, on a click only. **V** | same |

## Maintenance and footprint

- **DawDreamer:**
  - Its CI downloads libfaust without a hash check.
  - It is essentially one maintainer, in Alpha. 0.9.0 came 23 months after 0.8.3, and the last commit was on 2026-10-02.
  - CI uses mutable action refs (`@latest`, `@release/v1`).
- **pedalboard:**
  - Spotify, Beta, about monthly releases. 146 open issues, many of them VST3-specific crashes. Its JUCE pin is from 2021.
- **Both:**
  - No `SECURITY.md`, private vulnerability reporting off, no advisories, and OSV clean for all four versions. **V**
- **Unpacked size:** DawDreamer 136 MB, of which 11 MB is unused Faust `architecture/` sources. pedalboard 6.3 MB, numpy 20.7 MB, mido 0.15 MB. **V**

## Not verified

- Importing either package, the JIT under uv's Python, and both packages in one process.
- libfaust's LLVM build. It carries paths from a Cmajor developer's machine (`/Users/cesare/…/cmajor/3rdParty/llvm`): a third-party prebuilt inside a prebuilt. **I**
- That an `https://` Faust import goes out as cleartext. **I**, from the fetcher code.
- A rebuild from source.

## Method

PyPI JSON and integrity APIs; GitHub runs, jobs and artifacts via `gh`; shallow tag clones in scratch. Wheels unpacked with `unzip`, read with `otool`, `nm -u`, `strings`, `codesign -dv` and `grep`; hashes checked with `shasum` and against `RECORD`.
