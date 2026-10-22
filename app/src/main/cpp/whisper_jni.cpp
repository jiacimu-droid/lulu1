#include <jni.h>
#include <whisper.h>
#include <algorithm>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

// A single context avoids reloading 75/142 MB of weights for every spoken sentence.
// Calls are serialized: overlapping chunks must not corrupt the Whisper state.
static std::mutex g_mutex;
static struct whisper_context * g_context = nullptr;
static std::string g_model_path;

static void throw_java(JNIEnv *env, const char *message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, message);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jiacimu_lulu_LocalWhisperTranscriber_nativeTranscribe(
    JNIEnv *env, jobject, jstring model_path, jbyteArray pcm) {
    if (!model_path || !pcm) {
        throw_java(env, "Whisper 缺少模型或音频");
        return nullptr;
    }

    const jsize byte_count = env->GetArrayLength(pcm);
    if (byte_count < 16'000) return env->NewStringUTF(""); // Less than 0.5s is not reliable speech
    const char *chars = env->GetStringUTFChars(model_path, nullptr);
    if (!chars) return nullptr;
    std::string path(chars);
    env->ReleaseStringUTFChars(model_path, chars);

    std::vector<jbyte> raw(static_cast<size_t>(byte_count));
    env->GetByteArrayRegion(pcm, 0, byte_count, raw.data());
    if (env->ExceptionCheck()) return nullptr;
    std::vector<float> audio(static_cast<size_t>(byte_count / 2));
    for (size_t i = 0; i < audio.size(); ++i) {
        const uint16_t lo = static_cast<uint8_t>(raw[2 * i]);
        const uint16_t hi = static_cast<uint8_t>(raw[2 * i + 1]);
        const int16_t sample = static_cast<int16_t>(lo | (hi << 8));
        audio[i] = static_cast<float>(sample) / 32768.0f;
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_context || g_model_path != path) {
        if (g_context) {
            whisper_free(g_context);
            g_context = nullptr;
            g_model_path.clear();
        }
        auto context_params = whisper_context_default_params();
        context_params.use_gpu = false;
        g_context = whisper_init_from_file_with_params(path.c_str(), context_params);
        if (!g_context) {
            throw_java(env, "本地 Whisper 模型无法加载，请重新下载");
            return nullptr;
        }
        g_model_path = path;
    }

    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "zh";
    params.translate = false;
    params.no_context = true;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.suppress_blank = true;
    const unsigned cores = std::thread::hardware_concurrency();
    params.n_threads = static_cast<int>(std::min(4u, std::max(2u, cores / 2u)));
    if (whisper_full(g_context, params, audio.data(), static_cast<int>(audio.size())) != 0) {
        throw_java(env, "本地 Whisper 推理失败，请检查模型或手机内存");
        return nullptr;
    }
    std::string text;
    const int count = whisper_full_n_segments(g_context);
    for (int i = 0; i < count; ++i) {
        const char *segment = whisper_full_get_segment_text(g_context, i);
        if (segment) text += segment;
    }
    return env->NewStringUTF(text.c_str());
}
