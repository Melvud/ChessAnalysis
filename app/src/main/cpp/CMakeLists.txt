# Минимальная версия CMake
cmake_minimum_required(VERSION 3.18.1)

# Название проекта
project("sflauncher")

# Включаем C++17
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Флаги компилятора для оптимизации и отладки
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Wextra")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O0 -g")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -DNDEBUG")

# Создаем shared library
add_library(
    sflauncher
    SHARED
    sflauncher.cpp
)

# Находим системные библиотеки Android
find_library(log-lib log)
find_library(android-lib android)

# Линкуем библиотеки
target_link_libraries(
    sflauncher
    ${log-lib}
    ${android-lib}
)

# Дополнительные настройки для совместимости
target_compile_options(sflauncher PRIVATE -fno-rtti -fno-exceptions)

# Экспортируем символы JNI
set_target_properties(sflauncher PROPERTIES
    LINK_FLAGS "-Wl,--hash-style=both"
)

# Версия Android NDK
if(ANDROID_PLATFORM)
    message(STATUS "Building for Android platform: ${ANDROID_PLATFORM}")
    message(STATUS "Android ABI: ${ANDROID_ABI}")
endif()
