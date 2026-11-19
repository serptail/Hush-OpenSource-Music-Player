# Native bit-perfect output for Gamut: AAudio exclusive streams (gamut_exclusive)
# and a direct USB Audio Class driver on libusb (gamut_usb).
# Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0.

cmake_minimum_required(VERSION 3.22.1)
project(gamut_bitperfect LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)

# Bit-perfect output. AAudio exclusive streams through Oboe, built from a
# pinned release with its format/rate conversion compiled out: a stream that
# needed converting is one this library rejects.
# Oboe is Apache-2.0; exclusive_output.cpp is adapted from Mornye (LGPL-3.0).
include(FetchContent)
set(OBOE_DISABLE_CONVERSION ON CACHE BOOL "" FORCE)
FetchContent_Declare(oboe
    URL https://github.com/google/oboe/archive/refs/tags/1.11.0.tar.gz
    URL_HASH SHA256=0ccf2110640a2b4489f1c93a404f2978656b434d8c5dc9cb6258f9cc95c79b15)
FetchContent_MakeAvailable(oboe)

add_library(gamut_exclusive SHARED bitperfect/exclusive_output.cpp)
target_compile_options(gamut_exclusive PRIVATE -O2 -Wall -Wextra -fvisibility=hidden)
target_link_libraries(gamut_exclusive PRIVATE oboe ${log-lib})
# Only JNI is public: Oboe is static, and its symbols must not keep otherwise
# unused code alive or be exported from this library.
# 16KB page-size support (Android 15+ 16KB kernels, Play requirement).
target_link_options(gamut_exclusive PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--gc-sections"
    "-Wl,--version-script,${CMAKE_CURRENT_SOURCE_DIR}/bitperfect/exports.map")
set_property(TARGET gamut_exclusive APPEND PROPERTY LINK_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/bitperfect/exports.map")

# Direct USB output: a userspace USB Audio Class driver on libusb, so a DAC
# can be streamed to without Android's mixer on any Android version. libusb
# (LGPL-2.1) is built from its pinned release using the source list and
# config.h it ships for Android (android/jni/libusb.mk). The transport is
# adapted from Mornye's Rust driver (LGPL-3.0).
FetchContent_Declare(libusb
    URL https://github.com/libusb/libusb/releases/download/v1.0.27/libusb-1.0.27.tar.bz2
    URL_HASH SHA256=ffaa41d741a8a3bee244ac8e54a72ea05bf2879663c098c82fc5757853441575)
FetchContent_MakeAvailable(libusb)
add_library(gamut_libusb STATIC
    ${libusb_SOURCE_DIR}/libusb/core.c
    ${libusb_SOURCE_DIR}/libusb/descriptor.c
    ${libusb_SOURCE_DIR}/libusb/hotplug.c
    ${libusb_SOURCE_DIR}/libusb/io.c
    ${libusb_SOURCE_DIR}/libusb/sync.c
    ${libusb_SOURCE_DIR}/libusb/strerror.c
    ${libusb_SOURCE_DIR}/libusb/os/linux_usbfs.c
    ${libusb_SOURCE_DIR}/libusb/os/events_posix.c
    ${libusb_SOURCE_DIR}/libusb/os/threads_posix.c
    ${libusb_SOURCE_DIR}/libusb/os/linux_netlink.c)
target_include_directories(gamut_libusb
    PRIVATE ${libusb_SOURCE_DIR}/android ${libusb_SOURCE_DIR}/libusb/os
    PUBLIC ${libusb_SOURCE_DIR}/libusb)
target_compile_options(gamut_libusb PRIVATE -O2 -fvisibility=hidden -pthread -w)
set_target_properties(gamut_libusb PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(gamut_usb SHARED bitperfect/usb_transport.cpp)
target_compile_options(gamut_usb PRIVATE -O2 -Wall -Wextra -fvisibility=hidden)
target_link_libraries(gamut_usb PRIVATE gamut_libusb ${log-lib})
target_link_options(gamut_usb PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--gc-sections"
    "-Wl,--version-script,${CMAKE_CURRENT_SOURCE_DIR}/bitperfect/exports.map")
set_property(TARGET gamut_usb APPEND PROPERTY LINK_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/bitperfect/exports.map")
