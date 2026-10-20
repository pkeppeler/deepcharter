# Stable Audio 3 audit

Read-only audit of [Stable Audio 3](https://github.com/Stability-AI/stable-audio-3) `small-sfx` and `small-music` for raw sound takes on the dev Mac (M2, 16 GB), 2026-10-20. Nothing was installed or run. **V** = verified, **I** = inferred.

## Verdict: usable through the MLX CLI, pinned by hand. Do not run the one-liner

- **Weights cannot run code.** The MLX path loads only `.npz`. All 1,249 arrays in our five files are numeric, so there is no pickle (V: header scan). No `trust_remote_code` anywhere (V: grep).
- **No telemetry and no network** except a weight download when a file is missing (V: source).
- **Risk 1, nothing is pinned:** no repo tags, `>=` dependencies, weights from `main`. Conditions 1–3 pin all three.
- **Risk 2, the web UI:** `sa3-gradio` opens a **public share link by default** and binds `0.0.0.0` even with `--no-share` (V: `sa3_gradio.py:1331,1347`). Use the CLI only.
- **No public CoreML path.** The README times one, but no CoreML model or runtime exists in the repo or on HF (V: file lists). MLX (Metal) is the Mac path.

## Versions

| | Pin |
|---|---|
| Code | `main` @ `77e0398c26f7d22d801ec1bb036307e8b231f741` (2026-10-11). No tags or releases (V: `gh api`) |
| MLX weights | `stabilityai/stable-audio-3-optimized` @ `08b17423649b5714b22eab20f96bf00751f2358a`, **not gated** |
| Originals (not needed) | `small-sfx` @ `05ca3857…`, `small-music` @ `8bdfb53c…`: gated, `model.safetensors` 2.27 GB plus T5Gemma safetensors; safetensors only |

Files under `MLX/` for our path (sizes and LFS sha256 from the HF API):

| File | Bytes | sha256 | Arrays |
|---|---|---|---|
| `t5gemma_f16.npz` | 567443068 | `8deb20489f36d9aec539f26c9c67321f99bc5fe300d470435ed6e76be4f16bbd` | 137 |
| `dit_sm-sfx_f16.npz` | 919193814 | `7e702d2640699a57fe436ca975fda16832040ba568c1e092c2ae826987558118` | 441 |
| `dit_sm-music_f16.npz` | 919193814 | `8ed3f38e2597f361ee675051f1265d9aa2ae2fffce1c61acd2e9fe31e1db1cbc` | 441 |
| `same_s_decoder_f32.npz` | 218090820 | `909928a8e6937c1ebe6ac4b729f0462bd3773704a11ea18278e42671dc69bfe4` | 114 |
| `same_s_encoder_f32.npz` (audio-to-audio only) | 214946620 | `a48f80d81c30d74c45e2a3047082c4891f715e24c44645adb9c1f4f07afdaf0c` | 116 |

The HF repo also holds native `.so` engines (Intel Xeon), TensorRT, ONNX and TFLite files; the MLX manifest (`scripts/weights.py`) never fetches them (V). A `torch.load(weights_only=False)` fallback (`dit_mlx.py:275`) is unreachable: the CLI hard-codes `.npz` paths and torch is not installed (V).

## Dependencies (MLX path)

`requirements.txt`: `mlx>=0.30`, `numpy>=1.24`, `sentencepiece>=0.2`, `huggingface_hub>=0.20`, `soundfile>=0.12`, `pillow>=10.0`. Today on Python 3.11 these resolve to (I: PyPI metadata, not resolved):

- mlx 0.32.4 and mlx-metal 0.32.4 (MIT)
- numpy 2.4.6 (2.5 needs Python 3.12)
- sentencepiece 0.2.2
- huggingface_hub 2.2.0, which pulls click, filelock, fsspec, hf-xet 1.7.0 (a Rust binary), httpx2, packaging, pyyaml, tqdm and typing-extensions
- soundfile 0.14.0, with cffi and a bundled libsndfile
- pillow 13.0.0

All have macOS arm64 wheels, so nothing compiles (I). No torch, transformers or coremltools. uv 0.13.0 is already installed (V).

## Runtime (`./sa3`, which runs `scripts/sa3_mlx.py`)

- **Network:** only `hf_hub_download`, when a file is missing from `models/mlx/` (`weights.py:137`). Inference imports only mlx, numpy, sentencepiece and stdlib (V).
- **Writes:** the WAV (16-bit, 44.1 kHz, stereo) to `output/<prompt>_<seed>.wav` or `--out`; symlinks from `models/mlx/` into `~/.cache/huggingface/hub`.
- **Subprocesses:** `afplay` with `--play`; `ffmpeg` only to read a non-16-bit or non-44.1 kHz `--init-audio`.

## The one-liner (read as text, `optimized/mlx/bootstrap.sh`)

1. Clones **unpinned `main`** into the current directory (or runs `git pull` on an existing clone).
2. Runs `install.sh -y`: pipes `astral.sh/uv/install.sh` to `sh` unprompted if uv is missing, creates a Python 3.11 venv, installs the unpinned requirements.
3. Generates and plays 120 s of `sm-music`, downloading weights from `main`, then runs `exec "$SHELL" -i`.

Nothing malicious and no sudo, but unpinned at every layer.

## Licence

- **Code:** MIT (V: GitHub API).
- **Weights:** Stability AI Community License, July 5 2024 (V: `LICENSE.md`).
  - A free mod is a "Non-Commercial Purpose" (§2): no registration. If it ever earns money, register for free (§3, under USD 1M revenue).
  - "Derivative Works" excludes "the output of any Model" (§5), and you own outputs "to the extent permitted by applicable law" (§4c). Shipping WAVs is not distributing the model, so the §4a Notice and "Powered by Stability AI" duties do not attach (I: our reading, not a lawyer's).
  - Outputs must follow the AUP and must not train a foundational generative model (§4b).
- **AUP** (effective 2026-09-30) bans "misleading end users about the nature of outputs … (such as pretending it was made by a human)". The Gemma Prohibited Use Policy bans the same. **Credit the sounds as AI-generated.**
- **Gemma Terms** cover the T5Gemma text encoder (V). "Google claims no rights in Outputs" (§3.3); redistribution duties apply only to the weights.
- **Training data** (model card): 806,284 licensed AudioSparx and 472,618 Freesound CC0/CC-BY/CC Sampling+ recordings. No duty passes to outputs (I).
- **Gating:** the original repos need an HF account and send name, email, country, organisation and intended use to Stability. The MLX repo is **ungated** (V: anonymous requests worked). Both licences bind on use alone.

## Footprint and reproducibility

- **Disk:** 2.62 GB of weights for both small models; 2.84 GB with the encoder. The venv adds a few hundred MB (I).
- **RAM:** README peaks 1.6 GB (10 s clip) to 2.4 GB (120 s); about 1 s per 10 s clip on an M1 (not measured).
- **Seed:** `--seed` feeds `mx.random.key(seed)`, and the sampler uses `seed+1`; there is no other randomness (V). Without it, a random seed is printed.
- **Not tested:** bit-identical reruns on one machine. MLX does not promise it, and results may change across MLX versions or chips (I).

## Conditions

1. Clone at `77e0398c`, outside deepcharter. Read the diff before any update.
2. Install exact pins (`uv pip install mlx==0.32.4 …`), not `requirements.txt`.
3. Fetch the files above with `curl` from `resolve/08b17423…/MLX/`, check the sha256 values, and place them in `models/mlx/`. Run with `HF_HUB_OFFLINE=1`.
4. Use the CLI only. Never use `sa3-gradio` or `run_gradio.py`.
5. Never commit or redistribute the weights or the tool.
6. Record per take: prompt, seed, steps, cfg, seconds, model, dtype, weight sha256, repo commit and mlx version. Run one seed twice and compare hashes.
7. Credit the sounds as AI-generated (Stable Audio 3), processed by Deep Charter.

## Not verified

- Whole weight files (headers only), the resolved dependency tree, and transitive licences.
- Determinism, RAM and speed on the M2.
- Whether the model can reproduce training audio verbatim.
- The TFLite CPU route and the PyTorch path.

## Method

- `gh api`; `git clone` into an empty directory, read with `grep`/`sed`.
- HF API (`?blobs=true`) and licence files at pinned revisions; PyPI JSON; MLX `v0.32.4` `io/load.cpp` (npy parser accepts only numeric dtypes).
- A script outside the download directory (`python3 -I`) read each `.npz` zip directory and npy header by HTTP range requests. User-Agent `github.com/pkeppeler/deepcharter`.
