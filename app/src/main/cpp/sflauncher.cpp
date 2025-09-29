#include <jni.h>
#include <android/log.h>
#include <string>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <queue>
#include <atomic>
#include <memory>
#include <sstream>
#include <dlfcn.h>
#include <unistd.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <cstring>

#define LOG_TAG "sflauncher"
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

namespace {

class StockfishEngine {
public:
    StockfishEngine() : running(false), pipe_in{-1, -1}, pipe_out{-1, -1}, pid(-1) {}
    ~StockfishEngine() { stop(); }

    bool start(const std::string& binaryPath, int threads) {
        if (running.load()) return false;

        // Create pipes for communication
        if (pipe(pipe_in) == -1 || pipe(pipe_out) == -1) {
            ALOGE("Failed to create pipes");
            return false;
        }

        pid = fork();
        if (pid == -1) {
            ALOGE("Failed to fork");
            close_pipes();
            return false;
        }

        if (pid == 0) {
            // Child process
            close(pipe_in[1]);  // Close write end of input pipe
            close(pipe_out[0]); // Close read end of output pipe

            // Redirect stdin/stdout
            dup2(pipe_in[0], STDIN_FILENO);
            dup2(pipe_out[1], STDOUT_FILENO);
            dup2(pipe_out[1], STDERR_FILENO);

            close(pipe_in[0]);
            close(pipe_out[1]);

            // Execute stockfish
            execl(binaryPath.c_str(), "stockfish", nullptr);
            ALOGE("Failed to exec stockfish: %s", strerror(errno));
            _exit(1);
        }

        // Parent process
        close(pipe_in[0]);  // Close read end of input pipe
        close(pipe_out[1]); // Close write end of output pipe

        // Make output pipe non-blocking
        int flags = fcntl(pipe_out[0], F_GETFL, 0);
        fcntl(pipe_out[0], F_SETFL, flags | O_NONBLOCK);

        running = true;
        reader_thread = std::thread(&StockfishEngine::reader_loop, this);

        ALOGI("Stockfish engine started with PID %d", pid);
        return true;
    }

    void stop() {
        if (!running.load()) return;
        
        running = false;
        
        // Send quit command
        send_command("quit");
        
        // Wait for reader thread
        if (reader_thread.joinable()) {
            reader_thread.join();
        }
        
        // Wait for child process
        if (pid > 0) {
            int status;
            waitpid(pid, &status, 0);
            pid = -1;
        }
        
        close_pipes();
        ALOGI("Stockfish engine stopped");
    }

    void send_command(const std::string& cmd) {
        if (!running.load() || pipe_in[1] == -1) return;
        
        std::string line = cmd;
        if (line.empty() || line.back() != '\n') {
            line += '\n';
        }
        
        write(pipe_in[1], line.c_str(), line.length());
        ALOGD("Sent: %s", cmd.c_str());
    }

    std::string read_line(int timeout_ms) {
        std::unique_lock<std::mutex> lock(output_mutex);
        
        if (timeout_ms > 0) {
            output_cv.wait_for(lock, std::chrono::milliseconds(timeout_ms),
                              [this] { return !output_queue.empty() || !running.load(); });
        } else {
            output_cv.wait(lock, [this] { return !output_queue.empty() || !running.load(); });
        }
        
        if (!output_queue.empty()) {
            std::string line = output_queue.front();
            output_queue.pop();
            return line;
        }
        
        return "";
    }

private:
    std::atomic<bool> running;
    int pipe_in[2];
    int pipe_out[2];
    pid_t pid;
    std::thread reader_thread;
    
    std::mutex output_mutex;
    std::condition_variable output_cv;
    std::queue<std::string> output_queue;

