cmake_minimum_required(VERSION 3.20)

project(opentermx_native
        VERSION 1.0.0
        DESCRIPTION "OpenTermX native library: serial I/O y emulador VT/ANSI"
        LANGUAGES C)

# --------------------------------------------------------------------
# Opciones de compilación
# --------------------------------------------------------------------
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

# Para que JNA encuentre la librería con System.loadLibrary("opentermx_native"),
# el archivo resultante debe ser opentermx_native.dll / libopentermx_native.so / libopentermx_native.dylib.
set(CMAKE_WINDOWS_EXPORT_ALL_SYMBOLS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

# Directorio de salida: build/lib/<config>/<plataforma>
set(OPENTERMX_OUTPUT_BASE "${CMAKE_BINARY_DIR}/lib")
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${OPENTERMX_OUTPUT_BASE}")
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${OPENTERMX_OUTPUT_BASE}")
set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY "${OPENTERMX_OUTPUT_BASE}")

# --------------------------------------------------------------------
# Fuentes
# --------------------------------------------------------------------
set(OPENTERMX_SOURCES
        src/serial_native.c
        src/terminal_emu.c)

set(OPENTERMX_HEADERS
        src/serial_native.h
        src/terminal_emu.h)

add_library(opentermx_native SHARED
        ${OPENTERMX_SOURCES}
        ${OPENTERMX_HEADERS})

target_include_directories(opentermx_native
        PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/src>)

# Macro de exportación de símbolos (OPENTERMX_API)
target_compile_definitions(opentermx_native PRIVATE OPENTERMX_BUILDING_DLL)

# --------------------------------------------------------------------
# Flags y dependencias por plataforma
# --------------------------------------------------------------------
if(WIN32)
    target_compile_definitions(opentermx_native PRIVATE
            _CRT_SECURE_NO_WARNINGS
            WIN32_LEAN_AND_MEAN
            NOMINMAX
            _WIN32_WINNT=0x0601)   # Windows 7+
    if(MSVC)
        target_compile_options(opentermx_native PRIVATE /W4 /permissive- /utf-8)
    else()
        target_compile_options(opentermx_native PRIVATE -Wall -Wextra -Wpedantic)
    endif()
elseif(UNIX)
    target_compile_options(opentermx_native PRIVATE
            -Wall -Wextra -Wpedantic -fvisibility=hidden)
    find_package(Threads REQUIRED)
    target_link_libraries(opentermx_native PRIVATE Threads::Threads)
endif()

# --------------------------------------------------------------------
# Nombre final de la librería: opentermx_native(.dll|.so|.dylib)
# Sin prefijo "lib" en Windows.
# --------------------------------------------------------------------
set_target_properties(opentermx_native PROPERTIES
        OUTPUT_NAME "opentermx_native"
        PREFIX ""
        DEFINE_SYMBOL "OPENTERMX_BUILDING_DLL"
        C_VISIBILITY_PRESET hidden)

# --------------------------------------------------------------------
# Instalación (opcional)
# --------------------------------------------------------------------
include(GNUInstallDirs)
install(TARGETS opentermx_native
        RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
        LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
        ARCHIVE DESTINATION ${CMAKE_INSTALL_LIBDIR})
install(FILES ${OPENTERMX_HEADERS}
        DESTINATION ${CMAKE_INSTALL_INCLUDEDIR}/opentermx)

# --------------------------------------------------------------------
# Resumen
# --------------------------------------------------------------------
message(STATUS "OpenTermX native build")
message(STATUS "  CMAKE_BUILD_TYPE   = ${CMAKE_BUILD_TYPE}")
message(STATUS "  CMAKE_SYSTEM_NAME  = ${CMAKE_SYSTEM_NAME}")
message(STATUS "  Output dir         = ${OPENTERMX_OUTPUT_BASE}")