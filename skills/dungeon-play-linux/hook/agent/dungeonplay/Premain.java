package dungeonplay;

import java.lang.instrument.Instrumentation;
import java.util.Arrays;

/**
 * Javaagent entry point. Waits until the game has created its libGDX application, or until a
 * headless server has started its server loop, then starts {@code dungeonplay.Hook} from the game
 * classpath. JVMs without the game on the classpath are left alone.
 */
public final class Premain {
  private static final String SERVER_LOOP = "engine.network.server.AuthoritativeServerLoop";

  private Premain() {}

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
