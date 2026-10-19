# Ideawood Studio for Artists - Android

Native Android port of [ideawood-studio-artists](https://github.com/Goddy36-A/ideawood-studio-artists): AI lyric
video maker (karaoke lyrics, pro fonts, backgrounds). **Everything runs on the phone** - no browser, no server,
all tools free.

| Part | How |
|---|---|
| Lyric / timing / ASS / scenes / render command | Kotlin port in `core/` - **verified identical to the desktop Python engine** (`ParityTest`, 454 checks incl. every preset x video shape and the full ffmpeg filter graph) |
| Video rendering | FFmpeg (maintained FFmpegKit fork) in-process, same one-pass libass pipeline; **hardware H.264 encoder** (`h264_mediacodec`), compatible fallback |
| Speech timing | `whisper-cli` from whisper.cpp **b5130 (same as the Windows build)**, compiled by CI for arm64 and run with the same flags; same JSON parser |
| Model | Whisper `small.en` (466 MB, the one you use today) downloaded once from Hugging Face, or import your file; compact `small.en-q5_1` (181 MB) also offered |
| No freezing | Long jobs run in a foreground service (progress notification, survives screen-off / app switch); UI thread never renders |

## Build (GitHub Actions only, no PC needed)
Push to `main` -> workflow **Android build** -> download the artifact `IdeawoodStudio-Artists-apk`, install on the phone
(allow "install unknown apps"). Tag `v0.1.0` to publish the APK as a Release.

Jobs: `engine` (parity tests + real ffmpeg render of generated commands) -> `whisper` (NDK build) -> `apk`.

## Keeping parity with the desktop app
`python tools/gen_golden.py <path-to-desktop-repo> app/src/test/resources/golden.json` regenerates the golden data
from the Python code. If the desktop engine changes, regenerate and fix the Kotlin until `ParityTest` is green.

## Layout
```
app/src/main/java/com/ideawood/artists/
  core/    pure Kotlin port (no Android): Lyrics, AssLyrics, Scenes, RenderPlan, Syncer, Asr(json), Models
  engine/  Android glue: ModelManager, WhisperRunner, MediaTools (FFmpegKit), Pipeline, JobRunner + RenderService
  ui/      Jetpack Compose: Song / Timing / Look / Visuals / Export, tap-to-sync
```

## Status / next
Done: Song, auto-sync, tap-sync, spread evenly, LRC import/export, SRT export, timing table, look + live preview,
own photos/videos per scene, logo, export to Gallery + share.
Next: AI backgrounds (QuoteTube cloud client - needs the user's API key, port `cloud.py`), project open/save slots,
user fonts, first on-device speed tuning (test on the real phone).
