#include <jni.h>
#include <string>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <queue>
#include <atomic>
#include <sstream>

#include <android/log.h>
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "StockfishJNI", __VA_ARGS__)

namespace {

std::mutex mtxIn, mtxOut;
std::condition_variable cvIn, cvOut;
std::queue<std::string> inQueue;
std::queue<std::string> outQueue;
std::atomic<bool> running(false);
std::thread engineThread;

extern "C" {
// Объявляем main/uci loop из Stockfish
int stockfish_main_loop(std::istream& in, std::ostream& out); // сделаем обёртку ниже
}

// Класс-стрим, который читает из нашей очереди
struct InStream : public std::istream {
    struct Buf : public std::stringbuf {
        int underflow() override { return traits_type::eof(); }
    } buf;
    InStream(): std::istream(&buf) {}
    // читаем по строке
    bool getline(std::string& s) {
        std::unique_lock<std::mutex> lk(mtxIn);
        cvIn.wait(lk, []{ return !inQueue.empty() || !running.load(); });
        if (!running.load() && inQueue.empty()) return false;
        s = std::move(inQueue.front());
        inQueue.pop();
        return true;
    }
};

// Пишем в outQueue
struct OutStream : public std::ostream {
    struct Buf : public std::stringbuf {
        int sync() override {
            std::string s = str();
            str(""); // clear
            if (!s.empty()) {
                std::lock_guard<std::mutex> lk(mtxOut);
                outQueue.push(std::move(s));
                cvOut.notify_all();
            }
            return 0;
        }
    } buf;
    OutStream(): std::ostream(&buf) {}
};

InStream* gIn = nullptr;
OutStream* gOut = nullptr;

int uci_loop_wrapper() {
    InStream in;
    OutStream out;
    gIn = &in;
    gOut = &out;

    // Мини-цикл: читаем строки из inQueue и передаём туда, где Stockfish их ожидает.
    // Проще всего — собрать вокруг оригинального UCI-кода минимальный адаптер:
    // Здесь показан шаблон. Реально в Stockfish есть uci_loop(), который читает std::cin/std::cout.
    // Можно переопределить cin/cout через rdbuf, чтобы направить их на наши буферы.

    std::streambuf* cinBuf = std::cin.rdbuf();
    std::streambuf* coutBuf = std::cout.rdbuf();
    std::cin.rdbuf(in.rdbuf());
    std::cout.rdbuf(out.rdbuf());

    // Вызов стандартного UCI-цикла:
    extern int uci_main(); // объяви в одном из .cpp (см. ниже примечание)
    int rc = uci_main();

    // Восстанавливаем
    std::cin.rdbuf(cinBuf);
    std::cout.rdbuf(coutBuf);

    return rc;
}

void startEngine() {
    if (running.exchange(true)) return;
    engineThread = std::thread([]{
        LOGD("Engine thread start");
        uci_loop_wrapper();
        LOGD("Engine thread end");
        running = false;
        cvOut.notify_all();
        cvIn.notify_all();
    });
}

void stopEngine() {
    if (!running.exchange(false)) return;
    {
        std::lock_guard<std::mutex> lk(mtxIn);
        inQueue.push("quit\n");
    }
    cvIn.notify_all();
    if (engineThread.joinable()) engineThread.join();
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_example_chessanalysis_local_EngineNative_start(JNIEnv*, jclass) {
    startEngine();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_chessanalysis_local_EngineNative_stop(JNIEnv*, jclass) {
    stopEngine();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_chessanalysis_local_EngineNative_sendCommand(JNIEnv* env, jclass, jstring cmd_) {
    if (!running.load()) return;
    const char* c = env->GetStringUTFChars(cmd_, nullptr);
    std::string s(c);
    env->ReleaseStringUTFChars(cmd_, c);
    if (s.empty() || s.back() != '\n') s.push_back('\n');
    {
        std::lock_guard<std::mutex> lk(mtxIn);
        inQueue.push(std::move(s));
    }
    cvIn.notify_all();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_chessanalysis_local_EngineNative_readLineBlocking(JNIEnv* env, jclass, jint timeoutMs) {
    std::unique_lock<std::mutex> lk(mtxOut);
    if (timeoutMs > 0) {
        cvOut.wait_for(lk, std::chrono::milliseconds(timeoutMs), []{ return !outQueue.empty() || !running.load(); });
    } else {
        cvOut.wait(lk, []{ return !outQueue.empty() || !running.load(); });
    }
    if (outQueue.empty()) {
        return env->NewStringUTF(""); // timeout/engine stopped
    }
    std::string s = std::move(outQueue.front());
    outQueue.pop();
    return env->NewStringUTF(s.c_str());
}

