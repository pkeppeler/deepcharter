# Sound tooling options

Research from 2026-10-20 into a broader sound palette ([#718](https://github.com/pkeppeler/deepcharter/issues/718)). Nothing here is installed. Read the audits first: [DawDreamer and pedalboard](dawdreamer-pedalboard-audit.md), [Surge XT, Dexed and vorbis-tools](surge-dexed-vorbis-audit.md), [Stable Audio 3](stable-audio-3-audit.md). **V** = verified against a source, **I** = inferred, not tested.

CI re-renders every recipe with the standard library and compares PCM hashes ([ADR 0057](../adr/0057-every-shipped-sound-is-built-from-a-committed-recipe-and-a-layers-drone-lives-in-its-biome-data.md)). None of the tools below run in CI, and compiled DSP is very likely not bit-exact between arm64 and x86 (I). Their output is therefore committed.

## Recommended setup

**Tier 1: tools. Free. Needs one ADR amending ADR 0057 for committed stems, which the orchestrator writes (ADRs are autonomous).**

- **vorbis-tools:** `oggenc` writes mono positional OGGs. `-s` makes the bytes reproducible.
- **A uv-pinned environment with pedalboard:** effects, Rubber Band time-stretch, and a VST3 host.
- **Dexed:** FM bells, glass, metal, organ and UI blips. Use Key Sync on and no S&H wave.
- **Surge XT:** machinery, drills, creatures and pads. Use our own patches only, with no randomness. Its cask needs a root installer (about 780 MB, no way to deselect components), as Dexed's does. The user runs both.
- **DawDreamer, optional:** Faust physical models (reed, bells, choir) for the Lamp Hymn. It has a 123 MB binary and a weaker supply chain (audit).

**Tier 2: AI raw material.** Stable Audio 3 `small-sfx` and `small-music` through the pinned MLX command line (setup: audit conditions 1-3). Never use the one-liner or the web UI. A take is raw material layered under our DSP.

**Tier 3: recordings (the user decides).** NASA Mars audio, Freesound CC0, VCSL CC0 instruments, and the user's own field recordings.

**Hosted** (ElevenLabs SFX, Stability API): only if gaps remain. It costs money, so it is the user's call.

## Synthesis and processing tools

Ranked by fit. Installs are for an arm64 Mac.

| Tool | Adds | Licence | Install | Same PCM each run? |
|---|---|---|---|---|
| vorbis-tools 1.4.3 | Mono Ogg Vorbis ([man page](https://github.com/xiph/vorbis-tools/blob/master/oggenc/man/oggenc.1)) | GPL/LGPL; libvorbis BSD | Homebrew formula | Yes, same libvorbis (I) |
| [pedalboard](https://github.com/spotify/pedalboard) 0.9.26 | VST3 and AU host, convolution reverb, Rubber Band | GPL-3 | pip wheel | Built-in effects yes (I); plugins vary |
| [Dexed](https://github.com/asb2m10/dexed) 1.0.1 | DX7-style FM | GPL-3 | cask `dexed` | Yes, with LFO Key Sync on and no S&H wave |
| [Surge XT](https://surge-synthesizer.github.io/manual-xt/) 1.3.4 | Strings, wavetables, resonator, granular, vocoder | GPL-3 | cask `surge-xt`, root installer | No: clock-seeded, no exposed seed |
| [DawDreamer](https://github.com/DBraun/DawDreamer) 0.9.0 | Built-in Faust, [physical models](https://github.com/grame-cncm/faustlibraries/blob/master/physmodels.lib) | GPL-3 | pip wheel | Same Mac yes (I); across chips no (I) |
| [Paulstretch](https://github.com/paulnasca/paulstretch_python) | Extreme time-stretch for drones and groans | Public domain | numpy script | Yes, with a seed |
| SuperCollider 3.14.1 | Alien textures, resonators, granular | GPL-3 | cask, 250 MB | Only if every SynthDef seeds itself |
| Csound 6.18.1 | Physical models, formant choir | LGPL-2.1 | Homebrew pulls openjdk, llvm | Needs a non-zero `seed` |

Left out: Vital, ZynAddSubFX and sfizz. Add SuperCollider only if alien textures are still missing.

## AI generation

Stable Audio 3 (20 May 2026) replaces Stable Audio Open. It runs on a Mac through MLX (Metal). The README times a CoreML path, but none is public (audit). The MLX weights we use are not gated; the original `small-sfx` and `small-music` repos are. Re-generation is not reproducible across PyTorch releases and devices, so the seed is a provenance record, not a build step.

| Option | Fit | Licence and provenance | Mac, cost | Seed |
|---|---|---|---|---|
| **Stable Audio 3 small-sfx and small-music** (0.6B each) | Best open SFX score (FAD 0.395 against 0.500 for Open; vendor paper); suits drones and the hymn; choir and organ unknown (I) | Community licence, free under $1M revenue; you own outputs. 806k AudioSparx recordings plus 473k Freesound CC | MLX (Metal); README: about 1 s per 10 s clip on an M1, 1.7 s per 30 s on a Mac CPU via an unaudited route; not measured on our M2. Free. 2.62 GB for both models | `--seed`; reproducible only on the same MLX build and chip, untested (I) |
| ElevenLabs SFX v2 | Strong SFX and seamless loops | Paid plan: royalty-free; free plan: credit required. Fine-tuned on licensed data | Hosted, $6 per month ([pricing](https://elevenlabs.io/pricing)) | None |
| Stable Audio 3 medium and large, Stability API | Best for music | Same licensed data; do not remove watermarks | Hosted, $0.08 per generation ([pricing](https://docs.stability.ai/pricing)); open medium needs CUDA | Commit the take |
| Stable Audio Open Small and 1.0 | Superseded | CC Freesound and FMA | Open 1.0 is slow (minutes on an M2, I) | Open 1.0 has one |
| Adobe Firefly SFX, Eleven Music | Unknown; Eleven Music bans building a music library, which conflicts with a public repo of OGGs (I) | Vendor claims | Hosted, subscription | Commit the take |

Excluded: non-commercial weights (AudioLDM 2, Tango 2, TangoFlux, MMAudio, MusicGen, AudioGen, Woosh), and MOSS-SoundEffect (CUDA, undisclosed data).

## Recorded sources

All are inputs ADR 0057 now forbids, and SPEC section 2 says "Assets: our own".

| Source | Breadth | Licence | Credit |
|---|---|---|---|
| [Freesound](https://freesound.org/help/faq/), CC0 filter | Very high: drones, creatures, metal, hydraulics (383,851 CC0 sounds, I) | CC0; CC BY with credits; avoid NC and Sampling+ | CC BY only |
| [NASA Mars audio](https://www.nasa.gov/sounds-from-beyond/) | About 18 clips (I), on theme: wind, dust devil, rotor, quake | Generally not copyrighted in the US ([NASA](https://www.nasa.gov/nasa-brand-center/images-and-media/)); JPL: any purpose, no endorsement | Full credit line |
| [VCSL](https://github.com/sgossner/VCSL) | Organ, reeds, tubular bells, gongs, wine glasses | CC0 | None |
| The user's field recordings | Anything; a contact mic on a fence in wind gives groans no library has | Ours | None |
| [Kenney](https://kenney.nl/support), [Iowa MIS](https://theremin.music.uiowa.edu/MIS.html) | UI and impacts; orchestral instruments | CC0; no restrictions | Optional |
| Impulse responses | Cave and mine spaces | EchoThief: derivative work only, so not committable. OpenAIR: per-IR licence | Per IR |

Excluded: Sonniss, Zapsplat, BBC RemArc, Pixabay, Internet Archive, and any NC or SA file.

## How each kind of input fits the recipe system

Each kind needs one ADR that amends ADR 0057. For recordings, the user decides.

- **Stems from Mac-only tools.** `tools/sounds/stems/<name>.py` pins its tools in a header and writes `stems/<name>.flac`. The manifest records the stem hash and how it was made. `recipes.stem(name)` decodes the stem with ffmpeg, and the stdlib chain does the rest, so CI checks the final PCM hash with no new tool. `soundgen.py --stems [NAME] [--check]` re-renders on the Mac only. A Surge stem that uses randomness is `reproducible: false`.
- **AI takes with a provenance sidecar.** The take is a short `tools/sounds/takes/<name>.wav`. Its JSON sidecar holds the model, weights hash, prompt, seed, settings, device, date and licence. The recipe applies deterministic DSP. The manifest gains `source_sha256`, and `test_soundgen.py` fails on a take with no sidecar or a wrong hash.
- **CC0 and public-domain source excerpts.** Each excerpt is a trimmed mono 16-bit WAV under about 1 MB in `tools/sounds/sources/<id>.wav`, with no Git LFS. `tools/sounds/sources.json` holds the origin, author, SPDX licence, credit line, upstream hash and trim range. `soundgen.py` writes `CREDITS.txt` in the jar and a repo `CREDITS.md`. The test fails on an off-list licence, a missing credit, an orphan source or a hash mismatch. A dev-only `fetch_source.py` fetches sources, and CI uses no network.

Encode with `oggenc -q 4 -s <crc32(name)>`, mono for positional sounds.

## Needs the user

- Which tiers to adopt, after reading the three audits.
- DawDreamer, yes or no. Its PyPI wheel was uploaded outside CI (audit). Skip it unless you want Faust.
- The root installs for Surge XT and Dexed, or the audit's untested no-root route.
- Stable Audio 3: accept the Stability Community Licence and Gemma terms. They bind on use, and no Hugging Face account is needed for the MLX weights. Credit the sounds as AI-generated (Stable Audio 3), because the use policies ban presenting output as human-made. Set the Modrinth AI disclosure. Whether the mod is "commercial" (I: Modrinth payouts may make it so) means free Stability registration if it ever earns money.
- The SPEC section 2 and ADR 0057 change for recordings. That includes a free Freesound account and API key, and optional field-recording gear at about $100 to $150.
- Any money, such as hosted services.
