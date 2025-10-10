cmake_minimum_required(VERSION 3.22)
project(stockfish)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_BUILD_TYPE Release)

# МАКСИМАЛЬНАЯ оптимизация
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Ofast")  # Агрессивнее чем O3
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -flto=thin")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffast-math -funroll-loops -fomit-frame-pointer")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-stack-protector")  # Убираем проверки стека
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fmerge-all-constants")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffunction-sections -fdata-sections")

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a -mtune=cortex-a76")  # Настройка под конкретный CPU
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -DUSE_NEON=8")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    message(STATUS "✅ ARM32 SIMD")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv7-a -mfpu=neon")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -DUSE_NEON")
endif()

set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN YES)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

file(GLOB_RECURSE STOCKFISH_SRC stockfish/src/*.cpp)
list(FILTER STOCKFISH_SRC EXCLUDE REGEX ".*main\\.cpp$")

set(WRAPPER_SRC stockfish/src/uci_wrapper.cpp)
add_library(stockfish SHARED ${STOCKFISH_SRC} ${WRAPPER_SRC})

target_link_libraries(stockfish log)

target_link_options(stockfish PRIVATE
        "-flto=thin"
        "-Wl,-O3"
        "-Wl,--gc-sections"
        "-Wl,--strip-all"
)

target_compile_definitions(stockfish PRIVATE __ANDROID__ NDEBUG)