package dungeonplay;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.ai.pfa.GraphPath;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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

/**
 * Text command channel into a running game for the dungeon-play skill. The skill's javaagent loads
 * it into every game and server JVM that play.sh starts; it is not part of the repository.
 *
 * <p>play.sh writes {@code <deadline-epoch-ms> <command line>} to {@code
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
 * <p>Every process appends what changed (dialogs, inventories, players, interactables, hit points,
 * sounds played) to {@code <kind>/events} as {@code <epoch-ms> <text>} lines.
 */
public final class Hook {
  private static final int MAX_WIDGETS = 60;
  private static final int MAX_ENTITIES = 15;
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
      ensureRenderGate();
      ensureSoundLog();
    } catch (Exception e) {
      if (!gateFailed) e.printStackTrace();
      gateFailed = true;
    }
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
        case "look" -> reply(id, look(pos.get(1), pos.subList(2, pos.size())));
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
        default -> reply(id, "error: unknown command " + pos.get(0));
      }
    } catch (Exception e) {
      reply(id, "error: " + e);
    }
  }

  private static float f(String s) {
    return Float.parseFloat(s);
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
                  .append(ui.isVisible() ? "" : " (hidden)")
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
            .filter(ui -> ui.isVisible()
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
              .filter(ui -> ui.isVisible() && (ui.canBeClosed() || UIUtils.hasCloseRequestHandler(ui.dialog())))
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
              finish("error: stuck (movement blocked? level editor active, dialog open, or a wall)");
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

  // Under software GL the scene costs ~100 ms per frame and game logic advances once per frame, so
  // a rendered game runs at a third of its speed. Unless something looks (shot, look, record), the gate
  // keeps DrawSystem's stable width unset after DrawSystem.execute, so DrawSystem.render skips the
  // scene; the HUD stage still draws, and animations keep updating.
  private static volatile boolean sceneOn = "always".equals(System.getenv("DUNGEON_PLAY_RENDER"));
  private static Field stableWidth;
  // DrawSystem advances its shader clock only when it renders; the gate keeps it running.
  private static Field secondsElapsed;

  private static final class RenderGate extends engine.System {
    RenderGate() {
      super(AuthoritativeSide.BOTH);
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
        String file = outDir + "/" + parts[0] + ".png";
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
