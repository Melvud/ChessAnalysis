cmake_minimum_required(VERSION 3.22)
project(stockfish)

# C++17
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Скрываем символы по умолчанию (меньше "шума" снаружи .so)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN YES)

# Для единообразия PIC
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Источники Stockfish + JNI-обёртка
file(GLOB_RECURSE STOCKFISH_SRC
        stockfish/src/*.cpp
        stockfish/src/*.nnue
)
set(WRAPPER_SRC stockfish/src/uci_wrapper.cpp)

add_library(stockfish SHARED ${STOCKFISH_SRC} ${WRAPPER_SRC})

target_link_libraries(stockfish log)

target_link_options(stockfish PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
)
