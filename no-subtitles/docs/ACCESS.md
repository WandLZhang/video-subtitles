# Access the artifacts & play the movie

Subtitle artifacts live in a **private, IAM-gated GCS bucket** (not in git — the
bucket name is public here, but nobody can read it without granted access). For 分手100次:

```
gs://wz-qwen-test-canto-subs/fensau100/subtitles/
    fensau100.zh-HK-yue.srt     # 口語 (ASR) — SubRip
    fensau100.zh-HK-yue.vtt     # 口語 — WebVTT (top position)
    fensau100.en.srt / .vtt     # English (once translated)
```

## 1. Pull the subtitles
```bash
mkdir -p ~/canto-play && cd ~/canto-play
gcloud storage cp -r gs://wz-qwen-test-canto-subs/fensau100/subtitles/* .
```

## 2. Get the video (source is a public YouTube upload)
```bash
# muxed 360p (format 18) reliably carries audio+video
yt-dlp -f 18 -o fensau100.mp4 "https://www.youtube.com/watch?v=QWq7Xz-AU2s"
```

## 3. Play — pick one

**A. Local desktop (has a screen + audio): mpv**
```bash
mpv --sub-pos=5 --sub-file=fensau100.zh-HK-yue.srt fensau100.mp4
```

**B. Remote / headless workstation: browser player (recommended)**
A headless box has no display or sound card, so mpv/VLC can't render — serve the
files and open them in your *local* browser (VSCode forwards the port automatically):
```bash
cp <repo>/player/player.html <repo>/player/serve.py .   # player + Range-capable server
python serve.py 8000                                    # Range support = seeking works
# then open http://localhost:8000/player.html?stem=fensau100  in your laptop browser
```
Our **口語** shows at the top (blue), **English** just below, and the film's burned-in
**SWC** stays at the bottom — a live A/B/C: audio ↔ 口語 ↔ SWC.

> Plain `python -m http.server` will NOT let you seek (it ignores HTTP Range) — use the
> provided `player/serve.py`.

## Verify fidelity vs the film's SWC
The SWC is **burned-in** (pixels), not a subtitle stream — so tools like sub-convert
(PGS/Blu-ray) don't apply. Use `pipeline/04_fidelity_check.py`: it samples frames, OCRs
the SWC band with a Gemini vision model, and classifies each 口語 line against it.
```bash
GOOGLE_CLOUD_PROJECT=my-proj python pipeline/04_fidelity_check.py \
    --srt output/fensau100.zh-HK-yue.srt --video output/fensau100.mp4 --samples 48
```
**Caveat — divergence ≠ error.** HK subs paraphrase/condense fast speech into terse
書面語, and frame sampling has timing offset vs the sub display, so a chunk of flagged
"deviations" are not ASR mistakes. Treat the faithful-% as a *conservative floor*. To
certify line-level ASR accuracy, spot-check the flagged timestamps against the audio,
or run a second-ASR (whisper) cross-check via cantocaptions-ai `--ensemble_model whisper`.
