cmake_minimum_required(VERSION 3.10)
project(sflauncher)

add_library(sflauncher SHARED sflauncher.cpp)

# Ничего НЕ линкуем с libstockfish на этапе сборки лаунчера!
# Он подгружается через dlopen() во время выполнения.

find_library(log-lib     log)
find_library(android-lib android)
find_library(dl-lib      dl)

target_link_libraries(
        sflauncher
        ${log-lib}
        ${android-lib}
        ${dl-lib}
)

set_target_properties(sflauncher PROPERTIES
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED YES
)

