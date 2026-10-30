package se.grynna.pager.ui.sprattel;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Demo showing Blockies identicon generation with different sizes and seeds.
 */
public class BlockiesDemo extends JPanel {

    private static final String[] SEEDS = {"alice@example.com", "bob@example.com", "charlie@example.com",
            "dave@example.com", "eve@example.com", "frank@example.com"};

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;

        Blockies blockies = Blockies.defaultCache();

        int x = 20;
        int y = 20;

        // Show different seeds with default size (8x8 grid, scale 4 = 32x32px)
        g2d.setColor(Color.BLACK);
        g2d.drawString("Default size (32x32):", x, y);
        y += 20;

        for (String seed : SEEDS) {
            BufferedImage icon = blockies.icon(seed);
            g2d.drawImage(icon, x, y, null);
            g2d.drawString(seed, x + 40, y + 20);
            y += 50;
        }

        // Show different pixel sizes with same seed
        y += 20;
        g2d.drawString("Different pixel sizes (alice@example.com):", x, y);
        y += 20;

        String seed = "alice@example.com";
        int[] sizes = {16, 24, 32, 48, 64};

        for (int targetSize : sizes) {
            BufferedImage icon = blockies.icon(Blockies.forPixelSize(seed, targetSize));
            g2d.drawImage(icon, x, y, null);
            g2d.drawString(targetSize + "x" + targetSize, x + targetSize + 10, y + targetSize / 2);
            x += targetSize + 80;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Blockies Demo");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.add(new BlockiesDemo());
            frame.setSize(800, 600);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
