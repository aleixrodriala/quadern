# NoteAI

A simple, open-source voice-notes recorder for Android. Tap the button, talk, and get a transcript,
using **the same voice-to-text as the ChatGPT app, on your ChatGPT subscription**. No API key and
no extra bill.

It does one thing: record voice notes and turn them into text, with a title, a short summary and a
few tags so notes are easy to find later. No chats, folders or templates.

## Features

- **Record with one tap.** It keeps recording with the screen off, the app closed or the phone in
  your pocket, and you can pause and resume from the notification.
- **Nothing gets lost.**
  - Audio is written to disk continuously in a format that survives being cut off at any byte.
  - If Android kills the app, the phone reboots or the battery dies, everything up to the last few
    seconds is saved as a normal note.
- **Transcripts that finish.**
  - Long recordings are split at pauses into pieces of a few minutes.
  - Each piece is transcribed on its own, and retried with backoff when the network drops.
  - The queue survives restarts and waits for connectivity.
  - Long recordings are mostly transcribed by the time you press stop.
- **Choice of speech-to-text:**
  - **ChatGPT** (default): sign in with your ChatGPT account (Plus, Pro, Business…).
  - **On this device**: [whisper.cpp](https://github.com/ggml-org/whisper.cpp) models. Private and
    offline.
  - **API key providers**: OpenAI, Groq, Mistral (Voxtral), Deepgram, ElevenLabs, Google Gemini,
    AssemblyAI, or any OpenAI-compatible server.
  - **Backup provider**: an optional second provider used when the main one fails.
- **Titles, summaries and tags, written for you.**
  - When a note is transcribed, it gets a short title, a summary of one or two short paragraphs of
    plain prose (no bullet points), and one to three topic tags, in the language you spoke.
  - Also on your ChatGPT subscription, or on OpenAI, Gemini, Groq, Mistral or a custom server.
  - Rename a note whenever you like; your title is never overwritten. Clear it to get the
    automatic one back.
  - Search covers titles, summaries, tags and transcripts; tap a tag to see every note with it.
- **Share audio to NoteAI**, for example a voice message, to transcribe it.
- Search, rename, edit the transcript, share text or audio, and play back at 1× / 1.5× / 2×.
- A clean black-and-white design, with optional Material You wallpaper colors, and a dark theme.

## How the ChatGPT transcription works

The ChatGPT apps send dictation audio to `https://chatgpt.com/backend-api/transcribe`. NoteAI signs
you in with the same OAuth flow the open-source Codex CLI uses ("Sign in with ChatGPT"): a browser
page from OpenAI, with a redirect to `localhost` on your phone or a device code. It then sends your
audio to that endpoint with your token.

- **Not an official API.** OpenAI can change or restrict it at any time, which is why other
  providers are built in.
- **Silent truncation.** The endpoint silently drops audio past about 10.7 minutes of a single
  upload. NoteAI therefore never uploads more than 8 minutes at once; pieces are 4–6 minutes and
  cut at the quietest moment.
- **Your credentials stay on the phone.** Tokens are stored encrypted with a key held in the
  Android Keystore. The refresh token rotates on every use, so refreshes are serialized and saved
  before use.
- **Retention.** OpenAI keeps the uploaded audio for a limited time, as with ChatGPT dictation.

### Summaries on a ChatGPT subscription

Titles, summaries and tags use the Responses endpoint the Codex CLI uses with the same sign-in
(`https://chatgpt.com/backend-api/codex/responses`, streamed, not stored), with a small, fast model
(`gpt-6-luna` by default, changeable in Settings). Only the transcript text is sent, together with
the names of your most used tags so new tags stay consistent, and only to the service that writes
summaries: by default the one you transcribe with, and never an on-device-only setup unless you pick
a service yourself. Like transcription, this is not an official API.

## Reliability design

| Risk | What NoteAI does |
|---|---|
| App in background / screen off | `microphone` foreground service with an ongoing notification, plus a partial wake lock |
| Process killed or crash | Audio is AAC frames appended to an ADTS file and fsync'd every 3 s. On restart (`START_STICKY`) or the next launch it becomes a note marked "saved after interruption" |
| Reboot | Same recovery, triggered at boot |
| Another app takes the mic (a call) | Recording continues, and the UI and notification say the mic is in use |
| Phone storage nearly full | Recording stops cleanly and the note is kept |
| Network drops during transcription | One WorkManager queue with a connectivity constraint and exponential backoff, up to 12 attempts per piece |
| Session expired | Notes wait in "sign in again", a notification is shown, and they resume after sign-in |
| Provider rejects the audio | The backup provider is tried; otherwise "failed, tap to retry" |
| Very long recordings | Split at pauses (see above). Pieces are transcribed while you're still recording |
| Summary service down | Summaries queue separately from transcription, retry with backoff, and never block the transcript; a note without one still gets a title from its first words |

Aggressive phone makers can still stop background apps. Settings has a one-tap "unrestricted
battery" request and a link to [dontkillmyapp.com](https://dontkillmyapp.com).

## Building

Requirements: JDK 17+, and the Android SDK with platform 37, build-tools and NDK `29.0.14206865`
(for the on-device Whisper libraries).

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # JVM unit tests (chunking, ADTS parsing, summaries, formatting)
./gradlew :app:assembleRelease      # arm64 only; signed with keystore.properties if present
```

`whisper/` is a self-contained Android library wrapping whisper.cpp (vendored, pinned version; see
`whisper/README.md`).

## Project layout

```
app/src/main/java/io/github/aleixrodriala/noteai/
  audio/           recorder engine, AAC/ADTS writer, remuxing, chunk planner, audio import
  recording/       foreground service + UI controller
  transcription/   providers, WorkManager worker and scheduler, on-device Whisper models
  insights/        titles, summaries and tags: prompt, services, worker
  auth/            ChatGPT OAuth (PKCE + loopback server, device code), sign-in service
  data/            Room database, settings, encrypted secret store, repository
  ui/              Compose screens (home, recorder, note, settings, sign-in)
whisper/           whisper.cpp JNI library module
```

## Privacy

Recordings and transcripts stay on your phone. Audio is sent only to the transcription provider you
choose, or to no one with on-device Whisper. Transcript text is sent only to the service that writes
summaries (the transcription one by default), and summaries can be turned off. There are no
analytics and no NoteAI servers.

## License

MIT. whisper.cpp is MIT-licensed as well.
