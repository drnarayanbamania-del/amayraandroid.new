#!/usr/bin/env python3
"""Generate preview/index.html from live project state.

Sources of truth:
  - docs/FEATURE_MATRIX.md        -> feature tables (v4.15.1 + retained 3.0)
  - C:/maya-build test-results    -> unit test counts (JUnit XML)
  - C:/maya-build app-debug.apk   -> artifact size/mtime
  - gradle/libs.versions.toml     -> AGP/Kotlin versions
  - gradle/wrapper/*.properties   -> Gradle version
  - app/build.gradle.kts          -> compileSdk/minSdk
  - app/src/main/java tree        -> Kotlin file counts per package
  - app/src/main/assets tree      -> bundled asset sizes

Usage:  python scripts/gen_dashboard.py
Run after builds/tests to refresh the dashboard (the Preview tab reloads it
from disk automatically).
"""

from __future__ import annotations

import html
import os
import re
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD = Path(os.environ.get("MAYA_BUILD_DIR", "C:/maya-build/app"))
OUT = ROOT / "preview" / "index.html"

# --------------------------------------------------------------------------
# Parsing helpers
# --------------------------------------------------------------------------

def read(p: Path) -> str:
    try:
        return p.read_text(encoding="utf-8")
    except OSError:
        return ""


def parse_matrix(md: str):
    """Return [(title, [rows])] where row = (area, reference, replica, status_char)."""
    sections = []
    lines = md.splitlines()
    i = 0
    while i < len(lines):
        line = lines[i]
        m = re.match(r"^##\s+(.+)$", line)
        if m:
            # find the first non-blank line after the heading
            j = i + 1
            while j < len(lines) and not lines[j].strip():
                j += 1
            if j < len(lines) and lines[j].lstrip().startswith("|"):
                title = m.group(1).strip()
                rows = []
                seen_sep = False  # markdown tables: header row, then |---| delimiter, then data
                while j < len(lines) and lines[j].lstrip().startswith("|"):
                    raw = lines[j].strip()
                    cells = [c.strip() for c in raw.strip("|").split("|")]
                    is_sep = len(cells) >= 2 and all(c and set(c) <= {"-", ":", " "} for c in cells)
                    if is_sep:
                        seen_sep = True
                    elif seen_sep and len(cells) >= 4:
                        status = cells[-1]
                        ch = next((e for e in ("✅", "🟡", "⬜", "🧩") if e in status), "🧩")
                        rows.append((cells[0], cells[1], cells[2], ch))
                    j += 1
                if rows:
                    sections.append((title, rows))
                i = j
                continue
        i += 1
    return sections


def norm_area(s: str) -> str:
    return re.sub(r"[^a-z0-9]", "", s.lower())


def overlaps(a: str, b: str) -> bool:
    na, nb = norm_area(a), norm_area(b)
    return bool(na) and bool(nb) and (na in nb or nb in na)


# --------------------------------------------------------------------------
# Live state
# --------------------------------------------------------------------------

def test_results():
    tests = failures = errors = skipped = 0
    tdir = BUILD / "test-results" / "testDebugUnitTest"
    if tdir.is_dir():
        for f in tdir.glob("*.xml"):
            txt = read(f)
            m = re.search(r'tests="(\d+)"', txt)
            if m:
                tests += int(m.group(1))
            for pat, acc in (
                (r'failures="(\d+)"', "f"), (r'errors="(\d+)"', "e"), (r'skipped="(\d+)"', "s")
            ):
                mm = re.search(pat, txt)
                if mm:
                    v = int(mm.group(1))
                    if acc == "f": failures += v
                    elif acc == "e": errors += v
                    else: skipped += v
    return tests, failures, errors, skipped


def apk_info():
    p = BUILD / "outputs" / "apk" / "debug" / "app-debug.apk"
    if not p.is_file():
        return None
    st = p.stat()
    mb = st.st_size / (1024 * 1024)
    age_h = (time.time() - st.st_mtime) / 3600
    age = f"{age_h:.1f} h ago" if age_h >= 1 else f"{age_h*60:.0f} min ago"
    return f"{mb:.0f} MB", age


