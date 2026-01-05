cmake_minimum_required(VERSION 3.10.2)

project("groovebox")

# List C++ source files
add_library(native-lib SHARED
            native-lib.cpp
            AudioEngine.cpp)

# Find libraries
find_library(log-lib log)
find_library(android-lib android)

# Oboe (integrated via Prefab)
find_package(oboe REQUIRED CONFIG)

target_link_libraries(native-lib
                      ${log-lib}
                      ${android-lib}
                      oboe::oboe)
