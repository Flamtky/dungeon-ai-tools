package dungeonplay;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.ai.pfa.GraphPath;
import com.badlogic.gdx.backends.lwjgl3.DefaultLwjgl3Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.utils.BufferUtils;
import engine.Entity;
import engine.Game;
import engine.components.PlayerComponent;
import engine.components.PositionComponent;
import engine.game.GameLoop;
import engine.game.GameStarter;
import engine.game.MainMenuScreen;
import engine.game.PreRunConfiguration;
import engine.language.Translation;
import engine.level.Tile;
import engine.level.utils.LevelUtils;
import engine.network.messages.c2s.InputMessage;
import engine.sound.player.ISoundPlayer;
import engine.systems.CameraSystem;
import engine.systems.DrawSystem;
import engine.utils.Direction;
import engine.utils.IVoidFunction;
import engine.utils.Point;
import feature.components.HealthComponent;
import feature.components.ItemComponent;
import feature.components.InventoryComponent;
import feature.components.UIComponent;
import feature.entities.HeroController;
import feature.hud.UIUtils;
import feature.hud.dialogs.DialogContextKeys;
import feature.interaction.InteractionComponent;
import feature.inventory.Item;
import feature.inventory.ItemRegistry;
import feature.systems.HealthSystem;
import feature.utils.EntityUtils;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.TreeMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.glfw.GLFWWindowCloseCallback;
import org.lwjgl.openal.AL10;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.windows.User32;

/**
 * Text command channel into a running game for the dungeon-play skill. The skill's javaagent loads
 * it into every game and server JVM that the skill's driver starts (play.ps1 on Windows, play.sh
 * on Linux); it is not part of the repository.
 *
 * <p>The driver writes {@code <deadline-epoch-ms> <command line>} to {@code
 * $DUNGEON_PLAY_STATE/<kind>/req.<id>}; the answer appears as plain text in {@code res.<id>}.
 * {@code kind} is {@code server} for a headless server process and {@code game} for the windowed
 * process. Commands run on the game thread (libGDX frame or server tick) and are dropped once their
 * deadline passed. World coordinates are tiles; screen coordinates are window pixels from the
 * top-left corner.
 *
 * <p>game: {@code ping}, {@code ctx}, {@code view}, {@code widget <text>}, {@code goto <x> <y> <r>},
 * {@code interact <x> <y>}, {@code render on|off}, {@code look <dir> <name:x,y,zoom>...}. Processes with authority (server, or a
 * single-process game) also answer {@code world [cam=ax,bx,ay,by,w,h] [player=<name>]
 * [match=<text>]}, {@code find <id>}, {@code tp <x> <y>}, {@code give <item> [n]}, {@code hp <n>
 * [id]}, {@code god on|off} and {@code kill <id>}; player-related ones take {@code player=<name>}.
 *
 * <p>A game window on Windows ({@link Desk}) also answers {@code key <name> <hold-ms>}, {@code type
 * <text>}, {@code erase <n>}, {@code mouse <x> <y>}, {@code click <x> <y> <button>}, {@code drag
 * <x1> <y1> <x2> <y2>}, {@code scroll <notches>}, {@code shot <file> [crop=x,y,w,h] [zoom=n]},
 * {@code record <seconds> <file>} and {@code quit}. File arguments carry {@code |} for a space and
 * {@code ?} for an equals sign.
 *
 * <p>Every process appends what changed (dialogs, inventories, players, interactables, hit points,
 * sounds played) to {@code <kind>/events} as {@code <epoch-ms> <text>} lines.
 */
public final class Hook {
  private static final int MAX_WIDGETS = 60;
  private static final int MAX_ENTITIES = 15;
  private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
  private static String kind;
  private static Path dir;

  private Hook() {}

  /**
   * Starts the request poller; called by the agent once the game ({@code Gdx.app}) or the headless
   * server loop exists. The channel's pid file appears once commands can run.
   */
  public static void start() throws Exception {
    String state = System.getenv("DUNGEON_PLAY_STATE");
    if (state == null) return;
    // The agent starts us either for a libGDX app (windowed game) or for a headless server loop.
    boolean server = Gdx.app == null;
    kind = server ? "server" : "game";
    dir = Path.of(state, kind);
    Files.createDirectories(dir);
    Files.createDirectories(Path.of(state, "pids"));
    String pid = String.valueOf(ProcessHandle.current().pid());
    Files.writeString(Path.of(state, "pids", pid), kind);
    Thread poller =
        new Thread(
            () -> {
              try {
                if (server) {
                  installServerPump();
                  Files.writeString(dir.resolve("port"), String.valueOf(PreRunConfiguration.networkPort()));
                } else {
                  Gdx.app.postRunnable(Hook::frame);
                }
                Files.writeString(dir.resolve("pid"), pid);
              } catch (Exception e) {
                e.printStackTrace();
                return;
              }
              poll();
            },
            "dungeon-play-hook");
    poller.setDaemon(true);
    poller.start();
  }

  // The headless server ticks on a private executor. Its next userOnFrame call (on that thread)
  // restores the room's callback and registers Pump, which then runs queued commands every tick.
  private static final Queue<Runnable> SERVER_QUEUE = new ConcurrentLinkedQueue<>();

  private static void installServerPump() throws InterruptedException {
    while (Game.currentLevel().isEmpty()) Thread.sleep(200);
    CountDownLatch installed = new CountDownLatch(1);
    IVoidFunction original = PreRunConfiguration.userOnFrame();
    PreRunConfiguration.userOnFrame(
        () -> {
          PreRunConfiguration.userOnFrame(original);
          Game.add(new Pump());
          installed.countDown();
          original.execute();
        });
    if (!installed.await(120, TimeUnit.SECONDS)) {
      throw new IllegalStateException("server loop never ran userOnFrame; hook not installed");
    }
  }

  private static final class Pump extends engine.System {
    Pump() {
      super(AuthoritativeSide.SERVER);
    }

    @Override
    public void execute() {
      for (int n = SERVER_QUEUE.size(); n > 0; n--) {
        Runnable r = SERVER_QUEUE.poll();
        if (r != null) r.run();
      }
      tick();
    }
  }

  /** Runs once per frame on the game thread (libGDX runnable that posts itself again). */
  private static void frame() {
    try {
      // A GPU renders the scene at full speed, so only the software GL of a virtual display is gated.
      if (WINDOWS) Desk.keep();
      else {
        ensureRenderGate();
        Desk.mark();
      }
      ensureSoundLog();
    } catch (Exception e) {
      if (!gateFailed) e.printStackTrace();
      gateFailed = true;
    }
    Recording.frame();
    tick();
    Gdx.app.postRunnable(Hook::frame);
  }

  private static int ticks;
  private static boolean tickFailed;
  private static boolean gateFailed;

  private static void tick() {
    if (++ticks % 5 != 0) return;
    try {
      Events.sample();
    } catch (Exception e) {
      if (!tickFailed) e.printStackTrace();
      tickFailed = true;
    }
  }

  /** Runs r on the game thread during the next frame or server tick. */
  private static void exec(Runnable r) {
    if (kind.equals("server")) SERVER_QUEUE.add(r);
    else Gdx.app.postRunnable(r);
  }