    void reader_loop() {
        char buffer[4096];
        std::string line_buffer;
        
        while (running.load()) {
            ssize_t n = read(pipe_out[0], buffer, sizeof(buffer) - 1);
            
            if (n > 0) {
                buffer[n] = '\0';
                line_buffer += buffer;
                
                // Process complete lines
                size_t pos;
                while ((pos = line_buffer.find('\n')) != std::string::npos) {
                    std::string line = line_buffer.substr(0, pos);
                    line_buffer.erase(0, pos + 1);
                    
                    // Remove \r if present
                    if (!line.empty() && line.back() == '\r') {
                        line.pop_back();
                    }
                    
                    ALOGD("Received: %s", line.c_str());
                    
                    {
                        std::lock_guard<std::mutex> lock(output_mutex);
                        output_queue.push(line);
                    }
                    output_cv.notify_all();
                }
            } else if (n == 0) {
                // EOF
                break;
            } else if (errno != EAGAIN && errno != EWOULDBLOCK) {
                // Error
                ALOGE("Read error: %s", strerror(errno));
                break;
            } else {
                // No data available, wait a bit
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }
        }
    }

    void close_pipes() {
        if (pipe_in[0] != -1) { close(pipe_in[0]); pipe_in[0] = -1; }
        if (pipe_in[1] != -1) { close(pipe_in[1]); pipe_in[1] = -1; }
        if (pipe_out[0] != -1) { close(pipe_out[0]); pipe_out[0] = -1; }
        if (pipe_out[1] != -1) { close(pipe_out[1]); pipe_out[1] = -1; }
    }
};

// Global engine instance
std::unique_ptr<StockfishEngine> g_engine;
std::mutex g_engine_mutex;

} // namespace

// JNI functions for LocalStockfish
extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeInit(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    g_engine = std::make_unique<StockfishEngine>();
    return reinterpret_cast<jlong>(g_engine.get());
}

JNIEXPORT void JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeStart(JNIEnv* env, jclass, jlong handle, jint threads) {
    if (handle == 0) return;
    
    // Find stockfish binary
    std::string paths[] = {
        "/data/local/tmp/stockfish",
        "/system/bin/stockfish",
        "/system/xbin/stockfish",
        "/data/data/com.example.chessanalysis/files/stockfish"
    };
    
    std::string stockfish_path;
    for (const auto& path : paths) {
        if (access(path.c_str(), X_OK) == 0) {
            stockfish_path = path;
            ALOGI("Found stockfish at: %s", path.c_str());
            break;
        }
    }
    
    if (stockfish_path.empty()) {
        // Try to extract from assets or use bundled binary
        stockfish_path = "/data/local/tmp/stockfish";
        ALOGW("Using default stockfish path: %s", stockfish_path.c_str());
    }
    
    auto* engine = reinterpret_cast<StockfishEngine*>(handle);
    if (!engine->start(stockfish_path, threads)) {
        ALOGE("Failed to start stockfish engine");
    }
}

JNIEXPORT void JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeSend(JNIEnv* env, jclass, jlong handle, jstring cmd) {
    if (handle == 0 || !cmd) return;
    
    const char* cmd_str = env->GetStringUTFChars(cmd, nullptr);
    auto* engine = reinterpret_cast<StockfishEngine*>(handle);
    engine->send_command(cmd_str);
    env->ReleaseStringUTFChars(cmd, cmd_str);
}

JNIEXPORT jstring JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeReadLine(JNIEnv* env, jclass, jlong handle, jlong timeoutMs) {
    if (handle == 0) return env->NewStringUTF("");
    
    auto* engine = reinterpret_cast<StockfishEngine*>(handle);
    std::string line = engine->read_line(static_cast<int>(timeoutMs));
    return env->NewStringUTF(line.c_str());
}

JNIEXPORT void JNICALL
Java_com_example_chessanalysis_local_LocalStockfish_nativeStop(JNIEnv* env, jclass, jlong handle) {
    if (handle == 0) return;
    
    auto* engine = reinterpret_cast<StockfishEngine*>(handle);
    engine->stop();
}

// Legacy function for compatibility
JNIEXPORT jint JNICALL
Java_com_example_chessanalysis_local_SfLauncher_run(JNIEnv* env, jclass, jstring jPath) {
    return 0; // Not used anymore
}

} // extern "C"
