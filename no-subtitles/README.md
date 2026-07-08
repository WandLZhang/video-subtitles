# no-subtitles

Part of **[video-subtitles](../README.md)** — the modality for videos with **no usable
subtitles**: transcribe the audio (ASR) into colloquial written-Cantonese (口語 / 粵文)
and English, on a GCP L4 GPU. Per-video outputs go in
[`videos/<video>/`](videos/) (gitignored; mirrored to GCS).

It orchestrates two community tools rather than reinventing them:

| Stage | Tool | Output |
|-------|------|--------|
| Speech → 口語 SRT | [rookes/cantocaptions-ai](https://github.com/rookes/cantocaptions-ai) (Qwen3-ASR + mbroformer + pyannote VAD) | `*.srt` (口語) |
| 口語 → English SRT | [rookes/AI-translate-canto-subs](https://github.com/rookes/AI-translate-canto-subs) | `*.en.srt` |
| QA / watch | `player/` (Range-capable server + dual-track HTML5 player) | in-browser A/B |

Why ASR and not OCR: a Hong Kong film's **burned-in subtitles are Standard Written
Chinese (SWC / 書面語)** — transcribing the *audio* is the only way to get true spoken
**口語**. OCR of the burned-in subs only yields the SWC reference, used for
homophone-correction (see *Fidelity*).

> **Copyright:** tooling only. Generated `.srt`/`.vtt`/`.mp4` are derivatives of a
> copyrighted film — **gitignored**, kept in `videos/<video>/` and a private
> GCS bucket. See [`docs/ACCESS.md`](docs/ACCESS.md).

## Prerequisites
- `gcloud` + a GCP project (a fresh one is fine — `infra/provision.sh` resets the blocking org policies).
- A HuggingFace token with **pyannote/segmentation** terms accepted (VAD).
- An LLM API key for translation (Gemini / Claude / OpenAI — BYO, per AI-translate-canto-subs).

## Runbook
```bash
# 1. Provision an L4 GPU (Vertex Workbench; draws the pool, no CE GPU quota).
PROJECT=my-proj REGION=asia-southeast1 ZONE=asia-southeast1-b bash infra/provision.sh

# 2. On the VM: transcribe audio -> 口語 SRT
gcloud compute ssh canto-asr --zone=asia-southeast1-b --project=my-proj --tunnel-through-iap
HF_TOKEN=hf_... VIDEO_URL="https://www.youtube.com/watch?v=..." STEM=fensau100 bash pipeline/01_transcribe.sh

# 3. Translate 口語 -> English (BYO key)
GOOGLE_API_KEY=... bash pipeline/02_translate.sh <stem>.srt "Break Up 100 (2014 HK romcom)"

# 4. Publish artifacts to the private bucket (they also belong in videos/<stem>/)
BUCKET=gs://my-bucket STEM=fensau100 bash pipeline/03_publish.sh <stem>.srt <stem>.en.srt

# 5. Watch / QA  — see docs/ACCESS.md          6. Tear down:  bash infra/teardown.sh
```

## Capacity
GPU is drawn from the Vertex pool, but Workbench still lands on Compute-Engine capacity,
so a zone can **STOCKOUT**. Check the internal GCE-supply report and pick a zone whose
`g2`/`l4` **free-empty** count is > 0 (this build used asia-southeast1-b; me-central2-c
and northamerica-northeast2 also had supply). `g2-standard-8` = 1× L4 (plenty);
`g2-standard-96` = full 8-L4 node, only if single-GPU slots are contended.

## Fidelity vs the original SWC
The 口語 track is validated for **timing** (monotonic, no overlaps), **completeness**
(gaps are genuine silence), and **vernacularity** (~25:1 colloquial:SWC characters).
For **meaning** vs the film's SWC:
- `pipeline/04_fidelity_check.py` — sampled, segmentation-aware, meaning-only (~92% match on a 40-cue sample).
- `pipeline/05_sentence_fidelity.py` — the rigorous full version: OCR the SWC at every cue → reconstruct sentences → an LLM judges whether the 口語 utterances capture each sentence's meaning.

Divergence ≠ error (SWC paraphrases speech; segmentation differs). Real slips are
**homophone/proper-noun** mishearings (e.g. `税基`→`瑞記`), fixed via reference-correction
against the OCR'd SWC.

## Layout
```
infra/      provision + teardown of the GPU VM (org-policy / network setup)
pipeline/   01 transcribe · 02 translate · 03 publish · 04/05 fidelity checks
player/     Range-capable server + dual-track HTML5 player
docs/       ACCESS.md — pull artifacts from GCS + play commands
config/     characters.yaml — name map for translation accuracy
            outputs -> videos/<video>/
```
