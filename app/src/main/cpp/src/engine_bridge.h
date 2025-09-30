#pragma once

// --- STL ---
#include <string>
#include <queue>
#include <mutex>
#include <condition_variable>
#include <thread>
#include <atomic>
#include <chrono>
#include <vector>
#include <streambuf>
#include <istream>
#include <ostream>

// --- JNI ---
#include <jni.h>

// -------------------------------------------------------------
// Очередь строк с ожиданием и дренажем
// -------------------------------------------------------------
struct LineQueue {
    std::mutex m;
    std::condition_variable cv;
    std::queue<std::string> q;

    void push(std::string s) {
        { std::lock_guard<std::mutex> lk(m); q.push(std::move(s)); }
        cv.notify_all();
    }

    // Ждём строку до timeout_ms, если пришла — кладём в out и возвращаем true.
    bool pop_for(std::string& out, int timeout_ms) {
        std::unique_lock<std::mutex> lk(m);
        if (!cv.wait_for(lk, std::chrono::milliseconds(timeout_ms),
                         [&]{ return !q.empty(); }))
            return false;
        out = std::move(q.front());
        q.pop();
        return true;
    }

    void drain() {
        std::lock_guard<std::mutex> lk(m);
        std::queue<std::string> empty;
        q.swap(empty);
    }
};

// -------------------------------------------------------------
// streambuf, читающий строки из LineQueue как из stdin
// -------------------------------------------------------------
class InQueueBuf : public std::streambuf {
public:
    explicit InQueueBuf(LineQueue& q) : q_(q) {}

protected:
    int underflow() override {
        if (!buf_.empty())
            return traits_type::to_int_type(buf_[0]);

        if (!q_.pop_for(line_, 3600'000)) // ждать до 1 часа
            return traits_type::eof();

        line_.push_back('\n');
        buf_.assign(line_.begin(), line_.end());
        setg(reinterpret_cast<char*>(buf_.data()),
             reinterpret_cast<char*>(buf_.data()),
             reinterpret_cast<char*>(buf_.data()) + buf_.size());
        return traits_type::to_int_type(*gptr());
    }

private:
    LineQueue& q_;
    std::string line_;
    std::vector<char> buf_;
};

// -------------------------------------------------------------
// streambuf, складывающий stdout-строки по '\n' в LineQueue
// -------------------------------------------------------------
class OutQueueBuf : public std::streambuf {
public:
    explicit OutQueueBuf(LineQueue& q) : q_(q) {}

protected:
    int overflow(int ch) override {
        if (ch == traits_type::eof()) return 0;
        char c = static_cast<char>(ch);
        if (c == '\n') { q_.push(buffer_); buffer_.clear(); }
        else           { buffer_.push_back(c); }
        return ch;
    }

    int sync() override {
        if (!buffer_.empty()) { q_.push(buffer_); buffer_.clear(); }
        return 0;
    }

private:
    LineQueue& q_;
    std::string buffer_;
};

// -------------------------------------------------------------
// Хэндл движка: очереди, потоки, буферы, состояние
// -------------------------------------------------------------
namespace EngineBridge {

// Однократная инициализация глобальных таблиц Stockfish (UCI/Bitboards/PSQT/TT).
// Реализуется в .cpp и вызывается из EngineHandle::start().
    void init_once();

// Основной UCI-цикл Stockfish поверх произвольных std::istream/std::ostream.
// Реализация в .cpp вызывает Stockfish::UCI::loop(in, out) и учитывает флаг running.
    void uci_loop(std::istream& in, std::ostream& out, std::atomic<bool>& running);

} // namespace EngineBridge

struct EngineHandle {
    LineQueue   inQ, outQ;
    InQueueBuf  inBuf;
    OutQueueBuf outBuf;

    // Владеем потоками, т.к. streambuf требует живого iostream
    std::istream* inStream;
    std::ostream* outStream;

    std::thread   loopThread;
    std::atomic<bool> running{false};

    // Кол-во потоков для движка (передаётся через UCI setoption name Threads)
    int threads = 4;

    EngineHandle() : inBuf(inQ), outBuf(outQ) {
        inStream  = new std::istream(&inBuf);
        outStream = new std::ostream(&outBuf);
    }

    // Некопируемо
    EngineHandle(const EngineHandle&) = delete;
    EngineHandle& operator=(const EngineHandle&) = delete;

    ~EngineHandle() {
        try { stop(); } catch (...) {}
        delete inStream;
        delete outStream;
    }

    // Запуск фонового UCI-цикла
    void start();

    // Остановка UCI-цикла и дренаж очередей
    void stop();
};

// -------------------------------------------------------------
// JNI-интерфейс (сигнатуры соответствуют вашему Kotlin)
// Реализация в engine_bridge.cpp
// -------------------------------------------------------------
extern "C" {

// Native возвращает указатель (jlong) на EngineHandle
JNIEXPORT jlong JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeInit(
        JNIEnv*, jobject,
        jstring jStockfishPath,    // путь может игнорироваться при встроенном движке
        jboolean jPreferBuiltin,   // если true — использовать встроенный Stockfish
        jint jThreads              // желаемое количество потоков движка
);

// Запускает UCI-цикл (создаёт поток)
JNIEXPORT void JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeStart(
        JNIEnv*, jobject, jlong handle
);

// Передаёт одну UCI-команду в stdin движка
JNIEXPORT void JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeWriteLine(
        JNIEnv*, jobject, jlong handle, jstring jCmd
);

// Читает одну строку stdout движка с таймаутом (мс). Возвращает null если таймаут.
JNIEXPORT jstring JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeReadLine(
        JNIEnv*, jobject, jlong handle, jint timeoutMs
);

// Очищает очередь вывода (stdout-буфер), ожидая до timeoutMs (мс)
JNIEXPORT void JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeDrain(
        JNIEnv*, jobject, jlong handle, jint timeoutMs
);

// Дренаж stdout до появления строки, содержащей token, либо таймаут
JNIEXPORT void JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeDrainUntil(
        JNIEnv*, jobject, jlong handle, jstring jToken, jint timeoutMs
);

} // extern "C"
