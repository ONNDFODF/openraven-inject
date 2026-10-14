# OpenRaven-Inject

Raven B4 loaded into a Minecraft 1.8.9 client that is already running, instead of
installed as a mod — and not only Forge: the same client runs inside Lunar and
Badlion, neither of which has a `mods` folder to drop anything into.

A native loader attaches to the running game and the class rewriting happens in
Java, at class-load time, through JVMTI. There is no Mixin at runtime: the hook
table is data, the transformer is code, and every injection point is checked
against the class the game actually hands over.

```
openraven-inject/
├── dist/                      what you run and what gets injected
│   ├── openraven_loader.exe   the injector, already built. Run this one.
│   ├── openraven_agent.dll    the agent it stages (also what tools/launch stages)
│   ├── OpenRaven-Inject.jar        srg    — Forge 1.8.9
│   ├── OpenRaven-Inject-mcp.jar    mcp    — Lunar
│   └── OpenRaven-Inject-notch.jar  notch  — Badlion
├── OpenRaven-Inject/          the client (Java, Gradle, Forge 1.8.9 names)
│   ├── src/main/java/xyz/daikirai/         the client, shared with the Mixin build
│   ├── src/main/java/xyz/daikirai/inject/  the injection — new here
│   └── src/shim/java/net/minecraftforge/   the Forge-shaped shim, mcp and notch only
├── openraven-natives/         the loader and the agent (C++, CMake, Windows)
├── selftest/                  the offline test rigs (no game needed)
├── tools/                     mapgen, checkrefs, bytecode, classdump, launch, reobf, relocate
└── build.cmd                  rebuilds everything, offline
```

Everything below this line assumes a checkout and a Windows command prompt; there
is nothing to install beyond a JDK 17 to build with and the game to test against.

## Running it

Start the game, get to the main menu, then run the loader from a normal command
prompt:

```
dist\openraven_loader.exe
```

It lists the running Java processes, picks the one that looks like Minecraft, and
attaches. With a pid it uses that one instead. A tie between two equally likely
processes is refused rather than guessed at.

Two things to know before you try:

- **It has to run outside an agent sandbox.** A restricted child process cannot
  open or even enumerate the game's process, and the loader then reports "no java
  processes" while the game is plainly running. It says so rather than failing
  silently, which is the most that can be done from inside one.
- **The client is built on the first tick that has a world.** Injecting at the
  main menu is correct; it comes up when you load a world.

In game, **RShift** opens the ClickGui.

### The one command worth knowing

`tools\launch\run.ps1` does the whole loop — find the game, pick the jar for the
client that is actually running, inject, and print the lines that answer "did it
work":

```
powershell -ExecutionPolicy Bypass -File tools\launch\run.ps1
powershell -ExecutionPolicy Bypass -File tools\launch\run.ps1 -Launch -Restart -Build -Follow
```

| switch | what it adds |
|---|---|
| `-Launch` | start Forge 1.8.9 (Lunar and Badlion are their own launchers) and wait for its pid |
| `-Restart` | close a running game first — **this is the one to use after a client change** |
| `-Build` | rebuild the client jar for the detected naming before injecting |
| `-Follow` | keep printing the log until Ctrl+C |
| `-TargetPid <n>` | inject into this pid instead of searching |

Two things about it are worth knowing because they are the two ways this loop
wastes an hour:

- **The naming is read off the game's own command line**, before anything is
  copied, so `-Build` builds the jar for the process that is running rather than
  for the one that was expected.
- **It writes the sidecar.** The agent looks for `openraven_native.txt` next to
  itself and, if it is there, injects the jar that file names instead of the copy
  embedded in the DLL. So a client change needs `build.cmd client` and nothing
  else — no relink, no loader rebuild. Without it, every client change would need
  `build.cmd native` as well, and a `build.cmd native` that silently fails to
  refresh `dist\openraven_agent.dll` is a fix that is not in the game (see
  "Building" below).

It also tries the loader first and, when that is refused with error 5, retries the
same five calls from PowerShell — the workaround below, applied automatically.

### When the loader is refused

