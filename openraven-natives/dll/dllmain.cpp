/*
 * The injected agent.
 *
 * Loaded into a running game by openraven_loader.exe through LoadLibraryW.
 * Finds the JVM already in the process, asks it for a JVMTI environment, and
 * installs a ClassFileLoadHook -- the mechanism a profiler or a debugger uses.
 *
 * The structure follows InjectMyau's myau-natives/dll: a plain C++ DLL on the
 * standard toolchain, std::string and std::vector where they help, a log file
 * written through the CRT's file API, and a worker thread started from DllMain
 * because DllMain itself runs under the loader lock. That shape is known to
 * work when injected, which is worth more here than any cleverness.
 *
 * The division of labour: this file does what has to be native (finding the
 * JVM, the JVMTI calls, putting a jar in front of the game's class loader) and
 * xyz.daikirai.inject.Bridge does what is easier in Java (which classes to
 * rewrite, what to insert, where to report it). There is no Mixin here and no
 * tweaker: the rewriting is Java, driven by the table in InjectHooks.
 */

#include <windows.h>
#include <jni.h>
#include <jvmti.h>

#include <cstdarg>
#include <cstdio>
#include <string>
#include <vector>

namespace {

constexpr int IDR_EMBEDDED_JAR_SRG = 101;
constexpr int IDR_EMBEDDED_JAR_MCP = 102;
constexpr int IDR_EMBEDDED_JAR_NOTCH = 103;

JavaVM *g_vm = nullptr;
jvmtiEnv *g_jvmti = nullptr;
jclass g_bridge = nullptr;
jmethodID g_transform = nullptr;
HMODULE g_module = nullptr;
std::wstring g_dllDir;
std::wstring g_logPath;

std::string toUtf8(const std::wstring &text);

void logf(const char *format, ...) {
    char message[2048];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);

    // Raw Win32 rather than fopen/fprintf. This is called from DllMain, which
    // runs before the CRT's own per-thread state is usable in some hosts, and a
    // stdio failure there is silent -- which looks exactly like the DLL never
    // being entered at all. CreateFileW has no such dependency.
    std::wstring path = g_logPath.empty() ? L"openraven-native.log" : g_logPath;
    HANDLE file = CreateFileW(path.c_str(), FILE_APPEND_DATA,
                              FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_ALWAYS,
                              FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file != INVALID_HANDLE_VALUE) {
        DWORD written = 0;
        WriteFile(file, "[openraven] ", 11, &written, nullptr);
        WriteFile(file, message, (DWORD) strlen(message), &written, nullptr);
        WriteFile(file, "\r\n", 2, &written, nullptr);
        CloseHandle(file);
    }
    OutputDebugStringA(message);
    OutputDebugStringA("\n");
}

// ---------------------------------------------------------------- the JVM

JavaVM *findRunningVm() {
    HMODULE jvmModule = GetModuleHandleW(L"jvm.dll");
    if (!jvmModule) {
        logf("jvm.dll is not loaded in this process -- this is not a Java process");
        return nullptr;
    }
    typedef jint(JNICALL * GetCreatedVms)(JavaVM **, jsize, jsize *);
    GetCreatedVms getCreatedVms =
            (GetCreatedVms) GetProcAddress(jvmModule, "JNI_GetCreatedJavaVMs");
    if (!getCreatedVms) {
        logf("jvm.dll has no JNI_GetCreatedJavaVMs export");
        return nullptr;
    }
    JavaVM *vm = nullptr;
    jsize count = 0;
    if (getCreatedVms(&vm, 1, &count) != JNI_OK || count == 0 || !vm) {
        logf("no JVM has been created in this process yet");
        return nullptr;
    }
    return vm;
}

JNIEnv *attachThread() {
    JNIEnv *env = nullptr;
    if (g_vm->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        JavaVMAttachArgs args;
        args.version = JNI_VERSION_1_6;
        args.name = (char *) "openraven-inject";
        args.group = nullptr;
        if (g_vm->AttachCurrentThread((void **) &env, &args) != JNI_OK) {
            return nullptr;
        }
    }
    return env;
}

/**
 * Reports and clears a pending Java exception.
 *
 * Deliberately not ExceptionDescribe: that writes to the process's stderr, and
 * the process is javaw.exe, which has none -- so the one message that would
 * explain the failure goes nowhere at all. Reading the throwable's toString and
 * putting it in the log is the difference between a diagnosis and a guess.
 */