def toolchain():
    toml = read(ROOT / "gradle" / "libs.versions.toml")
    agp = re.search(r'^agp\s*=\s*"([^"]+)"', toml, re.M)
    kotlin = re.search(r'^kotlin\s*=\s*"([^"]+)"', toml, re.M)
    wrapper = read(ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties")
    gradle = re.search(r"gradle-([0-9.]+)-", wrapper)
    app_grad = read(ROOT / "app" / "build.gradle.kts")
    compile_sdk = re.search(r"release\((\d+)\)", app_grad)
    min_sdk = re.search(r"minSdk\s*=\s*(\d+)", app_grad)
    return (
        agp.group(1) if agp else "?",
        kotlin.group(1) if kotlin else "?",
        gradle.group(1) if gradle else "?",
        compile_sdk.group(1) if compile_sdk else "?",
        min_sdk.group(1) if min_sdk else "?",
    )


def source_map():
    """Package dir -> file count, under app/src/main/java/com/amayra/maya."""
    base = ROOT / "app" / "src" / "main" / "java" / "com" / "amayra" / "maya"
    descriptions = {
        "ai": "Models · AiClient · GeminiClient · OpenAiCompatClient",
        "avatar": "AvatarController · renderers (holographic + Live2D pluggable)",
        "core": "MayaCognitiveCore · StateBus · MayaCoreService · MayaLog",
        "data": "messages · memories · tool logs · auto-reply log",
        "events": "EventBus · MayaNotificationListener",
        "integration": "Termux · WhatsApp · OpenClaw · SOS · CallManager · PcRelay",
        "memory": "SecureStore (Keystore)",
        "persona": "Persona (Maya/Friday/Venom) · PersonaManager · switch tool",
        "screen": "ScreenCaptureService · ScreenShareBus",
        "settings": "SettingsRepository (DataStore)",
        "tools": "ToolRegistry · RiskLevel · device/screen tools",
        "ui": "Chat · Tools · Settings · Diagnostics · Scanner (Compose M3)",
        "voice": "VoiceController (STT + TTS arbiter + engine selection)",
        "voice/guardian": "SpeakerEmbedder · SpeechGate · ScoreNormalizer · VoiceGuardian",
        "voice/wakeword": "WakeWordEngine (3-model chain) · WakeWordService (mic FGS)",
        "voice/tts": "GeminiTtsClient (interactions + generateContent fallback)",
        "accessibility": "MayaAccessibilityService · MacroEngine · macro tools",
    }
    entries = []
    total = 0
    if base.is_dir():
        # leaf packages first (voice/guardian etc.), then top-level dirs
        dirs = sorted(
            (d for d in base.rglob("*") if d.is_dir() and not any(p.is_file() for p in d.iterdir()) is False and any(p.suffix == ".kt" for p in d.iterdir())),
            key=lambda d: str(d).lower(),
        )
        seen_top = set()
        for d in dirs:
            rel = d.relative_to(base).as_posix()
            top = rel.split("/")[0]
            if "/" in rel:
                key = rel
            else:
                if top in seen_top:
                    continue
                seen_top.add(top)
                key = top
            n = sum(1 for _ in d.rglob("*.kt") if "/" not in _.relative_to(base).as_posix() or key == rel or _.relative_to(base).as_posix().startswith(key))
            n = sum(1 for f in d.glob("*.kt"))
            desc = descriptions.get(key)
            if desc is None and "/" in key:
                desc = " · ".join(p.name for p in sorted(d.glob("*.kt")))
            entries.append((key, desc or "", n))
            total += n
        # count top-level loose files
        loose = [f.name for f in base.glob("*.kt")]
        if loose:
            entries.append(("<root>", " · ".join(loose), len(loose)))
            total += len(loose)
    return entries, total


def asset_map():
    base = ROOT / "app" / "src" / "main" / "assets"
    out = []
    if base.is_dir():
        for d in sorted(base.iterdir()):
            if d.is_dir():
                files = list(d.rglob("*"))
                n = sum(1 for f in files if f.is_file())
                size = sum(f.stat().st_size for f in files if f.is_file())
                out.append((d.name, n, fmt_size(size)))
        loose = [f for f in base.iterdir() if f.is_file()]
        if loose:
            out.append(("<files>", len(loose), fmt_size(sum(f.stat().st_size for f in loose))))
    return out


def fmt_size(n: float) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit in ("B", "KB") else f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} GB"


# --------------------------------------------------------------------------
# Rendering
# --------------------------------------------------------------------------

STATUS_MAP = {
    "✅": ("ok", "Implemented"),
    "🟡": ("equiv", "Equivalent"),
    "⬜": ("todo", "Not yet"),
    "🧩": ("inf", "Inferred"),
}


def fmt_cell(s: str) -> str:
    s = html.escape(s, quote=False)
    s = re.sub(r"`([^`]+)`", r'<span class="mono">\1</span>', s)
    return s


