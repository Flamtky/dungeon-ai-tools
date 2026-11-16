import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import javax.imageio.ImageIO;

/**
 * Compares two directories of PNGs: prints the changed pixels per name, writes {@code
 * <out>/<name>.png} (before | after) for every name in both, and {@code <out>/<name>_diff.png}
 * (after with changed pixels in red) when something changed.
 *
 * <p>Run through {@code dungeon-play compare <before_dir> <after_dir> <out_dir>}.
 */
public class Compare {
  private static final int THRESHOLD = 24; // luma difference; filters shader and dither noise
  private static final int GAP = 12;
  private static final int HEADER = 30;

  public static void main(String[] args) throws IOException {
    File before = new File(args[0]);
    File after = new File(args[1]);
    File out = new File(args[2]);
    out.mkdirs();
    String[] names = before.list((d, n) -> n.endsWith(".png") && new File(args[1], n).exists());
    Arrays.sort(names);
    for (String name : names) {
      BufferedImage a = ImageIO.read(new File(before, name));
      BufferedImage b = ImageIO.read(new File(after, name));
      if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
        System.out.println(name + ": sizes differ, not compared");
        continue;
      }
      compare(name, a, b, out);
    }
  }

  private static void compare(String name, BufferedImage a, BufferedImage b, File out) throws IOException {
    int w = a.getWidth();
    int h = a.getHeight();
    BufferedImage marked = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    int changed = 0;
    Rectangle box = null;
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int p = a.getRGB(x, y);
        int q = b.getRGB(x, y);
        boolean differs = luma(p, q) > THRESHOLD;
        marked.setRGB(x, y, differs ? 0xFF0000 : q);
        if (differs) {
          changed++;
          box = box == null ? new Rectangle(x, y, 1, 1) : box.union(new Rectangle(x, y, 1, 1));
        }
      }
    }
    System.out.println(name + ": " + changed + " changed px" + (box == null ? "" : ", box x=" + box.x + " y=" + box.y + " w=" + box.width + " h=" + box.height));

    BufferedImage side = new BufferedImage(w * 2 + GAP, h + HEADER, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = side.createGraphics();
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, side.getWidth(), side.getHeight());
    g.drawImage(a, 0, HEADER, null);
    g.drawImage(b, w + GAP, HEADER, null);
    g.setColor(Color.BLACK);
    g.drawString(name + ": before", 10, 20);
    g.drawString(name + ": after", w + GAP + 10, 20);
    g.dispose();
    ImageIO.write(side, "png", new File(out, name));
    if (changed > 0) ImageIO.write(marked, "png", new File(out, name.replace(".png", "_diff.png")));
  }

  /** Luma of the per-channel difference of two RGB pixels. */
  private static double luma(int p, int q) {
    int r = Math.abs((p >> 16 & 255) - (q >> 16 & 255));
    int g = Math.abs((p >> 8 & 255) - (q >> 8 & 255));
    int b = Math.abs((p & 255) - (q & 255));
    return 0.299 * r + 0.587 * g + 0.114 * b;
  }
}
