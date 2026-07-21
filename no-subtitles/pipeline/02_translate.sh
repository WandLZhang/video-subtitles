#!/usr/bin/env bash
# Translate a 粵文 SRT to English via rookes/AI-translate-canto-subs (bring your own
# LLM API key: Gemini / Claude / OpenAI). Timestamps are preserved by that tool.
#
#   GOOGLE_API_KEY=...  bash pipeline/02_translate.sh out/fensau100.srt "Break Up 100 (2014 HK romcom)"
#   ANTHROPIC_API_KEY=... bash pipeline/02_translate.sh out/fensau100.srt "..."
#
# Uncomment the matching provider in the tool's requirements.txt if needed.
set -euo pipefail
SRT="${1:?path to the 粵文 .srt}"
CONTEXT="${2:-}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOL="$HERE/vendor/AI-translate-canto-subs"

[ -d "$TOOL" ] || git clone https://github.com/rookes/AI-translate-canto-subs.git "$TOOL"
# Apply our in-repo override of translate_srt.py: resilient model calls (retry on empty
# response / MAX_TOKENS / SAFETY and on invalid JSON, then fall back to source text so one
# bad LLM response doesn't crash the whole run). Kept in-repo so it survives re-clones of
# the upstream tool. See pipeline/overrides/translate_srt.py.
cp "$HERE/pipeline/overrides/translate_srt.py" "$TOOL/translate_srt.py"
cd "$TOOL"
pip install -q -r requirements.txt
python translate_srt.py "$SRT" \
  --characters "$HERE/config/characters.yaml" \
  --context "$CONTEXT" \
  --out-dir "$HERE/out"

echo ">> English SRT + translator notes -> $HERE/out/"
