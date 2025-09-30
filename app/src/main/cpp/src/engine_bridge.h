#pragma once

#include <jni.h>
#include <atomic>
#include <condition_variable>
#include <mutex>
#include <queue>
#include <string>
#include <thread>

// Простой потокобезопасный очередь-канал строк
class StringQueue {
public:
    void push(std::string s) {
        {
            std::lock_guard<std::mutex> lk(m_);
            q_.push(std::move(s));
        }
        cv_.notify_one();
    }

    // Блокирующее чтение с таймаутом (мс). Возвращает true, если строка получена.
    bool pop_wait(std::string &out, int timeout_ms) {
        std::unique_lock<std::mutex> lk(m_);
        if (timeout_ms < 0) timeout_ms = 0;
        if (!cv_.wait_for(lk, std::chrono::milliseconds(timeout_ms), [&]{ return !q_.empty() || stopped_; }))
            return false;
        if (q_.empty()) return false;
        out = std::move(q_.front());
        q_.pop();
        return true;
    }

    // Неблокирующее чтение
    bool try_pop(std::string &out) {
        std::lock_guard<std::mutex> lk(m_);
        if (q_.empty()) return false;
        out = std::move(q_.front());
        q_.pop();
        return true;
    }

    void stop() {
        {
            std::lock_guard<std::mutex> lk(m_);
            stopped_ = true;
        }
        cv_.notify_all();
    }

    void clear() {
        std::lock_guard<std::mutex> lk(m_);
        std::queue<std::string> empty;
        std::swap(q_, empty);
    }

private:
    std::mutex m_;
    std::condition_variable cv_;
    std::queue<std::string> q_;
    bool stopped_ = false;
};

// Собственный streambuf/istream/ostream для общения с UCI::loop
#include <streambuf>
#include <istream>
#include <ostream>
#include <vector>

class QueueInBuf : public std::streambuf {
public:
    explicit QueueInBuf(StringQueue& q) : q_(q) { setg(nullptr, nullptr, nullptr); }

protected:
    // Загружаем следующую строку как «ввод»
    int_type underflow() override {
        if (gptr() < egptr())
            return traits_type::to_int_type(*gptr());

        buf_.clear();
        std::string line;
        // ждём бесконечно — поток UCI живёт в отдельном треде
        if (!q_.pop_wait(line, /*timeout*/ 24 * 60 * 60 * 1000)) // 24h
            return traits_type::eof();

        // гарантируем '\n' в конце, как у std::getline от stdin
        if (line.empty() || line.back() != '\n') line.push_back('\n');

        buf_.assign(line.begin(), line.end());
        setg(buf_.data(), buf_.data(), buf_.data() + buf_.size());
        return traits_type::to_int_type(*gptr());
    }

private:
    StringQueue& q_;
    std::vector<char> buf_;
};

class QueueOutBuf : public std::streambuf {
public:
    explicit QueueOutBuf(StringQueue& q) : q_(q) { }

protected:
    int_type overflow(int_type ch) override {
        if (ch == traits_type::eof()) return traits_type::not_eof(ch);
        char c = static_cast<char>(ch);
        if (c == '\n') {
            q_.push(current_);
            current_.clear();
        } else {
            current_.push_back(c);
        }
        return ch;
    }

    int sync() override {
        if (!current_.empty()) {
            q_.push(current_);
            current_.clear();
        }
        return 0;
    }

private:
    StringQueue& q_;
    std::string current_;
};

struct EngineHandle {
    // Каналы: java → engine, engine → java
    StringQueue inQueue;
    StringQueue outQueue;

    // Поток движка
    std::thread engineThread;
    std::atomic<bool> running{false};

    // Потоки-обёртки
    QueueInBuf  inBuf;
    QueueOutBuf outBuf;
    std::istream inStream;
    std::ostream outStream;

    EngineHandle()
            : inBuf(inQueue)
            , outBuf(outQueue)
            , inStream(&inBuf)
            , outStream(&outBuf)
    {}
};

// JNI функции (реализация в engine_bridge.cpp)
extern "C" {
JNIEXPORT jlong JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeInit(JNIEnv*, jclass,
                                                               jstring stockfishPath,
                                                               jboolean preferBuiltin,
                                                               jint threads);

JNIEXPORT void JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeStart(JNIEnv*, jclass, jlong handle);

JNIEXPORT void JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeWriteLine(JNIEnv*, jclass,
                                                                    jlong handle, jstring cmd);

JNIEXPORT jstring JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeReadLine(JNIEnv*, jclass,
                                                                   jlong handle, jint timeoutMs);

JNIEXPORT void JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeDrain(JNIEnv*, jclass,
                                                                jlong handle, jint timeoutMs);

JNIEXPORT void JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeDrainUntil(JNIEnv*, jclass,
                                                                     jlong handle,
                                                                     jstring token,
                                                                     jint timeoutMs);
}
