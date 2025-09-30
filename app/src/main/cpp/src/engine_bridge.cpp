#include <android/log.h>
#include <mutex>
#include <vector>

#include "engine_bridge.h"

// Stockfish headers (путь указывает CMake: .../stockfish/src)
#include "uci.h"
#include "bitboard.h"
#include "position.h"
#include "search.h"
#include "misc.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "sflauncher", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  "sflauncher", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "sflauncher", __VA_ARGS__)

using namespace Stockfish;

// -------------------------------------------------------------
// Внутренние утилиты
// -------------------------------------------------------------
namespace {

    std::once_flag g_sfInitOnce;

} // namespace

// -------------------------------------------------------------
// Реализация объявлений из engine_bridge.h
// -------------------------------------------------------------
namespace EngineBridge {

    void init_once() {
        std::call_once(g_sfInitOnce, [](){
            // Ровно то, что делается в stockfish main():
            // порядок важен
            UCI::init(Options);
            Bitboards::init();
            Position::init();
            Search::init();

            LOGI("Stockfish core initialized");
        });
    }

    void uci_loop(std::istream& in, std::ostream& out, std::atomic<bool>& running) {
        (void)running; // сам цикл управляется командой "quit"
        try {
            LOGI("UCI loop start");
            UCI::loop(in, out);
            LOGI("UCI loop done");
        } catch (const std::exception& e) {
            LOGE("UCI loop exception: %s", e.what());
        } catch (...) {
            LOGE("UCI loop unknown exception");
        }
    }

} // namespace EngineBridge

// -------------------------------------------------------------
// EngineHandle
// -------------------------------------------------------------
void EngineHandle::start() {
    if (running.exchange(true)) return;

    // Глобальная одноразовая инициализация движка
    EngineBridge::init_once();

    // Настраиваем параметры перед стартом
    try {
        // У Stockfish Option поддерживает присваивание int
        Options["Threads"] = threads;
    } catch (...) {
        LOGW("Failed to set Threads option, using default");
    }

    loopThread = std::thread([this]() {
        EngineBridge::uci_loop(*inStream, *outStream, running);
    });

    LOGI("Engine started (threads=%d)", threads);
}

void EngineHandle::stop() {
    if (!running.exchange(false)) return;

    // Корректно завершаем UCI-циклом
    inQ.push("quit");

    if (loopThread.joinable())
        loopThread.join();

    outQ.drain();
    LOGI("Engine stopped");
}

// -------------------------------------------------------------
// JNI glue
// -------------------------------------------------------------
static inline EngineHandle* from(jlong h) {
    return reinterpret_cast<EngineHandle*>(h);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeInit(
        JNIEnv*, jobject,
        jstring /*jStockfishPath*/,
        jboolean /*jPreferBuiltin*/,
        jint jThreads) {
    auto* eh = new EngineHandle();
    eh->threads = (jThreads > 0 ? jThreads : 2);
    LOGI("nativeInit: handle=%p, threads=%d", (void*)eh, eh->threads);
    return reinterpret_cast<jlong>(eh);
}

JNIEXPORT void JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeStart(
        JNIEnv*, jobject, jlong handle) {
    auto* eh = from(handle);
    if (!eh) { LOGE("nativeStart: null handle"); return; }
    eh->start();
}

JNIEXPORT void JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeWriteLine(
        JNIEnv* env, jobject, jlong handle, jstring jCmd) {
    auto* eh = from(handle);
    if (!eh) { LOGE("nativeWriteLine: null handle"); return; }

    const char* c = env->GetStringUTFChars(jCmd, nullptr);
    std::string s = c ? std::string(c) : std::string();
    env->ReleaseStringUTFChars(jCmd, c);

    eh->inQ.push(std::move(s));
}

JNIEXPORT jstring JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeReadLine(
        JNIEnv* env, jobject, jlong handle, jint timeoutMs) {
    auto* eh = from(handle);
    if (!eh) return nullptr;

    std::string out;
    if (!eh->outQ.pop_for(out, timeoutMs))
        return nullptr;

    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeDrain(
        JNIEnv*, jobject, jlong handle, jint timeoutMs) {
    auto* eh = from(handle);
    if (!eh) return;

    std::string dummy;
    auto deadline = std::chrono::steady_clock::now()
                    + std::chrono::milliseconds(timeoutMs);
    while (std::chrono::steady_clock::now() < deadline) {
        if (!eh->outQ.pop_for(dummy, 10))
            break;
    }
}

JNIEXPORT void JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeDrainUntil(
        JNIEnv* env, jobject, jlong handle, jstring jToken, jint timeoutMs) {
    auto* eh = from(handle);
    if (!eh) return;

    const char* c = env->GetStringUTFChars(jToken, nullptr);
    std::string token = c ? std::string(c) : std::string();
    env->ReleaseStringUTFChars(jToken, c);

    std::string line;
    auto deadline = std::chrono::steady_clock::now()
                    + std::chrono::milliseconds(timeoutMs);

    while (std::chrono::steady_clock::now() < deadline) {
        if (!eh->outQ.pop_for(line, 50)) continue;
        if (line.find(token) != std::string::npos) break;
    }
}

} // extern "C"
