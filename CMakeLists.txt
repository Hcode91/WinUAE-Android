cmake_minimum_required(VERSION 3.20)

if(CMAKE_HOST_APPLE)
    set(WINUAE_UNIX_DEFAULT_MACOS_DEPLOYMENT_TARGET "13.0")
    set(WINUAE_UNIX_MACOS_DEPS_PREFIX "${CMAKE_CURRENT_SOURCE_DIR}/../winuae-macos-deps" CACHE PATH "Private macOS dependency prefix built by tools/macos-build-deps.sh")
    set(WINUAE_QEMU_UAE_DEPS_PREFIX "${WINUAE_UNIX_MACOS_DEPS_PREFIX}" CACHE PATH "Private macOS GLib prefix for the QEMU-UAE plugin")
    if(WINUAE_UNIX_MACOS_DEPS_PREFIX)
        list(PREPEND CMAKE_PREFIX_PATH "${WINUAE_UNIX_MACOS_DEPS_PREFIX}")
        list(REMOVE_DUPLICATES CMAKE_PREFIX_PATH)
    endif()
    if(NOT DEFINED CMAKE_OSX_DEPLOYMENT_TARGET)
        set(CMAKE_OSX_DEPLOYMENT_TARGET "${WINUAE_UNIX_DEFAULT_MACOS_DEPLOYMENT_TARGET}" CACHE STRING "Minimum macOS version for WinUAE Unix builds")
    elseif(CMAKE_OSX_DEPLOYMENT_TARGET STREQUAL "")
        set(CMAKE_OSX_DEPLOYMENT_TARGET "${WINUAE_UNIX_DEFAULT_MACOS_DEPLOYMENT_TARGET}" CACHE STRING "Minimum macOS version for WinUAE Unix builds" FORCE)
    endif()
endif()

project(winuae_unix_port LANGUAGES C CXX)

include(GNUInstallDirs)
include(CTest)
set(THREADS_PREFER_PTHREAD_FLAG TRUE)
find_package(Threads REQUIRED)

set(WINUAE_UNIX_TEST_TARGETS)
function(winuae_unix_register_test target)
    list(APPEND WINUAE_UNIX_TEST_TARGETS "${target}")
    set(WINUAE_UNIX_TEST_TARGETS "${WINUAE_UNIX_TEST_TARGETS}" PARENT_SCOPE)
    if(BUILD_TESTING)
        add_test(NAME ${target} COMMAND $<TARGET_FILE:${target}>)
        set_tests_properties(${target} PROPERTIES LABELS "unix")
    endif()
endfunction()

function(winuae_unix_imported_location target out_var)
    foreach(prop IMPORTED_LOCATION_RELEASE IMPORTED_LOCATION_RELWITHDEBINFO IMPORTED_LOCATION_MINSIZEREL IMPORTED_LOCATION_DEBUG IMPORTED_LOCATION)
        get_target_property(location "${target}" "${prop}")
        if(location AND NOT location MATCHES "-NOTFOUND$" AND EXISTS "${location}")
            set("${out_var}" "${location}" PARENT_SCOPE)
            return()
        endif()
    endforeach()
    set("${out_var}" "" PARENT_SCOPE)
endfunction()

function(winuae_unix_macos_find_qt_frameworks out_var)
    set(prefix_candidates ${CMAKE_PREFIX_PATH})
    if(WINUAE_UNIX_MACOS_DEPS_PREFIX)
        list(APPEND prefix_candidates "${WINUAE_UNIX_MACOS_DEPS_PREFIX}")
    endif()
    list(REMOVE_DUPLICATES prefix_candidates)

    foreach(prefix IN LISTS prefix_candidates)
        if(EXISTS "${prefix}/lib/QtWidgets.framework/QtWidgets"
                AND EXISTS "${prefix}/lib/QtGui.framework/QtGui"
                AND EXISTS "${prefix}/lib/QtCore.framework/QtCore")
            if(NOT TARGET winuae_unix_qt_frameworks)
                add_library(winuae_unix_qt_frameworks INTERFACE)
                target_include_directories(winuae_unix_qt_frameworks INTERFACE
                    "${prefix}/lib/QtWidgets.framework/Headers"
                    "${prefix}/lib/QtGui.framework/Headers"
                    "${prefix}/lib/QtCore.framework/Headers"
                )
                target_compile_definitions(winuae_unix_qt_frameworks INTERFACE
                    QT_WIDGETS_LIB
                    QT_GUI_LIB
                    QT_CORE_LIB
                )
                target_compile_options(winuae_unix_qt_frameworks INTERFACE
                    "-F${prefix}/lib"
                )
                target_link_options(winuae_unix_qt_frameworks INTERFACE
                    "-F${prefix}/lib"
                )
                target_link_libraries(winuae_unix_qt_frameworks INTERFACE
                    "${prefix}/lib/QtWidgets.framework"
                    "${prefix}/lib/QtGui.framework"
                    "${prefix}/lib/QtCore.framework"
                )
            endif()
            if(EXISTS "${prefix}/bin/macdeployqt")
                set(MACDEPLOYQT_EXECUTABLE "${prefix}/bin/macdeployqt" CACHE FILEPATH "macdeployqt executable" FORCE)
            endif()
            set("${out_var}" winuae_unix_qt_frameworks PARENT_SCOPE)
            return()
        endif()
    endforeach()

    set("${out_var}" "" PARENT_SCOPE)
endfunction()