bool clearPendingException(JNIEnv *env, const char *what) {
    if (!env->ExceptionCheck()) {
        return false;
    }
    jthrowable thrown = env->ExceptionOccurred();
    env->ExceptionClear();

    std::string text = "?";
    if (thrown) {
        jclass throwableClass = env->FindClass("java/lang/Throwable");
        jmethodID toString = throwableClass
                ? env->GetMethodID(throwableClass, "toString", "()Ljava/lang/String;")
                : nullptr;
        jstring rendered = toString
                ? (jstring) env->CallObjectMethod(thrown, toString)
                : nullptr;
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        if (rendered) {
            const char *chars = env->GetStringUTFChars(rendered, nullptr);
            if (chars) {
                text = chars;
                env->ReleaseStringUTFChars(rendered, chars);
            }
        }
        env->DeleteLocalRef(thrown);
    }
    logf("java exception during %s: %s", what, text.c_str());
    return true;
}

std::string classNameOf(JNIEnv *env, jobject object) {
    if (!object) {
        return "null";
    }
    jclass objectClass = env->GetObjectClass(object);
    jclass classClass = env->FindClass("java/lang/Class");
    jmethodID getName = env->GetMethodID(classClass, "getName", "()Ljava/lang/String;");
    jstring name = (jstring) env->CallObjectMethod(objectClass, getName);
    std::string out = "?";
    if (name) {
        const char *chars = env->GetStringUTFChars(name, nullptr);
        if (chars) {
            out = chars;
            env->ReleaseStringUTFChars(name, chars);
        }
        env->DeleteLocalRef(name);
    }
    env->DeleteLocalRef(objectClass);
    env->DeleteLocalRef(classClass);
    return out;
}

/**
 * The loader the game's own classes came from.
 *
 * This matters more than it looks: the client has to be loaded by the same
 * loader as the game, or the classes its callbacks name are not the classes the
 * game is running. Getting it wrong produces a client that links and then
 * silently does nothing.
 *
 * The game's main class name is tried first because it is exact. If it is not
 * there -- some launchers rename it -- the loader holding the most classes wins,
 * which is a guess, but a reliable one.
 */
jobject findGameClassLoader(JNIEnv *env) {
    jint count = 0;
    jclass *classes = nullptr;
    if (g_jvmti->GetLoadedClasses(&count, &classes) != JVMTI_ERROR_NONE || !classes) {
        logf("GetLoadedClasses failed");
        return nullptr;
    }
    jobject byName = nullptr;
    std::vector<jobject> loaders;
    std::vector<int> counts;
    for (jint i = 0; i < count; i++) {
        jobject loader = nullptr;
        if (g_jvmti->GetClassLoader(classes[i], &loader) != JVMTI_ERROR_NONE || !loader) {
            continue;
        }
        if (!byName) {
            char *signature = nullptr;
            if (g_jvmti->GetClassSignature(classes[i], &signature, nullptr) == JVMTI_ERROR_NONE
                    && signature) {
                if (strcmp(signature, "Lnet/minecraft/client/Minecraft;") == 0) {
                    byName = env->NewGlobalRef(loader);
                }
                g_jvmti->Deallocate((unsigned char *) signature);
            }
        }
        bool seen = false;
        for (size_t k = 0; k < loaders.size(); k++) {
            if (env->IsSameObject(loaders[k], loader)) {
                counts[k]++;
                seen = true;
                break;
            }
        }
        if (!seen) {
            loaders.push_back(env->NewGlobalRef(loader));
            counts.push_back(1);
        }
        env->DeleteLocalRef(loader);
    }
    g_jvmti->Deallocate((unsigned char *) classes);

    jobject chosen = nullptr;
    if (byName) {
        logf("game class loader found by name (net.minecraft.client.Minecraft)");
        chosen = byName;
    } else {
        int best = -1;
        for (size_t k = 0; k < loaders.size(); k++) {
            if (best < 0 || counts[k] > counts[best]) {
                best = (int) k;
            }
        }
        for (size_t k = 0; k < loaders.size(); k++) {
            if (counts[k] < 50) {
                continue;
            }
            logf("  loader candidate: %-56s %d classes%s",
                 classNameOf(env, loaders[k]).c_str(), counts[k],
                 (int) k == best ? "   <-- chosen" : "");
        }
        if (best >= 0) {
            chosen = env->NewGlobalRef(loaders[best]);
        }
    }
    for (size_t k = 0; k < loaders.size(); k++) {
        env->DeleteGlobalRef(loaders[k]);
    }
    if (!chosen) {
        logf("no game class loader found");
    }
    return chosen;
}

/**
 * Which naming scheme the game is running with.
 *
 * <p>This decides which of the three client jars is unpacked, so it is asked
 * before anything of ours is loaded, from the game's own classes: Forge renames
 * the game to SRG, so Minecraft.runTick is func_71407_l; Lunar ships MCP names,
 * so it is runTick; and a Badlion client has no net.minecraft.client.Minecraft
 * at all -- its classes are the vanilla obfuscated ones, and the game's main
 * class is called ave.
 *
 * <p>The method test comes first because the class name alone cannot tell SRG
 * from MCP: both call the class net.minecraft.client.Minecraft.
 */
