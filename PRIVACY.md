# Privacy Policy for Quadern

**Last updated:** October 7, 2026

This policy applies to **Quadern**, an open-source voice-notes app for Android
(package ID `io.github.aleixrodriala.quadern`). Quadern is an independent
project. It is not made by, affiliated with or endorsed by OpenAI or any other
service it can use.

## 1. Summary

Quadern has **no server of its own** and its developer collects **no personal
data**. Your recordings, transcripts, titles, summaries and tags are stored only
on your phone. Audio leaves the phone only to reach the transcription service
**you** chose, and you can choose one that runs on the phone itself, so that
nothing leaves it. There are no analytics, no trackers, no ads and no crash
reporting.

## 2. Data the developer collects

**None.** There is no developer-run server, no Quadern account and no telemetry
(no Firebase, Crashlytics, Sentry or ad SDKs). The developer never receives your
recordings, notes, account details or usage.

## 3. What stays on your phone

- Recordings (`.m4a` audio), transcripts, titles, summaries and tags.
- Your settings.
- Sign-in tokens and API keys, encrypted with a key held in the Android Keystore.

Quadern turns off Android's cloud backup and device-to-device transfer for all
of this, so none of it is copied to Google Drive or another phone by the app.
Uninstalling Quadern deletes it. A note you share from the app goes wherever you
send it.

## 4. Who hears your audio

You choose how notes are transcribed, the first time you open the app and at any
time in Settings:

- **On this phone.** Whisper runs on the phone. Audio and text never leave it.
  The only network use is downloading the model you pick, once, from
  `huggingface.co`.
- **ChatGPT.** Audio is sent to OpenAI (`chatgpt.com`), signed in with your own
  ChatGPT account, the same way the ChatGPT app sends dictation. This is not an
  official OpenAI feature for other apps; the app explains this before you sign
  in. You sign in on OpenAI's own page (`auth.openai.com`); Quadern never sees
  your password. OpenAI's own terms and privacy policy apply, including how long
  it keeps the audio.
- **Your own API key.** Audio is sent to the service you pick: OpenAI
  (`api.openai.com`), Groq (`api.groq.com`), Mistral (`api.mistral.ai`), Deepgram
  (`api.deepgram.com`), ElevenLabs (`api.elevenlabs.io`), Google Gemini
  (`generativelanguage.googleapis.com`), AssemblyAI (`api.assemblyai.com`), or a
  server whose address you enter. That service's terms and privacy policy apply.

An optional **backup service** receives audio only when the main one fails.

## 5. Titles, summaries and tags

When summaries are on (they are by default), the finished **transcript text**
and the names of your most used tags are sent to the service that writes them:
by default the same service that transcribes, if it can; otherwise one you
choose. No audio is sent for summaries. You can turn summaries off in Settings.
When you transcribe on the phone, summaries need a service of your choice, or
stay off.

## 6. Service status check

While ChatGPT is the chosen service, Quadern downloads one small file,
`https://aleixrodriala.github.io/quadern/status.json`, at most about twice a day.
It lets the project pause the ChatGPT route for everyone (for example if OpenAI
asks), and the app then tells you. The request sends nothing about you or your
notes; like any web request, it shows your IP address to GitHub, which hosts
the file.

## 7. Permissions

- **Microphone**: to record, only after you tap record.
- **Change audio settings**: to record with a Bluetooth headset you pick, which
  needs the same link to the headset as a phone call.
- **Notifications**: the ongoing "Recording" notification with pause and stop,
  and messages such as "sign in again".
- **Foreground service** (microphone, data sync): to keep recording with the
  screen off and to finish transcription in the background.
- **Network**: to reach the services described above.
- **Keep the phone awake** while recording, so no audio is dropped.
- **Run at startup**: to save a recording that a restart interrupted.
- **Ask to ignore battery optimisation**: only if you tap the setting that
  asks for it, so long recordings are never paused by battery saving.

## 8. Children

Quadern is not directed at children and collects no data from anyone.

## 9. Changes

Changes to this policy are published in this file in the project's repository,
with a new date at the top.

## 10. Contact

Questions: open an issue at https://github.com/aleixrodriala/quadern/issues.
