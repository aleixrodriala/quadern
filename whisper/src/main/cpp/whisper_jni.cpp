// JNI glue between io.github.aleixrodriala.quadern.whisper.WhisperJni and whisper.cpp.
//
// Threading model (enforced on the Kotlin side): at most one whisper_full() per context at a time;
// requestCancel()/cancelGeneration() may be called from any thread at any time while the handle
// is alive (WhisperContext guarantees the handle is not freed concurrently).

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include "ggml-backend.h"
#include "ggml.h"
#include "whisper.h"

#define LOG_TAG "NoteAiWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

JavaVM * g_vm = nullptr;

struct Handle {
    whisper_context * ctx = nullptr;
    // Bumped by requestCancel(). A transcription aborts as soon as this differs from the value it
    // snapshotted when it was requested, so cancel() hits every call already running or waiting,
    // never a later one.
    std::atomic<int64_t> cancel_gen{0};
};

// ---------------------------------------------------------------------------------------------
// Logging: whisper.cpp / ggml print to stderr by default, which Android discards.

void log_to_logcat(ggml_log_level level, const char * text, void * /*user_data*/) {
    int prio;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        default: return;  // DEBUG and CONT (progress dots) are just noise in logcat
    }
    __android_log_write(prio, "whisper.cpp", text);
}

void ggml_abort_to_logcat(const char * message) {
    // ggml calls abort() right after this; make the reason visible in the crash log.
    __android_log_write(ANDROID_LOG_FATAL, "whisper.cpp", message);
}

// ---------------------------------------------------------------------------------------------
// CPU backend selection (see CMakeLists.txt). ggml_backend_load_all() cannot be used on Android:
// it enumerates the executable's directory (/system/bin for app_process) and the cwd, and with
// the default (non-legacy) packaging the .so files are not even extracted to disk — they are
// mapped straight out of the APK. dlopen() by bare soname works in both cases because the app's
// linker namespace searches the APK's lib/<abi>/ directory.

std::once_flag g_backend_once;
std::string g_cpu_backend;  // file name of the variant in use, empty if none could be loaded

void load_cpu_backend_once() {
    std::call_once(g_backend_once, [] {
        whisper_log_set(log_to_logcat, nullptr);  // also installs it as the ggml logger
        ggml_set_abort_callback(ggml_abort_to_logcat);

        // Returns the variant's self-assessed score for this CPU; 0 = unusable or missing.
        auto score_of = [](const std::string & lib) -> int {
            void * h = dlopen(lib.c_str(), RTLD_NOW | RTLD_LOCAL);
            if (h == nullptr) {
                LOGW("cannot dlopen %s: %s", lib.c_str(), dlerror());
                return 0;
            }
            using score_fn_t = int (*)();
            auto score_fn = reinterpret_cast<score_fn_t>(dlsym(h, "ggml_backend_score"));
            // A single, non-variant CPU backend has no score function and is always usable.
            const int score = score_fn != nullptr ? score_fn() : 1;
            dlclose(h);
            return score;
        };

        std::string best;
        int best_score = 0;
        std::stringstream candidates(QUADERN_GGML_CPU_BACKENDS);
        std::string lib;
        while (std::getline(candidates, lib, ',')) {
            if (lib.empty()) continue;
            const int score = score_of(lib);
            if (score > best_score) {
                best_score = score;
                best = lib;
            }
        }

        // Benchmarking aid: Os.setenv("QUADERN_WHISPER_CPU_BACKEND", "libggml-cpu-android_armv8.6_1.so",
        // true) before the first WhisperContext call forces a variant, if this CPU supports it.
        const char * forced = getenv("QUADERN_WHISPER_CPU_BACKEND");
        if (forced != nullptr && forced[0] != '\0') {
            const int score = score_of(forced);
            if (score > 0) {
                best = forced;
                best_score = score;
            } else {
                LOGW("QUADERN_WHISPER_CPU_BACKEND=%s is not usable on this CPU; ignoring it", forced);
            }
        }
        if (best.empty()) {
            LOGE("no ggml CPU backend is usable on this device (candidates: %s)", QUADERN_GGML_CPU_BACKENDS);
            return;
        }
        if (ggml_backend_load(best.c_str()) == nullptr) {
            LOGE("ggml_backend_load(%s) failed", best.c_str());
            return;
        }
        g_cpu_backend = best;
        LOGI("using ggml CPU backend %s (score %d)", best.c_str(), best_score);
    });
}