  private static void poll() {
    while (true) {
      try (Stream<Path> files = Files.list(dir)) {
        for (Path req : files.filter(p -> p.getFileName().toString().startsWith("req.")).toList()) {
          String id = req.getFileName().toString().substring(4);
          String[] parts = Files.readString(req).trim().split("\\s+", 2);
          Files.deleteIfExists(req);
          long deadline = Long.parseLong(parts[0]);
          String line = parts.length > 1 ? parts[1] : "";
          // play.sh gave up after the deadline; running the command later would surprise it.
          exec(() -> {
            if (System.currentTimeMillis() <= deadline) dispatch(id, line);
          });
        }
        Thread.sleep(25);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  private static void reply(String id, String text) {
    try {
      Path tmp = dir.resolve("tmp." + id);
      Files.writeString(tmp, text.endsWith("\n") ? text : text + "\n");
      Files.move(tmp, dir.resolve("res." + id), StandardCopyOption.ATOMIC_MOVE);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static void dispatch(String id, String line) {
    String[] a = line.split("\\s+");
    Map<String, String> opt = new HashMap<>();
    List<String> pos = new ArrayList<>();
    for (String s : a) {
      int eq = s.indexOf('=');
      if (eq > 0) opt.put(s.substring(0, eq), s.substring(eq + 1));
      else pos.add(s);
    }
    try {
      switch (pos.get(0)) {
        case "ping" -> reply(id, "ok " + kind);
        case "ctx" -> reply(id, ctx());
        case "view" -> reply(id, view());
        case "menu" -> reply(id, menu());
        case "widget" -> reply(id, widget(line.substring("widget".length()).trim()));
        case "world" -> reply(id, world(opt.get("cam"), opt.get("player"), opt.get("match")));
        case "find" -> reply(id, find(Integer.parseInt(pos.get(1)), opt.get("player")));
        case "tp" -> reply(id, tp(f(pos.get(1)), f(pos.get(2)), opt.get("player")));
        case "look" -> reply(id, look(file(pos.get(1)), pos.subList(2, pos.size())));
        case "render" -> {
          sceneOn = pos.get(1).equals("on");
          reply(id, "render " + pos.get(1));
        }
        // play.sh sends the count last; the item query may contain spaces.
        case "give" ->
            reply(id, Scenario.give(String.join(" ", pos.subList(1, pos.size() - 1)), Integer.parseInt(pos.get(pos.size() - 1)), opt.get("player")));
        case "hp" ->
            reply(id, Scenario.hp(Integer.parseInt(pos.get(1)), pos.size() > 2 ? Integer.parseInt(pos.get(2)) : -1, opt.get("player")));
        case "god" -> reply(id, Scenario.god(pos.get(1).equals("on"), opt.get("player")));
        case "kill" -> reply(id, Scenario.kill(Integer.parseInt(pos.get(1))));
        case "interact" -> interact(id, f(pos.get(1)), f(pos.get(2)));
        case "blocked" -> reply(id, blocked(opt.get("player")));
        case "skip" -> new Skip(id, line.substring("skip".length()).trim()).step();
        case "goto" ->
            new Walk(id, new Point(f(pos.get(1)), f(pos.get(2))), f(pos.get(3))).begin();
        case "key" -> {
          Desk.key(pos.get(1), true);
          afterMillis(Long.parseLong(pos.get(2)), () -> sequence(id, List.of(() -> Desk.key(pos.get(1), false))));
        }
        case "type" -> sequence(id, List.of(() -> Desk.type(line.substring("type".length()).trim())));
        case "erase" -> sequence(id, List.of(() -> Desk.erase(i(pos.get(1)))));
        case "mouse" -> sequence(id, List.of(() -> Desk.move(i(pos.get(1)), i(pos.get(2)))));
        case "click" ->
            sequence(
                id,
                List.of(
                    () -> Desk.move(i(pos.get(1)), i(pos.get(2))),
                    () -> Desk.button(i(pos.get(3)), true),
                    () -> Desk.button(i(pos.get(3)), false)));
        case "drag" -> {
          // Parsed up front: a bad number must fail before the button goes down.
          int x1 = i(pos.get(1));
          int y1 = i(pos.get(2));
          int x2 = i(pos.get(3));
          int y2 = i(pos.get(4));
          sequence(
              id,
              List.of(
                  () -> Desk.move(x1, y1),
                  () -> Desk.button(1, true),
                  () -> Desk.move((x1 + x2) / 2, (y1 + y2) / 2),
                  () -> Desk.move(x2, y2),
                  () -> Desk.button(1, false)));
        }
        case "scroll" -> sequence(id, List.of(() -> Desk.wheel(i(pos.get(1)))));
        case "shot" -> reply(id, Desk.shot(file(pos.get(1)), opt.get("crop"), opt.get("zoom")));
        case "record" -> reply(id, Recording.begin(f(pos.get(1)), file(pos.get(2))));
        case "quit" -> {
          Recording.finish();
          Desk.close();
          reply(id, "closing");
        }
        default -> reply(id, "error: unknown command " + pos.get(0));
      }
    } catch (Exception e) {
      reply(id, "error: " + e);
    }
  }

  private static float f(String s) {
    return Float.parseFloat(s);
  }

  private static int i(String s) {
    return Math.round(Float.parseFloat(s));
  }

  /**
   * A file argument; play.ps1, and play.sh for a desktop window's shot and record, send {@code |}
   * for each space and {@code ?} for each equals sign; play.sh sends look's directory as it is.
   */
  private static String file(String s) {
    return s.replace('|', ' ').replace('?', '=');
  }

  private static String fmt(float v) {
    return String.format(Locale.ROOT, "%.1f", v);
  }

  private static boolean authority() {
    return kind.equals("server") || Game.isSingleplayer();
  }

  private static String name(Entity e) {
    return e.fetch(PlayerComponent.class)
        .map(PlayerComponent::playerName)
        .filter(n -> n != null && !n.isBlank())
        .orElse(e.name());
  }

  // ---- screen transform {ax, bx, ay, by, w, h}: screen = (ax * x + bx, ay * y + by), top-left origin

  private static float[] cam() {
    OrthographicCamera c = CameraSystem.camera();
    Vector3 o = c.project(new Vector3(0, 0, 0));
    Vector3 u = c.project(new Vector3(1, 1, 0));
    float w = Gdx.graphics.getWidth();
    float h = Gdx.graphics.getHeight();
    return new float[] {u.x - o.x, o.x, -(u.y - o.y), h - o.y, w, h};
  }

  private static String screen(float[] cam, Point p) {
    if (cam == null) return "";
    int sx = Math.round(cam[0] * p.x() + cam[1]);
    int sy = Math.round(cam[2] * p.y() + cam[3]);
    boolean on = sx >= 0 && sy >= 0 && sx < cam[4] && sy < cam[5];
    return " screen " + sx + "," + sy + (on ? "" : " (off-screen)");
  }

  // ---- game side

  private static String ctx() {
    String player = Game.player().map(Hook::name).orElse("-");
    String c = "";
    if (Game.currentLevel().isPresent()) {
      float[] m = cam();
      c = " cam=" + m[0] + "," + m[1] + "," + m[2] + "," + m[3] + "," + m[4] + "," + m[5];
    }
    return "authority=" + (authority() ? 1 : 0)
        + " player=" + player
        + " port=" + PreRunConfiguration.networkPort()
        + " fps=" + Gdx.graphics.getFramesPerSecond()
        + c;
  }

  /**
   * The main menu's labels in the active language, one {@code key<TAB>label} per line: host (the
   * room may rename it), join and decline ({@code -} when the room has none), confirm.
   */
  private static String menu() throws ReflectiveOperationException {
    Object screen = ((com.badlogic.gdx.Game) Gdx.app.getApplicationListener()).getScreen();
    if (!(screen instanceof MainMenuScreen)) return "error: not in the main menu";
    Field f = MainMenuScreen.class.getDeclaredField("starter");
    f.setAccessible(true);
    GameStarter starter = (GameStarter) f.get(screen);
    Translation t = new Translation("main_menu");
    return "host\t" + starter.hostActionLabel().orElseGet(() -> t.text("host"))
        + "\njoin\t" + (starter.showJoinButton() ? t.text("join") : "-")
        + "\nconfirm\t" + t.text("confirm")
        + "\ndecline\t" + starter.startupConsent().map(GameStarter.StartupConsent::declineLabel).orElse("-");
  }

  private static String view() {
    StringBuilder o = new StringBuilder();
    Optional<Entity> me = Game.player();
    if (Game.currentLevel().isPresent() && me.isPresent()) {
      float[] cam = cam();
      Point p = EntityUtils.getPosition(me.get());
      o.append("hero ")
          .append(name(me.get()))
          .append(" at ")
          .append(fmt(p.x()))
          .append(",")
          .append(fmt(p.y()))
          .append(" tile ")
          .append((int) Math.floor(p.x()))
          .append(",")
          .append((int) Math.floor(p.y()))
          .append(screen(cam, p))
          .append(me.get().fetch(HealthComponent.class).map(h -> " hp " + h.currentHealthpoints() + "/" + h.maximalHealthpoints()).orElse(""))
          .append(" | zoom ")
          .append(fmt(CameraSystem.camera().zoom))
          .append(", ")
          .append(Math.round(cam[0]))
          .append(" px/tile\n");
      me.flatMap(h -> h.fetch(InventoryComponent.class))
          .ifPresent(inv -> o.append("inventory ").append(inventory(inv)).append('\n'));
    } else {
      o.append("no hero in a level (menu or loading)\n");
    }
    Game.entities(Set.of(UIComponent.class))
        .forEach(
            e -> {
              UIComponent ui = e.fetch(UIComponent.class).orElseThrow();
              o.append("dialog ")
                  .append(ui.dialogContext().dialogType())
                  .append(shown(ui))
                  .append(attributes(ui.dialogContext().attributes()))
                  .append('\n');
              // Containers the dialog shows (chest, second player); the hero's own is listed above.
              for (Object v : ui.dialogContext().attributes().values()) {
                if (!(v instanceof Integer eid)) continue;
                Game.findEntityById(eid)
                    .filter(x -> me.map(h -> h.id() != x.id()).orElse(true))
                    .ifPresent(
                        x ->
                            x.fetch(InventoryComponent.class)
                                .ifPresent(inv -> o.append("  #").append(x.id()).append(' ').append(name(x)).append(" inventory ").append(inventory(inv)).append('\n')));
              }
            });
    List<String> w = widgets().stream().limit(MAX_WIDGETS).map(Widget::show).toList();
    if (!w.isEmpty()) o.append("ui ").append(String.join(" | ", w)).append('\n');
    return o.toString();
  }

  /**
   * Whether the dialog shows. Asking builds the dialog, and one the game could not build (a missing
   * image) throws on every ask; uncaught on the game thread, that would end the game.
   */
  private static boolean visible(UIComponent ui) {
    return shown(ui).isEmpty();
  }

  /** The state suffix of a dialog line: nothing, hidden, or why the game could not build it. */
  private static String shown(UIComponent ui) {
    try {
      return ui.isVisible() ? "" : " (hidden)";
    } catch (RuntimeException e) {
      return " (not built: " + String.valueOf(e.getMessage()).replaceAll("\\s+", " ") + ")";
    }
  }

  /** Filled slots as {@code [slot] name}; {@code -} when empty. */
  static String inventory(InventoryComponent inv) {
    List<String> out = new ArrayList<>();
    Item[] items = inv.items();
    for (int i = 0; i < items.length; i++) {
      if (items[i] != null) out.add("[" + i + "] " + items[i].displayName());
    }
    return out.isEmpty() ? "-" : String.join(" | ", out);
  }

  /** Plain dialog attributes (texts, numbers, flags, entity ids as #id name); callbacks are left out. */
  private static String attributes(Map<String, Object> attrs) {
    StringBuilder o = new StringBuilder();
    attrs.forEach(
        (k, v) -> {
          String text;
          if (v instanceof Integer eid && k.toLowerCase(Locale.ROOT).endsWith("entity")) {
            text = "#" + eid + Game.findEntityById(eid).map(e -> " " + name(e)).orElse("");
          } else if (v instanceof CharSequence || v instanceof Number || v instanceof Boolean || v instanceof Enum<?>) {
            text = v.toString().replaceAll("\\s+", " ").trim();
            if (text.length() > 300) text = text.substring(0, 300) + "...";
            if (v instanceof CharSequence) text = "\"" + text + "\"";
          } else {
            return;
          }
          o.append(' ').append(k).append('=').append(text);
        });
    return o.toString();
  }

  private static Set<Stage> stages() {
    Set<Stage> out = new LinkedHashSet<>();
    collectStages(Gdx.input.getInputProcessor(), out);
    // Screens hand their stage to GameInput instead of installing it as the input processor.
    try {
      Field f = engine.input.GameInput.class.getDeclaredField("stage");
      f.setAccessible(true);
      if (f.get(null) instanceof Stage s) out.add(s);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
    Game.stage().ifPresent(out::add);
    return out;
  }

  private static void collectStages(InputProcessor p, Set<Stage> out) {
    if (p instanceof Stage s) out.add(s);
    if (p instanceof InputMultiplexer m) for (InputProcessor q : m.getProcessors()) collectStages(q, out);
  }

  /** A visible text widget; x/y is its centre in screen pixels, left/right its horizontal edges. */
  private record Widget(String type, String text, float x, float y, float left, float right) {
    String show() {
      String t = text.length() > 100 ? text.substring(0, 100) + "..." : text;
      return type + " \"" + t + "\" " + Math.round(x) + "," + Math.round(y);
    }
  }

  /** Visible buttons, fields and texts of all stages; adjacent texts on one line are merged. */
  private static List<Widget> widgets() {
    List<Widget> raw = new ArrayList<>();
    for (Stage s : stages()) collect(s, s.getRoot(), raw);
    List<Widget> out = new ArrayList<>();
    for (Widget w : raw) {
      Widget prev = out.isEmpty() ? null : out.get(out.size() - 1);
      if (prev != null
          && prev.type().equals("Text")
          && w.type().equals("Text")
          && Math.abs(prev.y() - w.y()) <= 4
          && w.left() - prev.right() < 30
          && w.left() >= prev.left()) {
        out.set(
            out.size() - 1,
            new Widget(
                "Text",
                prev.text() + " " + w.text(),
                (prev.left() + w.right()) / 2,
                prev.y(),
                prev.left(),
                w.right()));
      } else {
        out.add(w);
      }
    }
    return out;
  }

  private static void collect(Stage stage, Actor a, List<Widget> out) {
    if (!a.isVisible()) return;
    String text = null;
    String type = null;
    if (a instanceof TextButton b) {
      text = b.getText().toString();
      type = b.isDisabled() ? "Button(disabled)" : "Button";
    } else if (a instanceof TextField t) {
      text = t.getText().isEmpty() ? "<" + t.getMessageText() + ">" : t.getText();
      type = t.isDisabled() ? "Field(disabled)" : "Field";
    } else if (a instanceof Label l) {
      text = l.getText().toString();
      type = "Text";
    }
    if (text != null) {
      String clean = text.replaceAll("\\s+", " ").trim();
      if (!clean.isEmpty()) {
        Vector2 lo = stage.stageToScreenCoordinates(a.localToStageCoordinates(new Vector2(0, 0)));
        Vector2 hi =
            stage.stageToScreenCoordinates(
                a.localToStageCoordinates(new Vector2(a.getWidth(), a.getHeight())));
        float x = (lo.x + hi.x) / 2;
        float y = (lo.y + hi.y) / 2;
        if (x >= 0 && y >= 0 && x < Gdx.graphics.getWidth() && y < Gdx.graphics.getHeight()) {
          out.add(new Widget(type, clean, x, y, Math.min(lo.x, hi.x), Math.max(lo.x, hi.x)));
        }
      }
      return;
    }
    if (a instanceof Group g) for (Actor child : g.getChildren()) collect(stage, child, out);
  }

  /**
   * Centre of the enabled visible widget whose text contains the query; exact matches and buttons
   * win. An empty query picks the first text field.
   */
  private static String widget(String query) {
    String q = query.toLowerCase(Locale.ROOT);
    Comparator<Widget> rank =
        Comparator.comparing((Widget w) -> !w.text().equalsIgnoreCase(query))
            .thenComparing(w -> !w.type().startsWith("Button") && !w.type().equals("Field"));
    return widgets().stream()
        .filter(w -> !w.type().endsWith("(disabled)"))
        .filter(w -> q.isEmpty() ? w.type().equals("Field") : w.text().toLowerCase(Locale.ROOT).contains(q))
        .min(rank)
        .map(w -> Math.round(w.x()) + " " + Math.round(w.y()))
        .orElse("error: no visible widget contains \"" + query + "\"");
  }

  /**
   * Why an open dialog keeps the hero from moving and interacting, or {@code free}. Only the
   * authority knows: clients do not learn whether a server dialog pauses.
   */
  private static String blocked(String playerName) {
    if (!authority()) return "error: no authority here";
    Entity hero = player(playerName).orElseThrow(() -> new IllegalStateException("player not found"));
    if (!Game.hud().blocksGameplayInput(hero)) return "free";
    List<String> types =
        Game.entities(Set.of(UIComponent.class))
            .map(e -> e.fetch(UIComponent.class).orElseThrow())
            .filter(ui -> visible(ui)
                && (ui.willPauseGame() || ui.dialogContext().find(DialogContextKeys.BLOCKS_GAMEPLAY_INPUT, Boolean.class).orElse(false)))
            .map(ui -> String.valueOf(ui.dialogContext().dialogType()))
            .distinct()
            .toList();
    return "error: dialog " + String.join(", ", types) + " is open and blocks the hero; run skip or answer it";
  }

  private static void interact(String id, float x, float y) {
    Entity hero = Game.player().orElseThrow(() -> new IllegalStateException("no hero"));
    HeroController.requestInteraction(hero, new Point(x, y));
    afterFrames(20, () -> reply(id, "sent\n" + view()));
  }

  private static void afterFrames(int n, Runnable r) {
    if (n <= 0) r.run();
    else exec(() -> afterFrames(n - 1, r));
  }

  private static void afterMillis(long millis, Runnable r) {
    long at = System.currentTimeMillis() + millis;
    exec(
        new Runnable() {
          @Override
          public void run() {
            if (System.currentTimeMillis() >= at) r.run();
            else exec(this);
          }
        });
  }

  /** Runs the steps two frames apart, so the game sees each one in a frame of its own, then replies. */
  private static void sequence(String id, List<Runnable> steps) {
    try {
      if (steps.isEmpty()) {
        reply(id, "ok");
        return;
      }
      steps.get(0).run();
      afterFrames(2, () -> sequence(id, steps.subList(1, steps.size())));
    } catch (Exception e) {
      reply(id, "error: " + e);
    }
  }

  /**
   * Sends the player's close shortcut (Escape) to the topmost dialog until none takes it, and
   * replies with every page text it passed. Cutscenes and scripted dialogs advance a page per
   * request; a dialog that would close with an answer (more than one button, or a text field) stops
   * the skip, as does a page matching {@code stopAt}.
   */
  private static final class Skip {
    private final String id;
    private final java.util.regex.Pattern stopAt;
    private final long deadline = System.currentTimeMillis() + 120_000;
    private final List<String> pages = new ArrayList<>();
    private String shown = "";
    private long changedAt = System.currentTimeMillis();
    private long pageAt = changedAt;
    private long requestedAt;
    private long emptySince;

    Skip(String id, String stopAt) {
      this.id = id;
      this.stopAt = stopAt.isEmpty() ? null : java.util.regex.Pattern.compile(stopAt, java.util.regex.Pattern.CASE_INSENSITIVE);
    }

    void step() {
      long now = System.currentTimeMillis();
      // The topmost visible dialog that closes or handles a close request; passive HUD is left out.
      Optional<UIComponent> top =
          Game.entities(Set.of(UIComponent.class))
              .map(e -> e.fetch(UIComponent.class).orElseThrow())
              .filter(ui -> visible(ui) && (ui.canBeClosed() || UIUtils.hasCloseRequestHandler(ui.dialog())))
              .max(java.util.Comparator.comparingInt(ui -> ui.dialog().getZIndex()));
      if (top.isEmpty()) {
        // The next dialog of a chain may follow after a delay (callback to the server, story timer).
        if (emptySince == 0) emptySince = now;
        if (now - emptySince > 2500) finish("no dialog takes Escape now");
        else exec(this::step);
        return;
      }
      emptySince = 0;
      UIComponent ui = top.get();
      List<Widget> ws = new ArrayList<>();
      if (ui.dialog().getStage() != null) collect(ui.dialog().getStage(), ui.dialog(), ws);
      String type = String.valueOf(ui.dialogContext().dialogType());
      String text = String.join(" ", ws.stream().filter(w -> w.type().equals("Text")).map(Widget::text).toList());
      if (!(type + text).equals(shown)) {
        shown = type + text;
        changedAt = now;
        // A typewriter reveals a page gradually; keep only its full text.
        String page = type + ": " + text;
        if (!pages.isEmpty() && page.startsWith(pages.get(pages.size() - 1))) {
          pages.set(pages.size() - 1, page);
        } else {
          pageAt = now;
          if (!text.isEmpty()) pages.add(page);
        }
      }
      if (stopAt != null && stopAt.matcher(text).find()) {
        finish("stopped at the page matching /" + stopAt + "/");
        return;
      }
      if (!UIUtils.hasCloseRequestHandler(ui.dialog())
          && (count(ui.dialog(), TextButton.class) > 1 || count(ui.dialog(), TextField.class) > 0)) {
        finish("stopped at " + type + ": it needs a choice or input");
        return;
      }
      if (now - changedAt > 8000 || now > deadline) {
        finish("stopped at " + type + ": it does not react to Escape");
        return;
      }
      // A page without text yet may still fade in (cutscene); a request then would drop the page.
      // Cutscenes ignore requests while they fade, so repeat until one lands; the first request
      // on a typing page reveals it in full.
      if (now - pageAt >= (text.isEmpty() ? 2000 : 150) && now - requestedAt >= 400) {
        requestedAt = now;
        UIUtils.requestCloseDialog(ui);
      }
      exec(this::step);
    }

    /** Visible actors of the type in the dialog, wherever they are and enabled or not. */
    private static int count(Actor a, Class<?> type) {
      if (!a.isVisible()) return 0;
      int n = type.isInstance(a) ? 1 : 0;
      if (a instanceof Group g) for (Actor c : g.getChildren()) n += count(c, type);
      return n;
    }

    private void finish(String why) {
      StringBuilder o = new StringBuilder(pages.size() + " page(s) seen; " + why + "\n");
      for (String p : pages) o.append("  ").append(p.length() > 600 ? p.substring(0, 600) + "..." : p).append('\n');
      reply(id, o + view());
    }
  }

  /** Walks the local hero along a path by feeding movement input every frame, as a player would. */
  private static final class Walk {
    private final String id;
    private final Point target;
    private final float radius;
    private final long deadline = System.currentTimeMillis() + 90_000;
    private List<Point> path = List.of();
    private String note = "";
    private int next;
    private int replans;
    private long checkAt;
    private Point checkPos;
    private Direction lastMove = Direction.RIGHT;
    private Direction sidestep;
    private long sidestepUntil;

    Walk(String id, Point target, float radius) {
      this.id = id;
      this.target = target;
      this.radius = radius;
    }

    void begin() {
      plan();
      step();
    }

    private Point hero() {
      return EntityUtils.getPosition(Game.player().orElseThrow(() -> new IllegalStateException("no hero")));
    }

    private void plan() {
      Point from = hero();
      Tile goal = goalTile(from);
      Tile wanted = Game.tileAt(target).orElse(null);
      if (radius <= 0 && goal != wanted) {
        note = " (target tile blocked; went to nearest free tile " + goal.coordinate().x() + "," + goal.coordinate().y() + ")";
      }
      GraphPath<Tile> p = LevelUtils.calculatePath(from, goal.coordinate().toPoint());
      List<Point> pts = new ArrayList<>();
      for (Tile t : p) pts.add(new Point(t.coordinate().x() + 0.5f, t.coordinate().y() + 0.5f));
      if (pts.isEmpty()) throw new IllegalStateException("no path to " + goal.coordinate());
      path = pts;
      next = pts.size() > 1 ? 1 : 0;
      checkAt = System.currentTimeMillis() + 1200;
      checkPos = from;
    }

    /** The target tile, or the nearest accessible tile around it when the target is blocked. */
    private Tile goalTile(Point from) {
      Tile t = Game.tileAt(target).orElseThrow(() -> new IllegalStateException("no tile at target"));
      if (t.isAccessible()) return t;
      Tile best = null;
      double bestScore = Double.MAX_VALUE;
      for (int dx = -2; dx <= 2; dx++) {
        for (int dy = -2; dy <= 2; dy++) {
          Tile n = Game.tileAt(new Point(target.x() + dx, target.y() + dy)).orElse(null);
          if (n == null || !n.isAccessible()) continue;
          Point c = new Point(n.coordinate().x() + 0.5f, n.coordinate().y() + 0.5f);
          double score = c.distance(target) * 4 + c.distance(from) * 0.1;
          if (score < bestScore) {
            bestScore = score;
            best = n;
          }
        }
      }
      if (best == null) throw new IllegalStateException("target and its surroundings are blocked");
      return best;
    }

    // Movement goes to the server like the player's own: one input per frame while walking, and
    // a stop over UDP and TCP, because the server keeps applying the last direction until then.
    private static void move(float x, float y) {
      Game.network().sendInput(InputMessage.move(engine.utils.Vector2.of(x, y)));
    }

    private static void stopMoving() {
      InputMessage stop = InputMessage.move(engine.utils.Vector2.ZERO);
      Game.network().sendInput(stop);
      Game.network().send((short) 0, stop, true);
    }

    private void finish(String status) {
      stopMoving();
      Point p = hero();
      reply(id, status + " at " + fmt(p.x()) + "," + fmt(p.y()) + " distance " + fmt((float) p.distance(target)) + (status.startsWith("reached") ? note : ""));
    }

    private void step() {
      try {
        Point p = hero();
        if (radius > 0 && p.distance(target) <= radius * 0.85) {
          finish("reached");
          return;
        }
        long now = System.currentTimeMillis();
        if (now > deadline) {
          finish("error: timeout");
          return;
        }
        if (now < sidestepUntil) {
          move(sidestep.x(), sidestep.y());
          exec(this::step);
          return;
        }
        if (sidestep != null) {
          // Done stepping around a blocker (e.g. furniture with a collider): plan again from here.
          sidestep = null;
          plan();
        }
        if (now > checkAt) {
          if (p.distance(checkPos) < 0.2) {
            if (radius > 0 && p.distance(target) <= radius) {
              finish("reached");
              return;
            }
            if (++replans > 4) {
              finish("error: stuck (level editor active, dialog open, or a solid entity or closed door on the path; goto a waypoint first, or tp closer)");
              return;
            }
            // Tile paths ignore solid entities; step sideways, alternating sides per attempt.
            boolean horizontal = lastMove == Direction.LEFT || lastMove == Direction.RIGHT;
            sidestep =
                horizontal
                    ? (replans % 2 == 1 ? Direction.UP : Direction.DOWN)
                    : (replans % 2 == 1 ? Direction.LEFT : Direction.RIGHT);
            sidestepUntil = now + 500L * ((replans + 1) / 2);
            checkAt = sidestepUntil + 1200;
            checkPos = p;
            exec(this::step);
            return;
          }
          checkAt = now + 1200;
          checkPos = p;
        }
        Point wp = path.get(next);
        boolean last = next == path.size() - 1;
        float tol = last ? 0.25f : 0.4f;
        float dx = wp.x() - p.x();
        float dy = wp.y() - p.y();
        if (Math.abs(dx) < tol && Math.abs(dy) < tol) {
          if (last) {
            boolean in = radius <= 0 || p.distance(target) <= radius;
            finish(in ? "reached" : "error: path ended outside interaction range");
            return;
          }
          next++;
        } else {
          if (Math.abs(dx) >= tol || Math.abs(dy) >= tol) {
            move(Math.abs(dx) >= tol ? Math.signum(dx) : 0, Math.abs(dy) >= tol ? Math.signum(dy) : 0);
            lastMove =
                Math.abs(dx) >= Math.abs(dy)
                    ? (dx > 0 ? Direction.RIGHT : Direction.LEFT)
                    : (dy > 0 ? Direction.UP : Direction.DOWN);
          }
        }
        exec(this::step);
      } catch (Exception e) {
        stopMoving();
        reply(id, "error: " + e);
      }
    }
  }

  // ---- authority side

  private static Optional<Entity> player(String wanted) {
    List<Entity> players = Game.allPlayers().toList();
    if (wanted != null && !wanted.equals("-")) {
      return players.stream().filter(e -> name(e).equals(wanted)).findFirst();
    }
    return Game.player().or(() -> players.size() == 1 ? Optional.of(players.get(0)) : Optional.empty());
  }

  /**
   * Players and interactables; the 15 nearest, or every one whose name, or an item it holds (chest
   * contents), contains {@code match}.
   */
  private static String world(String camArg, String playerName, String match) {
    if (!authority()) return "error: no authority here";
    if (Game.currentLevel().isEmpty()) return "";
    float[] cam = null;
    if (camArg != null) {
      String[] c = camArg.split(",");
      cam = new float[] {f(c[0]), f(c[1]), f(c[2]), f(c[3]), f(c[4]), f(c[5])};
    } else if (kind.equals("game")) {
      cam = cam();
    }
    StringBuilder o = new StringBuilder();
    Optional<Entity> me = player(playerName);
    Point ref = me.map(EntityUtils::getPosition).orElse(null);
    float[] fc = cam;
    if (match == null)
      Game.allPlayers()
        .forEach(
            e -> {
              Point p = EntityUtils.getPosition(e);
              o.append("player ").append(name(e)).append(" at ").append(fmt(p.x())).append(",").append(fmt(p.y())).append(screen(fc, p)).append('\n');
            });
    List<Entity> items =
        Game.entities(Set.of(PositionComponent.class, InteractionComponent.class))
            .filter(e -> !e.isPresent(PlayerComponent.class))
            .filter(e -> match == null || has(e.name(), match) || holds(e, match))
            .sorted(Comparator.comparingDouble(e -> ref == null ? 0 : ref.distance(EntityUtils.getPosition(e))))
            .limit(match == null ? MAX_ENTITIES : 200)
            .toList();
    for (Entity e : items) {
      Point p = EntityUtils.getPosition(e);
      float range = e.fetch(InteractionComponent.class).orElseThrow().interaction().range();
      o.append("#").append(e.id()).append(' ').append(e.name()).append(" at ").append(fmt(p.x())).append(",").append(fmt(p.y())).append(screen(fc, p));
      if (ref != null) {
        double d = ref.distance(p);
        o.append(" dist ").append(fmt((float) d)).append(d <= range ? " IN RANGE" : " (range " + fmt(range) + ")");
      }
      if (match != null && !has(e.name(), match))
        e.fetch(InventoryComponent.class).ifPresent(inv -> o.append(" holds ").append(inventory(inv)));
      o.append('\n');
    }
    return o.toString();
  }

  private static boolean has(String text, String part) {
    return text.toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT));
  }

  /** Whether the entity's inventory holds an item whose name contains {@code part}. */
  private static boolean holds(Entity e, String part) {
    return e.fetch(InventoryComponent.class)
        .map(inv -> Stream.of(inv.items()).anyMatch(i -> i != null && has(i.displayName(), part)))
        .orElse(false);
  }

  /** Answers {@code x y range}, plus the distance to the player when one is named. */
  private static String find(int id, String playerName) {
    if (!authority()) return "error: no authority here";
    Entity e =
        Game.entities()
            .filter(x -> x.id() == id)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no entity #" + id));
    Point p = EntityUtils.getPosition(e);
    float range = e.fetch(InteractionComponent.class).map(ic -> ic.interaction().range()).orElse(1f);
    String distance = playerName == null ? "" : player(playerName).map(h -> " " + EntityUtils.getPosition(h).distance(p)).orElse("");
    return p.x() + " " + p.y() + " " + range + distance;
  }

  private static String tp(float x, float y, String playerName) {
    if (!authority()) return "error: no authority here";
    Entity hero = player(playerName).orElseThrow(() -> new IllegalStateException("player not found"));
    Point target = new Point(x, y);
    Tile t = Game.tileAt(target).orElse(null);
    if (t == null || !t.isAccessible()) return "error: tile " + (int) x + "," + (int) y + " is not accessible";
    PositionComponent pc = hero.fetch(PositionComponent.class).orElseThrow();
    Point center = EntityUtils.getPosition(hero);
    pc.position(new Point(x - (center.x() - pc.position().x()), y - (center.y() - pc.position().y())));
    return "teleported " + name(hero) + " to " + fmt(x) + "," + fmt(y);
  }

  // ---- render gate (game process)

  // Under software GL the scene costs ~100 ms per frame, so a rendered game stutters and may fall
  // behind its tick rate. Unless something looks (shot, look, record), the gate unsets DrawSystem's
  // stable width between DrawSystem.execute and DrawSystem.render, so render skips the scene; the HUD
  // stage still draws, and animations keep updating.
  private static volatile boolean sceneOn = WINDOWS || "always".equals(System.getenv("DUNGEON_PLAY_RENDER"));
  private static Field stableWidth;
  // DrawSystem advances its shader clock only when it renders; the gate keeps it running.
  private static Field secondsElapsed;

  private static final class RenderGate extends engine.System {
    /** A client system executes in the frame pass: after the DrawSystem of every tick, before render. */
    RenderGate() {
      super(AuthoritativeSide.CLIENT);
    }

    /** Pausing the game stops all systems; like DrawSystem, the gate keeps running. */
    @Override
    public void stop() {
      run = true;
    }

    @Override
    public void execute() {
      if (sceneOn) return;
      engine.System draw = Game.systems().get(DrawSystem.class);
      if (draw == null) return;
      try {
        stableWidth.setInt(draw, -1);
        secondsElapsed.setFloat(draw, secondsElapsed.getFloat(draw) + Gdx.graphics.getDeltaTime());
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /**
   * Renders the world without the HUD from fixed cameras into {@code <dir>/<name>.png}, one per
   * {@code name:x,y,zoom} shot (camera centre in tiles); the game camera stays where it was. Needs
   * the scene on for at least one frame, since the gate leaves DrawSystem.render empty otherwise.
   */
  private static String look(String outDir, List<String> shots) {
    if (kind.equals("server")) return "error: a server has no window";
    if (!sceneOn) return "error: scene rendering is off";
    engine.System draw = Game.systems().get(DrawSystem.class);
    if (draw == null || Game.currentLevel().isEmpty()) return "error: no level is loaded";
    OrthographicCamera camera = CameraSystem.camera();
    Vector3 position = camera.position.cpy();
    float zoom = camera.zoom;
    List<String> out = new ArrayList<>();
    try {
      for (String shot : shots) {
        String[] parts = shot.split(":");
        String[] values = parts[1].split(",");
        camera.position.set(f(values[0]), f(values[1]), 0);
        camera.zoom = f(values[2]);
        camera.update();
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        draw.render(Gdx.graphics.getDeltaTime());
        Pixmap pixmap =
            Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        String file = Path.of(outDir, parts[0] + ".png").toString();
        PixmapIO.writePNG(Gdx.files.absolute(file), pixmap, Deflater.DEFAULT_COMPRESSION, true);
        pixmap.dispose();
        out.add(file);
      }
    } finally {
      camera.position.set(position);
      camera.zoom = zoom;
      camera.update();
    }
    return String.join("\n", out);
  }

  /** Keeps a RenderGate registered after the DrawSystem, which a new game loop may re-create. */
  private static void ensureRenderGate() throws ReflectiveOperationException {
    Map<Class<? extends engine.System>, engine.System> systems = Game.systems();
    if (!systems.containsKey(DrawSystem.class)) return;
    boolean afterDraw = false;
    for (Class<?> k : systems.keySet()) {
      if (k == DrawSystem.class) afterDraw = true;
      else if (k == RenderGate.class) {
        if (afterDraw) return;
        break;
      }
    }
    if (stableWidth == null) {
      stableWidth = DrawSystem.class.getDeclaredField("stableWidth");
      stableWidth.setAccessible(true);
      secondsElapsed = DrawSystem.class.getDeclaredField("secondsElapsed");
      secondsElapsed.setAccessible(true);
    }
    Game.add(new RenderGate());
  }

  // ---- desktop window (game process)

  /**
   * The game window on the user's desktop. On Windows it stays behind the other windows, never takes
   * the keyboard focus, lets the real mouse through to whatever lies beneath and plays no sound, so
   * the user and the games of other agents go undisturbed. Input arrives as window messages posted to this window alone: it needs
   * no focus and moves no real cursor. Screenshots read the frame the window presented last, so
   * covering windows do not show in them.
   *
   * <p>On Linux (play.sh with DUNGEON_PLAY_DESKTOP) the window is an ordinary one that the window
   * manager places. Input goes straight to libGDX's own GLFW callbacks, so it needs no focus and
   * moves no real cursor either.
   */
  private static final class Desk {
    private static final long HWND_BOTTOM = 1;
    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SW_SHOWNOACTIVATE = 4;
    private static final int GWL_EXSTYLE = -20;
    private static final long WS_EX_NOACTIVATE = 0x08000000L;
    private static final int WM_CLOSE = 0x10;
    private static final int WM_KEYDOWN = 0x100;
    private static final int WM_KEYUP = 0x101;
    private static final int WM_CHAR = 0x102;
    private static final int WM_MOUSEMOVE = 0x200;
    private static final int WM_MOUSEWHEEL = 0x20A;
    private static long hwnd;
    private static int mouseX;
    private static int mouseY;
    private static final int SETTLE = 15;
    private static int minimizedFrames;
    // Frames left in which the window is sent behind the others again.
    private static int settleFrames;
    private static int width;
    private static int height;

    /**
     * Sets the window up on its first frame and keeps it out of the user's way afterwards. A
     * minimized window (Win+D) is restored, because libGDX reads no input while minimized; the
     * restore waits half a second, since one that lands in the middle of the minimize leaves the
     * window without a size. Fullscreen (F11, a settings toggle) would cover the desktop, so the
     * game goes back to its window at the size play.ps1 asked for.
     */
    static void keep() {
      if (hwnd == 0) setUp();
      boolean minimized = User32.IsIconic(hwnd);
      minimizedFrames = minimized ? minimizedFrames + 1 : 0;
      if (minimizedFrames > Gdx.graphics.getFramesPerSecond() / 2) {
        User32.ShowWindow(hwnd, SW_SHOWNOACTIVATE);
        settleFrames = SETTLE;
      }
      // The game's own toggle resizes over several frames and raises the window each time.
      if (!minimized && (Gdx.graphics.isFullscreen() || Gdx.graphics.getWidth() != width || Gdx.graphics.getHeight() != height)) {
        Gdx.graphics.setWindowedMode(width, height);
        settleFrames = SETTLE;
      }
      if (settleFrames > 0) {
        settleFrames--;
        User32.SetWindowPos(hwnd, HWND_BOTTOM, 0, 0, 0, 0, SWP_NOSIZE | SWP_NOMOVE | SWP_NOACTIVATE);
      }
    }

    private static void setUp() {
      Lwjgl3Graphics graphics = (Lwjgl3Graphics) Gdx.graphics;
      long window = graphics.getWindow().getWindowHandle();
      hwnd = GLFWNativeWin32.glfwGetWin32Window(window);
      String instance = dir.getParent().getFileName().toString();
      String[] size = System.getenv().getOrDefault("DUNGEON_PLAY_SIZE", "1280x750").split("x");
      width = Integer.parseInt(size[0]);
      height = Integer.parseInt(size[1]);
      graphics.setWindowedMode(width, height);
      GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_MOUSE_PASSTHROUGH, GLFW.GLFW_TRUE);
      // Windows activates the next window when the user closes or minimizes one; never this one.
      User32.SetWindowLongPtr(hwnd, GWL_EXSTYLE, User32.GetWindowLongPtr(hwnd, GWL_EXSTYLE) | WS_EX_NOACTIVATE);
      // Instances cascade, so the user sees every game when the windows come to the front.
      int shift = 32 * (Integer.parseInt(instance) - 1);
      User32.SetWindowPos(
          hwnd,
          HWND_BOTTOM,
          graphics.getWindow().getPositionX() + shift,
          graphics.getWindow().getPositionY() + shift,
          0,
          0,
          SWP_NOSIZE | SWP_NOACTIVATE);
      graphics.setTitle(Game.windowTitle() + " [dungeon-play " + instance + ", driven by an agent]");
      if (!PreRunConfiguration.disableAudio()) AL10.alListenerf(AL10.AL_GAIN, 0);
    }

    private static boolean marked;

    /** A Linux desktop window says in its title which game it is and that an agent drives it. */
    static void mark() {
      if (marked || !"1".equals(System.getenv("DUNGEON_PLAY_DESKTOP"))) return;
      marked = true;
      Gdx.graphics.setTitle(Game.windowTitle() + " [dungeon-play " + dir.getParent().getFileName() + ", driven by an agent]");
    }

    /**
     * Presses or releases a key named as in {@link Input.Keys} (E, Escape, Enter, Space, Left, F4,
     * L-Shift) or by one of the aliases below.
     */
    static void key(String name, boolean down) {
      String wanted =
          switch (name.toLowerCase(Locale.ROOT)) {
            case "return" -> "Enter";
            case "esc" -> "Escape";
            case "backspace" -> "Delete";
            case "delete" -> "Forward Delete";
            case "shift" -> "L-Shift";
            case "ctrl", "control" -> "L-Ctrl";
            case "alt" -> "L-Alt";
            // A name with a space would not survive the request line: Page_Up.
            default -> name.replace('_', ' ');
          };
      DefaultLwjgl3Input input = (DefaultLwjgl3Input) Gdx.input;
      for (int key = GLFW.GLFW_KEY_SPACE; key <= GLFW.GLFW_KEY_LAST; key++) {
        if (!wanted.equalsIgnoreCase(Input.Keys.toString(input.getGdxKeyCode(key)))) continue;
        if (!WINDOWS) {
          Desk.<GLFWKeyCallback>callback(Gdx.input, "keyCallback")
              .invoke(window(), key, GLFW.glfwGetKeyScancode(key), down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE, 0);
          return;
        }
        // Letters, digits and space carry their virtual key, so Windows adds the typed character.
        long virtualKey = key == ' ' || (key >= '0' && key <= '9') || (key >= 'A' && key <= 'Z') ? key : 0;
        long keystroke = 1L | (long) (GLFW.glfwGetKeyScancode(key) & 0x1FF) << 16;
        post(down ? WM_KEYDOWN : WM_KEYUP, virtualKey, down ? keystroke : keystroke | 0xC0000000L);
        return;
      }
      throw new IllegalArgumentException("unknown key " + name + " (use E, Escape, Enter, Space, Left, F4, L-Shift, ...)");
    }

    static void type(String text) {
      for (char c : text.toCharArray()) {
        if (WINDOWS) post(WM_CHAR, c, 1);
        else Desk.<GLFWCharCallback>callback(Gdx.input, "charCallback").invoke(window(), c);
      }
    }

    /** Empties the focused text field: End, then n backspaces. */
    static void erase(int n) {
      key("End", true);
      key("End", false);
      for (int k = 0; k < n; k++) {
        key("BackSpace", true);
        key("BackSpace", false);
      }
    }

    static void move(int x, int y) {
      mouseX = x;
      mouseY = y;
      if (WINDOWS) post(WM_MOUSEMOVE, 0, point());
      else Desk.<GLFWCursorPosCallback>callback(Gdx.input, "cursorPosCallback").invoke(window(), x, y);
    }

    /** Presses or releases a mouse button at the last position: 1 left, 2 middle, 3 right. */
    static void button(int button, boolean down) {
      if (!WINDOWS) {
        int glfw =
            switch (button) {
              case 1 -> GLFW.GLFW_MOUSE_BUTTON_LEFT;
              case 2 -> GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
              case 3 -> GLFW.GLFW_MOUSE_BUTTON_RIGHT;
              default -> throw new IllegalArgumentException("mouse button " + button + " (1 left, 2 middle, 3 right)");
            };
        Desk.<GLFWMouseButtonCallback>callback(Gdx.input, "mouseButtonCallback")
            .invoke(window(), glfw, down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE, 0);
        return;
      }
      int message =
          switch (button) {
            case 1 -> 0x201;
            case 2 -> 0x207;
            case 3 -> 0x204;
            default -> throw new IllegalArgumentException("mouse button " + button + " (1 left, 2 middle, 3 right)");
          };
      post(down ? message : message + 1, 0, point());
    }

    /** Turns the wheel; positive notches scroll up. */
    static void wheel(int notches) {
      if (WINDOWS) post(WM_MOUSEWHEEL, (long) (notches * 120 & 0xFFFF) << 16, 0);
      else Desk.<GLFWScrollCallback>callback(Gdx.input, "scrollCallback").invoke(window(), 0, notches);
    }

    /** Closes the window as its close button would, so the game shuts down in order. */
    static void close() {
      if (WINDOWS) post(WM_CLOSE, 0, 0);
      else {
        // As GLFW does for the window's close button: the flag, then libGDX's callback, which asks the game.
        GLFW.glfwSetWindowShouldClose(window(), true);
        Desk.<GLFWWindowCloseCallback>callback(((Lwjgl3Graphics) Gdx.graphics).getWindow(), "closeCallback").invoke(window());
      }
    }

    private static long window() {
      return ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle();
    }

    /** One of the GLFW callbacks libGDX registered, by the field of its input or window that holds it. */
    @SuppressWarnings("unchecked")
    private static <T> T callback(Object owner, String name) {
      try {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(owner);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }

    private static long point() {
      return (long) (mouseY & 0xFFFF) << 16 | (mouseX & 0xFFFF);
    }

    private static void post(int message, long wParam, long lParam) {
      need();
      User32.PostMessage(hwnd, message, wParam, lParam);
    }

    /** Fails before the window is set up; a Linux window needs no setup. */
    static void need() {
      if (WINDOWS && hwnd == 0) throw new IllegalStateException("the game window is not set up yet");
    }

    /** Writes the frame the window presented last as a PNG; crop is x,y,w,h in pixels, zoom scales it. */
    static String shot(String file, String crop, String zoom) {
      need();
      int w = Gdx.graphics.getBackBufferWidth();
      int h = Gdx.graphics.getBackBufferHeight();
      GL11.glReadBuffer(GL11.GL_FRONT);
      Pixmap frame = Pixmap.createFromFrameBuffer(0, 0, w, h);
      GL11.glReadBuffer(GL11.GL_BACK);
      // Rows arrive bottom-up with the alpha the last draw left: flip them and make them opaque.
      Pixmap image = new Pixmap(w, h, Pixmap.Format.RGBA8888);
      byte[] row = new byte[w * 4];
      ByteBuffer from = frame.getPixels();
      ByteBuffer to = image.getPixels();
      for (int y = h - 1; y >= 0; y--) {
        from.get(y * row.length, row);
        for (int a = 3; a < row.length; a += 4) row[a] = -1;
        to.put((h - 1 - y) * row.length, row);
      }
      frame.dispose();
      if (crop != null || zoom != null) {
        String[] c = (crop == null ? "0,0," + w + "," + h : crop).split(",");
        float z = zoom == null ? 1 : f(zoom);
        Pixmap part = new Pixmap(Math.round(i(c[2]) * z), Math.round(i(c[3]) * z), Pixmap.Format.RGBA8888);
        part.setFilter(Pixmap.Filter.NearestNeighbour);
        part.drawPixmap(image, i(c[0]), i(c[1]), i(c[2]), i(c[3]), 0, 0, part.getWidth(), part.getHeight());
        image.dispose();
        image = part;
      }
      PixmapIO.writePNG(Gdx.files.absolute(file), image, Deflater.DEFAULT_COMPRESSION, false);
      image.dispose();
      return file;
    }
  }

  /**
   * Pipes the frames the window presents to ffmpeg for a fixed time, at 30 frames per second of
   * real time. The video is {@code <file>.part} until it is complete.
   */
  private static final class Recording {
    private static final int FPS = 30;
    private static Process ffmpeg;
    private static OutputStream frames;
    private static Path target;
    private static long startedAt;
    private static int written;
    private static int total;
    private static int width;
    private static int height;
    private static ByteBuffer pixels;
    private static byte[] bytes;

    static String begin(float seconds, String file) throws IOException {
      Desk.need();
      if (ffmpeg != null) return "error: a recording is already running";
      width = Gdx.graphics.getBackBufferWidth();
      height = Gdx.graphics.getBackBufferHeight();
      target = Path.of(file);
      pixels = BufferUtils.newByteBuffer(width * height * 4);
      bytes = new byte[width * height * 4];
      total = Math.round(seconds * FPS);
      written = 0;
      // play.ps1 sheet waits while the .part file exists, so it is there before ffmpeg starts
      // writing. Once ffmpeg runs, an older video of that name is gone: sheet must not show it.
      Path part = Path.of(file + ".part");
      Files.write(part, new byte[0]);
      try {
        // x264 needs even sides; the frames arrive bottom-up.
        ffmpeg =
            new ProcessBuilder(
                    "ffmpeg", "-loglevel", "error", "-y", "-f", "rawvideo", "-pixel_format", "rgba",
                    "-video_size", width + "x" + height, "-framerate", String.valueOf(FPS), "-i", "-",
                    "-vf", "vflip,crop=trunc(iw/2)*2:trunc(ih/2)*2", "-pix_fmt", "yuv420p", "-f", "mp4",
                    file + ".part")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
      } catch (IOException e) {
        Files.deleteIfExists(part);
        throw e;
      }
      Files.deleteIfExists(target);
      frames = ffmpeg.getOutputStream();
      startedAt = System.nanoTime();
      return "recording " + seconds + " s to " + file;
    }

    /** Runs every frame: writes the frames that are due and finishes the video after the last one. */
    static void frame() {
      if (ffmpeg == null) return;
      try {
        long due = Math.min(total, (System.nanoTime() - startedAt) * FPS / 1_000_000_000L + 1) - written;
        if (due > 0) {
          GL11.glReadBuffer(GL11.GL_FRONT);
          Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1);
          Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixels);
          GL11.glReadBuffer(GL11.GL_BACK);
          pixels.get(0, bytes);
          for (; due > 0; due--, written++) frames.write(bytes);
        }
        if (written >= total) finish();
      } catch (Exception e) {
        e.printStackTrace();
        abandon();
      }
    }

    /** Ends a running recording with the frames written so far; the game calls it before it closes. */
    static void finish() {
      if (ffmpeg == null) return;
      try {
        frames.close();
        if (!ffmpeg.waitFor(30, TimeUnit.SECONDS) || ffmpeg.exitValue() != 0) throw new IOException("ffmpeg failed");
        Files.move(Path.of(target + ".part"), target, StandardCopyOption.REPLACE_EXISTING);
        ffmpeg = null;
      } catch (Exception e) {
        e.printStackTrace();
        abandon();
      }
    }

    private static void abandon() {
      ffmpeg.destroyForcibly();
      ffmpeg = null;
      try {
        Files.deleteIfExists(Path.of(target + ".part"));
      } catch (IOException ignored) {
        // ffmpeg may still hold the file
      }
    }
  }

  // ---- sound log (game process)

  private static Field soundPlayer;

  /** Wraps the game's sound player, which a new game loop may re-create: played sounds become events. */
  private static void ensureSoundLog() throws ReflectiveOperationException {
    if (soundPlayer == null) {
      soundPlayer = GameLoop.class.getDeclaredField("soundPlayer");
      soundPlayer.setAccessible(true);
    }
    ISoundPlayer real = (ISoundPlayer) soundPlayer.get(null);
    if (Proxy.isProxyClass(real.getClass())) return;
    soundPlayer.set(
        null,
        Proxy.newProxyInstance(
            ISoundPlayer.class.getClassLoader(),
            new Class<?>[] {ISoundPlayer.class},
            (proxy, method, args) -> {
              if (method.getName().equals("playWithInstance")) Events.write(List.of("sound " + args[1]));
              try {
                return method.invoke(real, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            }));
  }

  // ---- events

  /**
   * Compares the observable state every few frames with the previous sample and appends each
   * difference as an event line. The game process reports its dialogs; processes with authority
   * report players, inventories, interactables and hit points.
   */
  private static final class Events {
    private static Map<String, String> last = Map.of();
    private static Object lastLevel;

    static void sample() throws Exception {
      Object level = Game.currentLevel().orElse(null);
      if (level == null) return;
      Map<String, String> now = new TreeMap<>();
      if (kind.equals("game")) {
        Game.entities(Set.of(UIComponent.class))
            .forEach(e -> {
              UIComponent ui = e.fetch(UIComponent.class).orElseThrow();
              String t = ui.dialogContext().find("title", String.class).map(x -> " \"" + x + "\"").orElse("");
              now.put("dialog " + e.id(), ui.dialogContext().dialogType() + t);
            });
      }
      if (authority()) {
        Game.allPlayers().forEach(p -> {
          now.put("player " + name(p), "");
          p.fetch(InventoryComponent.class).ifPresent(inv -> now.put("inventory " + name(p), inventory(inv)));
        });
        Game.entities(Set.of(PositionComponent.class, InteractionComponent.class))
            .filter(e -> !e.isPresent(PlayerComponent.class))
            .forEach(e -> now.put("interactable #" + e.id(), e.name()));
        Game.entities(Set.of(HealthComponent.class))
            .forEach(e -> now.put("hp #" + e.id() + " " + name(e), String.valueOf(e.fetch(HealthComponent.class).orElseThrow().currentHealthpoints())));
      }
      List<String> out = new ArrayList<>();
      if (level != lastLevel) {
        out.add("level loaded");
      } else {
        for (var e : now.entrySet()) {
          String old = last.get(e.getKey());
          if (old == null) out.add(appeared(e.getKey(), e.getValue()));
          else if (!old.equals(e.getValue())) out.add(changed(e.getKey(), old, e.getValue()));
        }
        for (var e : last.entrySet()) {
          if (!now.containsKey(e.getKey())) out.add(gone(e.getKey(), e.getValue()));
        }
      }
      last = now;
      lastLevel = level;
      out.removeIf(String::isEmpty);
      if (!out.isEmpty()) write(out);
    }

    /** Appends the lines to the events file, stamped with the current time. */
    static void write(List<String> lines) throws IOException {
      StringBuilder b = new StringBuilder();
      long t = System.currentTimeMillis();
      for (String line : lines) b.append(t).append(' ').append(line).append('\n');
      Files.writeString(dir.resolve("events"), b, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String appeared(String key, String v) {
      if (key.startsWith("dialog ")) return "dialog opened " + v;
      if (key.startsWith("player ")) return "player joined " + key.substring(7);
      if (key.startsWith("interactable ")) return "appeared " + key.substring(13) + " " + v;
      return "";
    }

    private static String gone(String key, String v) {
      if (key.startsWith("dialog ")) return "dialog closed " + v;
      if (key.startsWith("player ")) return "player left " + key.substring(7);
      if (key.startsWith("interactable ")) return "removed " + key.substring(13) + " " + v;
      if (key.startsWith("hp ")) return "gone " + key.substring(3);
      return "";
    }

    private static String changed(String key, String old, String v) {
      if (key.startsWith("hp ")) return "hp " + key.substring(3) + " " + old + " -> " + v;
      if (key.startsWith("inventory ")) return key + ": " + v;
      if (key.startsWith("interactable ")) return "renamed " + key.substring(13) + " " + old + " -> " + v;
      if (key.startsWith("dialog ")) return "dialog changed " + v;
      return "";
    }
  }

  // ---- scenario setup (authority)

  /** Debug changes that put the world into the state under test without playing there. */
  private static final class Scenario {
    private static Entity hero(String playerName) {
      if (!authority()) throw new IllegalStateException("no authority here");
      return player(playerName).orElseThrow(() -> new IllegalStateException("player not found"));
    }

    /** A new item from a registry id and item data; empty when the item cannot be built so. */
    private static Optional<Item> create(String id, Map<String, String> data) {
      try {
        Optional<Item> made = ItemRegistry.create(id, data);
        if (made.isPresent() || !data.isEmpty()) return made;
        Class<? extends Item> c = ItemRegistry.lookup(id).orElse(null);
        return c == null ? Optional.empty() : Optional.of(c.getDeclaredConstructor().newInstance());
      } catch (ReflectiveOperationException | RuntimeException e) {
        return Optional.empty();
      }
    }

    /**
     * Adds n of an item whose registry id or name contains the query: registered items with a
     * default instance, and copies of the items lying in the level (e.g. data-driven puzzle pieces).
     */
    static String give(String query, int n, String playerName) {
      Entity hero = hero(playerName);
      // label -> {registry id, item data}
      Map<String, Map.Entry<String, Map<String, String>>> all = new TreeMap<>();
      for (String id : ItemRegistry.entries().keySet()) {
        create(id, Map.of()).ifPresent(i -> all.put(id + " (" + i.displayName() + ")", Map.entry(id, Map.of())));
      }
      Game.entities(Set.of(ItemComponent.class))
          .map(e -> e.fetch(ItemComponent.class).orElseThrow().item())
          .forEach(i -> {
            try {
              all.putIfAbsent(i.displayName() + " (in level)", Map.entry(ItemRegistry.idFor(i), i.itemData()));
            } catch (RuntimeException ignored) {
              // not registered: cannot be copied
            }
          });
      String q = query.toLowerCase(Locale.ROOT);
      List<String> matches = new ArrayList<>();
      for (String label : all.keySet()) {
        String id = all.get(label).getKey();
        String display = label.substring(label.indexOf('(') + 1, label.length() - 1);
        if (id.equalsIgnoreCase(query) || label.startsWith(query + " (") || display.equalsIgnoreCase(query)) {
          matches = List.of(label);
          break;
        }
        if (label.toLowerCase(Locale.ROOT).contains(q)) matches.add(label);
      }
      if (matches.size() != 1) {
        String list = String.join(", ", matches.isEmpty() ? all.keySet() : matches);
        return "error: " + (matches.isEmpty() ? "no item matches \"" + query + "\"" : "ambiguous") + "; items: " + list;
      }
      InventoryComponent inv = hero.fetch(InventoryComponent.class).orElseThrow(() -> new IllegalStateException("hero has no inventory"));
      Map.Entry<String, Map<String, String>> src = all.get(matches.get(0));
      for (int i = 0; i < n; i++) {
        Item item = create(src.getKey(), src.getValue()).orElseThrow(() -> new IllegalStateException("cannot create " + src.getKey()));
        if (!inv.add(item)) return "error: inventory full after " + i + "; inventory " + inventory(inv);
      }
      return "gave " + n + "x " + matches.get(0) + " to " + name(hero) + "; inventory " + inventory(inv);
    }

    /** Sets the hit points of entity id, or of the player when id is negative. */
    static String hp(int value, int id, String playerName) {
      Entity e = id >= 0 ? entity(id) : hero(playerName);
      HealthComponent h = e.fetch(HealthComponent.class).orElseThrow(() -> new IllegalStateException(name(e) + " has no hit points"));
      h.currentHealthpoints(value);
      return "hp " + name(e) + " " + h.currentHealthpoints() + "/" + h.maximalHealthpoints() + noHealthSystem();
    }

    static String god(boolean on, String playerName) {
      Entity e = hero(playerName);
      e.fetch(HealthComponent.class).orElseThrow(() -> new IllegalStateException("hero has no hit points")).godMode(on);
      return "god mode " + (on ? "on" : "off") + " for " + name(e);
    }

    /** Kills an entity with hit points (death handling runs), or removes any other entity. */
    static String kill(int id) {
      Entity e = entity(id);
      Optional<HealthComponent> h = e.fetch(HealthComponent.class);
      if (h.isPresent()) {
        h.get().godMode(false);
        h.get().currentHealthpoints(0);
        return "hp of #" + id + " " + name(e) + " set to 0" + noHealthSystem();
      }
      Game.remove(e);
      return "removed #" + id + " " + name(e);
    }

    /** Damage and death run in HealthSystem; some rooms do not register it. */
    private static String noHealthSystem() {
      return Game.systems().containsKey(HealthSystem.class)
          ? ""
          : " (this game has no HealthSystem: damage is not applied and nothing dies)";
    }

    private static Entity entity(int id) {
      if (!authority()) throw new IllegalStateException("no authority here");
      return Game.findEntityById(id).orElseThrow(() -> new IllegalStateException("no entity #" + id));
    }
  }
}
