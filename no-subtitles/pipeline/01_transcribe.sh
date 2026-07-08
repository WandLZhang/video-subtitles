#!/usr/bin/env bash
# Run ON the GPU VM. Sets up cantocaptions-ai and transcribes a video's audio into a
# colloquial-Cantonese (口語) SRT via Qwen3-ASR + mbroformer + pyannote VAD.
#
#   HF_TOKEN=hf_... VIDEO_URL="https://www.youtube.com/watch?v=..." STEM=fensau100 \
#       bash pipeline/01_transcribe.sh
#
# HF_TOKEN must belong to an account that accepted the pyannote/segmentation terms.
set -euo pipefail
: "${HF_TOKEN:?HuggingFace token required for the pyannote VAD model}"
: "${VIDEO_URL:?video URL required}"
STEM="${STEM:-movie}"
export PATH="$HOME/.local/bin:$PATH"

command -v uv     >/dev/null || curl -LsSf https://astral.sh/uv/install.sh | sh
command -v ffmpeg >/dev/null || { sudo apt-get update -qq && sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq ffmpeg; }
command -v yt-dlp >/dev/null || { sudo curl -sL https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp && sudo chmod +x /usr/local/bin/yt-dlp; }

[ -d "$HOME/cantocaptions-ai" ] || git clone https://github.com/rookes/cantocaptions-ai.git "$HOME/cantocaptions-ai"
cd "$HOME/cantocaptions-ai"
uv sync --extra transformers_qwen           # torch cu128 + Qwen3-ASR (~6 GB first run)

# 'ba/18' = best audio, else muxed 360p (format 18) which reliably serves audio+video
yt-dlp -f 'ba/18' -x --audio-format wav -o "$HOME/$STEM.%(ext)s" "$VIDEO_URL"

mkdir -p "$HOME/out"
uv run cantocaptions "$HOME/$STEM.wav" \
  --hf_token "$HF_TOKEN" --device cuda \
  --vocal_isolation_method mbroformer \
  --output_dir "$HOME/out" --output_format srt \
  --log_level info --print_progress False

echo ">> 口語 SRT -> $HOME/out/$STEM.srt"
