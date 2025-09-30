#include "engine_bridge.h"

#include <android/log.h>
#define LOG_TAG "sflauncher/native"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#include <cstring>
#include <memory>

// ==== Stockfish headers ====
#ifdef HAS_STOCKFISH
// В современных версиях всё в namespace Stockfish; uci.h нам достаточно.
#include "uci.h"
using namespace Stockfish;
using namespace Stockfish::UCI;
#endif

// Утилиты JNI строк
static std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// ============= Основной запуск движка =================
//
// Мы строим два потока = std::istream/std::ostream поверх очередей и
// просто передаём их в UCI::loop(...). Весь UCI-протокол остаётся «родным».
static void engineMain(EngineHandle* eh, int threadsOpt) {
#ifndef HAS_STOCKFISH
    // Теоретически можно было бы стартовать внешний бинарник, но
    // по условиям задачи используем только встроенный движок.
    ALOGE("Built without HAS_STOCKFISH");
    return;
#else
    try {
        eh->running.store(true, std::memory_order_release);

        // Минимальный аналог main.cpp: UCI контур.
        // Большинство инициализаций Stockfish внутри UCI::loop не требуется вручную.
        // Пара опций (Threads) настраивается командой setoption из Java.

        // Если хочется задать количество потоков непосредственно тут,
        // можно отправить в входную очередь команды, как будто это stdin:
        if (threadsOpt > 0) {
            eh->inQueue.push(std::string("setoption name Threads value ") + std::to_string(threadsOpt));
        }

        // Запускаем стандартный UCI loop: читает из eh->inStream, пишет в eh->outStream
        Stockfish::UCI::loop(eh->inStream, eh->outStream);

        eh->outStream.flush();
        eh->running.store(false, std::memory_order_release);
        ALOGI("UCI loop finished");
    } catch (const std::exception& ex) {
        ALOGE("engineMain exception: %s", ex.what());
        eh->running.store(false, std::memory_order_release);
    } catch (...) {
        ALOGE("engineMain unknown exception");
        eh->running.store(false, std::memory_order_release);
    }
#endif
}

// ============= JNI реализация =================

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeInit(JNIEnv* env, jclass,
                                                               jstring stockfishPath,
                                                               jboolean preferBuiltin,
                                                               jint threads)
{
    (void)env;
    (void)stockfishPath;   // в этой минимальной версии исп. встроенный движок
    (void)preferBuiltin;

#ifndef HAS_STOCKFISH
    ALOGE("HAS_STOCKFISH is not defined — нет встроенного движка");
    return 0;
#else
    try {
        auto* eh = new EngineHandle();
        // Поток запустим позднее через nativeStart(...)
        // Но сохраним желаемое число потоков, передадим в старт
        // Чтобы просто сохранить — положим команду в очередь здесь:
        if (threads > 0) {
            eh->inQueue.push(std::string("setoption name Threads value ") + std::to_string((int)threads));
        }
        return reinterpret_cast<jlong>(eh);
    } catch (...) {
        ALOGE("nativeInit: allocation failed");
        return 0;
    }
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeStart(JNIEnv* env, jclass, jlong handle)
{
    (void)env;
#ifndef HAS_STOCKFISH
    return;
#else
    auto* eh = reinterpret_cast<EngineHandle*>(handle);
    if (!eh) return;

    if (eh->running.load(std::memory_order_acquire)) {
        ALOGI("Engine already running");
        return;
    }

    // Положим стандартную инициализацию UCI снаружи (Java вызывает 'uci').
    // Тут только стартуем поток и ждём.
    eh->engineThread = std::thread([eh]{
        // threadsOpt == -1: потоки уже заданы в nativeInit через setoption
        engineMain(eh, /*threadsOpt*/ -1);
    });
    // detach не делаем — будем жить до quit
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeWriteLine(JNIEnv* env, jclass,
                                                                    jlong handle, jstring cmd)
{
    auto* eh = reinterpret_cast<EngineHandle*>(handle);
    if (!eh) return;

    std::string s = jstr(env, cmd);
    // Для UCI важно завершать команду переводом строки: это делает наш поток,
    // но мы кладём команду «как есть», а streambuf сам добавит '\n' при чтении.
    eh->inQueue.push(std::move(s));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeReadLine(JNIEnv* env, jclass,
                                                                   jlong handle, jint timeoutMs)
{
    auto* eh = reinterpret_cast<EngineHandle*>(handle);
    if (!eh) return nullptr;

    std::string line;
    if (eh->outQueue.pop_wait(line, timeoutMs < 0 ? 0 : timeoutMs)) {
        return env->NewStringUTF(line.c_str());
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeDrain(JNIEnv* env, jclass,
                                                                jlong handle, jint timeoutMs)
{
    (void)env;
    auto* eh = reinterpret_cast<EngineHandle*>(handle);
    if (!eh) return;

    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs < 0 ? 0 : timeoutMs);
    std::string tmp;
    while (std::chrono::steady_clock::now() < deadline) {
        if (!eh->outQueue.try_pop(tmp)) break;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeDrainUntil(JNIEnv* env, jclass,
                                                                     jlong handle,
                                                                     jstring token,
                                                                     jint timeoutMs)
{
    auto* eh = reinterpret_cast<EngineHandle*>(handle);
    if (!eh) return;

    const std::string tok = jstr(env, token);
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs < 0 ? 0 : timeoutMs);

    std::string line;
    while (std::chrono::steady_clock::now() < deadline) {
        if (!eh->outQueue.pop_wait(line, 100)) continue;
        if (!tok.empty() && line.find(tok) != std::string::npos) break;
    }
    // Остальное оставим в очереди — LocalStockfish сам дочитает через readLine
}
