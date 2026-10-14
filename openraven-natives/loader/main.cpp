#include <windows.h>

#include <cstdio>
#include <string>
#include <vector>

#include "inject.h"
#include "target.h"

namespace {

void log(const std::wstring &line) {
    // The loader is a console program, so its output goes to the console. The
    // client's own log is the file the agent writes; this is only about what
    // the injector did.
    fwprintf(stdout, L"%s\n", line.c_str());
    fflush(stdout);
}

std::wstring narrow(const std::wstring &text, size_t limit) {
    return text.size() <= limit ? text : text.substr(0, limit) + L"...";
}

/** Attaches to the console that started this process, for a double-click run. */
void attachConsole() {
    if (AttachConsole(ATTACH_PARENT_PROCESS)) {
        FILE *ignored = nullptr;
        freopen_s(&ignored, "CONOUT$", "w", stdout);
        freopen_s(&ignored, "CONOUT$", "w", stderr);
    }
}

/** This executable's own path, which decides where the agent gets written. */
std::wstring ownPath() {
    wchar_t buffer[MAX_PATH];
    if (!GetModuleFileNameW(nullptr, buffer, MAX_PATH)) {
        return L"";
    }
    return buffer;
}

int run(DWORD explicitPid) {
    std::wstring loaderPath = ownPath();
    DWORD pid = explicitPid;
    if (pid == 0) {
        std::vector<Candidate> all = findCandidates();
        log(L"java processes:");
        if (all.empty()) {
            log(L"  (none running)");
        }
        const Candidate *best = nullptr;
        int ties = 0;
        for (const Candidate &candidate : all) {
            log(L"  " + narrow(candidate.commandLine, 90));
            if (candidate.score <= 0) {
                continue;
            }
            if (!best || candidate.score > best->score) {
                best = &candidate;
                ties = 1;
            } else if (candidate.score == best->score) {
                ties++;
            }
        }
        if (!best) {
            log(L"none of these look like Minecraft -- start the game first");
            return 2;
        }
        if (ties > 1) {
            log(L"several processes look equally likely, so none was chosen.");
            log(L"run again with the pid: openraven_loader.exe <pid>");
            return 2;
        }
        pid = best->pid;
        log(L"chosen: " + std::to_wstring(pid));
    }
    return runInjection(pid, loaderPath, log) ? 0 : 1;
}

}  // namespace

int wmain(int argc, wchar_t **argv) {
    if (argc > 1 && (wcscmp(argv[1], L"--help") == 0 || wcscmp(argv[1], L"-h") == 0)) {
        attachConsole();
        log(L"openraven_loader [pid]");
        log(L"");
        log(L"Without a pid, every running Java process is listed and the one that");
        log(L"looks most like Minecraft is used. With a pid, that process is used.");
        log(L"");
        log(L"Start the game and get to the main menu before running this.");
        return 0;
    }
    if (argc > 1) {
        attachConsole();
        return run((DWORD) _wtoi(argv[1]));
    }
    // No argument: this was probably double-clicked, so keep the window open
    // long enough to read the result.
    attachConsole();
    int code = run(0);
    log(L"");
    log(L"press enter to close");
    getchar();
    return code;
}
