# YouTube Bilingual Subtitles (with Attestation Bypass)

A lightweight, unprivileged, 100% client-side pipeline to inject a highly responsive settings menu and bilingual translation subtitle overlay directly on the native YouTube video player.

This bypasses **enterprise software restrictions** (blocked extension installs/developer folders) and YouTube's modern **Proof of Play (PoP) / Attestation Protection** (`pot` tokens).

---

## The Challenge: Proof of Play (PoP) Attestation

Recently, YouTube implemented secure player verification. When requesting raw tracks via the `/api/timedtext` API, requests must be signed with a session-specific **Player Operations Token (`pot` parameter)**. 

If you attempt to construct the subtitle fetch URL manually or use standard crawler tools, the server responds with a silent empty body:
- **HTTP Status:** `200 OK`
- **Content Length:** `0` characters (completely empty)

---

## The Solution: Dynamic Network Hooking

Our approach uses a clever web engineering loophole to extract authentic subtitles on any active video page:

1. **Native API Hooking:**
   The script monkey-patches Chrome's native page-level network APIs (`window.fetch` and `XMLHttpRequest.prototype.open`). When the native YouTube video player requests a caption track, our hook immediately intercepts and stores the **fully-signed, authenticated URL** (carrying the fresh, legitimate session `pot` token).

2. **The Signature Loophole:**
   YouTube's server-side URL signature checks are bound strictly to a parameter list (`sparams=ip,ipbits,expire,v,ei,...`). 
   - **Crucially, the `tlang` (target language) and `fmt` (response format) parameters are NOT part of the signed parameter list.**
   - This means we can dynamically overwrite or append `&tlang=en` (for English) or any other language, and fetch the URL. YouTube’s server accepts the signature as valid and returns the complete cloud-translated subtitle data!

3. **DOM Rendering & Event Syncing:**
   The script listens to the YouTube `<video>` element's `timeupdate` event. Every time the video plays, it calculates the current position in milliseconds, finds the corresponding translated line, and renders it in a premium, glassmorphic subtitle container overlaid on the video player.

---

## How to Reproduce & Run (No Extensions Needed!)

Choose one of these three extremely simple ways to execute the script in your browser.

### Option 1: Create a Bookmarklet (Easiest - Single Click!)
You can save this script as a bookmarklet. Whenever you open any YouTube video, click your bookmark button, and the bilingual subtitles instantly launch!

1. Make sure your Bookmarks Bar is visible in Chrome (`Cmd + Shift + B` on macOS or `Ctrl + Shift + B` on Windows).
2. Right-click on your bookmarks bar and select **Add Page...**
3. Set the **Name** to: `🌐 Dual Subs`
4. Set the **URL** to the exact contents of [`bookmarklet.js`](bookmarklet.js) (starting with `javascript:`).
5. Click **Save**.
6. Open any video, e.g., [Watch YouTube Video](https://www.youtube.com/watch?v=0X3fmjVWmsE&t=48s), and click the bookmark.

---

### Option 2: Run directly in the DevTools Console
1. Open the YouTube video page.
2. Open Chrome Developer Tools Console (`Cmd + Option + J` or `F12`).
3. Copy the entire multiline script in [`script.js`](script.js), paste it into the console, and press **Enter**.
4. If native closed captions (CC) are currently off, the script will automatically activate CC once to force the player to make the signed network request, which is then intercepted and translated.

---

### Option 3: Save as a DevTools Snippet (For Developers)
If you do not want to keep copying and pasting:
1. Open DevTools, go to the **Sources** tab.
2. Click the **`>>`** arrow on the left sidebar and select **Snippets**.
3. Click **`+ New snippet`**, name it `dual-subs`.
4. Paste the entire content of [`script.js`](script.js) inside the editor panel and save (`Cmd + S`).
5. To run it at any time, just right-click the snippet and select **Run** (or press `Cmd + Enter`).

---

## File Manifest
- [`script.js`](script.js): The clean, formatted, fully commented multiline JavaScript file. Perfect for reviewing, editing, or loading as a tampermonkey userscript.
- [`bookmarklet.js`](bookmarklet.js): The URI-encoded bookmark-safe string used to trigger the bypass with a single click.
