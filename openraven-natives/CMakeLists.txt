cmake_minimum_required(VERSION 3.20)
project(openraven_native CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The agent is a JVMTI agent, so it needs jni.h and jvmti.h -- a JDK, not a JRE.
if(NOT DEFINED JAVA_INCLUDE)
    set(JAVA_INCLUDE "$ENV{JAVA_HOME}/include")
endif()
if(NOT EXISTS "${JAVA_INCLUDE}/jni.h")
    message(FATAL_ERROR "jni.h not found under ${JAVA_INCLUDE}. Pass -DJAVA_INCLUDE=<jdk>/include")
endif()

# ---------------------------------------------------------------------- agent

add_library(openraven_native SHARED dll/dllmain.cpp)
target_include_directories(openraven_native PRIVATE
        "${JAVA_INCLUDE}"
        "${JAVA_INCLUDE}/win32")
set_target_properties(openraven_native PROPERTIES PREFIX "" OUTPUT_NAME "openraven_native")

# The client jar, embedded as a resource. Failing here rather than shipping a
# loader with nothing to load is deliberate: that one looks like it works.
if(NOT DEFINED CLIENT_JAR)
    file(GLOB JAR_CANDIDATES "${CMAKE_CURRENT_SOURCE_DIR}/../OpenRaven-Inject/build/libs/*.jar")
    list(FILTER JAR_CANDIDATES EXCLUDE REGEX "(sources|javadoc|srg|dev)\\.jar$")
    if(NOT JAR_CANDIDATES)
        message(FATAL_ERROR
                "No client jar under ../OpenRaven-Inject/build/libs. Run the Gradle build first "
                "(gradlew injectJar), or pass -DCLIENT_JAR=<path>")
    endif()
    list(GET JAR_CANDIDATES 0 CLIENT_JAR)
endif()
message(STATUS "client jar: ${CLIENT_JAR}")

set(PAYLOAD_DIR "${CMAKE_BINARY_DIR}/payload")
add_custom_command(
        OUTPUT "${PAYLOAD_DIR}/openraven_native.dll"
        COMMAND ${CMAKE_COMMAND} -E make_directory "${PAYLOAD_DIR}"
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
                $<TARGET_FILE:openraven_native> "${PAYLOAD_DIR}/openraven_native.dll"
        DEPENDS openraven_native
        COMMENT "staging the agent for embedding")

set(NATIVE_RC "${CMAKE_CURRENT_BINARY_DIR}/openraven_native_resources.rc")
configure_file(dll/native_resources.rc.in "${NATIVE_RC}" @ONLY)
set_property(SOURCE "${NATIVE_RC}" APPEND PROPERTY OBJECT_DEPENDS "${CLIENT_JAR}")
target_sources(openraven_native PRIVATE "${NATIVE_RC}")

# --------------------------------------------------------------------- loader

set(MANIFEST_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/loader/app.manifest")
if(EXISTS "${MANIFEST_SOURCE}")
    set(MANIFEST_RESOURCE "1 24 \"${MANIFEST_SOURCE}\"")
else()
    set(MANIFEST_RESOURCE "// no manifest at build time")
endif()

set(GENERATED_RC "${CMAKE_CURRENT_BINARY_DIR}/openraven_resources.rc")
configure_file(loader/resources.rc.in "${GENERATED_RC}" @ONLY)
set_property(SOURCE "${GENERATED_RC}" APPEND PROPERTY
        OBJECT_DEPENDS "${PAYLOAD_DIR}/openraven_native.dll;${MANIFEST_SOURCE}")

add_executable(openraven_loader WIN32
        loader/main.cpp
        loader/inject.cpp
        loader/target.cpp
        "${GENERATED_RC}")
add_dependencies(openraven_loader openraven_native)
target_include_directories(openraven_loader PRIVATE loader)
target_link_libraries(openraven_loader PRIVATE psapi advapi32 user32 shell32)

# WIN32 above selects the GUI subsystem, which needs WinMain. The loader is a
# console program -- it lists the running Java processes and says what it did --
# so it is linked as one.
if(MSVC)
    set_target_properties(openraven_loader PROPERTIES
            WIN32_EXECUTABLE OFF
            LINK_FLAGS "/SUBSYSTEM:CONSOLE")
endif()

if(MSVC)
    target_compile_options(openraven_native PRIVATE /W3 /EHsc)
    target_compile_options(openraven_loader PRIVATE /W3 /EHsc)
    # Static CRT, and an ordinary DLL link with the CRT's own entry point.
    #
    # Two things that look like improvements and are not. Both were tried, and
    # both produce a DLL that maps into the target and then does nothing:
    #   - /NODEFAULTLIB with /ENTRY:DllMain, to avoid depending on a C runtime
    #     inside someone else's process. The entry point then skips the CRT's
    #     startup, so DllMain is never called -- and LoadLibraryW still returns
    #     a module handle, so from outside it looks like a successful injection.
    #   - /MD, the dynamic CRT: adds a VCRUNTIME140 dependency the target may
    #     not have.
    # What works is what InjectMyau does: plain /MT and a plain link.
    set_property(TARGET openraven_native PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded")
    set_property(TARGET openraven_loader PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded")
    cmake_policy(SET CMP0091 NEW)
endif()