// ---------------------------------------------------------------------------------------------
// Default thread count: the "big" cores only. ggml's worker threads meet at a barrier after every
// op, so one slow efficiency core holds back all the others. Cores whose max frequency is well
// below the fastest core's (< 80%) form the little cluster and are skipped; e.g. Pixel 9
// (1x X4 3.1 GHz + 3x A720 2.6 GHz + 4x A520 1.92 GHz) -> 4 threads.

long read_max_freq_khz(int cpu) {
    char path[96];
    snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
    FILE * f = fopen(path, "r");
    if (f == nullptr) return -1;
    long khz = -1;
    if (fscanf(f, "%ld", &khz) != 1) khz = -1;
    fclose(f);
    return khz;
}

int default_thread_count() {
    const long n_cpus = sysconf(_SC_NPROCESSORS_CONF);
    std::vector<long> freqs;
    for (int i = 0; i < n_cpus; ++i) {
        const long f = read_max_freq_khz(i);
        if (f > 0) freqs.push_back(f);
    }
    int n;
    if (freqs.empty()) {
        n = (int) std::thread::hardware_concurrency();
    } else {
        const long top = *std::max_element(freqs.begin(), freqs.end());
        const long low = *std::min_element(freqs.begin(), freqs.end());
        if (low * 10 < top * 8) {
            n = (int) std::count_if(freqs.begin(), freqs.end(), [low](long f) { return f > low; });
        } else {
            n = (int) freqs.size();
        }
    }
    return std::clamp(n, 1, 8);
}

// ---------------------------------------------------------------------------------------------
// Per-transcription state shared with whisper.cpp callbacks.

struct Job {
    Handle * handle = nullptr;
    int64_t start_gen = 0;

    jobject sink = nullptr;       // global ref to ProgressSink, may be null
    jmethodID on_progress = nullptr;
    int last_progress = -1;

    jthrowable callback_error = nullptr;  // global ref; exception thrown by the progress callback
    std::atomic<bool> failed{false};

    bool cancelled() const {
        return handle->cancel_gen.load(std::memory_order_relaxed) != start_gen;
    }
    bool should_stop() const {
        return cancelled() || failed.load(std::memory_order_relaxed);
    }
};

// Returns a JNIEnv for the current thread, attaching it if whisper.cpp called us from a native one.
JNIEnv * env_for_current_thread(bool * attached) {
    *attached = false;
    JNIEnv * env = nullptr;
    const jint rc = g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (rc == JNI_OK) return env;
    if (rc == JNI_EDETACHED && g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        *attached = true;
        return env;
    }
    return nullptr;
}

void report_progress(Job * job, int progress) {
    if (job->sink == nullptr || job->failed.load()) return;
    progress = std::clamp(progress, 0, 100);
    if (progress <= job->last_progress) return;
    job->last_progress = progress;

    bool attached = false;
    JNIEnv * env = env_for_current_thread(&attached);
    if (env == nullptr) return;
    env->CallVoidMethod(job->sink, job->on_progress, (jint) progress);
    if (env->ExceptionCheck()) {
        jthrowable ex = env->ExceptionOccurred();
        env->ExceptionClear();
        job->callback_error = static_cast<jthrowable>(env->NewGlobalRef(ex));
        env->DeleteLocalRef(ex);
        job->failed.store(true);  // abort the transcription; rethrown by transcribe()
    }
    if (attached) g_vm->DetachCurrentThread();
}

void on_whisper_progress(whisper_context *, whisper_state *, int progress, void * user_data) {
    report_progress(static_cast<Job *>(user_data), progress);
}

// Polled by ggml between graph nodes (from worker threads): must be cheap and thread-safe.
bool on_ggml_abort(void * user_data) {
    return static_cast<const Job *>(user_data)->should_stop();
}

// Called before every 30 s window is encoded (and before language auto-detection).
bool on_encoder_begin(whisper_context *, whisper_state *, void * user_data) {
    return !static_cast<const Job *>(user_data)->should_stop();
}

void throw_java(JNIEnv * env, const char * cls, const std::string & msg) {
    jclass c = env->FindClass(cls);
    if (c != nullptr) env->ThrowNew(c, msg.c_str());
}

