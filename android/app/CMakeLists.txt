cmake_minimum_required(VERSION 3.2)

set(CMAKE_CXX_STANDARD 14)

include_directories(src/main/cpp/third-party)

add_library(chiaki-jni SHARED
        src/main/cpp/chiaki-jni.c
        src/main/cpp/log.h
        src/main/cpp/log.c
        src/main/cpp/video-decoder.h
        src/main/cpp/video-decoder.c
        src/main/cpp/audio-decoder.h
        src/main/cpp/audio-decoder.c
        src/main/cpp/audio-output.h
        src/main/cpp/audio-output.cpp
        src/main/cpp/circular-fifo.hpp
        src/main/cpp/openxr_manager.h
        src/main/cpp/openxr_manager.cpp)

target_link_libraries(chiaki-jni chiaki-lib)

find_library(ANDROID_LIB_LOG log)
find_library(ANDROID_LIB_MEDIANDK mediandk)
find_library(ANDROID_LIB_ANDROID android)
find_library(ANDROID_LIB_EGL EGL)
find_library(ANDROID_LIB_GLESV3 GLESv3)
find_library(ANDROID_LIB_DL dl)

target_link_libraries(chiaki-jni
        "${ANDROID_LIB_LOG}"
        "${ANDROID_LIB_MEDIANDK}"
        "${ANDROID_LIB_ANDROID}"
        "${ANDROID_LIB_EGL}"
        "${ANDROID_LIB_GLESV3}"
        "${ANDROID_LIB_DL}")

find_package (oboe REQUIRED CONFIG)
target_link_libraries(chiaki-jni oboe::oboe)