On a machine with a security product the loader can be refused at `OpenProcess`
("cannot open process &lt;pid&gt; (error 5)") while `powershell.exe` doing the same
five calls is not. `tools\launch\inject.ps1 -TargetPid <pid>` is that path: same
sequence as `inject.cpp`, same staged-DLL naming, so the agent's log lands in
`dist\` as usual. It is not a replacement for the loader; it is what to run when
the loader says it cannot open the process. `run.ps1` does this by itself.

The log is written next to the agent it staged — `openraven-native.log`, in the
loader's own directory, or in the game's `java.io.tmpdir`. It records every step
and every hook that failed to install, and it is the first place to look.

Injecting twice does nothing the second time: the classes are already loaded and
will not be read again. **Restart the game to pick up a new build** — and note
that this means a rebuilt agent cannot be tested against a running game, which is
the single most common way to spend an hour chasing a fix that is not in the
build you are testing. Check the deployed jar's size against `dist\OpenRaven-Inject-<naming>.jar`
before believing a live result.

## Where the three clients stand

| | Forge 1.8.9 | Lunar 1.8.9 | Badlion 1.8.9 |
|---|---|---|---|
| naming | `srg` | `mcp` | `notch` |
| jar | `OpenRaven-Inject.jar` | `OpenRaven-Inject-mcp.jar` | `OpenRaven-Inject-notch.jar` |
| picked by | `func_71407_l` present | `runTick` present | no `net.minecraft.client.Minecraft` |
| injection | works | works | works |
| hook placement | 32/33 | 32/33 | 32/33 |
| client starts | yes | yes, with the shim | yes, with the shim |
| inbound packets | works | works — verified with 11 distinct `play.server` classes | **does not** — see below |

The one hook that does not place on any of the three is
`EntityRenderer.orientCamera`'s camera clip; each client rewrites that method and
the call it wants is not in what is left. It is reported, not silent.

### Badlion's inbound pipeline

On Badlion the client stops routing received play packets through
`NetworkManager.channelRead0` after the login handshake. The hook is on the
method, the method is reached — measured, both with the invoke replacement and
with a separate `HEAD` hook that only counts, on the same packets:

```
channelRead0 reached: the method is called after login (jr)
inbound: jr        <- C00Handshake
inbound: jq
inbound: jh        <- S01PacketEncryptionRequest
inbound: ji        <- S03PacketEnableCompression
inbound: jg        <- S02PacketLoginSuccess
---- client started ----
                   (and nothing after that, for the whole session)
