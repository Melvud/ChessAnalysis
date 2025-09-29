#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <string>

#define LOG_TAG "sflauncher"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

// тип функции main(int,char**)
using MainFn = int (*)(int, char**);

static int run_engine_from_so(const char* soPath) {
    if (!soPath || !*soPath) {
        ALOGE("Empty path to libstockfish.so");
        return -1;
    }

    void* handle = dlopen(soPath, RTLD_NOW);
    if (!handle) {
        ALOGE("dlopen failed: %s", dlerror());
        return -2;
    }

    // пробуем найти символы в таком порядке: "main" -> "stockfish_main"
    dlerror(); // clear
    MainFn entry = reinterpret_cast<MainFn>(dlsym(handle, "main"));
    if (!entry) {
        const char* e1 = dlerror();
        dlerror();
        entry = reinterpret_cast<MainFn>(dlsym(handle, "stockfish_main"));
        if (!entry) {
            const char* e2 = dlerror();
            ALOGE("dlsym failed: main=%s, stockfish_main=%s", e1 ? e1 : "null", e2 ? e2 : "null");
            dlclose(handle);
            return -3;
        }
    }

    // минимальный argv: ["stockfish", nullptr]
    const char* arg0 = "stockfish";
    char* argv[2];
    argv[0] = const_cast<char*>(arg0);
    argv[1] = nullptr;

    ALOGI("Starting engine entrypoint...");
    int rc = entry(1, argv);
    ALOGI("Engine returned %d", rc);

    dlclose(handle);
    return rc;
}

// JNI-обёртка: com.example.chessanalysis.local.SfLauncher.run(pathToLibStockfish)
extern "C"
JNIEXPORT jint JNICALL
Java_com_example_chessanalysis_local_SfLauncher_run(
        JNIEnv* env,
        jclass /*clazz*/,
        jstring jPath /* path to libstockfish.so */) {
    if (!jPath) return -10;
    const char* cpath = env->GetStringUTFChars(jPath, nullptr);
    int rc = run_engine_from_so(cpath);
    env->ReleaseStringUTFChars(jPath, cpath);
    return rc;
}

