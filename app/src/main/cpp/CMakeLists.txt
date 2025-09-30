cmake_minimum_required(VERSION 3.22)

project(sflauncher LANGUAGES C CXX)

# ===================== Общие настройки =====================
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Компоновка/оптимизация под Android
add_compile_options(-fexceptions -frtti -fvisibility=hidden -ffunction-sections -fdata-sections)
add_link_options(-Wl,--gc-sections -Wl,--exclude-libs,ALL)

if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_compile_definitions(NDEBUG=1)
    add_compile_options(-O3)
endif()

# ===================== JNI-библиотека =====================
add_library(sflauncher SHARED
        src/sflauncher.cpp          # у тебя уже есть
        src/engine_bridge.cpp       # новый код ниже
)

target_include_directories(sflauncher PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}/src
)

# ===================== Встроенный Stockfish =====================
# Ожидается структура: <this CMakeLists.txt>/stockfish/src/*.cpp
set(STOCKFISH_DIR "${CMAKE_CURRENT_LIST_DIR}/stockfish")

if (EXISTS "${STOCKFISH_DIR}/src")
    file(GLOB_RECURSE STOCKFISH_SOURCES
            "${STOCKFISH_DIR}/src/*.cpp"
    )
    # Удалим стандартный main.cpp (у нас свой вход через JNI)
    list(REMOVE_ITEM STOCKFISH_SOURCES "${STOCKFISH_DIR}/src/main.cpp")

    add_library(stockfish_core STATIC ${STOCKFISH_SOURCES})

    target_include_directories(stockfish_core PUBLIC
            "${STOCKFISH_DIR}/src"
            "${STOCKFISH_DIR}/src/nnue"
    )

    # ВАЖНО: без SSE на ARM, только NEON
    set_property(TARGET stockfish_core PROPERTY POSITION_INDEPENDENT_CODE ON)
    target_compile_definitions(stockfish_core PUBLIC
            IS_64BIT=1
            USE_PTHREADS=1
            IS_ARM=1
            USE_NEON=1
            # Не задаём никаких USE_SSE*, чтобы не тащить <xmmintrin.h>/<emmintrin.h> на ARM
    )

    # Небольшая оптимизация под arm64
    if (${CMAKE_ANDROID_ARCH_ABI} STREQUAL "arm64-v8a")
        target_compile_options(stockfish_core PUBLIC "-march=armv8-a")
    endif()

    target_link_libraries(sflauncher PRIVATE stockfish_core)
    target_compile_definitions(sflauncher PRIVATE HAS_STOCKFISH=1)
    target_include_directories(sflauncher PRIVATE
            "${STOCKFISH_DIR}/src"
            "${STOCKFISH_DIR}/src/nnue"
    )
else()
    message(FATAL_ERROR "Stockfish sources not found at ${STOCKFISH_DIR}/src")
endif()

# ===================== Android NDK системные либы =====================
find_library(log-lib log)
find_library(android-lib android)
target_link_libraries(sflauncher PRIVATE ${log-lib} ${android-lib})