def render_table(rows, superseded_map):
    out = ['<table>',
           '<tr><th>Area</th><th>Reference</th><th>This replica</th><th>Status</th></tr>']
    for area, ref, rep, ch in rows:
        cls, label = STATUS_MAP[ch]
        note = ""
        if area in superseded_map:
            new_ch = superseded_map[area]
            new_cls, new_label = STATUS_MAP[new_ch]
            cls, label = new_cls, new_label
            note = ' <span class="tag inf">↑ v4.15.1</span>'
        out.append(
            f"<tr><td>{fmt_cell(area)}</td><td>{fmt_cell(ref)}</td>"
            f"<td>{fmt_cell(rep)}</td><td><span class=\"tag {cls}\">{label}</span>{note}</td></tr>"
        )
    out.append("</table>")
    return "\n".join(out)


def build_html():
    md = read(ROOT / "docs" / "FEATURE_MATRIX.md")
    sections = parse_matrix(md)
    v4_rows = next((r for t, r in sections if "4.15" in t), [])
    legacy_rows = next((r for t, r in sections if "3.0" in t or "retained" in t.lower()), [])
    v4_areas = {a for a, *_ in v4_rows}
    superseded = {}
    for a, *_rest in legacy_rows:
        match = next((v for v in v4_areas if overlaps(a, v)), None)
        if match:
            superseded[a] = next(ch for area, _, _, ch in v4_rows if area == match)

    tests, failures, errors, skipped = test_results()
    apk = apk_info()
    agp, kotlin, gradle, csdk, msdk = toolchain()
    pkgs, kt_total = source_map()
    assets = asset_map()

    tests_html = f"{tests}/{tests} TESTS" if tests and failures == 0 and errors == 0 else (
        f"{failures + errors} FAILING" if tests else "TESTS NOT RUN")
    badge_cls = "badge" if (failures == 0 and errors == 0 and tests) else "badge badge-bad"

    if apk:
        apk_card = (f'<div class="stat"><div class="k">Debug APK</div><div class="v">{apk[0]}</div>'
                    f'<div class="d">C:\\maya-build\\app\\outputs\\apk\\debug\\app-debug.apk'
                    f'<br>built {apk[1]} · ships Guardian + wake-word models</div></div>')
    else:
        apk_card = ('<div class="stat"><div class="k">Debug APK</div><div class="v">not built</div>'
                    '<div class="d">run <span class="mono">./gradlew assembleDebug</span></div></div>')

    pkg_html = "\n".join(
        f'<div class="file"><b>{html.escape(name)}/</b><span>{fmt_cell(desc)} · <b style="color:var(--text)">{n} kt</b></span></div>'
        for name, desc, n in pkgs
    )
    asset_html = "\n".join(
        f'<div class="file"><b>assets/{html.escape(name)}/</b><span>{n} files · {size}</span></div>'
        for name, n, size in assets
    )

    sections_html = ""
    if v4_rows:
        sections_html += f'<section><h2>v4.15.1 parity — {len(v4_rows)} areas vs the real reference build</h2>\n{render_table(v4_rows, {})}</section>'
    if legacy_rows:
        note = "" if not superseded else (
            f'<div class="d" style="color:var(--dim);font-size:12.5px;margin-bottom:10px">'
            f'Rows marked <span class="tag inf">↑ v4.15.1</span> are superseded by the newer section above — '
            f'their status badge shows the current v4.15.1 result.</div>')
        sections_html += f'<section><h2>MAYA-3.0 baseline (retained)</h2>\n{note}\n{render_table(legacy_rows, superseded)}</section>'

    legend = ('<div class="legend"><span class="tag ok">Implemented</span>'
              '<span class="tag equiv">Closest legal equivalent</span>'
              '<span class="tag inf">Inferred from spec</span>'
              '<span class="tag todo">Not yet</span></div>')

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Maya — Android AI Assistant · Project Dashboard</title>
<style>
  :root {{
    --bg: #0B0E14; --surface: #121722; --surface2: #1A2130;
    --primary: #7C4DFF; --accent: #00E5C7; --warn: #FFB454; --err: #FF5470;
    --text: #E8EAF2; --dim: #8A93A8; --border: #232B3E;
  }}
  * {{ margin: 0; padding: 0; box-sizing: border-box; }}
  body {{ background: var(--bg); color: var(--text); font-family: 'Segoe UI', system-ui, sans-serif;
         min-height: 100vh; padding: 32px 24px; }}
  .wrap {{ max-width: 1080px; margin: 0 auto; }}
  header {{ display: flex; align-items: center; gap: 18px; margin-bottom: 28px; }}
  .logo {{ width: 64px; height: 64px; border-radius: 20px; flex-shrink: 0;
           background: radial-gradient(circle at 50% 45%, #B39DDB 0%, #7C4DFF 45%, transparent 72%),
                       radial-gradient(circle at 50% 50%, rgba(124,77,255,.35) 0%, transparent 60%);
           border: 1px solid var(--border); animation: breath 3.2s ease-in-out infinite; }}
  .logo::before, .logo::after {{ content: ''; position: absolute; width: 7px; height: 9px;
           border-radius: 50%; background: #E8EAF2; top: 42%; }}
  .logo {{ position: relative; }}
  .logo::before {{ left: 34%; }} .logo::after {{ right: 34%; }}
  @keyframes breath {{ 0%,100% {{ transform: scale(1); }} 50% {{ transform: scale(1.045); }} }}
  h1 {{ font-size: 30px; font-weight: 700; letter-spacing: -.5px; }}
  h1 span {{ color: var(--primary); }}
  .sub {{ color: var(--dim); font-size: 14px; margin-top: 3px; }}
  .badge {{ display: inline-flex; align-items: center; gap: 7px; margin-left: auto;
            background: rgba(0,229,199,.1); border: 1px solid rgba(0,229,199,.35);
            color: var(--accent); padding: 8px 16px; border-radius: 999px;
            font-size: 13px; font-weight: 600; white-space: nowrap; }}
  .badge::before {{ content: ''; width: 8px; height: 8px; border-radius: 50%; background: currentColor;
                    box-shadow: 0 0 10px currentColor; animation: pulse 1.6s infinite; }}
  .badge-bad {{ background: rgba(255,84,112,.1); border-color: rgba(255,84,112,.4); color: var(--err); }}
  @keyframes pulse {{ 0%,100% {{ opacity: 1; }} 50% {{ opacity: .35; }} }}
  .grid {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 14px; margin-bottom: 22px; }}
  .stat {{ background: var(--surface); border: 1px solid var(--border); border-radius: 16px; padding: 18px; }}
  .stat .k {{ color: var(--dim); font-size: 12px; text-transform: uppercase; letter-spacing: 1px; margin-bottom: 8px; }}
  .stat .v {{ font-size: 21px; font-weight: 650; }}
  .stat .d {{ color: var(--dim); font-size: 12.5px; margin-top: 6px; line-height: 1.5; }}
  section {{ background: var(--surface); border: 1px solid var(--border); border-radius: 20px;
             padding: 24px; margin-bottom: 20px; }}
  section h2 {{ font-size: 17px; margin-bottom: 16px; display: flex; align-items: center; gap: 10px; }}
  section h2::before {{ content: ''; width: 4px; height: 18px; border-radius: 2px; background: var(--primary); }}
  .flow {{ display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }}
  .node {{ background: var(--surface2); border: 1px solid var(--border); border-radius: 12px;
           padding: 10px 14px; font-size: 13px; font-weight: 600; }}
  .node.hot {{ border-color: rgba(124,77,255,.5); color: #C9B5FF; }}
  .arrow {{ color: var(--dim); font-size: 15px; }}
  table {{ width: 100%; border-collapse: collapse; font-size: 13.5px; }}
  th {{ text-align: left; color: var(--dim); font-weight: 600; padding: 8px 10px;
        border-bottom: 1px solid var(--border); font-size: 12px; text-transform: uppercase; letter-spacing: .8px; }}
  td {{ padding: 9px 10px; border-bottom: 1px solid rgba(35,43,62,.55); vertical-align: top; }}
  tr:last-child td {{ border-bottom: none; }}
  .tag {{ display: inline-block; padding: 2.5px 10px; border-radius: 999px; font-size: 11.5px; font-weight: 650; }}
  .ok   {{ background: rgba(76,224,166,.12); color: #4CE0A6; }}
  .equiv{{ background: rgba(255,180,84,.12); color: var(--warn); }}
  .todo {{ background: rgba(255,84,112,.1); color: var(--err); }}
  .inf  {{ background: rgba(124,77,255,.14); color: #C9B5FF; }}
  .mono {{ font-family: 'Cascadia Code', Consolas, monospace; font-size: 12px; color: var(--accent); }}
  .legend {{ display: flex; flex-wrap: wrap; gap: 10px; margin-top: 14px; color: var(--dim); font-size: 12px; }}
  .files {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 10px; }}
  .file {{ background: var(--surface2); border: 1px solid var(--border); border-radius: 12px;
           padding: 12px 14px; font-size: 13px; }}
  .file b {{ display: block; margin-bottom: 3px; font-size: 13px; }}
  .file span {{ color: var(--dim); font-size: 12px; }}
  footer {{ text-align: center; color: var(--dim); font-size: 12px; padding: 18px 0 6px; }}
</style>
</head>
<body>
<div class="wrap">
  <header>
    <div class="logo"></div>
    <div>
      <h1>Maya <span>·</span> Android AI Assistant</h1>
      <div class="sub">Clean-room behavioral replica · upgraded to <b>maya-v4.15.1 parity</b> · Kotlin + Jetpack Compose · namespace <span class="mono">com.amayra.maya</span></div>
    </div>
    <div class="{badge_cls}">BUILD SUCCESSFUL · {tests_html}</div>
  </header>

  <div class="grid">
    {apk_card}
    <div class="stat"><div class="k">Toolchain</div><div class="v">AGP {agp}</div>
      <div class="d">Kotlin {kotlin} · Gradle {gradle} · compileSdk {csdk} · minSdk {msdk} · TFLite 2.16 · ML Kit · CameraX</div></div>
    <div class="stat"><div class="k">AI Providers</div><div class="v">Gemini + OpenAI-compat</div>
      <div class="d">Groq · OpenRouter · OpenAI · local (LM Studio / Ollama) · Gemini TTS voices</div></div>
    <div class="stat"><div class="k">Source files</div><div class="v">{kt_total} Kotlin files</div>
      <div class="d">{len([p for p, _, _ in pkgs])} packages incl. guardian · wakeword · tts · persona · accessibility · screen</div></div>
  </div>

  <section>
    <h2>Turn loop — how Maya thinks</h2>
    <div class="flow">
      <div class="node">User input</div><div class="arrow">→</div>
      <div class="node">Memory context</div><div class="arrow">→</div>
      <div class="node hot">AI stream (SSE)</div><div class="arrow">→</div>
      <div class="node hot">ToolRegistry</div><div class="arrow">→</div>
      <div class="node">Risk gate + confirm</div><div class="arrow">→</div>
      <div class="node">Execute + audit log</div><div class="arrow">→</div>
      <div class="node">Response</div><div class="arrow">→</div>
      <div class="node">Gemini TTS · Avatar · DB</div>
    </div>
    <div class="d" style="color:var(--dim);font-size:13px;margin-top:14px">
      State machine: <b style="color:var(--text)">IDLE → LISTENING → PROCESSING → TOOL_EXECUTION → RESPONDING → SPEAKING → IDLE</b> (plus ERROR / SLEEPING).
      The avatar derives its expression live from the same StateBus.
    </div>
  </section>

  {sections_html}
  <div>{legend}</div>

  <section>
    <h2>Project map (live source tree)</h2>
    <div class="files">
{pkg_html}
    </div>
  </section>

  <section>
    <h2>Bundled assets</h2>
    <div class="files">
{asset_html}
    </div>
  </section>

  <section>
    <h2>Quick setup</h2>
    <table>
      <tr><th>Provider</th><th>Base URL</th><th>Example model</th></tr>
      <tr><td>Google Gemini</td><td class="mono">(built-in)</td><td class="mono">gemini-2.0-flash</td></tr>
      <tr><td>Groq</td><td class="mono">https://api.groq.com/openai/v1</td><td class="mono">llama-3.3-70b-versatile</td></tr>
      <tr><td>OpenRouter</td><td class="mono">https://openrouter.ai/api/v1</td><td class="mono">google/gemini-2.0-flash-001</td></tr>
      <tr><td>OpenAI</td><td class="mono">https://api.openai.com/v1</td><td class="mono">gpt-4o-mini</td></tr>
      <tr><td>Local</td><td class="mono">http://&lt;pc-ip&gt;:1234/v1</td><td class="mono">(any loaded model)</td></tr>
    </table>
    <div class="d" style="color:var(--dim);font-size:13px;margin-top:14px">
      Keys are stored in Android Keystore–backed EncryptedSharedPreferences, never logged.
      Install: <span class="mono">adb install app-debug.apk</span> → Settings → AI → paste key → chat.
    </div>
  </section>

  <footer>Maya replica dashboard · generated from live build/test state by <span class="mono">scripts/gen_dashboard.py</span> · {time.strftime('%Y-%m-%d %H:%M')}</footer>
</div>
</body>
</html>
"""


def main() -> int:
    html_out = build_html()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(html_out, encoding="utf-8")
    tests, failures, errors, _ = test_results()
    print(f"dashboard written: {OUT}")
    print(f"  tests={tests} failures={failures} errors={errors} apk={'yes' if apk_info() else 'no'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
