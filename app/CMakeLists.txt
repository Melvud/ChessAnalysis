cmake_minimum_required(VERSION 3.22.1)
project(sflauncher LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Наш JNI-слой
add_library(sflauncher SHARED
    src/main/cpp/sflauncher.cpp
)

# Говорим JNI-коду, что движок встроенный, а не exec()
add_definitions(-DBUILTIN_STOCKFISH=1)

# Системные либы
find_library(log-lib     log)
find_library(android-lib android)

# Импортируем предсобранный Stockfish из jniLibs/<ABI>/
add_library(stockfish SHARED IMPORTED GLOBAL)
set_target_properties(stockfish PROPERTIES
    IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/src/main/jniLibs/${ANDROID_ABI}/libstockfish.so"
)

# Линкуем
target_link_libraries(sflauncher
    stockfish
    ${log-lib}
    ${android-lib}
)

# (необязательно, но полезно)
target_compile_options(sflauncher PRIVATE -Wall -Wextra)