```

The same build on Lunar, in the same run:

```
inbound: net.minecraft.network.play.server.S32PacketConfirmTransaction
inbound: net.minecraft.network.play.server.S3EPacketTeams
inbound: net.minecraft.network.play.server.S14PacketEntity$S15PacketEntityRelMove
inbound: net.minecraft.network.play.server.S12PacketEntityVelocity
inbound: net.minecraft.network.play.server.S18PacketEntityTeleport
...
```

So this is a property of the client, not of the hook. It costs Badlion every
module that reads the server — `AntiKnockback`, `Velocity`, `Backtrack` — while
`Lag Range`, which only holds outbound packets, is unaffected. Hooking Badlion's
own inbound layer would mean finding it at runtime; it is not in the obfuscation
table this build resolves names through.

## Building

```
build.cmd                    everything: srg client, then the loader
build.cmd client srg|mcp|notch   one client jar
build.cmd native             the loader, needs all three jars already built
```

No network access is needed. The MCP-named Minecraft and Forge jars, LWJGL, Guava
and ASM are read out of the Gradle cache a previous Loom build populated, so this
bypasses Gradle and Loom entirely. Edit `JAVA_HOME` and `VCVARS` at the top of
`build.cmd` if they live somewhere else.

`build.cmd` fails the build on two things, and both are worth keeping:

- **an unresolved reference** (`tools/checkrefs`), which is the guard for the rule
  that a rename is only correct if the class it lands on has the member;
- **a required hook that will not place** (`tools/bytecode`), which is the guard
  for the hook table, a list of strings no compiler checks.

The CMake path works too and is what a Linux or macOS port would start from:

```
cd openraven-natives
cmake -S . -B build -G "Visual Studio 17 2022" -A x64 -DJAVA_INCLUDE="<jdk>/include"
cmake --build build --config Release
```

## The three ways this is checked, and what each one can see

| | what it proves | cannot see |
|---|---|---|
| `selftest/run-selftest.ps1` | the transformer produces valid bytes for every hook shape — 63 checks over compiled fixtures, each one verified, loaded and called | anything about the game's real classes |
| `tools/bytecode` | every hook in the table still lands on the game's own classes for a given naming, and what the instruction next to it is | whether the client still *calls* what it lands on |
| `tools/classdump` | one dumped class through the transformer, with ASM's verifier over the result | anything but the class it is given |

The middle one runs after every client build and is the reason a table entry that
has rotted is a build failure rather than a module that quietly does nothing. It
resolves names exactly as the running transformer does — `MappingBridge` and the
same table — so the offline answer and the game's answer are the same answer.

`selftest/` also holds the two injection rigs: `fake-game.cpp` is a real JVM with
the real mapped Minecraft jar on its class path, enough for the whole native chain
to run (find the VM, get a JVMTI environment, unpack the jar, put it in front of
the game's class loader, bind `NativeBridge`, arm `ClassFileLoadHook`,
retransform), and `inject-probe.c` is a 64-bit test injector.

## How a hook gets placed

Everything that differs between this and the Mixin build of the same client lives
in one table, `inject/InjectHooks.java`. Read a line as: rewrite `owner.method`,
at `position`, calling `callback`. The Mixin annotation it came from is in the
comment above it, so the two builds can be diffed by eye when a hook stops
landing.

`HookTransformer` is the whole of what Mixin does for this client. It finds the
injection point, checks the callback against the target's descriptor, and refuses
rather than guesses when it cannot — a refusal is a log line naming the candidates
it saw, not a crash.

Four rules the table obeys, each of which cost a live session to learn:

1. **Declare the descriptor.** `hook(owner, method).desc("(...)V")`. On an
   obfuscated client a name is not unique — `EntityPlayer` has `f(pk)` *and*
   `f(wn)`, `EntityRenderer` has four methods called `b` — and a name-only match
   is refused rather than guessed at. InjectMyau declares it on every hook and
   this table now does too.
2. **A hook is only correct if the class it lands on has the member.** The
   mapping table is keyed by the member's declaring class and its flat fallback
   answers for any class at all, which is how a field of a Forge event class once
   became a Minecraft field name and the game threw `NoSuchFieldError` once per
   frame until the log was 1.4 GB.
3. **Cancel needs an empty stack.** Only a head or return hook can cancel;
   anywhere else the stack is non-empty and there is no frame to return from. A
   method that returns a value and has to be skipped uses `OVERRIDE` instead.
4. **Only a head hook may read arguments.** At any other position the slot may
   have been overwritten, so that is refused rather than trusted.

## The five that cost the most time

All five produced a symptom that pointed somewhere else.

1. **A rename is only correct if the class it lands on has the member.**
   `tools/checkrefs` is the guard. It found 31 in the build that shipped a
   `NoSuchFieldError` per frame.

2. **`REPLACE_FIELD_WITH` matches reads *or* writes, and the callback's own
   descriptor says which.** A write-shaped callback on a read is a class that does
   not verify — silently, until a world loads, where it is either a `VerifyError`
   or a refused retransform and five hooks that quietly do nothing.

3. **Members are declared on interfaces.** `IInventory.getName` is on
   `IWorldNameable`, one interface up; a walk that follows only superclasses
   leaves that call in MCP names. `MappingBridge.parents` returns superclasses
   *and* interfaces and the walks are breadth-first over both.

4. **Accessors have to walk the hierarchy of the *runtime* class.** With OptiFine,
   `mc.playerController` is `PlayerControllerOF` and declares none of what it
   inherits from `PlayerControllerMP`; a miss there throws inside a tick.

5. **Fire events where the game fires them.** For the render event that means
   `BEFORE_FIELD` on `EntityRenderer.renderHand`, read inside `renderWorldPass` —
   Forge's `dispatchRenderLast` sits in front of the one place vanilla draws the
   hand, and InjectMyau anchors its `render3DPost` at the same field. It is the
   *caller's* field, so a launcher that rewrites `renderHand` does not take the
   anchor away. The partial ticks come from the pass-head hook, because a
   non-head hook cannot read arguments.

## Two more that are specific to running inside someone else's client

**The first transformed class can deadlock the game.** `ClassFileLoadHook` fires
*during* a class definition, so anything the transformer touches for the first
time inside it — `ClassReader`, `ClassNode`, the tree package, `ClassWriter` —
is a class being loaded by the game's loader while that loader is already busy
defining something else. Neither the `HookTransformer` constructor nor anything in
`install()` loads any of them: they are held in fields, and a field is not
resolved until it is used. `NativeBridge.warmUp()` puts one of our own classes
through `transform` before the hook is armed. This is InjectMyau's `warmUp`, and
it is not optional.

**A game launched by a launcher often cannot write to its own temp directory.**
`GetTempPathW` returns whatever the environment says — an 8.3 short name under a
redirected profile, or a directory owned by another user. When it is not writable
every log line is dropped in silence and the agent looks like it never ran. The
agent probes for a directory it can actually write to, starting with its own, and
puts the unpacked jar there too.

## What is deliberately not done here

- **Forge's event bus is not reimplemented.** A Forge client has one; the two
  launcher clients do not. `src/shim/java` is a Forge-shaped set of the ~28 types
  the client names, with a real bus and stubs for the rest, and `ForgeBridge`
  stands down when the real Forge is on the class path. `Reobf` rewrites
  `net/minecraftforge/**` along with the client, because on those two platforms
  the shim *is* part of the client.
- **The jar is not rewritten inside the game.** It is reobfuscated at build time,
  the way InjectMyau ships theirs: `tools/reobf/Reobf.java` rewrites the client,
  and `openraven-naming.txt` — written into the jar by `build.cmd` — is what tells
  the runtime which naming it is already in. The first design rewrote the client's
  own classes during class definition and every crash came from it.
- **ASM is shaded out of `org.objectweb.asm`** into `xyz/daikirai/shadow/asm/`.
  Forge bundles an ASM of its own, `LaunchClassLoader` is parent-first for that
  package, and mixing a 9.x `ClassRemapper` with the game's 5.x `ClassVisitor` is
  an `IllegalArgumentException` from inside `ClassVisitor.<init>` with no class
  name in it.
- **The client's assets are copied into a resource pack**, not read from the jar:
  a mod's jar is readable by the game's resource manager because Forge knows the
  container, and this jar has no container.

## What is still open

- **`keystrokesmod:textures/capes/jewstein.png` is named by `SettingsModule.Cape`
  and not shipped** — in this tree or upstream. The bind is caught and the cape is
  simply not drawn. Ship the file and the option starts working.
- **`EntityRenderer.renderHand`'s head hook is gone; the event comes from the
  field anchor instead.** If the overlays ever draw more than once a frame,
  `ForgeBridge.renderWorld`'s `worldFired` flag is what is holding them.
- **`retransformed N of M` may be one short** when OptiFine has already
  transformed `EntityPlayerSP`; the class is not retransformable in that state and
  the hooks on it are deferred to a later load.
- **`InjectHooks` marks two hooks `invisible()`** because they pick an instruction
  by ordinal (`ItemRenderer.itemToRender`, the swing-animation local). They
  install, but a shifted ordinal is a silent miss; the hook report counts them on
  their own line and the log prints what the method actually contains.
- **`Badlion`'s inbound pipeline**, above. Everything that depends on reading the
  server is unavailable there and cannot be fixed from this side of it.

## Credits

The injection architecture follows [InjectMyau](../../InjectMyau-main), which is
the reference implementation of doing this for a launcher client. The client is
Open B4, which is Raven B4 with the backend calls removed.

## License

GPL-3.0, inherited from Open B4.
