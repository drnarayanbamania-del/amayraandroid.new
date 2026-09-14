# AMAYRA Desktop (recovered Electron app) — Capability Audit & Run Report

**Date:** 2026-09-12 · **Source:** `AMAYRA-project.zip` → extracted to `E:\MAYRA PROJECTS\amayra-desktop\AMAYRA`
(C: was 100% full; E: has 85 GB free. Do not run npm/Gradle from the OneDrive-synced C: path.)

## 1. Verdict

The recovered app is a **substantially complete, high-quality implementation of the
JARVIS-style brief** — voice-first (Gemini Live), brain (full cognitive runtime),
memory (categorized + consolidation), hands (64-tool Python desktop agent),
eyes (multimodal screen vision), and a mature safety architecture. It supersedes
the Kotlin `pc-assistant` module for the PC milestone.

## 2. Provenance (from its own README)

- `electron/`, `server.ts`, `server_*.ts`, `cognition/*`, `api_hub/*` — **original source**, recovered verbatim from the shipped `dist/server.cjs.map` sourcesContent.
- Renderer TS/TSX — **lost**; only compiled bundles in `dist/assets/` ship. UI changes mean editing bundles or rebuilding `src/`.
- `agent_dist/amayra-agent/` — original PyInstaller-frozen Python agent.
- Types in `src/lib/memoryTypes.ts`, `cognition/types.ts` etc. — reconstructed from usage.

## 3. Capability inventory

### Hands — MYRAA Desktop Control Agent (FastAPI, 127.0.0.1:8765)
64 verified tools including: `openApplication`, `closeApplication`, `switchApplication`,
`clickText` (exact-text clicking — resolves labels at action time, avoids hardcoded
coordinates), `click`, `doubleClick`, `rightClick`, `drag`, `typeText`, `pressKey`,
`hotkey`, `scroll`, `moveMouse`, `listVisibleWindows`, `getActiveWindow`,
`maximizeWindow`, `minimizeWindow`, `closeWindow`, `viewScreen`, `readScreen`,
`takeScreenshot`, `analyzeScreenshot`, `observeDesktopState`, `waitForUi`,
`locateText`, files (`createFile`, `readFile`, `deleteFile`, `renameFile`, `moveFile`,
`searchFiles`, `writeCodeFile`, `createPythonFile`), web (`openWebsite`,
`searchWeb`, `searchYouTube`, `searchGoogle`, `searchGitHub`), system
(`setVolume`, `setBrightness`, `systemInfo`, `gpuInfo`, `temperatureInfo`,
power actions), clipboard, `runPythonScript`.

### Eyes
`server_screenVision.ts` injects screen captures into the **Gemini Live** session
as realtime video frames, with conservative intent detection (only explicit
"look at my screen" requests trigger capture) and a short-lived capture cache
for follow-ups. Screenshot-driven clicking is explicitly discouraged in the
system instructions in favor of `clickText`.

### Brain
`cognition/` (~4,100 lines): `planner`, `critic`, `autonomousMind`, `attentionEngine`,
`initiativeEngine`, `curiosityEngine`, `socialInitiativeEngine`,
`conversationContinuationEngine`, `proactivePresence`, `situationModel`,
`goalManager`, `skillManager`, `modelRouter`, `speechOrchestrator`,
`errorProtocol`, `eventBus`, `desktopPerception`. OBSERVE→ACT→VERIFY discipline is
enforced in the capability instructions ("observe again, and verify the expected
change... after two equivalent failures change strategy or report the blocker").

### Memory
`StructuredMemoryStore` + Gemini-driven consolidation; categories: identity,
preference, goal, project, relationship, emotional, behavior. Legacy flat cards
in `memories.json`. `saveCustomMemory` instruction limits storage to durable,
useful facts.

### Safety
`cognition/safety.ts`: `SafetyPolicy` (permission gates + autonomy pause) and
`ConfirmationStore` (TTL-bounded, 120 s) with risk levels 1–4; risk ≥ 3 requires
explicit confirmation. Risk is context-sensitive (e.g. `deleteFile` permanent → 4;
`closeApplication --force` → 3). Cancellation ("stop") is honored. Power actions
blocked from autonomous turns.

### Personality
Extensive persona engineering in `server.ts` (~2,050–2,120): conversational-equal
rules, Hinglish/Hindi/English matching, anti-chatbot bans, final-turn discipline,
proactive-presence rules, "never claim feelings/consciousness". Matches the
JARVIS-personality brief closely.

## 4. Security & hygiene findings

1. **API key** — per-user Gemini key in `<userData>/secrets.json` (`.amayra-data/` is gitignored). OK.
2. **`runPythonScript` tool** — arbitrary code execution; it sits behind the same
   risk/confirmation gates but is the most dangerous surface. Consider disabling
   by default or gating to DANGEROUS.
3. **Agent binds 127.0.0.1 only** — good, but any local process can call it
   (no auth token on :8765). Local-trust model; acceptable for a personal
   machine, not for shared machines.
4. **Dependency freshness** — Electron 43, React 19, `@google/genai` 2.4.0 — current as of the recovery. `npm install` runs several postinstall scripts; review with `npm approve-scripts` if supply-chain is a concern.
5. **C: drive full (0 bytes free)** — fixed by relocating to E:. Keep build data off OneDrive.

## 5. Run procedure (verified working)

```powershell
cd "E:\MAYRA PROJECTS\amayra-desktop\AMAYRA"
npm install                # done once (ELECTRON_SKIP_BINARY_DOWNLOAD=1 was used; electron@43.1.0 added later)
npm run build:server       # esbuild → dist/server.cjs  (363 KB, 0 errors)
npx electron .             # launches backend + agent + UI
```

Verified live: server on http://localhost:3000 (HTTP 200), agent on
http://127.0.0.1:8765/health (`tool_count: 64`), Electron window processes running,
no errors in `electron-run.log`. A Gemini API key is required in-app for
conversation (first-run Setup).

## 6. Gaps / next steps

1. **Renderer source** is missing — pick UI work carefully (bundles are editable but not rebuildable from TS).
2. **Android bridge**: Maya's `PcRelay` speaks :18789; AMAYRA desktop doesn't. Add a small :18789 listener in `server.ts` that forwards `SEND_COMMAND` text into the Live session.
3. **Voice**: Gemini Live is already voice-first — test mic flow, then wire the tablet as a remote microphone via the bridge.
4. **Retire or archive the Kotlin `pc-assistant`** module to avoid two competing PC assistants, or keep it purely as a relay/sidecar.
5. Free C: drive space (system health) — npm caches and Gradle builds will keep failing at 0 bytes.
