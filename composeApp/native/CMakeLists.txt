cmake_minimum_required(VERSION 3.10.2)

project("groovebox")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Common source files for the core synthesis engine
set(CORE_SOURCES
    AudioEngineCore.cpp
)

# Conditional logic for different platforms
if(APPLE)
    # Find JNI for MacOS - Common Homebrew paths for OpenJDK
    set(JAVA_AWT_LIBRARY_DIRECTORIES
        /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home/lib
        /usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home/lib
    )
    
    find_package(JNI)
    
    if(NOT JNI_FOUND)
        message(STATUS "Standard FindJNI failed, trying manual search...")
        # Fallback for Homebrew OpenJDK
        find_path(JAVA_INCLUDE_PATH jni.h
            PATH_SUFFIXES include
            PATHS /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
                  /usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home
                  /opt/homebrew/Cellar/openjdk@17/17.0.17/libexec/openjdk.jdk/Contents/Home
        )
        find_path(JAVA_INCLUDE_PATH2 jni_md.h
            PATH_SUFFIXES include/darwin
            PATHS /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
                  /usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home
                  /opt/homebrew/Cellar/openjdk@17/17.0.17/libexec/openjdk.jdk/Contents/Home
        )
        if(JAVA_INCLUDE_PATH AND JAVA_INCLUDE_PATH2)
            set(JNI_INCLUDE_DIRS ${JAVA_INCLUDE_PATH} ${JAVA_INCLUDE_PATH2})
            set(JNI_FOUND TRUE)
        endif()
    endif()

    if(NOT JNI_FOUND)
        message(FATAL_ERROR "JNI not found. Please set JAVA_HOME or install OpenJDK.")
    endif()
    
    # MacOS implementation
    add_library(native-lib SHARED
                ${CORE_SOURCES}
                MacAudioEngine.cpp
                native-lib-desktop.cpp) # New entry point for Desktop
    
    target_include_directories(native-lib PRIVATE ${JNI_INCLUDE_DIRS})
    
    target_link_libraries(native-lib
                          "-framework AudioUnit"
                          "-framework AudioToolbox"
                          "-framework CoreAudio"
                          "-framework CoreFoundation")
                          
    target_compile_definitions(native-lib PRIVATE OS_MACOS)

elseif(ANDROID)
    # Android implementation
    add_library(native-lib SHARED
                ${CORE_SOURCES}
                AudioEngine.cpp
                native-lib.cpp)
                
    find_library(log-lib log)
    find_library(android-lib android)
    find_package(oboe REQUIRED CONFIG)
    
    target_link_libraries(native-lib
                          ${log-lib}
                          ${android-lib}
                          oboe::oboe)
                          
    target_compile_definitions(native-lib PRIVATE OS_ANDROID)
endif()