// False (with an OutOfMemoryError pending) if the string could not be read.
bool jstring_to_std(JNIEnv * env, jstring s, std::string * out) {
    out->clear();
    if (s == nullptr) return true;
    const char * chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return false;
    *out = chars;
    env->ReleaseStringUTFChars(s, chars);
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM * vm, void * /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_io_github_aleixrodriala_quadern_whisper_WhisperJni_loadModel(JNIEnv * env, jclass, jstring j_path) {
    load_cpu_backend_once();
    if (g_cpu_backend.empty()) {
        throw_java(env, "java/lang/IllegalStateException",
                   "No usable ggml CPU backend on this device (see logcat tag NoteAiWhisper)");
        return 0;
    }
    std::string path;
    if (!jstring_to_std(env, j_path, &path)) return 0;

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;  // CPU-only build
    // cparams.flash_attn keeps whisper.cpp's default (true).

    whisper_context * ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (ctx == nullptr) {
        throw_java(env, "java/lang/IllegalStateException",
                   "whisper.cpp could not load model '" + path + "' (see logcat tag whisper.cpp)");
        return 0;
    }
    auto * handle = new Handle();
    handle->ctx = ctx;
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL
Java_io_github_aleixrodriala_quadern_whisper_WhisperJni_freeModel(JNIEnv *, jclass, jlong ptr) {
    auto * handle = reinterpret_cast<Handle *>(ptr);
    if (handle == nullptr) return;
    whisper_free(handle->ctx);
    delete handle;
}

JNIEXPORT void JNICALL
Java_io_github_aleixrodriala_quadern_whisper_WhisperJni_requestCancel(JNIEnv *, jclass, jlong ptr) {
    auto * handle = reinterpret_cast<Handle *>(ptr);
    if (handle != nullptr) handle->cancel_gen.fetch_add(1);
}

JNIEXPORT jlong JNICALL
Java_io_github_aleixrodriala_quadern_whisper_WhisperJni_cancelGeneration(JNIEnv *, jclass, jlong ptr) {
    auto * handle = reinterpret_cast<Handle *>(ptr);
    return handle != nullptr ? (jlong) handle->cancel_gen.load() : 0;
}

JNIEXPORT jstring JNICALL
Java_io_github_aleixrodriala_quadern_whisper_WhisperJni_systemInfo(JNIEnv * env, jclass) {
    load_cpu_backend_once();
    std::string info = whisper_print_system_info();
    info += "CPU_BACKEND = ";
    info += g_cpu_backend.empty() ? "none" : g_cpu_backend;
    info += " | DEFAULT_THREADS = " + std::to_string(default_thread_count());
    info += " | WHISPER_VERSION = ";
    info += whisper_version();
    return env->NewStringUTF(info.c_str());
}

// Returns the UTF-8 bytes of the transcript (segments concatenated, not trimmed). Whisper can emit
// 4-byte UTF-8 (emoji, some CJK) that JNI's NewStringUTF (modified UTF-8) would reject, so the
// Kotlin side decodes the bytes instead.
JNIEXPORT jbyteArray JNICALL
Java_io_github_aleixrodriala_quadern_whisper_WhisperJni_transcribe(
        JNIEnv * env, jclass, jlong ptr, jlong start_gen, jfloatArray j_samples, jstring j_language,
        jint threads, jint beam_size, jobject sink) {
    auto * handle = reinterpret_cast<Handle *>(ptr);

    Job job;
    job.handle = handle;
    job.start_gen = start_gen;

    if (job.cancelled()) {
        throw_java(env, "java/util/concurrent/CancellationException", "Transcription cancelled");
        return nullptr;
    }

    // Language: null/"" /"auto" = auto-detect. English-only (.en) models ignore the language token
    // anyway, so skip the (wasted) detection pass for them.
    std::string language;
    if (!jstring_to_std(env, j_language, &language)) return nullptr;
    if (language.empty() || language == "auto") language = "auto";
    if (!whisper_is_multilingual(handle->ctx)) {
        language = "en";
    } else if (language != "auto" && whisper_lang_id(language.c_str()) < 0) {
        throw_java(env, "java/lang/IllegalArgumentException", "Unsupported language code: " + language);
        return nullptr;
    }

    const jsize n_samples = env->GetArrayLength(j_samples);

    // Global refs owned by this call, released on every exit path.
    struct Refs {
        JNIEnv * env;
        Job * job;
        ~Refs() {
            if (job->sink != nullptr) env->DeleteGlobalRef(job->sink);
            if (job->callback_error != nullptr) env->DeleteGlobalRef(job->callback_error);
        }
    } refs{env, &job};

    if (sink != nullptr) {
        jclass sink_class = env->GetObjectClass(sink);
        job.on_progress = env->GetMethodID(sink_class, "onProgress", "(I)V");
        env->DeleteLocalRef(sink_class);
        if (job.on_progress == nullptr) return nullptr;  // NoSuchMethodError pending (R8 rules missing?)
        job.sink = env->NewGlobalRef(sink);
        if (job.sink == nullptr) return nullptr;  // OutOfMemoryError pending
    }

    std::string text;
    if (n_samples > 0) {
        whisper_full_params params = whisper_full_default_params(
                beam_size > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
        if (beam_size > 1) params.beam_search.beam_size = beam_size;
        // Candidates sampled when the temperature fallback kicks in (low log-prob / high entropy
        // window). The beam-search defaults leave it at -1 (= 1 candidate); whisper-cli and
        // OpenAI's reference implementation use 5.
        params.greedy.best_of = 5;
        params.n_threads        = threads > 0 ? threads : default_thread_count();
        params.language         = language.c_str();
        params.detect_language  = false;  // true would *only* detect the language and return
        params.translate        = false;
        params.no_context       = true;   // don't feed previous windows' text back as a prompt
        // Timestamp tokens stay enabled for decoding (whisper.cpp uses them to decide where the
        // next 30 s window starts, so words are not cut at window edges); we just never print them.
        params.no_timestamps    = false;
        params.print_special    = false;
        params.print_progress   = false;
        params.print_realtime   = false;
        params.print_timestamps = false;
        params.suppress_blank   = true;
        params.suppress_nst     = true;   // drop non-speech tokens like "(music)", "[BLANK_AUDIO]"
        params.progress_callback = on_whisper_progress;
        params.progress_callback_user_data = &job;
        params.abort_callback = on_ggml_abort;
        params.abort_callback_user_data = &job;
        params.encoder_begin_callback = on_encoder_begin;
        params.encoder_begin_callback_user_data = &job;

        // Large arrays live in ART's non-moving large-object space, so this normally pins instead
        // of copying. (Not GetPrimitiveArrayCritical: the progress callback calls back into Java.)
        jfloat * samples = env->GetFloatArrayElements(j_samples, nullptr);
        if (samples == nullptr) return nullptr;  // OutOfMemoryError pending
        const int ret = whisper_full(handle->ctx, params, samples, n_samples);
        env->ReleaseFloatArrayElements(j_samples, samples, JNI_ABORT);

        if (job.callback_error != nullptr) {
            env->Throw(job.callback_error);
            return nullptr;
        }
        if (job.cancelled()) {
            throw_java(env, "java/util/concurrent/CancellationException", "Transcription cancelled");
            return nullptr;
        }
        if (ret != 0) {
            throw_java(env, "java/lang/IllegalStateException",
                       "whisper_full failed with code " + std::to_string(ret) + " (see logcat tag whisper.cpp)");
            return nullptr;
        }

        const int n_segments = whisper_full_n_segments(handle->ctx);
        for (int i = 0; i < n_segments; ++i) {
            const char * seg = whisper_full_get_segment_text(handle->ctx, i);
            if (seg != nullptr) text += seg;
        }
    }

    report_progress(&job, 100);
    if (job.callback_error != nullptr) {
        env->Throw(job.callback_error);
        return nullptr;
    }
    if (job.cancelled()) {  // e.g. the final onProgress(100) itself called cancel() or close()
        throw_java(env, "java/util/concurrent/CancellationException", "Transcription cancelled");
        return nullptr;
    }

    jbyteArray out = env->NewByteArray((jsize) text.size());
    if (out == nullptr) return nullptr;  // OutOfMemoryError pending
    env->SetByteArrayRegion(out, 0, (jsize) text.size(), reinterpret_cast<const jbyte *>(text.data()));
    return out;
}

}  // extern "C"
