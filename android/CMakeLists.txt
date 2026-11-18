cmake_minimum_required(VERSION 3.22)
project(OpenBGIAndroid LANGUAGES C)

if(NOT ANDROID)
    message(FATAL_ERROR "Configure with the Android NDK CMake toolchain")
endif()

set(BGI_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/..")
set(BGI_SOURCE_DIRS
    asm bpc core dbg dbg/views dump gfx gfx/background gfx/mgr gfx/particle
    gfx/text gfx/window scn snd snd/bw snd/wm sys sys/install sysobj vm wait
)
set(BGI_SOURCES "${BGI_ROOT}/src/os/os_common.c")
foreach(directory IN LISTS BGI_SOURCE_DIRS)
    file(GLOB sources CONFIGURE_DEPENDS "${BGI_ROOT}/src/${directory}/*.c")
    list(APPEND BGI_SOURCES ${sources})
endforeach()

# The Android host will own the application lifecycle and call the engine.
list(REMOVE_ITEM BGI_SOURCES "${BGI_ROOT}/src/sys/main.c")

option(BGI_ANDROID_APP "Build the Android activity library" OFF)
if(BGI_ANDROID_APP)
    list(FILTER BGI_SOURCES EXCLUDE REGEX "/src/(dbg|dump)/")
endif()

add_library(openbgi_core STATIC ${BGI_SOURCES})
set_target_properties(openbgi_core PROPERTIES
    C_STANDARD 99
    C_STANDARD_REQUIRED YES
    C_EXTENSIONS NO
    POSITION_INDEPENDENT_CODE YES
)
target_include_directories(openbgi_core PUBLIC "${BGI_ROOT}/inc")
target_compile_definitions(openbgi_core PRIVATE _POSIX_C_SOURCE=200809L)

if(BGI_ANDROID_APP)
    if(ANDROID_PLATFORM_LEVEL LESS 28)
        message(FATAL_ERROR "The Android host requires API 28")
    endif()
    if(NOT EXISTS "${BGI_ROOT}/src/snd/stb_vorbis.c")
        message(FATAL_ERROR "Missing stb_vorbis.c. Run android/build-apk.ps1 to fetch it.")
    endif()
    target_compile_definitions(openbgi_core PRIVATE BGI_WASM BGI_USE_STB_VORBIS)
    file(GLOB BGI_HOST_SOURCES CONFIGURE_DEPENDS "${BGI_ROOT}/src/os/wasm/*.c")
    add_library(openbgi_android SHARED host.c ${BGI_HOST_SOURCES})
    target_include_directories(openbgi_android PRIVATE "${BGI_ROOT}" "${BGI_ROOT}/tools/wasmstub")
    target_compile_definitions(openbgi_android PRIVATE BGI_WASM _POSIX_C_SOURCE=200809L)
    target_link_libraries(openbgi_android PRIVATE openbgi_core jnigraphics log m)
endif()
