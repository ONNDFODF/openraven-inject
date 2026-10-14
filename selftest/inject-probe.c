/*
 * A 64-bit injector for testing, so the address handed to the remote thread is
 * from the same architecture as the target.
 *
 * This is the mistake a 32-bit PowerShell injector makes: GetProcAddress in a
 * 32-bit PowerShell returns an address in 32-bit kernel32, and passing that to a
 * 64-bit process is an access violation before LoadLibraryW is ever entered --
 * which looks exactly like "the injected DLL crashes on load".
 *
 * Same three steps the real loader takes, so it tests the same mechanism:
 * OpenProcess, write the path, CreateRemoteThread(LoadLibraryW).
 *
 * Usage: inject-probe.exe <pid> <dll path>
 */
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>

int main(int argc, char **argv) {
    if (argc < 3) {
        printf("usage: inject64.exe <pid> <dll path>\n");
        return 2;
    }
    DWORD pid = (DWORD) atoi(argv[1]);
    const char *path = argv[2];

    printf("this injector is %s\n", sizeof(void *) == 8 ? "64-bit" : "32-bit");

    HANDLE process = OpenProcess(PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION
                                         | PROCESS_VM_OPERATION | PROCESS_VM_WRITE
                                         | PROCESS_VM_READ,
                                 FALSE, pid);
    if (process == NULL) {
        printf("OpenProcess failed: %lu\n", GetLastError());
        return 1;
    }
    int wideCount = MultiByteToWideChar(CP_ACP, 0, path, -1, NULL, 0);
    SIZE_T bytes = (SIZE_T) wideCount * sizeof(wchar_t);
    void *remote = VirtualAllocEx(process, NULL, bytes, MEM_COMMIT | MEM_RESERVE,
                                  PAGE_READWRITE);
    if (remote == NULL) {
        printf("VirtualAllocEx failed: %lu\n", GetLastError());
        return 1;
    }
    wchar_t *wide = (wchar_t *) malloc(bytes);
    MultiByteToWideChar(CP_ACP, 0, path, -1, wide, wideCount);
    SIZE_T written = 0;
    if (!WriteProcessMemory(process, remote, wide, bytes, &written)) {
        printf("WriteProcessMemory failed: %lu\n", GetLastError());
        return 1;
    }
    free(wide);
    printf("wrote %llu bytes to %p in pid %lu\n",
           (unsigned long long) written, remote, (unsigned long) pid);

    HMODULE kernel32 = GetModuleHandleW(L"kernel32.dll");
    FARPROC loadLibrary = GetProcAddress(kernel32, "LoadLibraryW");
    printf("kernel32 at %p, LoadLibraryW at %p\n", (void *) kernel32, (void *) loadLibrary);

    DWORD threadId = 0;
    HANDLE thread = CreateRemoteThread(process, NULL, 0,
                                       (LPTHREAD_START_ROUTINE) loadLibrary, remote, 0,
                                       &threadId);
    if (thread == NULL) {
        printf("CreateRemoteThread failed: %lu\n", GetLastError());
        return 1;
    }
    WaitForSingleObject(thread, 15000);
    DWORD result = 0;
    GetExitCodeThread(thread, &result);
    printf("LoadLibraryW returned 0x%08lX", result);
    if (result != 0) {
        printf("  (loaded, module handle in the target)");
    } else {
        printf("  (failed; GetLastError in the target was not readable from here)");
    }
    printf("\n");

    VirtualFreeEx(process, remote, 0, MEM_RELEASE);
    CloseHandle(thread);
    CloseHandle(process);
    return result != 0 ? 0 : 1;
}
