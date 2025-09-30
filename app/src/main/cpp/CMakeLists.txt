cmake_minimum_required(VERSION 3.22)

project(sflauncher LANGUAGES C CXX)

# ------------------------------
# Опции
# ------------------------------
option(BUILTIN_STOCKFISH "Build Stockfish inside the app as a library" ON)
option(STF_ENABLE_LTO    "Enable LTO for Release builds"               ON)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ------------------------------
# Общие флаги для Android
# ------------------------------
# (section/data gc помогает уменьшить размер so)
add_compile_options(-fexceptions -frtti -fvisibility=hidden -ffunction-sections -fdata-sections)
add_link_options(-Wl,--gc-sections -Wl,--exclude-libs,ALL)

if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_compile_definitions(NDEBUG=1)
    if(STF_ENABLE_LTO)
        # Для NDK/LLVM нормально работает обычное LTO
        add_compile_options(-flto)
        add_link_options(-flto)
    endif()
endif()

# ------------------------------
# JNI библиотека
# ------------------------------
add_library(sflauncher SHARED
        src/sflauncher.cpp
        src/engine_bridge.cpp
)

# Наши инклюды
target_include_directories(sflauncher PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}/src
)

# ------------------------------
# Встроенный Stockfish (ожидается в app/src/main/cpp/stockfish/src)
# ------------------------------
if(BUILTIN_STOCKFISH)
    set(STOCKFISH_DIR "${CMAKE_CURRENT_LIST_DIR}/stockfish")
    if(EXISTS "${STOCKFISH_DIR}/src")
        message(STATUS "Stockfish sources found in: ${STOCKFISH_DIR}")

        file(GLOB_RECURSE STOCKFISH_SOURCES
                "${STOCKFISH_DIR}/src/*.cpp"
                "${STOCKFISH_DIR}/src/*.cc"
                "${STOCKFISH_DIR}/src/*.c"
        )
        # Исключаем точку входа консольной версии
        list(FILTER STOCKFISH_SOURCES EXCLUDE REGEX ".*/main\\.cpp$")

        add_library(stockfish_core STATIC ${STOCKFISH_SOURCES})

        # Короткие include вида "uci.h"
        target_include_directories(stockfish_core PUBLIC "${STOCKFISH_DIR}/src")

        # PIC обязателен для статики, используемой внутри SHARED
        set_property(TARGET stockfish_core PROPERTY POSITION_INDEPENDENT_CODE ON)

        # Дефайны/опции для мобильной сборки
        target_compile_definitions(stockfish_core PUBLIC
                USE_POPCNT=1
                # NNUE_EMBEDDING_OFF не выключаем — хотим incbin сеть
        )

        # --------------------------
        # NNUE: имена файлов incbin (при необходимости поменяй)
        # --------------------------
        set(NNUE_SRC_DIR "${STOCKFISH_DIR}/src/nnue")
        set(NNUE_A "nn-1c0000000000.nnue")
        set(NNUE_B "nn-37f18f62d772.nnue")

        add_custom_target(copy_nnue ALL
                COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_CURRENT_BINARY_DIR}"
                COMMAND ${CMAKE_COMMAND} -E copy_if_different "${NNUE_SRC_DIR}/${NNUE_A}" "${CMAKE_CURRENT_BINARY_DIR}/${NNUE_A}"
                COMMAND ${CMAKE_COMMAND} -E copy_if_different "${NNUE_SRC_DIR}/${NNUE_B}" "${CMAKE_CURRENT_BINARY_DIR}/${NNUE_B}"
                BYPRODUCTS
                "${CMAKE_CURRENT_BINARY_DIR}/${NNUE_A}"
                "${CMAKE_CURRENT_BINARY_DIR}/${NNUE_B}"
                COMMENT "Copy NNUE files for incbin into ${CMAKE_CURRENT_BINARY_DIR}"
        )
        add_dependencies(stockfish_core copy_nnue)

        # Линкуем JNI с движком и добавляем include, чтобы тоже видеть "uci.h" и др.
        target_link_libraries(sflauncher PRIVATE stockfish_core)
        target_compile_definitions(sflauncher PRIVATE HAS_STOCKFISH=1)
        target_include_directories(sflauncher PRIVATE "${STOCKFISH_DIR}/src")
    else()
        message(WARNING "Stockfish sources not found. Building fallback stub (no real analysis).")
        target_compile_definitions(sflauncher PRIVATE STF_FALLBACK_STUB=1)
    endif()
endif()

# ------------------------------
# Зависимости Android NDK
# ------------------------------
find_library(log-lib     log)
find_library(android-lib android)
# (atomic иногда нужен для некоторых ABI; если нет — просто не линкуем)
find_library(atomic-lib  atomic)

if(atomic-lib)
    target_link_libraries(sflauncher PRIVATE ${log-lib} ${android-lib} ${atomic-lib})
else()
    target_link_libraries(sflauncher PRIVATE ${log-lib} ${android-lib})
endif()
