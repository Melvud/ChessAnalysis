cmake_minimum_required(VERSION 3.22)
project(stockfish)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_BUILD_TYPE Release)

# Общие оптимизации
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG -flto -ffast-math -funroll-loops -fomit-frame-pointer")
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ARM64 оптимизации + dotprod (нужно для vdotq_s32)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+crypto+crc+dotprod -DUSE_NEON=8 -DUSE_NEON_DOTPROD -DUSE_POPCNT")
    message(STATUS "✅ ARM64 optimizations: NEON, dotprod, crypto, POPCNT")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv7-a -mfpu=neon -mfloat-abi=softfp -DUSE_NEON")
endif()

# Источники Stockfish (исключаем main.cpp)
file(GLOB_RECURSE STOCKFISH_SRC stockfish/src/*.cpp)
list(FILTER STOCKFISH_SRC EXCLUDE REGEX ".*main\\.cpp$")

# Наш обёртка UCI
set(WRAPPER_SRC stockfish/src/uci_wrapper.cpp)

add_library(stockfish SHARED ${STOCKFISH_SRC} ${WRAPPER_SRC})
target_link_libraries(stockfish log)

# --- ВАЖНО ДЛЯ 16KB-СТРАНИЦ ---
# -z,max-page-size=16384 заставляет выравнивать LOAD-сегменты по 16KB
# -z,common-page-size=4096 уменьшает паддинг на 4KB-девайсах
# Остальные опции — тонкая настройка размера и производительности
target_link_options(stockfish PRIVATE
        "-flto"
        "-Wl,-O3"
        "-Wl,--gc-sections"
        "-Wl,--strip-all"
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=4096"
)

# Для надёжности добавим то же глобально для всех shared (если появятся другие so)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=4096")

# Дефайны для Android-сборки
target_compile_definitions(stockfish PRIVATE __ANDROID__ NDEBUG)
