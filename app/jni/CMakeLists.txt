cmake_minimum_required(VERSION 3.10)
project(espeak-ng-android)

# Define where your standalone espeak sources live
set(ESPEAK_SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../espeak-src)

# Include the main espeak-ng build
add_subdirectory(${ESPEAK_SRC_DIR} ${CMAKE_BINARY_DIR}/espeak-ng-build)

# JNI wrapper for Android
add_library(ttsespeak SHARED
        jni/eSpeakService.c
)

target_include_directories(ttsespeak PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${ESPEAK_SRC_DIR}/include
        ${ESPEAK_SRC_DIR}/libespeak-ng
)

find_library(LOG_LIB log)

target_link_libraries(ttsespeak
        espeak-ng
        ${LOG_LIB}
)