int detectNaming(JNIEnv *env) {
    jint count = 0;
    jclass *classes = nullptr;
    if (g_jvmti->GetLoadedClasses(&count, &classes) != JVMTI_ERROR_NONE || !classes) {
        logf("cannot list loaded classes; assuming the Forge naming");
        return 0;
    }
    jclass minecraft = nullptr;
    for (jint i = 0; i < count && !minecraft; i++) {
        char *signature = nullptr;
        if (g_jvmti->GetClassSignature(classes[i], &signature, nullptr) == JVMTI_ERROR_NONE
                && signature) {
            if (strcmp(signature, "Lnet/minecraft/client/Minecraft;") == 0) {
                minecraft = classes[i];
            }
            g_jvmti->Deallocate((unsigned char *) signature);
        }
    }
    int naming = 2;  // notch: no such class at all
    if (minecraft) {
        naming = 0;  // srg until a method name says otherwise
        jint methods = 0;
        jmethodID *ids = nullptr;
        if (g_jvmti->GetClassMethods(minecraft, &methods, &ids) == JVMTI_ERROR_NONE && ids) {
            for (jint i = 0; i < methods; i++) {
                char *name = nullptr;
                char *signature = nullptr;
                char *generic = nullptr;
                if (g_jvmti->GetMethodName(ids[i], &name, &signature, &generic)
                        != JVMTI_ERROR_NONE || !name) {
                    continue;
                }
                if (strcmp(name, "runTick") == 0) {
                    naming = 1;  // mcp
                }
                g_jvmti->Deallocate((unsigned char *) name);
                if (signature) {
                    g_jvmti->Deallocate((unsigned char *) signature);
                }
                if (generic) {
                    g_jvmti->Deallocate((unsigned char *) generic);
                }
            }
            g_jvmti->Deallocate((unsigned char *) ids);
        }
    }
    g_jvmti->Deallocate((unsigned char *) classes);
    logf("the game is running %s names", naming == 1 ? "mcp" : naming == 2 ? "notch" : "srg");
    return naming;
}

/**
 * Puts the client jar in front of the game's class loader.
 *
 * Two routes, because launchers differ: the system loader takes a path through
 * JVMTI, a URLClassLoader takes a URL. Everything after this asks the loader
 * for the classes it now has instead of defining them here -- 1.8.9's
 * LaunchClassLoader finds them itself, runs Forge's transformers over them on
 * the way in, and defines them into the namespace that can see Minecraft. A
 * loader that is neither of those is reported rather than worked around.
 */
bool addJarToLoader(JNIEnv *env, jobject loader, const std::wstring &jarPath) {
    std::string utf8Path = toUtf8(jarPath);
    jclass classLoaderClass = env->FindClass("java/lang/ClassLoader");
    jmethodID getSystem = env->GetStaticMethodID(classLoaderClass, "getSystemClassLoader",
                                                 "()Ljava/lang/ClassLoader;");
    jobject systemLoader = getSystem
            ? env->CallStaticObjectMethod(classLoaderClass, getSystem)
            : nullptr;
    clearPendingException(env, "getSystemClassLoader");
    if (systemLoader && env->IsSameObject(loader, systemLoader)) {
        jvmtiError error = g_jvmti->AddToSystemClassLoaderSearch(utf8Path.c_str());
        if (error == JVMTI_ERROR_NONE) {
            logf("jar appended to the system class loader search: %s", utf8Path.c_str());
            return true;
        }
        logf("AddToSystemClassLoaderSearch failed (error %d)", error);
        return false;
    }

    jclass fileClass = env->FindClass("java/io/File");
    jclass uriClass = env->FindClass("java/net/URI");
    jclass urlClassLoaderClass = env->FindClass("java/net/URLClassLoader");
    if (!fileClass || !uriClass || !urlClassLoaderClass) {
        clearPendingException(env, "resolving java.io.File / java.net.URI");
        return false;
    }
    if (!env->IsInstanceOf(loader, urlClassLoaderClass)) {
        logf("game class loader is %s -- neither the system loader nor a URLClassLoader,",
             classNameOf(env, loader).c_str());
        logf("so there is no supported way to put the jar in front of it.");
        return false;
    }
    jmethodID fileCtor = env->GetMethodID(fileClass, "<init>", "(Ljava/lang/String;)V");
    jmethodID toUri = env->GetMethodID(fileClass, "toURI", "()Ljava/net/URI;");
    jmethodID toUrl = env->GetMethodID(uriClass, "toURL", "()Ljava/net/URL;");
    jmethodID addUrl = env->GetMethodID(urlClassLoaderClass, "addURL", "(Ljava/net/URL;)V");
    if (!fileCtor || !toUri || !toUrl || !addUrl) {
        clearPendingException(env, "resolving addURL");
        return false;
    }
    jstring jPath = env->NewStringUTF(utf8Path.c_str());
    jobject file = env->NewObject(fileClass, fileCtor, jPath);
    jobject uri = env->CallObjectMethod(file, toUri);
    jobject url = env->CallObjectMethod(uri, toUrl);
    if (clearPendingException(env, "building the jar URL") || !url) {
        return false;
    }
    env->CallVoidMethod(loader, addUrl, url);
    if (clearPendingException(env, "addURL")) {
        return false;
    }
    logf("jar added to the game class loader: %s", utf8Path.c_str());

    // Nothing else is added anywhere. The jar used to be pushed into the system
    // loader's search path as well, so that the ASM this jar carried could be
    // found despite LaunchClassLoader being parent-first for org.objectweb.asm.
    // That fix caused the next problem: the game's own ASM is on the system
    // path too, the two versions split the package between them, and
    // ClassRemapper handed its API version to a ClassVisitor that had never
    // heard of it. ASM lives under xyz/daikirai/shadow now -- see
    // tools/relocate/Relocate.java -- so the game's loader finds all of it in
    // the one jar, and the system loader never needs to know this jar exists.
    return true;
}

