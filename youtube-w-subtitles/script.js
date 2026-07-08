/**
 * YouTube Bilingual Subtitles Injection Script
 * 
 * Bypasses YouTube's modern Proof of Play (PoP) Attestation (the "pot" token)
 * by hooking the native network APIs (Fetch & XHR) in the page context.
 * Once a signed timedtext URL is captured from the active player session,
 * the script safely injects translation parameters (which are unsigned) and 
 * retrieves the high-quality server-side translated English captions track.
 * 
 * Usage:
 * - Copy and paste this script directly into the Chrome DevTools Console, or
 * - Run it via a Bookmarklet or a Userscript manager (e.g., Tampermonkey).
 */
(async function() {
  const S_ID = "yt-dual-subs-style";
  const C_ID = "yt-dual-subs-container";
  const M_ID = "yt-dual-subs-menu";

  // Prevent duplicate interval instances
  if (window.YT_DUAL_SUB_INTERVAL) {
    clearInterval(window.YT_DUAL_SUB_INTERVAL);
  }

  // Clear previous UI elements if reloading
  [S_ID, C_ID, M_ID].forEach(id => {
    const el = document.getElementById(id);
    if (el) el.remove();
  });

  // Install Global Network Hooks to intercept the player's active signed URLs
  if (!window.YT_DUAL_HOOKS_INSTALLED) {
    window.YT_DUAL_HOOKS_INSTALLED = true;
    window.YT_CAPTURED_URL = "";

    // Hook window.fetch
    const oldFetch = window.fetch;
    window.fetch = async function(...args) {
      const url = args[0];
      if (typeof url === "string" && url.includes("timedtext")) {
        window.YT_CAPTURED_URL = url;
        console.log("[Dual Subs Hook] Intercepted fetch to timedtext!");
      }
      return oldFetch.apply(this, args);
    };

    // Hook XMLHttpRequest
    const oldOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url, ...args) {
      if (typeof url === "string" && url.includes("timedtext")) {
        window.YT_CAPTURED_URL = url;
        console.log("[Dual Subs Hook] Intercepted XHR to timedtext!");
      }
      return oldOpen.call(this, method, url, ...args);
    };
  }

  console.clear();
  console.log(
    "%c[Dual Subs] Hooked & Ready! Default target language is English.",
    "color:#3ea6ff;font-weight:bold;"
  );

  let targetLang = "en";
  let enabled = true;
  let subtitleSegments = [];
  let videoElement = null;
  let lastFetchedUrl = "";
  let lastVideoId = "";

  // Inject Premium Glassmorphism UI Styles
  const style = document.createElement("style");
  style.id = S_ID;
  style.textContent = `
    #yt-dual-subs-container {
      position: absolute;
      left: 50%;
      transform: translateX(-50%);
      bottom: 12%;
      z-index: 1000;
      color: #ffffff;
      background: rgba(0, 0, 0, 0.75);
      padding: 10px 24px;
      border-radius: 8px;
      font-family: system-ui, sans-serif;
      font-size: 24px;
      font-weight: 500;
      text-align: center;
      max-width: 85%;
      white-space: pre-wrap;
      word-break: break-word;
      pointer-events: none;
      opacity: 0;
      visibility: hidden;
      text-shadow: 0 2px 4px rgba(0,0,0,0.9);
      transition: opacity 0.15s ease-in-out;
    }
    #yt-dual-subs-container.visible {
      opacity: 1;
      visibility: visible;
    }
    #yt-dual-subs-menu {
      position: absolute;
      top: 60px;
      right: 20px;
      z-index: 2000;
      background: rgba(15, 23, 42, 0.9);
      backdrop-filter: blur(8px);
      border: 1px solid rgba(255, 255, 255, 0.15);
      padding: 12px 16px;
      border-radius: 12px;
      font-family: system-ui, sans-serif;
      color: #f8fafc;
      display: flex;
      align-items: center;
      gap: 12px;
      font-size: 13px;
      box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.5);
    }
    #yt-dual-subs-menu select {
      background: rgba(30, 41, 59, 0.9);
      border: 1px solid rgba(255, 255, 255, 0.2);
      color: #ffffff;
      border-radius: 6px;
      padding: 6px 10px;
      outline: none;
      cursor: pointer;
      font-size: 12px;
    }
    #yt-dual-subs-menu button {
      background: #3ea6ff;
      border: none;
      color: #ffffff;
      padding: 6px 14px;
      border-radius: 6px;
      cursor: pointer;
      font-weight: 700;
      transition: background 0.2s;
    }
    #yt-dual-subs-menu button:hover {
      background: #1c88ff;
    }
    #yt-dual-subs-menu button.disabled {
      background: #475569;
    }
  `;
  document.head.appendChild(style);

  // XML Subtitle Parser (for native srv1 / srv3 formats)
  function parseXml(xmlText) {
    const list = [];
    try {
      const p = new DOMParser();
      const doc = p.parseFromString(xmlText, "text/xml");
      const nodes = doc.getElementsByTagName("text");
      for (const n of nodes) {
        const start = parseFloat(n.getAttribute("start") || "0");
        const dur = parseFloat(n.getAttribute("dur") || "0");
        const txt = n.textContent.trim();
        if (!txt) continue;
        list.push({
          start: start * 1000,
          end: (start + dur) * 1000,
          text: txt
        });
      }
    } catch (e) {
      console.error("[Dual Subs] XML Parse error:", e);
    }
    return list;
  }

  // JSON3 Subtitle Parser (for standard modern formats)
  function parseJson3(data) {
    const list = [];
    if (!data || !data.events) return list;
    for (const ev of data.events) {
      if (!ev.segs) continue;
      const text = ev.segs
        .map(s => s.utf8)
        .join("")
        .replace(/\\n/g, "\n")
        .trim();
      if (!text) continue;
      list.push({
        start: ev.tStartMs,
        end: ev.tStartMs + (ev.dDurationMs || 0),
        text: text
      });
    }
    return list;
  }

  // Inject target language parameters while keeping signature valid
  function getTranslationUrl(baseUrl, lang) {
    try {
      const url = new URL(baseUrl);
      url.searchParams.set("tlang", lang);
      url.searchParams.set("fmt", "json3");
      return url.toString();
    } catch (e) {
      let clean = baseUrl.includes("?") ? baseUrl : baseUrl + "?";
      clean = clean
        .replace(/&tlang=[^&]*/g, "")
        .replace(/\s*tlang=[^&]*/g, "?");
      clean = clean
        .replace(/&fmt=[^&]*/g, "")
        .replace(/\s*fmt=[^&]*/g, "?");
      return clean + "&tlang=" + lang + "&fmt=json3";
    }
  }

  // Pull translated captions using the captured authenticated URL
  async function fetchSubtitles() {
    const u = new URL(location.href);
    const videoId = u.searchParams.get("v");
    if (!videoId) return;

    // Detect Video SPA Navigation
    if (lastVideoId !== videoId) {
      lastVideoId = videoId;
      subtitleSegments = [];
      lastFetchedUrl = "";
      window.YT_CAPTURED_URL = "";
      updateDisplay("");
    }

    // Force Caption Activation to Seed the URL
    if (!window.YT_CAPTURED_URL) {
      const btn = document.querySelector(".ytp-subtitles-button");
      if (btn && btn.getAttribute("aria-pressed") === "false") {
        console.log("[Dual Subs] Enabling native captions to generate secure session token...");
        btn.click();
      }
      return;
    }

    const urlToFetch = getTranslationUrl(window.YT_CAPTURED_URL, targetLang);
    if (urlToFetch === lastFetchedUrl) return;

    try {
      console.log("[Dual Subs] Fetching translation from YouTube servers...");
      const res = await fetch(urlToFetch);
      if (!res.ok) {
        console.error("[Dual Subs] Subtitle fetch failed with status:", res.status);
        return;
      }
      const rawText = await res.text();
      if (!rawText.trim()) {
        console.error("[Dual Subs] Received empty subtitle body.");
        return;
      }

      if (rawText.trim().startsWith("<")) {
        console.log("[Dual Subs] Parsing XML format...");
        subtitleSegments = parseXml(rawText);
      } else {
        console.log("[Dual Subs] Parsing JSON format...");
        const data = JSON.parse(rawText);
        subtitleSegments = parseJson3(data);
      }

      lastFetchedUrl = urlToFetch;
      console.log(
        `%c[Dual Subs] Successfully loaded ${subtitleSegments.length} segments!`,
        "color:#4caf50;font-weight:bold;"
      );
    } catch (err) {
      console.error("[Dual Subs] Fetch error:", err);
    }
  }

  // Update the custom UI overlay
  function updateDisplay(text) {
    const el = document.getElementById(C_ID);
    if (!el) return;
    if (text && enabled) {
      el.textContent = text;
      el.classList.add("visible");
    } else {
      el.classList.remove("visible");
    }
  }

  // Synchronize subtitle segment with video.currentTime
  function onTimeUpdate() {
    if (!videoElement) return;
    const timeMs = videoElement.currentTime * 1000;
    const matched = subtitleSegments.find(
      s => s.start <= timeMs && timeMs <= s.end
    );
    updateDisplay(matched ? matched.text : "");
  }

  // Inject UI widgets and link player events
  function syncDOM() {
    const video = document.querySelector("video.html5-main-video");
    const player =
      document.getElementById("movie_player") ||
      document.querySelector(".html5-video-player");

    if (video && player) {
      // 1. Dual Subtitles Text Overlay
      let container = document.getElementById(C_ID);
      if (!container) {
        container = document.createElement("div");
        container.id = C_ID;
        player.appendChild(container);
      }

      // 2. Bilingual Settings Floating Control Panel
      let menu = document.getElementById(M_ID);
      if (!menu) {
        menu = document.createElement("div");
        menu.id = M_ID;

        const label = document.createElement("span");
        label.style.fontWeight = "bold";
        label.style.color = "#3ea6ff";
        label.textContent = "Bilingual CC: ";

        const select = document.createElement("select");
        select.id = "yt-dual-subs-lang";

        const langs = [
          { value: "en", name: "English" },
          { value: "es", name: "Spanish" },
          { value: "zh-Hans", name: "Chinese (Simp)" },
          { value: "zh-Hant", name: "Chinese (Trad)" },
          { value: "fr", name: "French" },
          { value: "de", name: "German" },
          { value: "ja", name: "Japanese" },
          { value: "ko", name: "Korean" },
          { value: "pt", name: "Portuguese" },
          { value: "it", name: "Italian" },
          { value: "ru", name: "Russian" }
        ];

        langs.forEach(item => {
          const opt = document.createElement("option");
          opt.value = item.value;
          opt.textContent = item.name;
          if (item.value === targetLang) {
            opt.selected = true;
          }
          select.appendChild(opt);
        });

        const toggleBtn = document.createElement("button");
        toggleBtn.id = "yt-dual-subs-toggle";
        toggleBtn.textContent = "ON";

        menu.appendChild(label);
        menu.appendChild(select);
        menu.appendChild(toggleBtn);
        player.appendChild(menu);

        // Language Selector Event
        select.onchange = () => {
          targetLang = select.value;
          fetchSubtitles().then(() => onTimeUpdate());
        };

        // On/Off Toggle Event
        toggleBtn.onclick = () => {
          enabled = !enabled;
          toggleBtn.textContent = enabled ? "ON" : "OFF";
          toggleBtn.className = enabled ? "" : "disabled";
          onTimeUpdate();
        };
      }

      // 3. Link Video Playback State listeners
      if (videoElement !== video) {
        if (videoElement) {
          videoElement.removeEventListener("timeupdate", onTimeUpdate);
        }
        videoElement = video;
        videoElement.addEventListener("timeupdate", onTimeUpdate);
        fetchSubtitles();
      }
    }
  }

  // Run initial DOM Sync
  syncDOM();

  // Polling loop to manage page navigations and capture timing
  window.YT_DUAL_SUB_INTERVAL = setInterval(() => {
    syncDOM();
    fetchSubtitles();
  }, 1000);
})();
