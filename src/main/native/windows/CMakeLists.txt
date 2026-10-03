cmake_minimum_required(VERSION 3.25)
project(javaclaw_desktop_windows LANGUAGES CXX)

if(NOT WIN32 OR NOT MSVC)
    message(FATAL_ERROR "The Windows desktop bridge requires MSVC and the Windows 11 SDK")
endif()
if(CMAKE_VS_WINDOWS_TARGET_PLATFORM_VERSION AND
        CMAKE_VS_WINDOWS_TARGET_PLATFORM_VERSION VERSION_LESS "10.0.22000.0")
    message(FATAL_ERROR "Windows SDK 10.0.22000.0 or newer is required")
endif()

add_library(javaclaw_desktop_windows SHARED WindowsDesktopBridge.cpp)
target_compile_features(javaclaw_desktop_windows PRIVATE cxx_std_20)
target_compile_options(javaclaw_desktop_windows PRIVATE /W4 /EHsc)
target_compile_definitions(javaclaw_desktop_windows PRIVATE
        WIN32_LEAN_AND_MEAN NOMINMAX UNICODE _UNICODE)
target_include_directories(javaclaw_desktop_windows PRIVATE ..)
set(_cppwinrt_headers
        "$ENV{ProgramFiles(x86)}/Windows Kits/10/Include/${CMAKE_VS_WINDOWS_TARGET_PLATFORM_VERSION}/cppwinrt")
if(EXISTS "${_cppwinrt_headers}/winrt/Windows.Graphics.Capture.h")
    target_include_directories(javaclaw_desktop_windows PRIVATE "${_cppwinrt_headers}")
endif()
target_link_libraries(javaclaw_desktop_windows PRIVATE
        d3d11 dxgi dwmapi runtimeobject ole32 oleaut32 uiautomationcore
        user32 shell32 advapi32 uuid windowsapp)

# The signed distribution copies this DLL to runtime/native/windows/.
# It must never be loaded from the writable plugins/ tree.
set_target_properties(javaclaw_desktop_windows PROPERTIES
        OUTPUT_NAME "javaclaw_desktop")

install(TARGETS javaclaw_desktop_windows
        RUNTIME DESTINATION runtime/native/windows)
