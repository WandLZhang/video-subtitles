#!/usr/bin/env python3
"""
translate_srt.py — Translate Written Cantonese (粵文) SRT files to English via Claude.

Design: the SRT is parsed and reassembled in *code*, so timestamps are preserved exactly.
Claude only ever sees/returns cue text keyed by index (plus optional merge + notes), so it
can never mangle timings. Merges are validated against a time-gap threshold in code.

Usage:
    export ANTHROPIC_API_KEY=sk-...
    python translate_srt.py input.srt \
        --characters config/characters.yaml \
        --context "Ninja Hattori-kun, ep 12. Kanzo is the boy, Hattori the ninja." \
        --out-dir out

Outputs:
    <out>/<stem>.en.srt     translated subtitles
    <out>/<stem>.notes.md   commentary on contentious/uncertain/merged lines

Add --dry-run to build and print the prompts without calling the API.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

TIMING_RE = re.compile(
    r"(\d{2}:\d{2}:\d{2}[,.]\d{3})\s*-->\s*(\d{2}:\d{2}:\d{2}[,.]\d{3})(.*)"
)


# --------------------------------------------------------------------------- #
# SRT parsing / timing
# --------------------------------------------------------------------------- #
@dataclass
class Cue:
    number: int          # original SRT index number
    start: str           # raw "HH:MM:SS,mmm"
    end: str
    trailer: str         # anything after the timestamps (rare position coords)
    lines: list[str]     # source text lines

    @property
    def text(self) -> str:
        return "\n".join(self.lines)

    @property
    def start_ms(self) -> int:
        return ts_to_ms(self.start)

    @property
    def end_ms(self) -> int:
        return ts_to_ms(self.end)


def ts_to_ms(ts: str) -> int:
    ts = ts.replace(".", ",")
    hh, mm, rest = ts.split(":")
    ss, mmm = rest.split(",")
    return ((int(hh) * 60 + int(mm)) * 60 + int(ss)) * 1000 + int(mmm)


def parse_srt(raw: str) -> list[Cue]:
    raw = raw.lstrip("\ufeff").replace("\r\n", "\n").replace("\r", "\n")
    blocks = re.split(r"\n\s*\n", raw.strip())
    cues: list[Cue] = []
    for block in blocks:
        lines = block.split("\n")
        if not lines:
            continue
        # Find the timing line (usually line 2, but be tolerant).
        timing_idx = next(
            (i for i, ln in enumerate(lines) if TIMING_RE.search(ln)), None
        )
        if timing_idx is None:
            continue  # not a real cue; skip
        m = TIMING_RE.search(lines[timing_idx])
        start, end, trailer = m.group(1), m.group(2), m.group(3).rstrip()
        # Number: the line before timing if it's an int, else sequential fallback.
        number = None
        if timing_idx >= 1 and lines[timing_idx - 1].strip().isdigit():
            number = int(lines[timing_idx - 1].strip())
        if number is None:
            number = (cues[-1].number + 1) if cues else 1
        text_lines = [ln for ln in lines[timing_idx + 1:] if ln.strip() != ""]
        cues.append(Cue(number, start, end, trailer, text_lines))
    return cues


# --------------------------------------------------------------------------- #
# Chunking
# --------------------------------------------------------------------------- #
def chunk_cues(cues: list[Cue], size: int, ctx: int):
    """Yield (context_before, target_cues, context_after) tuples."""
    for i in range(0, len(cues), size):
        target = cues[i : i + size]
        before = cues[max(0, i - ctx) : i]
        after = cues[i + size : i + size + ctx]
        yield before, target, after


def cues_to_payload(cues: list[Cue]):
    return [{"index": c.number, "text": c.text} for c in cues]


# --------------------------------------------------------------------------- #
# Prompt building
# --------------------------------------------------------------------------- #
def build_system_prompt(prompt_dir: Path, data_dir: Path) -> str:
    sys_tmpl = (prompt_dir / "system_prompt.md").read_text(encoding="utf-8")
    particles = (data_dir / "particles.md").read_text(encoding="utf-8")
    return sys_tmpl.replace("{PARTICLE_REFERENCE}", particles.strip())

def format_characters(char_cfg: dict) -> str:
    if not char_cfg:
        return "(none provided — transliterate sensibly and note any guessed names)"
    lines = []
    for src, en in (char_cfg.get("names") or {}).items():
        lines.append(f"- {src} -> {en}")
    for src, en in (char_cfg.get("terms") or {}).items():
        lines.append(f"- (term) {src} -> {en}")
    return "\n".join(lines) if lines else "(none)"

def build_user_prompt(
    tmpl: str,
    episode_context: str,
    character_map: str,
    user_notes: str,
    merge_threshold_ms: int,
    before: list[Cue],
    target: list[Cue],
    after: list[Cue],
) -> str:
    def dump(cues):
        return json.dumps(cues_to_payload(cues), ensure_ascii=False, indent=2)

    return (
        tmpl.replace("{episode_context}", episode_context or "(none provided)")
        .replace("{character_map}", character_map)
        .replace("{user_notes}", user_notes)
        .replace("{merge_threshold_ms}", str(merge_threshold_ms))
        .replace("{context_before}", dump(before) if before else "(none — start of file)")
        .replace("{cues_json}", dump(target))
        .replace("{context_after}", dump(after) if after else "(none — end of file)")
    )


# --------------------------------------------------------------------------- #
# Model providers
# --------------------------------------------------------------------------- #
# Everything else in this file is provider-agnostic. To add a provider, write a
# _call_<name>() that takes (model, system, user, max_tokens) and returns raw
# text, then register it in PROVIDERS. _parse_json() is the universal safety net.

DEFAULT_MODELS = {
    "anthropic": "claude-sonnet-5",
    "gemini": "gemini-2.5-pro",   # model IDs move fast — override with --model
    "openai": "gpt-4o",
}


def _call_anthropic(model: str, system: str, user: str, max_tokens: int) -> str:
    from anthropic import Anthropic  # needs ANTHROPIC_API_KEY
    client = Anthropic()
    resp = client.messages.create(
        model=model,
        max_tokens=max_tokens,
        system=system,
        messages=[{"role": "user", "content": user}],
    )
    return "".join(b.text for b in resp.content if getattr(b, "type", "") == "text")


def _call_gemini(model: str, system: str, user: str, max_tokens: int) -> str:
    from google import genai  # GEMINI_API_KEY/GOOGLE_API_KEY, or Vertex env vars
    from google.genai import types
    client = genai.Client()
    cfg = types.GenerateContentConfig(
        system_instruction=system,
        max_output_tokens=max_tokens,
        response_mime_type="application/json",  # native JSON mode
        temperature=0.2,
    )
    # Gemini can return no text (MAX_TOKENS / SAFETY / RECITATION) -> resp.text is None,
    # which otherwise crashes the whole run. Retry, then yield an empty result so this
    # chunk falls back to source text (reassemble() handles that) and the run completes.
    for _ in range(4):
        resp = client.models.generate_content(model=model, contents=user, config=cfg)
        if resp.text:
            return resp.text
    return "{}"


def _call_openai(model: str, system: str, user: str, max_tokens: int) -> str:
    from openai import OpenAI  # needs OPENAI_API_KEY
    client = OpenAI()
    resp = client.chat.completions.create(
        model=model,
        max_tokens=max_tokens,
        temperature=0.2,
        response_format={"type": "json_object"},  # native JSON mode
        messages=[
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
    )
    return resp.choices[0].message.content


PROVIDERS = {
    "anthropic": _call_anthropic,
    "gemini": _call_gemini,
    "openai": _call_openai,
}


def call_model(provider: str, model: str, system: str, user: str, max_tokens: int) -> dict:
    # Resilient: retry on empty/invalid JSON (truncation, malformed output); yield {} on
    # persistent failure so the chunk falls back to source text (reassemble() handles it)
    # instead of crashing the entire run on one bad response.
    for _ in range(3):
        raw = PROVIDERS[provider](model, system, user, max_tokens)
        try:
            return _parse_json(raw)
        except Exception:
            continue
    return {}


def _parse_json(raw: str) -> dict:
    raw = raw.strip()
    if raw.startswith("```"):
        raw = re.sub(r"^```[a-zA-Z]*\n?", "", raw)
        raw = re.sub(r"\n?```$", "", raw)
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        # Salvage the outermost object.
        start, end = raw.find("{"), raw.rfind("}")
        if start != -1 and end != -1:
            return json.loads(raw[start : end + 1])
        raise


# --------------------------------------------------------------------------- #
# Reassembly + validation
# --------------------------------------------------------------------------- #
def reassemble(
    cues: list[Cue],
    entries: list[dict],
    merge_threshold_ms: int,
    max_merge: int,
    renumber: bool,
) -> tuple[str, list[str]]:
    by_num = {c.number: c for c in cues}
    warnings: list[str] = []
    out_cues: list[tuple[int, str, str, str]] = []  # (number, start, end, text)

    covered: list[int] = []
    for e in entries:
        idxs = e.get("indices") or ([e["index"]] if "index" in e else [])
        idxs = [int(i) for i in idxs]
        text = (e.get("text") or "").rstrip()
        if not idxs:
            warnings.append(f"Entry with no indices skipped: {e!r}")
            continue
        missing = [i for i in idxs if i not in by_num]
        if missing:
            warnings.append(f"Unknown cue number(s) {missing} in entry — skipped")
            continue

        if len(idxs) > 1:
            # Validate a merge.
            if len(idxs) > max_merge:
                warnings.append(
                    f"Merge {idxs} exceeds max group size {max_merge}; kept but review"
                )
            if idxs != list(range(idxs[0], idxs[-1] + 1)):
                warnings.append(f"Merge {idxs} is not contiguous; kept but review")
            gap = by_num[idxs[-1]].start_ms - by_num[idxs[0]].end_ms
            if gap > merge_threshold_ms:
                warnings.append(
                    f"Merge {idxs} gap {gap}ms exceeds threshold "
                    f"{merge_threshold_ms}ms; kept but review"
                )
        first, last = by_num[idxs[0]], by_num[idxs[-1]]
        number = first.number
        trailer_note = _punct_warn(text)
        if trailer_note:
            warnings.append(f"Cue {number}: {trailer_note}")
        out_cues.append((number, first.start + first.trailer, last.end, text))
        covered.extend(idxs)

    # Coverage check: every source cue accounted for exactly once.
    covered_set = set(covered)
    all_nums = [c.number for c in cues]
    for n in all_nums:
        if n not in covered_set:
            warnings.append(f"Cue {n} was not translated; source text kept as fallback")
            c = by_num[n]
            out_cues.append((n, c.start + c.trailer, c.end, c.text))
    dupes = [n for n in covered if covered.count(n) > 1]
    if dupes:
        warnings.append(f"Cue(s) translated more than once: {sorted(set(dupes))}")

    out_cues.sort(key=lambda t: ts_to_ms(t[1].split()[0]))

    # Emit SRT.
    blocks = []
    for seq, (number, start, end, text) in enumerate(out_cues, start=1):
        disp = seq if renumber else number
        blocks.append(f"{disp}\n{start} --> {end}\n{text}")
    return "\n\n".join(blocks) + "\n", warnings


def _punct_warn(text: str) -> str | None:
    """Warn if a line ends with disallowed punctuation (period/comma, not '...')."""
    for ln in text.split("\n"):
        s = ln.rstrip()
        if not s:
            continue
        if s.endswith(",") or (s.endswith(".") and not s.endswith("...")):
            return f"line ends with disallowed punctuation: {s[-8:]!r}"
    return None


def render_notes(all_notes: list[dict], warnings: list[str], stem: str) -> str:
    out = [f"# Translation notes — {stem}\n"]
    if all_notes:
        out.append("## Translator notes (uncertain / contentious lines)\n")
        for n in all_notes:
            idxs = n.get("indices") or ([n.get("index")] if n.get("index") else [])
            out.append(f"- **Cue {', '.join(map(str, idxs))}**: {n.get('comment','').strip()}")
        out.append("")
    else:
        out.append("_No lines were flagged as uncertain._\n")
    if warnings:
        out.append("## Pipeline warnings (auto-generated — please review)\n")
        for w in warnings:
            out.append(f"- {w}")
        out.append("")
    return "\n".join(out)


# --------------------------------------------------------------------------- #
# Orchestration
# --------------------------------------------------------------------------- #
def load_characters(path: str | None) -> dict:
    if not path:
        return {}
    import yaml
    return yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}


def main() -> int:
    ap = argparse.ArgumentParser(description="Translate Written Cantonese SRT to English.")
    ap.add_argument("input", help="input .srt file")
    ap.add_argument("--out-dir", default="out", help="output directory (default: out)")
    ap.add_argument("--characters", help="YAML character/term map")
    ap.add_argument("--context", default="", help="free-text series/episode context")
    ap.add_argument("--notes", default="", help="plaintext rules or guidance for this transcription")
    ap.add_argument("--provider", default="anthropic",
                    choices=list(PROVIDERS), help="LLM provider (default: anthropic)")
    ap.add_argument("--model", default=None,
                    help="model id (defaults to a sensible model per provider)")
    ap.add_argument("--max-cues", type=int, default=60, help="cues per API call")
    ap.add_argument("--context-cues", type=int, default=6, help="reference cues each side")
    ap.add_argument("--merge-threshold-ms", type=int, default=1000,
                    help="max gap (ms) between cues eligible for merging")
    ap.add_argument("--max-merge", type=int, default=2, help="max cues in one merge group")
    ap.add_argument("--max-tokens", type=int, default=8192, help="API max_tokens per call")
    ap.add_argument("--renumber", action="store_true",
                    help="renumber output cues sequentially (default: keep source numbers)")
    ap.add_argument("--dry-run", action="store_true",
                    help="build and print prompts without calling the API")
    args = ap.parse_args()
    model = args.model or DEFAULT_MODELS[args.provider]

    root = Path(__file__).resolve().parent
    prompt_dir, data_dir = root / "prompts", root / "data"

    src = Path(args.input)
    cues = parse_srt(src.read_text(encoding="utf-8"))
    if not cues:
        print("No cues parsed — is this a valid SRT?", file=sys.stderr)
        return 1
    print(f"Parsed {len(cues)} cues from {src.name}", file=sys.stderr)

    system = build_system_prompt(prompt_dir, data_dir)
    user_tmpl = (prompt_dir / "user_prompt.tmpl").read_text(encoding="utf-8")
    characters = load_characters(args.characters)
    char_map = format_characters(characters)

    chunks = list(chunk_cues(cues, args.max_cues, args.context_cues))

    if args.dry_run:
        before, target, after = chunks[0]
        up = build_user_prompt(user_tmpl, args.context, char_map, args.notes,
                               args.merge_threshold_ms, before, target, after)
        print("=" * 70 + "\nSYSTEM PROMPT\n" + "=" * 70)
        print(system)
        print("\n" + "=" * 70 + f"\nUSER PROMPT (chunk 1/{len(chunks)})\n" + "=" * 70)
        print(up)
        return 0

    print(f"Provider: {args.provider} | model: {model}", file=sys.stderr)
    all_translations: list[dict] = []
    all_notes: list[dict] = []
    for i, (before, target, after) in enumerate(chunks, 1):
        print(f"Translating chunk {i}/{len(chunks)} ({len(target)} cues)...",
              file=sys.stderr)
        up = build_user_prompt(user_tmpl, args.context, char_map, args.notes,
                               args.merge_threshold_ms, before, target, after)
        result = call_model(args.provider, model, system, up, args.max_tokens)
        all_translations.extend(result.get("translations", []))
        all_notes.extend(result.get("notes", []))

    srt_out, warnings = reassemble(
        cues, all_translations, args.merge_threshold_ms, args.max_merge, args.renumber
    )

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    stem = src.stem
    (out_dir / f"{stem}.en.srt").write_text(srt_out, encoding="utf-8")
    (out_dir / f"{stem}.notes.md").write_text(
        render_notes(all_notes, warnings, stem), encoding="utf-8"
    )
    print(f"Wrote {out_dir/(stem + '.en.srt')}", file=sys.stderr)
    print(f"Wrote {out_dir/(stem + '.notes.md')}", file=sys.stderr)
    if warnings:
        print(f"{len(warnings)} warning(s) — see notes file.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