function(winuae_unix_find_lzma_sdk out_var)
    # A system lzma-sdk source package (e.g. openSUSE lzma-sdk-devel) is
    # preferred over fetching the vendored archive; it must provide the SDK
    # C sources, not just headers.
    set(WINUAE_LZMA_SDK_CANDIDATES
        "${WINUAE_LZMA_SDK_DIR}/C"
        "${WINUAE_LZMA_SDK_DIR}"
    )
    foreach(prefix IN LISTS WINUAE_LZMA_SDK_SYSTEM_PATHS)
        list(APPEND WINUAE_LZMA_SDK_CANDIDATES "${prefix}/C" "${prefix}")
    endforeach()
    foreach(candidate IN LISTS WINUAE_LZMA_SDK_CANDIDATES)
        if(EXISTS "${candidate}/7z/7zVersion.h" AND EXISTS "${candidate}/7z/7zCrc.c")
            set(WINUAE_LZMA_SDK_SOURCE_DIR "${candidate}")
            break()
        endif()
    endforeach()

    if(NOT WINUAE_LZMA_SDK_SOURCE_DIR)
        if(NOT WINUAE_LZMA_SDK_FETCH)
            message(FATAL_ERROR
                "7-Zip/LZMA SDK ${WINUAE_LZMA_SDK_VERSION} was not found in "
                "${WINUAE_LZMA_SDK_DIR}. Enable WINUAE_LZMA_SDK_FETCH or "
                "pre-seed WINUAE_LZMA_SDK_DIR with the SDK archive contents."
            )
        endif()

        message(STATUS
            "Fetching 7-Zip/LZMA SDK ${WINUAE_LZMA_SDK_VERSION} into "
            "${WINUAE_LZMA_SDK_DIR}"
        )
        execute_process(
            COMMAND sh "${CMAKE_CURRENT_SOURCE_DIR}/tools/fetch-lzma-sdk.sh"
                    "${WINUAE_LZMA_SDK_DIR}"
                    "${WINUAE_LZMA_SDK_URL}"
                    "${WINUAE_LZMA_SDK_VERSION}"
                    "${WINUAE_LZMA_SDK_SHA256}"
            RESULT_VARIABLE WINUAE_LZMA_SDK_FETCH_RESULT
            OUTPUT_VARIABLE WINUAE_LZMA_SDK_FETCH_OUTPUT
            ERROR_VARIABLE WINUAE_LZMA_SDK_FETCH_ERROR
            OUTPUT_STRIP_TRAILING_WHITESPACE
            ERROR_STRIP_TRAILING_WHITESPACE
        )
        if(NOT WINUAE_LZMA_SDK_FETCH_RESULT EQUAL 0)
            message(FATAL_ERROR
                "Failed to fetch 7-Zip/LZMA SDK "
                "${WINUAE_LZMA_SDK_VERSION}: "
                "${WINUAE_LZMA_SDK_FETCH_ERROR}\n"
                "${WINUAE_LZMA_SDK_FETCH_OUTPUT}"
            )
        endif()

        foreach(candidate IN LISTS WINUAE_LZMA_SDK_CANDIDATES)
            if(EXISTS "${candidate}/7z/7zVersion.h")
                set(WINUAE_LZMA_SDK_SOURCE_DIR "${candidate}")
                break()
            endif()
        endforeach()
    endif()

    if(NOT WINUAE_LZMA_SDK_SOURCE_DIR)
        message(FATAL_ERROR
            "7-Zip/LZMA SDK ${WINUAE_LZMA_SDK_VERSION} was fetched, but "
            "no 7z/7zVersion.h was found in ${WINUAE_LZMA_SDK_DIR}."
        )
    endif()

    file(STRINGS "${WINUAE_LZMA_SDK_SOURCE_DIR}/7z/7zVersion.h"
        WINUAE_LZMA_SDK_VERSION_LINES
        REGEX "^#define MY_VERSION \"${WINUAE_LZMA_SDK_VERSION}\""
    )
    if(NOT WINUAE_LZMA_SDK_VERSION_LINES)
        message(FATAL_ERROR
            "Expected 7-Zip/LZMA SDK ${WINUAE_LZMA_SDK_VERSION}, but "
            "${WINUAE_LZMA_SDK_SOURCE_DIR}/7z/7zVersion.h did not match."
        )
    endif()

    set("${out_var}" "${WINUAE_LZMA_SDK_SOURCE_DIR}" PARENT_SCOPE)
endfunction()

function(winuae_unix_drive_sounds_fingerprint sample_dir out_var)
    file(GLOB sample_files LIST_DIRECTORIES FALSE "${sample_dir}/*.wav")
    list(SORT sample_files)

    set(sample_manifest "")
    foreach(sample_file IN LISTS sample_files)
        get_filename_component(sample_name "${sample_file}" NAME)
        file(SHA256 "${sample_file}" sample_sha256)
        string(APPEND sample_manifest "${sample_name}\t${sample_sha256}\n")
    endforeach()
    string(SHA256 sample_fingerprint "${sample_manifest}")
    set("${out_var}" "${sample_fingerprint}" PARENT_SCOPE)
endfunction()

function(winuae_unix_prepare_drive_sounds out_var)
    set(sample_dir "${WINUAE_DRIVE_SOUNDS_DIR}/plugins/floppysounds")
    set(archive_marker "${sample_dir}/.archive-sha256")
    set(samples_marker "${sample_dir}/.s