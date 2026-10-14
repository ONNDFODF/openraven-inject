#pragma once

#include <windows.h>

#include <string>

/** Called for every line the injector wants to say. */
using Logger = void (*)(const std::wstring &line);

/**
 * Writes the embedded agent to disk and loads it into the given process.
 *
 * The agent finds its own jar: it is built into the agent DLL as a resource. A
 * sidecar file next to the DLL names a different jar when one is there, which
 * is how a client built just now is tested without rebuilding the loader.
 *
 * @param loaderPath full path of this executable, used to pick a writable
 *                   directory for the staged agent
 */
bool runInjection(DWORD pid, const std::wstring &loaderPath, const Logger &log);
