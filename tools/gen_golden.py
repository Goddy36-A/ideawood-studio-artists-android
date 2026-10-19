#!/usr/bin/env python3
"""Generate app/src/test/resources/golden.json from the DESKTOP app (Goddy36-A/ideawood-studio-artists).

The Kotlin port must reproduce these results exactly (see ParityTest.kt):
  - Whisper JSON parsing, lyric parsing, alignment, timing clean-up, edit operations
  - ASS scripts for every preset / video shape, scene planning, LRC / SRT
  - the full ffmpeg filter graph for the render

Usage:  python tools/gen_golden.py /path/to/ideawood-studio-artists  app/src/test/resources/golden.json
"""
import json
import os
import random
import sys

repo, out = sys.argv[1], sys.argv[2]
sys.path.insert(0, repo)

from ideawood_artists import asr, lyrics as L  # noqa: E402
from ideawood_artists.ass_lyrics import build_ass  # noqa: E402
from ideawood_artists.project import Project, Scene, frame_size  # noqa: E402
from ideawood_artists.render import build_command  # noqa: E402
from ideawood_artists.scenes import plan_scenes  # noqa: E402
from ideawood_artists.styles import PRESETS  # noqa: E402

rnd = random.Random(7)

LYRICS = """[Intro]
[Verse 1]
Walking down the road tonight
Stars are shining way up bright
I can feel the rhythm calling me, calling me home
Every step is a story that I know

[Chorus]
Oh we dance, we dance until the sun comes up
Hold my hand, hold my hand and never let go
We dance, we dance until the sun comes up
Hold my hand, hold my hand and never let go

[Verse 2]
Mama said the river knows the way
Papa said keep the faith each day
I can hear the drums across the valley, across the sea, across the hills where we belong
Every step is a story that I know

[Chorus]
Oh we dance, we dance until the sun comes up
Hold my hand, hold my hand and never let go
We dance, we dance until the sun comes up
Hold my hand, hold my hand and never let go

[Bridge]
Mpa mpa, nyabo, tugende
Tugende

[Outro]
Hold my hand and never let go
"""

DURATION = 214.0