jclass loadThroughLoader(JNIEnv *env, jobject loader, const char *className) {
    jclass loaderClass = env->GetObjectClass(loader);
    jmethodID loadClass = env->GetMethodID(loaderClass, "loadClass",
                                           "(Ljava/lang/String;)Ljava/lang/Class;");
    if (!loadClass) {
        clearPendingException(env, "resolving loadClass");
        return nullptr;
    }
    jstring name = env->NewStringUTF(className);
    jclass found = (jclass) env->CallObjectMethod(loader, loadClass, name);
    if (clearPendingException(env, className) || !found) {
        return nullptr;
    }
    return (jclass) env->NewGlobalRef(found);
}

// ---------------------------------------------------------------- the jar

/** The client jar, built into this DLL. */
std::wstring extractEmbeddedJar(int resource) {
    HRSRC found = FindResourceW(g_module, MAKEINTRESOURCEW(resource),
                                MAKEINTRESOURCEW(10));
    if (!found) {
        logf("no embedded jar resource in this agent (FindResource failed, error %lu)",
             GetLastError());
        return L"";
    }
    HGLOBAL loaded = LoadResource(g_module, found);
    if (!loaded) {
        logf("LoadResource failed (error %lu)", GetLastError());
        return L"";
    }
    const void *bytes = LockResource(loaded);
    DWORD size = SizeofResource(g_module, found);
    if (!bytes || size == 0) {
        logf("the embedded jar resource is empty");
        return L"";
    }

    // The jar is written next to the log, not into %TEMP%: that directory has
    // already been proven writable by the time anything gets here, which is not
    // something a launcher's idea of the temp directory can be relied on for.
    std::wstring directory = g_logPath.empty() ? L"." : g_logPath;
    size_t slash = directory.find_last_of(L'\\');
    directory = (slash == std::wstring::npos) ? L"." : directory.substr(0, slash);

    // Named by a hash of its own contents, not by the pid.
    //
    // This matters more than it sounds. A pid-named jar is reused when the file
    // is already there -- the game holds its jar open, so the write fails and
    // the copy from a previous injection is loaded instead. Inject a fixed
    // build over an old one and the old classes come back, with no sign of why:
    // a ClassNotFoundException for a class that is plainly in the jar, because
    // it is not in *that* jar. A content hash cannot have that problem.
    unsigned int hash = 2166136261u;
    const unsigned char *scan = (const unsigned char *) bytes;
    for (DWORD i = 0; i < size; i++) {
        hash = (hash ^ scan[i]) * 16777619u;
    }
    wchar_t hashText[16];
    for (int nibble = 0; nibble < 8; nibble++) {
        unsigned int value = (hash >> (28 - nibble * 4)) & 0xF;
        hashText[nibble] = (wchar_t) (value < 10 ? L'0' + value : L'a' + value - 10);
    }
    hashText[8] = 0;

    std::wstring path = directory + L"\\openraven_client_" + hashText + L".jar";
    HANDLE handle = CreateFileW(path.c_str(), GENERIC_WRITE, FILE_SHARE_READ, nullptr,
                                CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (handle == INVALID_HANDLE_VALUE) {
        if (GetFileAttributesW(path.c_str()) != INVALID_FILE_ATTRIBUTES) {
            // The game already has this exact build open, which is what happens
            // when the same loader is run twice. Loading it again is correct.
            logf("the jar for this build is already unpacked; reusing it");
            return path;
        }
        logf("cannot create %S (error %lu)", path.c_str(), GetLastError());
        return L"";
    }
    DWORD written = 0;
    BOOL ok = WriteFile(handle, bytes, size, &written, nullptr);
    CloseHandle(handle);
    if (!ok || written != size) {
        logf("short write unpacking the jar (error %lu)", GetLastError());
        DeleteFileW(path.c_str());
        return L"";
    }
    logf("unpacked the embedded jar: %lu bytes to %S", (unsigned long) size, path.c_str());
    return path;
}

/**
 * The jar to load: a sidecar next to the agent if there is one, otherwise the
 * embedded copy.
 *
 * The sidecar is how a client built just now gets tested without rebuilding the
 * loader: it is a small text file holding a path.
 */
std::wstring findClientJar(int naming) {
    std::wstring sidecar = g_dllDir + L"\\openraven_native.txt";
    HANDLE handle = CreateFileW(sidecar.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr,
                                OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (handle != INVALID_HANDLE_VALUE) {
        char buffer[MAX_PATH * 4] = {};
        DWORD read = 0;
        ReadFile(handle, buffer, sizeof(buffer) - 1, &read, nullptr);
        CloseHandle(handle);
        if (read > 0) {
            int size = MultiByteToWideChar(CP_UTF8, 0, buffer, (int) read, nullptr, 0);
            std::wstring path(size, 0);
            MultiByteToWideChar(CP_UTF8, 0, buffer, (int) read, &path[0], size);
            // Trailing whitespace goes, because a file written by hand or by a
            // script that appends a newline is the normal case and the path is
            // about to be handed to the filesystem verbatim. Without this the
            // file that is plainly there reads as missing.
            while (!path.empty()
                   && (path.back() == L'\n' || path.back() == L'\r'
                       || path.back() == L' ' || path.back() == L'\t')) {
                path.pop_back();
            }
            // And a relative path is resolved against the game's working
            // directory, not this DLL's, so it is worth saying so rather than
            // logging the same "not there" line as a typo would.
            if (!path.empty() && GetFileAttributesW(path.c_str()) != INVALID_FILE_ATTRIBUTES) {
                logf("using the jar named by the sidecar");
                return path;
            }
            logf("the sidecar names a jar that is not there: %S "
                 "(it must be an absolute path; a relative one resolves against the game's "
                 "working directory)", path.c_str());
        }
    }
    int resource = IDR_EMBEDDED_JAR_SRG;
    if (naming == 1) {
        resource = IDR_EMBEDDED_JAR_MCP;
    } else if (naming == 2) {
        resource = IDR_EMBEDDED_JAR_NOTCH;
    }
    return extractEmbeddedJar(resource);
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

// ----------------------------------------------------------- the class hook

/**
 * Every class the JVM defines while the hook is on comes through here. The
 * bytes go to NativeBridge.transform and whatever comes back is defined
 * instead. Java's own classes are skipped: they are either already loaded or
 * not ours to touch, and passing them through costs a JNI round trip each.
 */
void JNICALL classFileLoadHook(jvmtiEnv *jvmti, JNIEnv *env, jclass, jobject,
                               const char *name, jobject, jint length,
                               const unsigned char *data, jint *newLength,
                               unsigned char **newData) {
    if (!g_bridge || !g_transform || !name || !env) {
        return;
    }
    if (strncmp(name, "java/", 5) == 0 || strncmp(name, "javax/", 6) == 0
            || strncmp(name, "sun/", 4) == 0 || strncmp(name, "jdk/", 4) == 0
            || strncmp(name, "com/sun/", 8) == 0
            || strncmp(name, "org/objectweb/asm/", 18) == 0) {
        return;
    }
    jbyteArray original = env->NewByteArray(length);
    if (!original) {
        return;
    }
    env->SetByteArrayRegion(original, 0, length, (const jbyte *) data);
    jstring className = env->NewStringUTF(name);
    jbyteArray result = (jbyteArray) env->CallStaticObjectMethod(g_bridge, g_transform,
                                                                 className, original);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(original);
        env->DeleteLocalRef(className);
        return;
    }
    if (result && result != original) {
        jint size = env->GetArrayLength(result);
        unsigned char *buffer = nullptr;
        if (jvmti->Allocate(size, &buffer) == JVMTI_ERROR_NONE && buffer) {
            env->GetByteArrayRegion(result, 0, size, (jbyte *) buffer);
            *newData = buffer;
            *newLength = size;
        }
    }
    env->DeleteLocalRef(original);
    env->DeleteLocalRef(className);
    if (result) {
        env->DeleteLocalRef(result);
    }
}

const char *describe(jvmtiError error) {
    switch (error) {
        case JVMTI_ERROR_INVALID_CLASS_FORMAT:
            return "the rewritten class is malformed";
        case JVMTI_ERROR_FAILS_VERIFICATION:
            return "the rewritten class does not verify";
        case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED:
            return "a method was added, which retransformation forbids";
        case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_SCHEMA_CHANGED:
            return "the fields changed, which retransformation forbids";
        case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_HIERARCHY_CHANGED:
            return "the hierarchy changed, which retransformation forbids";
        case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_DELETED:
            return "a method was removed, which retransformation forbids";
        case JVMTI_ERROR_UNSUPPORTED_VERSION:
            return "unsupported class file version";
        case JVMTI_ERROR_NAMES_DONT_MATCH:
            return "the class name changed";
        case JVMTI_ERROR_UNMODIFIABLE_CLASS:
            return "the class cannot be modified at all";
        default:
            return "refused";
    }
}

/**
 * Catches up on classes defined before the hook was armed.
 *
 * By the time anything can be injected the game has loaded hundreds of classes,
 * including every one the hooks point at. Retransformation is what makes those
 * reachable: the JVM re-runs the load hook for each, so the same rewriting
 * applies.
 */
void JNICALL nativeRetransform(JNIEnv *env, jclass, jobjectArray names) {
    if (!g_jvmti || !names) {
        return;
    }
    jint wanted = env->GetArrayLength(names);
    jint count = 0;
    jclass *classes = nullptr;
    if (g_jvmti->GetLoadedClasses(&count, &classes) != JVMTI_ERROR_NONE || !classes) {
        return;
    }
    std::vector<jclass> targets;
    for (jint w = 0; w < wanted; w++) {
        jstring name = (jstring) env->GetObjectArrayElement(names, w);
        const char *chars = env->GetStringUTFChars(name, nullptr);
        if (!chars) {
            continue;
        }
        std::string signature = "L";
        for (const char *c = chars; *c; c++) {
            signature += (*c == '.') ? '/' : *c;
        }
        signature += ';';
        env->ReleaseStringUTFChars(name, chars);
        for (jint i = 0; i < count; i++) {
            char *actual = nullptr;
            if (g_jvmti->GetClassSignature(classes[i], &actual, nullptr) != JVMTI_ERROR_NONE
                    || !actual) {
                continue;
            }
            bool hit = signature == actual;
            g_jvmti->Deallocate((unsigned char *) actual);
            if (hit) {
                targets.push_back(classes[i]);
                break;
            }
        }
    }
    g_jvmti->Deallocate((unsigned char *) classes);
    if (targets.empty()) {
        logf("none of the hook targets are loaded yet -- they will be patched as they load");
        return;
    }
    int patched = 0;
    for (size_t i = 0; i < targets.size(); i++) {
        jvmtiError error = g_jvmti->RetransformClasses(1, &targets[i]);
        if (error == JVMTI_ERROR_NONE) {
            patched++;
            continue;
        }
        char *signature = nullptr;
        const char *shown = "?";
        if (g_jvmti->GetClassSignature(targets[i], &signature, nullptr) == JVMTI_ERROR_NONE
                && signature) {
            shown = signature + 1;
        }
        logf("  REFUSED %s -- %s (%d)", shown, describe(error), error);
        if (signature) {
            g_jvmti->Deallocate((unsigned char *) signature);
        }
    }
    logf("retransformed %d of %d already-loaded classes", patched, (int) targets.size());
}

// -------------------------------------------------------------- bootstrap

DWORD WINAPI bootstrap(LPVOID) {
    logf("---- injected ----");
    g_vm = findRunningVm();
    if (!g_vm) {
        return 0;
    }
    JNIEnv *env = attachThread();
    if (!env) {
        logf("could not attach to the JVM");
        return 0;
    }
    if (g_vm->GetEnv((void **) &g_jvmti, JVMTI_VERSION_1_1) != JNI_OK || !g_jvmti) {
        logf("no JVMTI environment available");
        return 0;
    }
    jvmtiCapabilities capabilities = {};
    capabilities.can_generate_all_class_hook_events = 1;
    capabilities.can_retransform_classes = 1;
    capabilities.can_redefine_classes = 1;
    jvmtiError error = g_jvmti->AddCapabilities(&capabilities);
    if (error != JVMTI_ERROR_NONE) {
        logf("AddCapabilities failed (error %d)", error);
        return 0;
    }
    logf("JVMTI capabilities added");

    int naming = detectNaming(env);
    std::wstring jar = findClientJar(naming);
    if (jar.empty()) {
        logf("no client jar: no sidecar and nothing embedded");
        return 0;
    }
    jobject loader = findGameClassLoader(env);
    if (!loader) {
        return 0;
    }
    // The jar goes in front of the game's loader and nothing else is done with
    // it here: the loader's own loadClass reads the classes out of the jar.
    //
    // This is the part that matters, and the reason is worth writing down.
    // LaunchClassLoader finds what is in its URL list itself, runs Forge's
    // transformers over it, and defines it in the namespace that can see
    // Minecraft -- the same road every Forge mod comes in on. Defining our
    // classes here instead (JNI DefineClass) skips all of that: Forge never
    // sees them, so their MCP member names survive into a game that was
    // renamed to SRG, and the first tick dies on NoSuchMethodError. That is
    // what happened; the client rewrites its own classes now, and it needs to
    // be the loader that defines them for that to be the only rewrite.
    addJarToLoader(env, loader, jar);

    g_bridge = loadThroughLoader(env, loader, "xyz.daikirai.inject.Bridge");
    if (!g_bridge) {
        logf("could not load xyz.daikirai.inject.Bridge through the game's loader");
        return 0;
    }
    logf("Bridge loaded");
    g_transform = env->GetStaticMethodID(g_bridge, "transform", "(Ljava/lang/String;[B)[B");
    if (!g_transform) {
        // Which lookup failed, and what the class actually holds. "Could not
        // resolve" on its own does not distinguish a wrong descriptor from a
        // classloader conflict from a name that something renamed on the way in
        // -- and 1.8.9 Forge does rename things on the way in. Listing the
        // methods is what tells those apart.
        logf("resolving Bridge.transform (Ljava/lang/String;[B)[B failed");
        clearPendingException(env, "resolving transform");
        jmethodID install = env->GetStaticMethodID(g_bridge, "install", "()V");
        logf("  install -> %s", install ? "found" : "missing");
        clearPendingException(env, "probing install");

        // What class did loadClass actually return, and what does it hold?
        // g_bridge is a jclass, so calling getClass() on it answers "what is the
        // class of a Class object" -- which is java.lang.Class and tells us
        // nothing. getName() and getDeclaredMethods() are the ones that answer
        // the real question.
        jclass classClass = env->FindClass("java/lang/Class");
        jmethodID getName = env->GetMethodID(classClass, "getName", "()Ljava/lang/String;");
        jstring className = (jstring) env->CallObjectMethod(g_bridge, getName);
        const char *nameChars = className ? env->GetStringUTFChars(className, nullptr) : nullptr;
        logf("  loaded class: %s", nameChars ? nameChars : "(unreadable)");
        if (nameChars) {
            env->ReleaseStringUTFChars(className, nameChars);
        }

        jmethodID getMethods = env->GetMethodID(classClass, "getDeclaredMethods",
                                               "()[Ljava/lang/reflect/Method;");
        jobjectArray methods = getMethods
                ? (jobjectArray) env->CallObjectMethod(g_bridge, getMethods)
                : nullptr;
        if (clearPendingException(env, "getDeclaredMethods") || !methods) {
            logf("  cannot list the methods on the class");
            return 0;
        }
        jclass methodClass = env->FindClass("java/lang/reflect/Method");
        jmethodID methodName = env->GetMethodID(methodClass, "getName",
                                                "()Ljava/lang/String;");
        jmethodID getParameterTypes = env->GetMethodID(methodClass, "getParameterTypes",
                                                       "()[Ljava/lang/Class;");
        jint count = env->GetArrayLength(methods);
        logf("  the class declares %d methods:", (int) count);
        for (jint i = 0; i < count && i < 40; i++) {
            jobject method = env->GetObjectArrayElement(methods, i);
            jstring methodNameString = (jstring) env->CallObjectMethod(method, methodName);
            const char *chars = methodNameString
                    ? env->GetStringUTFChars(methodNameString, nullptr) : nullptr;
            jobjectArray parameters = getParameterTypes
                    ? (jobjectArray) env->CallObjectMethod(method, getParameterTypes)
                    : nullptr;
            jint parameterCount = parameters ? env->GetArrayLength(parameters) : -1;
            logf("    %s (%d parameters)", chars ? chars : "?", (int) parameterCount);
            if (chars) {
                env->ReleaseStringUTFChars(methodNameString, chars);
            }
        }
        return 0;
    }
    JNINativeMethod natives[] = {
            {(char *) "retransform", (char *) "([Ljava/lang/String;)V",
             (void *) &nativeRetransform}};
    if (env->RegisterNatives(g_bridge, natives, 1) != JNI_OK) {
        // Not fatal: install() and start() are the ones that matter. Without
        // this, classes that were already loaded before the hook went on are
        // not retransformed, so the hooks land on whatever loads next.
        clearPendingException(env, "RegisterNatives");
        logf("native retransform not bound -- already-loaded classes will not be retransformed");
    } else {
        logf("native retransform registered");
    }

    // install() registers the hook table and checks every callback against it
    // before anything is transformed, so a typo in the table is one log line
    // instead of a NoSuchMethodError from inside the render loop.
    //
    // The jar path goes first: our classes were defined rather than loaded from
    // a URL, so they have no CodeSource and cannot find their own jar. Without
    // this, reading it throws NullPointerException and the client decides it has
    // no classes of its own -- which reads as "hooks registered: 0".
    jmethodID setJar = env->GetStaticMethodID(g_bridge, "jar", "(Ljava/lang/String;)V");
    if (setJar) {
        std::string utf8ForJar = toUtf8(jar);
        jstring jJar = env->NewStringUTF(utf8ForJar.c_str());
        env->CallStaticVoidMethod(g_bridge, setJar, jJar);
        clearPendingException(env, "Bridge.jar");
    } else {
        clearPendingException(env, "resolving Bridge.jar");
        logf("this build of the client has no Bridge.jar; it will not find its own jar");
    }

    jmethodID install = env->GetStaticMethodID(g_bridge, "install", "()V");
    if (!install) {
        clearPendingException(env, "resolving NativeBridge.install");
        return 0;
    }
    env->CallStaticVoidMethod(g_bridge, install);
    if (clearPendingException(env, "NativeBridge.install")) {
        return 0;
    }

    jvmtiEventCallbacks callbacks = {};
    callbacks.ClassFileLoadHook = classFileLoadHook;
    if (g_jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks)) != JVMTI_ERROR_NONE
            || g_jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                                 JVMTI_EVENT_CLASS_FILE_LOAD_HOOK,
                                                 nullptr) != JVMTI_ERROR_NONE) {
        logf("could not enable ClassFileLoadHook");
        return 0;
    }
    logf("ClassFileLoadHook active");

    jmethodID retransformLoaded = env->GetStaticMethodID(g_bridge, "retransformLoaded", "()V");
    if (retransformLoaded) {
        env->CallStaticVoidMethod(g_bridge, retransformLoaded);
        clearPendingException(env, "NativeBridge.retransformLoaded");
    }
    jmethodID start = env->GetStaticMethodID(g_bridge, "start", "()V");
    if (start) {
        env->CallStaticVoidMethod(g_bridge, start);
        clearPendingException(env, "NativeBridge.start");
    }
    logf("---- installed ----");
    return 0;
}

}  // namespace

