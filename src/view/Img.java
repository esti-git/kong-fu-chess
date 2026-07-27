package view;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class Img {

    private BufferedImage img;

    public Img read(String path,
                    Dimension targetSize,
                    boolean keepAspect,
                    Object interpolation) {

        try {
            img = ImageIO.read(new File(path));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot load image: " + path);
        }
        if (img == null) throw new IllegalArgumentException("Unsupported image: " + path);

        if (targetSize != null) {
            int tw = targetSize.width, th = targetSize.height;
            int w = img.getWidth(), h = img.getHeight();

            int nw, nh;
            if (keepAspect) {
                double s = Math.min(tw / (double) w, th / (double) h);
                nw = (int) Math.round(w * s);
                nh = (int) Math.round(h * s);
            } else { nw = tw; nh = th; }

            BufferedImage dst = new BufferedImage(
                    nw, nh,
                    img.getColorModel().hasAlpha()
                            ? BufferedImage.TYPE_INT_ARGB
                            : BufferedImage.TYPE_INT_RGB);

            Graphics2D g = dst.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                               RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, nw, nh, null);
            g.dispose();
            img = dst;
        }
        return this;
    }

    public Img blank(int width, int height, Color bgColor) {
        img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        if (bgColor != null) {
            Graphics2D g = img.createGraphics();
            g.setColor(bgColor);
            g.fillRect(0, 0, width, height);
            g.dispose();
        }
        return this;
    }

    public void drawOn(Img other, int x, int y) {
        if (img == null || other.img == null)
            throw new IllegalStateException("Both images must be loaded.");

        if (x + img.getWidth()  > other.img.getWidth()
         || y + img.getHeight() > other.img.getHeight())
            throw new IllegalArgumentException("Patch exceeds destination bounds.");

        Graphics2D g = other.img.createGraphics();
        g.setComposite(AlphaComposite.SrcOver);
        g.drawImage(img, x, y, null);
        g.dispose();
    }

    public BufferedImage get() { return img; }
}
