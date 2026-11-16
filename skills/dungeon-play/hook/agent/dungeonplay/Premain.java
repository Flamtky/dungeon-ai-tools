package dungeonplay;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Javaagent entry point. Waits until the game has created its libGDX application, or until a
 * headless server has started its server loop, then starts {@code dungeonplay.Hook} from the game
 * classpath. JVMs without the game on the classpath are left alone.
 */
public final class Premain {
  private static final String SERVER_LOOP = "engine.network.server.AuthoritativeServerLoop";

  /** Name prefix of the event that marks a process as a guarded game; its pid follows. */
  private static final String GAME_EVENT = "Local\\dungeon-play-game-";

  private static final int SYNCHRONIZE = 0x00100000;

  private Premain() {}

  /**
   * Keeps the keyboard focus with the user for as long as the game runs. A game window takes the
   * focus when it opens and when Windows restores it for the first time, and only the process that
   * holds the focus may pass it on. So whenever a window of this process holds it, this hands it
   * back to the window that had it before, or to the desktop when that window is gone.
   *
   * <p>Two guards must not hand the focus to each other, so a window of another agent's game never
   * counts as the window that had it before. Every guarded process holds a named event for its
   * lifetime; a process whose event opens is such a game.
   */
  private static void guardFocus() {
    try {
      Linker linker = Linker.nativeLinker();
      SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
      SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
      MethodHandle createEvent =
          linker.downcallHandle(
              kernel32.find("CreateEventW").orElseThrow(),
              FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS));
      MethodHandle openEvent =
          linker.downcallHandle(
              kernel32.find("OpenEventW").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS));
      MethodHandle closeHandle =
          linker.downcallHandle(kernel32.find("CloseHandle").orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
      MethodHandle foreground =
          linker.downcallHandle(user32.find("GetForegroundWindow").orElseThrow(), FunctionDescriptor.of(JAVA_LONG));
      MethodHandle processOf =
          linker.downcallHandle(
              user32.find("GetWindowThreadProcessId").orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS));
      MethodHandle setForeground =
          linker.downcallHandle(
              user32.find("SetForegroundWindow").orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
      MethodHandle desktop =
          linker.downcallHandle(user32.find("GetShellWindow").orElseThrow(), FunctionDescriptor.of(JAVA_LONG));
      MemorySegment pid = Arena.global().allocate(JAVA_INT);
      int own = (int) ProcessHandle.current().pid();
      createEvent.invoke(0L, 1, 0, Arena.global().allocateFrom(GAME_EVENT + own, StandardCharsets.UTF_16LE));
      long previous = 0;
      int lastOwner = 0;
      boolean lastIsGame = false;
      while (true) {
        long window = (long) foreground.invoke();
        pid.set(JAVA_INT, 0, 0);
        if (window != 0) processOf.invoke(window, pid);
        int owner = pid.get(JAVA_INT, 0);
        if (owner == own) {
          if ((int) setForeground.invoke(previous) == 0) setForeground.invoke((long) desktop.invoke());
        } else {
          if (owner != lastOwner) {
            lastOwner = owner;
            try (Arena arena = Arena.ofConfined()) {
              long event =
                  (long) openEvent.invoke(SYNCHRONIZE, 0, arena.allocateFrom(GAME_EVENT + owner, StandardCharsets.UTF_16LE));
              lastIsGame = event != 0;
              if (event != 0) closeHandle.invoke(event);
            }
          }
          if (!lastIsGame) previous = window;
        }
        Thread.sleep(5);
      }
    } catch (Throwable e) {
      e.printStackTrace();
    }
  }

  /**
   * Called by the JVM for {@code -javaagent}. Applies {@code DUNGEON_PLAY_PORT} before {@code main}
   * runs, so hosting, joining and the hosted server child all default to that port.
   */
  public static void premain(String args, Instrumentation inst) throws Exception {
    String port = System.getenv("DUNGEON_PLAY_PORT");
    if (port != null && !port.isBlank()) {
      try {
        Class.forName("engine.game.PreRunConfiguration")
            .getMethod("networkPort", int.class)
            .invoke(null, Integer.parseInt(port.trim()));
      } catch (ClassNotFoundException e) {
        // not a game JVM
      }
    }
    if (System.getProperty("os.name").startsWith("Windows")) {
      Thread guard = new Thread(Premain::guardFocus, "dungeon-play-focus");
      guard.setDaemon(true);
      guard.start();
    }
    Thread t =
        new Thread(
            () -> {
              try {
                ClassLoader cl = ClassLoader.getSystemClassLoader();
                Class<?> gdx = Class.forName("com.badlogic.gdx.Gdx", true, cl);
                while (gdx.getField("app").get(null) == null && !serverLoopLoaded(inst)) {
                  Thread.sleep(250);
                }
                // A windowed game may load the server loop class too; give it time to create its app.
                for (int i = 0; i < 12 && gdx.getField("app").get(null) == null; i++) Thread.sleep(250);
                Class.forName("dungeonplay.Hook", true, cl).getMethod("start").invoke(null);
              } catch (ClassNotFoundException e) {
                // not a game JVM
              } catch (Exception e) {
                e.printStackTrace();
              }
            },
            "dungeon-play-agent");
    t.setDaemon(true);
    t.start();
  }

  private static boolean serverLoopLoaded(Instrumentation inst) {
    return Arrays.stream(inst.getAllLoadedClasses()).anyMatch(c -> c.getName().equals(SERVER_LOOP));
  }
}