def make_whisper_json():
    """A synthetic whisper-cli -ojf transcript: the lyric words with timing, some missed / misspelled /
    glued to punctuation, plus special tokens and a [MUSIC] segment (so every parser branch is exercised)."""
    lines = [x for x in L.parse_lyrics(LYRICS)]
    t = 9.0
    segs = []
    for i, ln in enumerate(lines):
        if i in (4, 12, 20):
            t += 11.0                                    # instrumental breaks
        words = ln.text.split()
        toks = [{"text": "[_BEG_]", "offsets": {"from": int(t * 1000), "to": int(t * 1000)}}]
        s0 = t
        for w in words:
            d = 0.22 + 0.04 * (len(w) % 5)
            r = rnd.random()
            if r < 0.07:
                t += d                                    # word missed by the recogniser
                continue
            if r < 0.14 and len(w) > 4:
                w = w[:-1] + "e"                          # misspelled
            if r > 0.93:
                toks.append({"text": " " + w[: len(w) // 2], "offsets": {"from": int(t * 1000), "to": int((t + d / 2) * 1000)}})
                toks.append({"text": w[len(w) // 2:], "offsets": {"from": int((t + d / 2) * 1000), "to": int((t + d) * 1000)}})
            else:
                toks.append({"text": " " + w, "offsets": {"from": int(t * 1000), "to": int((t + d) * 1000)}})
            t += d + 0.03
        if rnd.random() < 0.3:
            toks.append({"text": ",", "offsets": {"from": int(t * 1000), "to": int(t * 1000)}})
        toks.append({"text": "<|endoftext|>", "offsets": {}})
        text = " ".join(x["text"].strip() for x in toks if not x["text"].startswith(("[_", "<|"))) or "..."
        segs.append({"offsets": {"from": int(s0 * 1000), "to": int(t * 1000)}, "text": " " + text, "tokens": toks})
        t += 0.55
        if i == 9:
            segs.append({"offsets": {"from": int(t * 1000), "to": int((t + 4) * 1000)}, "text": " [MUSIC]", "tokens": []})
            t += 4
    return {"result": {"language": "en"}, "transcription": segs}


def ld(l):
    """Full-precision dump of a lyric line (to_dict() rounds to 3 decimals)."""
    return {"text": l.text, "start": l.start, "end": l.end, "section": l.section, "stanza": l.stanza,
            "words": [[w.start, w.end, w.text] for w in l.words]}


def tr_dump(tr):
    return {"language": tr.language,
            "segments": [{"start": s.start, "end": s.end, "text": s.text,
                          "words": [[w.start, w.end, w.text] for w in s.words]} for s in tr.segments]}


wj = make_whisper_json()
tr = asr.parse_whisper_json(wj)
pasted = L.parse_lyrics(LYRICS)
aligned = L.align_lyrics(L.parse_lyrics(LYRICS), tr, DURATION)
from_tr = L.lines_from_transcript(tr)
even = L.distribute_evenly(L.parse_lyrics(LYRICS), DURATION, lead=6.0)
even = L.finalize_timing(even, DURATION)

g = {
    "lyrics_text": LYRICS, "duration": DURATION, "whisper_json": wj, "parsed_transcript": tr_dump(tr),
    "parsed_lines": [ld(l) for l in pasted],
    "aligned": [ld(l) for l in aligned],
    "from_transcript": [ld(l) for l in from_tr],
    "even": [ld(l) for l in even],
}

# edit operations on a copy of the aligned lines
ed = [L.LyricLine(l.text, l.start, l.end, [asr.Word(w.start, w.end, w.text) for w in l.words], l.section, l.stanza)
      for l in aligned]
L.retext_line(ed[3], "Brand new   words here for this line")
L.set_times(ed[5], ed[5].start + 0.5, ed[5].end + 1.5)
L.split_line(ed, 7, 3)
L.merge_with_next(ed, 1)
L.shift_all(ed, 0.35)
g["edited"] = [ld(l) for l in ed]
g["gaps"] = L.gaps(aligned, DURATION)

# LRC / SRT
g["lrc"] = L.to_lrc(aligned, "My Song", "Some Artist")
g["lrc_enhanced"] = L.to_lrc(aligned, "My Song", "Some Artist", enhanced=True)
g["srt"] = asr.to_srt(L.to_cues(aligned))
g["parsed_lrc"] = [ld(l) for l in L.parse_lrc(g["lrc"], DURATION)]
g["parsed_lrc_enhanced"] = [ld(l) for l in L.parse_lrc(g["lrc_enhanced"], DURATION)]
g["lines_to_text"] = L.lines_to_text(pasted)

# syncer (auto-sync result, even spread, tap-to-sync, helpers)
from ideawood_artists import syncer  # noqa: E402
from ideawood_artists import lyrics as _L  # noqa: E402
import types  # noqa: E402

class _FakeEngine:  # syncer.auto_sync needs a transcript: patch asr.transcribe_file to return ours
    pass

_orig = asr.transcribe_file
asr.transcribe_file = lambda *a, **k: tr
sp = Project(audio="x.mp3", duration=DURATION, lyrics_text=LYRICS)
rep = syncer.auto_sync(sp, None)
g["sync_aligned"] = {"report": rep, "lines": [ld(l) for l in sp.lines],
                     "scenes": [[s.start, s.end, s.key] for s in sp.scenes]}
sp2 = Project(audio="x.mp3", duration=DURATION, lyrics_text="")
rep2 = syncer.auto_sync(sp2, None)
g["sync_transcribed"] = {"report": rep2, "lines": [ld(l) for l in sp2.lines]}
asr.transcribe_file = _orig
sp3 = Project(audio="x.mp3", duration=DURATION, lyrics_text=LYRICS)
syncer.spread_evenly(sp3)
g["sync_spread"] = [ld(l) for l in sp3.lines]
sp4 = Project(audio="x.mp3", duration=DURATION, lyrics_text=LYRICS)
sp4.lines = _L.parse_lyrics(LYRICS)
taps = [10.0 + i * 5.3 + (0.17 if i % 3 == 0 else 0) for i in range(len(sp4.lines) - 4)]
syncer.apply_tap_times(sp4, taps, last_end=DURATION - 3)
g["sync_taps"] = {"taps": taps, "lines": [ld(l) for l in sp4.lines], "scenes": [[s.start, s.end, s.key] for s in sp4.scenes]}
g["hint"] = syncer._hint(LYRICS)
g["match_rate"] = syncer._match_rate(_L.parse_lyrics(LYRICS), tr)
g["title_from_name"] = "My_Song-final version"

# scenes
scenes = plan_scenes(aligned, DURATION)
g["scenes"] = [{"start": s.start, "end": s.end, "key": s.key, "label": s.label, "colors": s.colors} for s in scenes]
g["scenes_no_lines"] = [{"start": s.start, "end": s.end, "key": s.key, "colors": s.colors} for s in plan_scenes([], 100.0)]

# ASS for every preset and shape
SHAPES = {"16x9": (1280, 720), "9x16": (720, 1280), "1x1": (720, 720), "4x5": (720, 900)}
g["ass"] = {}
for name, st in PRESETS.items():
    for sk, (w, h) in SHAPES.items():
        g["ass"][f"{name}|{sk}"] = build_ass(aligned, st, w, h, DURATION, "My Song", "Some Artist",
                                             "Lyric video made with Ideawood Studio for Artists")
g["ass"]["noTitle|16x9"] = build_ass(aligned, PRESETS["Neon Night"], 1280, 720, DURATION, "", "", "")

# full render commands
tmp = "/tmp/iwtest"
os.makedirs(tmp, exist_ok=True)
for f in ("img.jpg", "img2.jpg", "clip.mp4", "logo.png"):
    open(os.path.join(tmp, f), "wb").write(b"x")
g["cmds"] = {}
FMTS = list(__import__("ideawood_artists.project", fromlist=["FORMATS"]).FORMATS.keys())
for name, st in PRESETS.items():
    for fi, fmt in enumerate(FMTS):
        for q in ("480p (fast draft)", "1080p (Full HD)"):
            p = Project(audio="/tmp/iwtest/song.mp3", duration=DURATION, title="My Song", artist="Some Artist",
                        lines=[L.LyricLine.from_dict(l.to_dict()) for l in aligned], style=st.copy(),
                        fmt=fmt, quality=q)
            p.scenes = plan_scenes(p.lines, DURATION)
            # mix of media kinds so every _segment branch is hit
            for i, sc in enumerate(p.scenes):
                if i % 4 == 1:
                    sc.kind, sc.path = "image", f"{tmp}/img.jpg"
                elif i % 4 == 2:
                    sc.kind, sc.path = "video", f"{tmp}/clip.mp4"
                elif i % 4 == 3:
                    sc.kind, sc.path = "image", f"{tmp}/img2.jpg"
            if fi == 1:
                p.logo, p.logo_pos, p.logo_size = f"{tmp}/logo.png", "Bottom left", 0.15
                p.normalize_audio = True
            g.setdefault("projects", {})[f"{name}|{fi}|{q}"] = p.to_dict()
            cmd, dur = build_command(p, "/tmp/iwtest/out.mp4", "/tmp/iwtest", "ffmpeg")
            g["cmds"][f"{name}|{fi}|{q}"] = {"args": cmd[1:3] + cmd[6:], "dur": dur}
            if fi == 0 and q.startswith("480"):
                pc, pdur = build_command(p, "/tmp/iwtest/prev.mp4", "/tmp/iwtest", "ffmpeg", preview=True,
                                         t0=max(0.0, 9.0 - 4.0), span=22.0)
                g["cmds"][f"{name}|{fi}|{q}|preview"] = {"args": pc[1:3] + pc[6:], "dur": pdur}

# single-scene and no-transition variants
p = Project(audio="/tmp/iwtest/song.mp3", duration=30.0, lines=[L.LyricLine.from_dict(l.to_dict()) for l in aligned[:3]],
            style=PRESETS["Street Poster"].copy().__class__(**{**PRESETS["Street Poster"].to_dict(), "transition": "none"}),
            fmt=FMTS[0], quality="720p (HD)")
p.scenes = [Scene(0, 12.0), Scene(12.0, 30.0)]
cmd, dur = build_command(p, "/tmp/iwtest/o2.mp4", "/tmp/iwtest", "ffmpeg")
g["projects"]["notrans"] = p.to_dict()
g["cmds"]["notrans"] = {"args": cmd[1:3] + cmd[6:], "dur": dur}
p.scenes = [Scene(0, 30.0)]
cmd, dur = build_command(p, "/tmp/iwtest/o3.mp4", "/tmp/iwtest", "ffmpeg")
g["projects"]["single"] = p.to_dict()
g["cmds"]["single"] = {"args": cmd[1:3] + cmd[6:], "dur": dur}

g["frame_sizes"] = {f"{fi}|{s}": list(frame_size(fmt, s)) for fi, fmt in enumerate(FMTS) for s in (480, 540, 720, 1080, 1440, 2160)}

os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, "w", encoding="utf-8") as f:
    json.dump(g, f, ensure_ascii=False)
print("golden written:", out, f"{os.path.getsize(out) / 1024:.0f} KB;", len(g["ass"]), "ASS scripts,", len(g["cmds"]), "commands")
