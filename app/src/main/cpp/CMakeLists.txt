cmake_minimum_required(VERSION 3.22)
project(stockfish)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_BUILD_TYPE Release)

# МАКСИМАЛЬНЫЕ оптимизации
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG -flto -ffast-math -funroll-loops -fomit-frame-pointer")
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ARM64 оптимизации - ПОЛНАЯ МОЩЬ!
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # КРИТИЧНО: добавляем все доступные инструкции ARM
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+crypto+crc+dotprod+fp16+rcpc+simd")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -DUSE_NEON=8 -DUSE_NEON_DOTPROD -DUSE_POPCNT")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mtune=cortex-a76") # Для современных процессоров
    message(STATUS "✅ ARM64 FULL POWER: NEON, dotprod, crypto, POPCNT, FP16")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv7-a -mfpu=neon -mfloat-abi=softfp -DUSE_NEON")
endif()

# Источники
file(GLOB_RECURSE STOCKFISH_SRC stockfish/src/*.cpp)
list(FILTER STOCKFISH_SRC EXCLUDE REGEX ".*main\\.cpp$")

set(WRAPPER_SRC stockfish/src/uci_wrapper.cpp)

add_library(stockfish SHARED ${STOCKFISH_SRC} ${WRAPPER_SRC})
target_link_libraries(stockfish log)

# Линковка
target_link_options(stockfish PRIVATE
        "-flto"
        "-Wl,-O3"
        "-Wl,--gc-sections"
        "-Wl,--strip-all"
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=4096"
)

set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=4096")

target_compile_definitions(stockfish PRIVATE __ANDROID__ NDEBUG)