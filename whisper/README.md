# :whisper — on-device speech-to-text for Quadern

Android library module wrapping [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (CPU only)
behind a small Kotlin API. Namespace `io.github.aleixrodriala.quadern.whisper`, minSdk 29,
compileSdk 37, Java/JVM 17, ABIs `arm64-v8a` + `x86_64`.

## Vendored whisper.cpp

| | |
|---|---|
| Tag | **v1.9.4** (released 2026-09-11) |
| Commit | `927cfce34f31707e17f2bff35c349632fb9e2c3a` |
| Source | `https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.4.tar.gz` |
| Tarball sha256 | `57e280cee375ab02425b806ad5146b99f6eb9357e3c2b31357c8a6af2e2e44ae` |

Lives in `src/main/cpp/whisper.cpp/` (4.6 MB). Kept: `LICENSE` (MIT), `include/`,
`src/whisper.cpp` + `src/whisper-arch.h`, and ggml's CMake files, headers, core sources and CPU
backend (arm + x86 code paths). Removed: examples, models, tests, bindings, every non-CPU backend
(CUDA, Vulkan, OpenCL, Metal, ...), KleidiAI and the RISC-V/PowerPC/s390/LoongArch/WASM CPU code.
Apart from that pruning nothing upstream is modified; the added `whisper.cpp/VERSION` records tag
+ commit and feeds `whisper_version()`.

To upgrade: `scripts/vendor-whisper-cpp.sh v1.x.y` (downloads, prunes, prints sha256 + commit),
then update the table above and rerun the tests.

## Using it

```kotlin
val ctx = WhisperContext.load(modelFile.path)   // blocking (tiny: ~0.15 s on the emulator); keep one per model
val text = ctx.transcribeSuspend(pcm16kMonoFloats, "en") { pct -> progress.value = pct }
ctx.close()                                     // frees the model (native memory, not GC'd)

/** Suspending wrapper: cancelling the calling coroutine aborts the native transcription. */
suspend fun WhisperContext.transcribeSuspend(
    samples: FloatArray, language: String?, onProgress: ((Int) -> Unit)? = null,
): String = coroutineScope {
    val work = async(Dispatchers.Default) { transcribe(samples, language, onProgress = onProgress) }
    try {
        work.await()
    } catch (e: CancellationException) {
        // Only if *we* were cancelled (a blocking call ignores coroutine cancellation on its own).
        if (!isActive) this@transcribeSuspend.cancel()
        throw e
    }
}
```

- Input: 16 kHz, mono, float PCM in [-1, 1] (from 16-bit PCM: `s / 32768f`).
- `language`: `"en"`, `"es"`, ... or `null` for auto-detect. English-only models (`*.en.bin`)
  always decode English and skip detection. Unknown codes throw `IllegalArgumentException`.
- `threads = 0` uses the big cores only (see below).
- `onProgress` gets strictly increasing values ending in 100.
- `cancel()` (any thread) aborts every `transcribe()` call that is running or queued at that
  moment; they throw `java.util.concurrent.CancellationException` (= kotlin's). Later calls are
  unaffected. Measured latency on the emulator: 3-180 ms. Not interruptible: the up-front log-mel
  spectrogram (~100 ms for 5 min of audio on the emulator) and, with `language = null`, the
  language-detection pass over the first window. `close()` cancels, then waits and frees.
- One transcription at a time per context; concurrent calls queue.
- `WhisperContext.systemInfo()` → on the emulator
  `WHISPER : VITISAI = 0 | COREML = 0 | OPENVINO = 0 | CPU : SSE3 = 1 | SSSE3 = 1 | AVX = 1 | F16C = 1 | REPACK = 1 | CPU_BACKEND = libggml-cpu-ivybridge.so | DEFAULT_THREADS = 4 | WHISPER_VERSION = 1.9.4`;
  on arm64 the CPU part lists `NEON`, `ARM_FMA`, `FP16_VA`, `DOTPROD`, `MATMUL_INT8`, `SVE`, `SME`... as available.
  whisper.cpp/ggml logs go to logcat tags `whisper.cpp` and `NoteAiWhisper`.

Long audio: `whisper_full` walks the whole input in 30 s windows by itself (verified with 5 min =
4.8 M samples). The limit is memory: the float array lives on the Java heap (4 bytes/sample:
5 min = 19 MB, 1 h = 230 MB), so for hour-long recordings feed chunks of ~10 min, ideally cut
at silences, and join the texts.

## Including it in the app

`settings.gradle.kts`: `include(":whisper")` (this directory's own `settings.gradle.kts`,
`gradle/`, `gradlew` and `gradle.properties` are then ignored).

Catalog aliases used by `build.gradle.kts` (the app catalog already has the first three):

| alias | value |
|---|---|
| `plugins.android-library` | `com.android.library` 9.3.2 (root must also declare `alias(libs.plugins.android.library) apply false` — it does) |
| `versions.compileSdk` | 37 |
| `versions.minSdk` | 29 |
| `libraries.androidx-test-runner` | `androidx.test:runner:1.7.0` (androidTest only) |
| `libraries.androidx-test-ext-junit` | `androidx.test.ext:junit:1.3.0` (androidTest only) |

Add to the app's `gradle/libs.versions.toml`:

```toml
[versions]
androidxTestRunner = "1.7.0"
androidxTestExtJunit = "1.3.0"

[libraries]
androidx-test-runner = { group = "androidx.test", name = "runner", version.ref = "androidxTestRunner" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestExtJunit" }
```

App module: `implementation(project(":whisper"))`, and set `ndkVersion = "29.0.14206865"` in
`android {}` — without an NDK the app can't strip native libs ("Unable to strip the following
libraries") and ships ~11.6 MB instead of ~9.4 MB of arm64 libs. No `useLegacyPackaging` or
`extractNativeLibs` needed. R8: `consumer-rules.pro` is applied automatically (verified with a
minified, non-debuggable release app).

Standalone (this directory only): `./gradlew :assembleRelease` → `build/outputs/aar/whisper-release.aar`.
Standalone builds compile Kotlin with AGP 9.3.2's bundled KGP 2.2.10; inside the app it's the
app's 2.4.10 (the source is plain Kotlin, both work).

## CPU features (arm64 and x86_64)

ggml's `GGML_CPU_ALL_VARIANTS` + `GGML_BACKEND_DL` build the CPU backend once per feature level,
each as its own `.so` compiled with its own `-march`:

| arm64 variant | `-march` | picked on |
|---|---|---|
| `android_armv8.0_1` | armv8-a | anything arm64 |
| `android_armv8.2_1` | armv8.2-a+dotprod | e.g. Cortex-A55/A75 without fp16 |
| `android_armv8.2_2` | armv8.2-a+dotprod+fp16 | most 2018-2021 phones |
| `android_armv8.6_1` | armv8.6-a+dotprod+fp16+i8mm | i8mm but no (exposed) SVE2 |
| `android_armv9.0_1` | armv8.6-a+dotprod+fp16+i8mm+sve2 | **Pixel 9 / Tensor G4** (X4/A720/A520: SVE2 + i8mm, no SME), if the kernel exposes SVE2 |
| `android_armv9.2_1/_2` | armv9.2-a+...+sve(+sve2)+sme | cores with SME |

(x86_64 gets the 14 upstream variants from `x64`/`sse42` to `sapphirerapids`; the emulator here
exposes only AVX+F16C and picks `ivybridge`.)

Each variant exports `ggml_backend_score()`, compiled *without* the variant's flags, that checks
`getauxval(AT_HWCAP/AT_HWCAP2)` (cpuid on x86) and returns 0 if the CPU lacks any required
feature. At first use, `whisper_jni.cpp` `dlopen()`s every variant by soname, keeps the highest
score and registers only that one with `ggml_backend_load()`. So armv9 code never executes on an
armv8.0 phone. I checked the arm64 variants' static constructors and score functions contain no
SVE/SME/dotprod/i8mm/fp16 instructions (only compiler-rt's `init_have_lse_atomics` /
`__init_cpu_features`), so the probing `dlopen` itself is safe on old cores.

Why this and not whisper.android's approach: that example (as of v1.9.4) builds two copies of
the JNI lib and picks one from `/proc/cpuinfo`, but its `-march=armv8.2-a+fp16` only reaches
`whisper.cpp` and the `ggml` registry target, never the `ggml-cpu` kernels (and `ggml` is
fetched once, so both copies share it); it only knows fp16, and needs a copy per combination. ggml's own variants cover dotprod/fp16/i8mm/SVE2/SME
with upstream-maintained flags and runtime checks. ggml's `ggml_backend_load_all()` can't find
them on Android (it scans `/system/bin` and the cwd, and with default packaging the libs aren't
extracted to disk), hence the ~40-line soname loader; it works whether or not libs are extracted.

Cost: ~1.05 MB per arm64 variant; the AAR carries 9.4 MB of arm64 `.so` (22.1 MB x86_64); Play
delivers only the device's ABI from an AAB. Not tested on real arm64 hardware (not allowed here):
if a benchmark on the Pixel 9 shows the SVE2 variant slower than the NEON+i8mm one, force it with
`Os.setenv("QUADERN_WHISPER_CPU_BACKEND", "libggml-cpu-android_armv8.6_1.so", true)` before the
first `WhisperContext` call, or run the androidTest with `-e cpuBackend <lib>` to compare.

Other native build choices (`src/main/cpp/CMakeLists.txt`): always `-O3 -DNDEBUG`, also for AGP
debug variants (`CMAKE_BUILD_TYPE` forced to Release); no DWARF (`-g0`, `--strip-debug`) so
unstripped libs stay small; `GGML_OPENMP=OFF` (ggml's own thread pool, no libomp);
`GGML_LLAMAFILE=OFF` and KleidiAI off (upstream whisper.cpp defaults); `c++_static` with hidden
symbols (the libs only talk through C APIs; nothing to clash with other native deps); NDK r29
→ 16 KB-aligned ELF segments (Android 15+ 16 KB-page devices). `flash_attn` stays on (default).

## Decoding: beam search, 5 beams

`whisper_full` with `WHISPER_SAMPLING_BEAM_SEARCH`, `beam_size = 5`, `best_of = 5` for the
temperature fallback (same as whisper-cli and OpenAI's reference), `no_context = true`,
`suppress_blank = true`, `suppress_nst = true`, timestamps decoded (whisper uses them to place
the next 30 s window so words aren't cut at window edges) but never printed, `print_progress =
false`. Change `WhisperContext.DEFAULT_BEAM_SIZE` to 1 for greedy.

Measured on the x86_64 emulator (4 threads) on the 5-minute LibriVox clip, WER against the
Project Gutenberg text of the same chapter (crude normalisation, so absolute numbers are
pessimistic; compare rows):

| model | greedy | beam 5 |
|---|---|---|
| tiny.en | 7.6 % WER, 5.4-5.5 s | 5.1 % WER, 9.5-10.7 s |
| base | 5.2 % WER, 12.7-17.8 s | 4.8 % WER, 18.0-23.7 s |

(Timings are 2-3 runs each; the emulator is noisy, so compare runs made back to back: beam 5 was
1.3-1.4x greedy for base and 1.7-1.9x for tiny.)

The errors greedy adds are the kind that hurt notes: tiny.en greedy dropped a whole phrase at a
window boundary ("But the St. James was distinctive. It guaranteed a man, so to speak, that is,
it ..."), base greedy looped ("hour hour hour"). Beam search costs that extra time and ~0 extra
memory (whisper.cpp allocates 5 decoders for greedy's fallback anyway).
On a phone the decoder is relatively more expensive than on this host, so re-measure there with
`-e benchmark true` (below); for a real-time/streaming use greedy would be the better trade.

## Threads

`threads = 0` → cores whose max frequency is ≥ 80 % of the fastest core's (from
`/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq`), clamped to 1..8. ggml's workers meet at
a barrier after every op, so including a slow efficiency core stalls the others. Pixel 9 (1x X4
3.1 GHz, 3x A720 2.6 GHz, 4x A520 1.92 GHz) → 4; Snapdragon 8 Elite (all ≥ 3.5 GHz) → 8;
no cpufreq → `hardware_concurrency()`.

## Tests

`src/androidTest/.../WhisperContextTest.kt` runs against a real model and real speech. On this
machine the emulator belongs to the Windows adb server, so instead of `connectedAndroidTest`
(which drives the SDK's Linux adb) build the test APK and run it through `adb.exe` (which needs
Windows paths: put files under `/mnt/c/...` and pass `$(wslpath -w ...)`):

```sh
ffmpeg -i clip.m4a -ar 16000 -ac 1 -f f32le clip_30.f32          # raw 16 kHz mono float32
adb -s emulator-5560 shell mkdir -p /data/local/tmp/whisper-test
adb -s emulator-5560 push "$(wslpath -w /mnt/c/.../ggml-tiny.en.bin)" /data/local/tmp/whisper-test/
#   same for clip_30.f32, clip_300.f32 (5 min) and, optionally, ggml-base.bin
./gradlew :assembleDebugAndroidTest
cp build/outputs/apk/androidTest/debug/whisper-debug-androidTest.apk /mnt/c/Users/$USER/
adb -s emulator-5560 install -r -t "$(wslpath -w /mnt/c/Users/$USER/whisper-debug-androidTest.apk)"
adb -s emulator-5560 shell am instrument -w io.github.aleixrodriala.quadern.whisper.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5560 logcat -d -s WhisperTest:I       # transcripts + timings ("RESULT ...")
```

Extra args: `-e model ggml-base.en.bin`, `-e benchmark true [-e clip clip_300.f32]` (greedy vs
beam timings), `-e cpuBackend libggml-cpu-android_armv8.6_1.so`.

Results on `emulator-5560` (Android 15, x86_64, 4 vCPU, AVX+F16C → `libggml-cpu-ivybridge.so`),
debug build (native code is -O3 regardless):

- 30 s clip, tiny.en, beam 5: **1.26-1.36 s** — "Part 1, Chapter 1-A of the Adventures of Jimmy Dale.
  This is a liberal box recording. All liberal box recordings are in the public domain. For more
  information or to volunteer, please go to liberalbox.org. The Adventures of Jimmy Dale by Frank
  L. Packard, Reading by Mary Rody. Part 1, The Man in the Case, Chapter 1-A, The Grey Seal."
  (greedy: 0.68 s, "Libra-Vox"; base: "LibriVox", 2.3-3.3 s with auto-detect)
- 5 min clip (4,800,171 samples), tiny.en: 9.3-10.5 s (~100 ms of it the mel spectrogram), 742
  words, whole clip covered.
- cancel mid-way through 5 min: `CancellationException` within 3-181 ms; the context works after.
  `close()` from inside the final `onProgress(100)` also cancels (and frees afterwards).
- Also covered: two concurrent calls serialise and agree, callback exceptions propagate, empty
  input, bad args, close/cancel idempotence, auto-detect on a multilingual model.
