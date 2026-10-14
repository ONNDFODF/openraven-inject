#pragma once

#include <windows.h>

#include <string>
#include <vector>

/** A running java.exe/javaw.exe that looks like a game, and why. */
struct Candidate {
    DWORD pid;
    std::wstring commandLine;
    int score;
};

/**
 * Every running Java process, scored by how much its command line looks like a
 * Minecraft client.
 *
 * Score rather than a yes/no answer because the decision is made by the caller:
 * a single clear winner is injected into, and a tie is refused rather than
 * guessed at.
 */
std::vector<Candidate> findCandidates();

/** True when the process is 64-bit, which the x64 agent requires. */
bool is64Bit(DWORD pid);
