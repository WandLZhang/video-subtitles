// webplayer-overlay (LLM translation) — paste into the DevTools console on a video
// page, or save the javascript: form as a bookmarklet. Prompts for an external .srt
// URL (e.g. a CantoCaptions raw .srt), translates every cue with a keyed Gemini call,
// and overlays 口語 (top) + English (bottom) on the page's <video>.
//
// Why the LLM variant (vs bookmarklet.js on-device Translator): it uses cross-cue
// context and REPAIRS ASR slips in the source (e.g. 過工 -> "high blood sugar",
// 物探 -> "spies"), where the on-device translator faithfully mistranslates them.
//
// SETUP — mint a browser-usable API key (CORS-open generativelanguage endpoint):
//   AI Studio:  https://aistudio.google.com/apikey   (fastest)
//   or GCP:     gcloud services enable generativelanguage.googleapis.com
//               then create an API key in the console, restrict it to that API.
//   Paste it as KEY below. Keep it OUT of any committed file (placeholder here).
//
// MODEL — gemini-flash-lite-latest is fastest (~3.5s / 50 cues, always newest lite).
//   For higher-quality translation swap to gemini-3.5-flash (~4x slower).
//
// Timing off (community srt vs this cut)?  ->  window.SUB_OFFSET = -30   (seconds)
(async () => {
  const KEY = 'YOUR_GEMINI_API_KEY';                 // <-- paste your key; do NOT commit it
  const MODEL = 'gemini-flash-lite-latest';          // or 'gemini-3.5-flash' for quality
  const BATCH = 50, CONC = 8;                         // cues per call / parallel calls
  if (KEY === 'YOUR_GEMINI_API_KEY') { alert('Set KEY first (see header).'); return; }
  const SRT = prompt('External .srt URL to overlay:'); if (!SRT) return;

  const toMs = ts => { ts = ts.trim().replace(',', '.'); const p = ts.split(':'), sp = (p[2] || '0').split('.');
    return ((+p[0] * 60 + +p[1]) * 60 + +sp[0]) * 1000 + +((sp[1] || '0').padEnd(3, '0').slice(0, 3)); };
  const parse = t => { t = t.split('\r').join(''); if (t.charCodeAt(0) === 0xFEFF) t = t.slice(1); const cs = [];
    for (const b of t.split('\n\n')) { const ls = b.split('\n'); let ti = ls.findIndex(l => l.indexOf(' --> ') > -1);
      if (ti < 0) continue; const tc = ls[ti].split(' --> '); const s = toMs(tc[0]), e = toMs((tc[1] || '').split(' ')[0]);
      const body = ls.slice(ti + 1).join('\n').trim(); if (body && e > s) cs.push({ start: s, end: e, text: body, en: '' }); }
    return cs; };
  const cues = parse(await (await fetch(SRT)).text());
  console.log('[overlay] parsed', cues.length, 'cues — translating via', MODEL, '…');

  // One batch -> {i, en}[]. maxOutputTokens caps the response so the JSON never
  // truncates (the bug when it was omitted); retry once, then blank-skip so one
  // bad batch can't kill the whole run.
  const tr = async texts => {
    const prompt = 'Translate each Hong Kong colloquial-Cantonese subtitle to natural English. '
      + 'Lines are auto-transcribed and may contain homophone/ASR errors — infer the intended meaning from context. '
      + 'Return ONLY a JSON array of {"i":number,"en":string} for every input.\n\n'
      + JSON.stringify(texts.map((t, i) => ({ i, zh: t })));
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent?key=${KEY}`,
          { method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ contents: [{ parts: [{ text: prompt }] }],
              generationConfig: { responseMimeType: 'application/json', temperature: 0.2, maxOutputTokens: 8192 } }) });
        const j = await r.json();
        const arr = JSON.parse(j.candidates[0].content.parts[0].text);
        const out = texts.map(() => ''); arr.forEach(x => { if (x.i >= 0 && x.i < out.length) out[x.i] = x.en; });
        return out;
      } catch (e) { if (attempt) { console.warn('[overlay] batch skipped:', e.message); return texts.map(() => ''); } }
    }
  };
  const starts = []; for (let i = 0; i < cues.length; i += BATCH) starts.push(i); let done = 0;
  const runOne = async s => { const en = await tr(cues.slice(s, s + BATCH).map(c => c.text));
    en.forEach((t, j) => cues[s + j].en = t); done += Math.min(BATCH, cues.length - s);
    console.log('[overlay] translated', done, '/', cues.length); };
  for (let k = 0; k < starts.length; k += CONC) await Promise.all(starts.slice(k, k + CONC).map(runOne));
  window.__cues = cues;                               // reusable (e.g. dump a pre-translated en.srt)

  const v = document.querySelector('video'); if (!v) { alert('no <video> found — start playback first'); return; }
  document.getElementById('wp-overlay')?.remove();
  const box = document.createElement('div'); box.id = 'wp-overlay';
  box.style.cssText = 'position:fixed;left:50%;bottom:12%;transform:translateX(-50%);z-index:2147483647;max-width:86%;text-align:center;pointer-events:none;font-family:system-ui';
  const mk = (c, s) => { const d = document.createElement('div'); d.style.cssText = 'display:inline-block;margin:2px;padding:2px 10px;background:rgba(0,0,0,.6);border-radius:6px;color:' + c + ';font-size:' + s + 'px;text-shadow:0 2px 4px #000'; return d; };
  const zh = mk('#7fd7ff', 26), en = mk('#ffd479', 20);
  const w1 = document.createElement('div'); w1.append(zh); const w2 = document.createElement('div'); w2.append(en); box.append(w1, w2); document.body.append(box);
  const find = ms => { for (const c of cues) if (c.start <= ms && ms <= c.end) return c; return null; };
  v.addEventListener('timeupdate', () => { const c = find((v.currentTime + (window.SUB_OFFSET || 0)) * 1000);
    zh.textContent = c ? c.text : ''; en.textContent = c ? (c.en || '') : '';
    w1.style.visibility = c && c.text ? 'visible' : 'hidden'; w2.style.visibility = c && c.en ? 'visible' : 'hidden'; });
  console.log('[overlay] attached. Timing off? run:  window.SUB_OFFSET = -30');
})();
