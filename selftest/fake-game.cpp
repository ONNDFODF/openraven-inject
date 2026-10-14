/*
 * A stand-in for a running Minecraft client.
 *
 * Creates a real JVM with the real mapped Minecraft jar on its class path, then
 * sleeps. That is enough for the injector to find: a jvm.dll, a JVM that
 * JNI_GetCreatedJavaVMs returns, and net.minecraft.client.Minecraft among the
 * loaded classes, which is what the agent looks for when it picks a class
 * loader.
 *
 * Running the injector against this exercises the whole chain -- finding the
 * VM, the JVMTI environment, unpacking the embedded jar, putting it in front of
 * the game's loader, binding NativeBridge, arming ClassFileLoadHook and
 * retransforming the already-loaded hooks -- without needing the game.
 *
 * Usage: fake_game.exe <jar> [more jars...]
 */
#include <windows.h>

#include <jni.h>

#include <stdio.h>
#include <string.h>

int main(int argc, char **argv) {
    if (argc < 2) {
        printf("usage: fake_game.exe <jar> [more jars...]\n");
        return 2;
    }
    // A main class that exists in any JDK, so the VM starts without needing one
    // of ours. What matters is the class path, not what runs.
    //
    // Several jars, because the client needs both Minecraft and Forge on the
    // path before anything of its own will link: the bootstrap names
    // MinecraftForge, and without it the injection succeeds and the client then
    // throws NoClassDefFoundError, which looks like an injection failure and is
    // not one.
    static char classpath[8192];
    int used = snprintf(classpath, sizeof(classpath), "-Djava.class.path=");
    for (int i = 1; i < argc && used > 0 && (size_t) used < sizeof(classpath); i++) {
        used += snprintf(classpath + used, sizeof(classpath) - used, "%s%s",
                         i == 1 ? "" : ";", argv[i]);
    }

    JavaVMOption options[1];
    options[0].optionString = classpath;
    JavaVMInitArgs args;
    args.version = JNI_VERSION_1_6;
    args.nOptions = 1;
    args.options = options;
    args.ignoreUnrecognized = JNI_FALSE;

    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    jint created = JNI_CreateJavaVM(&vm, (void **) &env, &args);
    if (created != JNI_OK) {
        printf("JNI_CreateJavaVM failed: %d\n", created);
        return 1;
    }
    printf("jvm created, pid %lu\n", (unsigned long) GetCurrentProcessId());
    fflush(stdout);

    // Prove the game class is really on this loader: this is the class the
    // agent looks for by name. It cannot be initialised without the rest of the
    // game, so it is only looked up, not used.
    jclass minecraft = env->FindClass("net/minecraft/client/Minecraft");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        printf("net/minecraft/client/Minecraft NOT found -- check the jar path\n");
    } else {
        printf("net/minecraft/client/Minecraft found: %p\n", (void *) minecraft);
    }
    fflush(stdout);

    // Where the client's own log will land, and the first thing to check when
    // it does not appear: an embedded JVM does not get the launcher's
    // java.io.tmpdir, so it can be somewhere unhelpful or unset entirely.
    {
        jclass systemClass = env->FindClass("java/lang/System");
        jmethodID getProperty = env->GetStaticMethodID(
                systemClass, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;");
        jstring key = env->NewStringUTF("java.io.tmpdir");
        jstring value = (jstring) env->CallStaticObjectMethod(systemClass, getProperty, key);
        if (value && !env->ExceptionCheck()) {
            const char *text = env->GetStringUTFChars(value, nullptr);
            printf("java.io.tmpdir = %s\n", text ? text : "(unreadable)");
            if (text) {
                env->ReleaseStringUTFChars(value, text);
            }
        } else {
            env->ExceptionClear();
            printf("java.io.tmpdir is not set -- the client's log will go somewhere odd\n");
        }
        fflush(stdout);
    }

    printf("sleeping, inject now\n");
    fflush(stdout);
    for (int i = 0; i < 600; i++) {
        Sleep(500);
    }
    vm->DestroyJavaVM();
    return 0;
}
