#include "target.h"

#include <psapi.h>
#include <tlhelp32.h>

#include <algorithm>

namespace {

std::wstring lower(std::wstring text) {
    std::transform(text.begin(), text.end(), text.begin(), towlower);
    return text;
}

bool contains(const std::wstring &haystack, const wchar_t *needle) {
    return haystack.find(needle) != std::wstring::npos;
}

/**
 * How much this command line looks like a Minecraft client.
 *
 * The distinguishing part is what the launcher passes on the command line:
 * every launcher names the game jar, the main class or the version in some
 * form, and none of them agree on the rest. Zero means "not a candidate".
 */
int scoreFor(const std::wstring &commandLineLower) {
    int score = 0;
    if (contains(commandLineLower, L"net.minecraft.client.main.main")) {
        score += 100;
    }
    if (contains(commandLineLower, L"minecraft")) {
        score += 40;
    }
    if (contains(commandLineLower, L"1.8.9")) {
        score += 20;
    }
    if (contains(commandLineLower, L"--version")) {
        score += 10;
    }
    if (contains(commandLineLower, L"--accessToken")) {
        score += 10;
    }
    if (contains(commandLineLower, L"--assetsdir")) {
        score += 10;
    }
    if (contains(commandLineLower, L"launcher")) {
        score += 5;
    }
    return score;
}

std::wstring commandLineOf(HANDLE process) {
    // Read the command line as a sequence of wide strings rather than through
    // one fixed buffer: they are long, and a truncated command line scores
    // wrong in a way that is hard to see.
    std::wstring commandLine;
    PVOID buffer = nullptr;
    ULONG size = 0;
    HMODULE ntdll = GetModuleHandleW(L"ntdll.dll");
    if (!ntdll) {
        return commandLine;
    }
    typedef NTSTATUS(NTAPI * QueryInformationProcess)(HANDLE, ULONG, PVOID, ULONG, PULONG);
    QueryInformationProcess query = (QueryInformationProcess) GetProcAddress(
            ntdll, "NtQueryInformationProcess");
    if (!query || query(process, 60 /* ProcessCommandLineInformation */, nullptr, 0, &size)
            != 0xC0000004L /* STATUS_INFO_LENGTH_MISMATCH */) {
        return commandLine;
    }
    buffer = HeapAlloc(GetProcessHeap(), 0, size);
    if (!buffer) {
        return commandLine;
    }
    if (query(process, 60, buffer, size, &size) == 0) {
        typedef struct {
            ULONG length;
            ULONG maximumLength;
            PWSTR text;
        } UnicodeString;
        typedef struct {
            ULONG length;
            ULONG maximumLength;
            PVOID text;
        } CommandLine;
        CommandLine *line = (CommandLine *) buffer;
        if (line->text && line->length > 0) {
            commandLine.assign((const wchar_t *) line->text,
                               line->length / sizeof(wchar_t));
        }
    }
    HeapFree(GetProcessHeap(), 0, buffer);
    return commandLine;
}

bool exeNameMatches(const std::wstring &exeLower) {
    return exeLower.size() >= 4
            && (exeLower.compare(exeLower.size() - 4, 4, L".exe") == 0)
            && (contains(exeLower, L"java.exe") || contains(exeLower, L"javaw.exe"));
}

}  // namespace

std::vector<Candidate> findCandidates() {
    std::vector<Candidate> found;
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snapshot == INVALID_HANDLE_VALUE) {
        return found;
    }
    PROCESSENTRY32W entry = {};
    entry.dwSize = sizeof(entry);
    if (Process32FirstW(snapshot, &entry)) {
        do {
            std::wstring exe = lower(entry.szExeFile);
            if (!exeNameMatches(exe)) {
                continue;
            }
            HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE,
                                         entry.th32ProcessID);
            if (!process) {
                continue;
            }
            std::wstring commandLine = commandLineOf(process);
            CloseHandle(process);

            Candidate candidate;
            candidate.pid = entry.th32ProcessID;
            candidate.commandLine = commandLine;
            candidate.score = scoreFor(lower(commandLine));
            found.push_back(candidate);
        } while (Process32NextW(snapshot, &entry));
    }
    CloseHandle(snapshot);
    return found;
}

bool is64Bit(DWORD pid) {
    HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (!process) {
        // Say yes so the caller reports the failure it actually hit, rather
        // than blaming the architecture for a permissions problem.
        return true;
    }
    BOOL wow64 = FALSE;
    IsWow64Process(process, &wow64);
    CloseHandle(process);
    return !wow64;
}