/**
 * Decides where the log goes, by trying to write in each candidate and keeping
 * the first that works.
 *
 * Not a detail worth leaving to a guess: GetTempPathW returns whatever the
 * target's environment says, and for a game started by a launcher that is often
 * a path the game cannot write to -- an 8.3 short name under a redirected
 * profile, or a directory owned by a different user. When it is, every log line
 * is silently dropped and the agent looks like it never ran. Next to the DLL is
 * tried first because that directory has to be writable already: the loader put
 * the DLL there.
 */
static void chooseLogPath() {
    wchar_t modulePath[MAX_PATH];
    if (GetModuleFileNameW(g_module, modulePath, MAX_PATH)) {
        std::wstring full = modulePath;
        size_t slash = full.find_last_of(L'\\');
        g_dllDir = (slash == std::wstring::npos) ? L"." : full.substr(0, slash);
    }

    std::vector<std::wstring> candidates;
    if (!g_dllDir.empty()) {
        candidates.push_back(g_dllDir + L"\\openraven-native.log");
    }
    wchar_t tempDir[MAX_PATH];
    if (GetTempPathW(MAX_PATH, tempDir)) {
        std::wstring inTemp = std::wstring(tempDir) + L"openraven-native.log";
        if (candidates.empty() || candidates[0] != inTemp) {
            candidates.push_back(inTemp);
        }
    }
    // Last resort: next to the loader is not reachable from here, but the
    // system temp is, and it is writable by everyone on a normal install.
    wchar_t windowsDir[MAX_PATH];
    if (GetWindowsDirectoryW(windowsDir, MAX_PATH)) {
        candidates.push_back(std::wstring(windowsDir) + L"\\Temp\\openraven-native.log");
    }

    for (size_t i = 0; i < candidates.size(); i++) {
        HANDLE probe = CreateFileW(candidates[i].c_str(), FILE_APPEND_DATA,
                                   FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_ALWAYS,
                                   FILE_ATTRIBUTE_NORMAL, nullptr);
        if (probe != INVALID_HANDLE_VALUE) {
            CloseHandle(probe);
            g_logPath = candidates[i];
            return;
        }
    }
    g_logPath.clear();
}

BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID) {
    if (reason != DLL_PROCESS_ATTACH) {
        return TRUE;
    }
    DisableThreadLibraryCalls(module);
    g_module = module;
    chooseLogPath();

    // Written here, synchronously, before the worker thread starts. If the DLL
    // is mapped but this line is missing, the entry point never ran; if it is
    // there with nothing after it, the thread started and something in it
    // failed. Two very different problems, and this is what tells them apart.
    logf("---- loaded into pid %lu ----", (unsigned long) GetCurrentProcessId());
    logf("log file: %S", g_logPath.empty() ? L"(nowhere writable)" : g_logPath.c_str());

    // DllMain runs under the loader lock: nothing that loads another library or
    // waits on a lock may happen here, so the work goes on its own thread.
    HANDLE thread = CreateThread(nullptr, 0, bootstrap, nullptr, 0, nullptr);
    if (thread) {
        CloseHandle(thread);
    } else {
        logf("CreateThread failed (error %lu)", GetLastError());
    }
    return TRUE;
}
