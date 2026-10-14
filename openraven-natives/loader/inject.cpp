#include "inject.h"

#include <cstdarg>
#include <cstdio>

#include <string>
#include <vector>

#include "target.h"

namespace {

constexpr int IDR_EMBEDDED_AGENT = 101;

std::wstring format(const wchar_t *pattern, ...) {
    wchar_t buffer[1024];
    va_list args;
    va_start(args, pattern);
    _vsnwprintf_s(buffer, _TRUNCATE, pattern, args);
    va_end(args);
    return buffer;
}

std::wstring tempDirectory() {
    wchar_t path[MAX_PATH];
    if (!GetTempPathW(MAX_PATH, path)) {
        return L"";
    }
    std::wstring directory = path;
    // GetTempPathW appends a separator, but not always the same one.
    while (!directory.empty() && (directory[directory.size() - 1] == L'\\'
                                  || directory[directory.size() - 1] == L'/')) {
        directory.erase(directory.size() - 1);
    }
    return directory;
}

std::wstring directoryOf(const std::wstring &path) {
    size_t slash = path.find_last_of(L'\\');
    return slash == std::wstring::npos ? L"." : path.substr(0, slash);
}

bool writeFile(const std::wstring &path, const void *bytes, size_t size) {
    HANDLE handle = CreateFileW(path.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                                FILE_ATTRIBUTE_NORMAL, nullptr);
    if (handle == INVALID_HANDLE_VALUE) {
        return false;
    }
    DWORD written = 0;
    BOOL ok = WriteFile(handle, bytes, (DWORD) size, &written, nullptr);
    CloseHandle(handle);
    return ok && written == size;
}

/**
 * Where to put the agent DLL.
 *
 * Next to the loader first, which is where someone who copied the executable
 * somewhere expects a temporary file to appear, and which also works when the
 * game runs as a different user than the one TEMP belongs to. TEMP is the
 * fallback for a loader sitting in a directory that is not writable --
 * Program Files, or a read-only share.
 *
 * Both are tried and both failures are reported with their error codes. The
 * usual cause is permissions, and not being told which path failed makes that
 * much harder to see than it needs to be.
 *
 * The name is also retried with a counter, because injecting twice into the same
 * game is normal during development and the previous agent is still mapped: the
 * file is locked, and the write fails with ERROR_SHARING_VIOLATION (32) on a name
 * that has nothing wrong with it. InjectMyau retries the same way, for the same
 * reason.
 */
bool stageAgent(const void *bytes, DWORD size, const std::wstring &loaderPath,
                DWORD pid, const Logger &log, std::wstring &staged) {
    std::vector<std::wstring> directories;
    std::wstring beside = directoryOf(loaderPath);
    if (!beside.empty()) {
        directories.push_back(beside);
    }
    std::wstring temp = tempDirectory();
    if (!temp.empty() && temp != beside) {
        directories.push_back(temp);
    }
    if (directories.empty()) {
        log(L"nowhere to write the agent: no directory for the loader and no temp directory");
        return false;
    }
    DWORD lastError = 0;
    for (int attempt = 0; attempt < 32; attempt++) {
        std::wstring suffix = std::to_wstring(pid);
        if (attempt > 0) {
            suffix += L"_" + std::to_wstring(attempt);
        }
        for (size_t i = 0; i < directories.size(); i++) {
            std::wstring path = directories[i] + L"\\openraven_native_" + suffix + L".dll";
            if (writeFile(path, bytes, size)) {
                if (i > 0) {
                    log(L"the loader's own directory is not writable, so the agent went to temp");
                }
                if (attempt > 0) {
                    log(format(L"the first %d names were locked, so this build is %s",
                               attempt, path.substr(path.find_last_of(L'\\') + 1).c_str()));
                }
                staged = path;
                return true;
            }
            lastError = GetLastError();
        }
    }
    log(format(L"the agent could not be written anywhere it can be loaded from "
               L"(last error %lu)", lastError));
    log(L"move the loader somewhere writable, or run it as the user who owns the temp dir.");
    return false;
}

std::string toUtf8(const std::wstring &text) {
    if (text.empty()) {
        return "";
    }
    int size = WideCharToMultiByte(CP_UTF8, 0, text.c_str(), (int) text.size(), nullptr, 0,
                                   nullptr, nullptr);
    std::string out(size, 0);
    WideCharToMultiByte(CP_UTF8, 0, text.c_str(), (int) text.size(), &out[0], size, nullptr,
                        nullptr);
    return out;
}

/** The agent DLL, as built into this executable. */
bool readEmbeddedAgent(const void **bytes, DWORD *size) {
    HRSRC found = FindResourceW(nullptr, MAKEINTRESOURCEW(IDR_EMBEDDED_AGENT),
                                MAKEINTRESOURCEW(10));
    if (!found) {
        return false;
    }
    HGLOBAL loaded = LoadResource(nullptr, found);
    if (!loaded) {
        return false;
    }
    *bytes = LockResource(loaded);
    *size = SizeofResource(nullptr, found);
    return *bytes != nullptr && *size > 0;
}

bool inject(DWORD pid, const std::wstring &dllPath, const Logger &log) {
    HANDLE process = OpenProcess(PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION
                                         | PROCESS_VM_OPERATION | PROCESS_VM_WRITE
                                         | PROCESS_VM_READ,
                                 FALSE, pid);
    if (!process) {
        log(format(L"cannot open process %lu (error %lu)", pid, GetLastError()));
        log(L"if the game runs elevated, run this the same way");
        return false;
    }
    SIZE_T bytes = (dllPath.size() + 1) * sizeof(wchar_t);
    void *remote = VirtualAllocEx(process, nullptr, bytes, MEM_COMMIT | MEM_RESERVE,
                                  PAGE_READWRITE);
    if (!remote) {
        log(format(L"VirtualAllocEx failed (error %lu)", GetLastError()));
        CloseHandle(process);
        return false;
    }
    SIZE_T written = 0;
    if (!WriteProcessMemory(process, remote, dllPath.c_str(), bytes, &written)
            || written != bytes) {
        log(format(L"WriteProcessMemory failed (error %lu)", GetLastError()));
        VirtualFreeEx(process, remote, 0, MEM_RELEASE);
        CloseHandle(process);
        return false;
    }
    HMODULE kernel32 = GetModuleHandleW(L"kernel32.dll");
    LPTHREAD_START_ROUTINE loadLibrary = (LPTHREAD_START_ROUTINE) GetProcAddress(kernel32,
                                                                                 "LoadLibraryW");
    if (!loadLibrary) {
        log(L"kernel32 has no LoadLibraryW");
        VirtualFreeEx(process, remote, 0, MEM_RELEASE);
        CloseHandle(process);
        return false;
    }
    HANDLE thread = CreateRemoteThread(process, nullptr, 0, loadLibrary, remote, 0, nullptr);
    if (!thread) {
        log(format(L"CreateRemoteThread failed (error %lu)", GetLastError()));
        VirtualFreeEx(process, remote, 0, MEM_RELEASE);
        CloseHandle(process);
        return false;
    }
    WaitForSingleObject(thread, 15000);
    DWORD result = 0;
    GetExitCodeThread(thread, &result);
    CloseHandle(thread);
    VirtualFreeEx(process, remote, 0, MEM_RELEASE);
    CloseHandle(process);
    if (result == 0) {
        log(L"LoadLibraryW returned null in the target -- the agent did not load.");
        log(L"Usually an architecture mismatch: this builds x64 and the game must be too.");
        return false;
    }
    return true;
}

/**
 * Deletes the staged files once the game exits.
 *
 * Done from a detached PowerShell process rather than from the agent: the agent
 * lives inside the game, so it cannot clean up the file it was loaded from
 * while it is still mapped.
 */
void spawnReaper(DWORD pid, const std::wstring &dll, const std::wstring &sidecar) {
    std::wstring script =
            L"Wait-Process -Id " + std::to_wstring(pid) + L" -ErrorAction SilentlyContinue;"
            L"$f=@('" + dll + L"','" + sidecar + L"');"
            L"for($i=0;$i -lt 30;$i++){"
            L"Remove-Item -LiteralPath $f -Force -ErrorAction SilentlyContinue;"
            L"if(-not (Test-Path -LiteralPath '" + dll + L"')){break};"
            L"Start-Sleep -Seconds 1}";
    std::wstring command = L"powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden"
                           L" -Command \"" + script + L"\"";
    std::vector<wchar_t> buffer(command.begin(), command.end());
    buffer.push_back(L'\0');
    STARTUPINFOW startup = {sizeof(startup)};
    startup.dwFlags = STARTF_USESHOWWINDOW;
    startup.wShowWindow = SW_HIDE;
    PROCESS_INFORMATION info = {};
    if (CreateProcessW(nullptr, buffer.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW,
                       nullptr, nullptr, &startup, &info)) {
        CloseHandle(info.hThread);
        CloseHandle(info.hProcess);
    }
}

}  // namespace

bool runInjection(DWORD pid, const std::wstring &loaderPath, const Logger &log) {
    const void *agentBytes = nullptr;
    DWORD agentSize = 0;
    if (!readEmbeddedAgent(&agentBytes, &agentSize)) {
        log(L"this loader has no agent built into it");
        return false;
    }
    log(format(L"agent: %lu bytes", (unsigned long) agentSize));

    if (!is64Bit(pid)) {
        log(format(L"process %lu is 32-bit and this agent is x64 -- they cannot mix.", pid));
        return false;
    }

    // The agent unpacks the client jar itself, from its own resources, and
    // looks for a sidecar next to itself. Staging it under a name that includes
    // the pid keeps two clients from sharing one file.
    std::wstring dll;
    if (!stageAgent(agentBytes, agentSize, loaderPath, pid, log, dll)) {
        return false;
    }

    log(format(L"injecting into %lu", pid));
    if (!inject(pid, dll, log)) {
        DeleteFileW(dll.c_str());
        return false;
    }
    spawnReaper(pid, dll, dll + L".txt");
    log(format(L"loaded (agent %s)", dll.c_str()));
    log(L"the client writes its log next to that agent, as openraven-native.log");
    return true;
}
