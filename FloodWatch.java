import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Line2D;
import java.util.Arrays;
import java.util.Random;

/**
 * FLOOD WATCH - a night-shift hazard survival game (FNAF-style).
 *
 * Office hazards : Flooding Room (door + audio cue), Blackout (reboot pad + hit points)
 * Outside hazards: Street Flooding  1.1 flood surge creeping toward the office
 *                                   1.2 flooding zones -> deploy flood walls (drains power)
 *
 * Compile: javac FloodWatch.java      Run: java FloodWatch
 */
public class FloodWatch extends JPanel implements KeyListener, MouseListener {

    // ===================== TUNING (change these to rebalance) =====================
    static final double NIGHT = 240;          // seconds from 12 AM to 6 AM
    static final double HOUR = NIGHT / 6;
    static final double LEAK_FIX = 2.5;       // seconds the door must stay closed to stop a leak
    static final double REBOOT_TIME = 4;      // seconds to reboot the system
    static final double HP_MAX = 20;          // system hit points
    static final double HP_TICK = 20;         // every N seconds the blackout AI level is deducted from HP
    static final double WALL_COST = 12;       // % power per flood wall
    static final double WALL_TIME = 15;       // seconds a flood wall holds
    static final double ZONE_OVERFLOW = 25;   // seconds a flooded zone can go unwalled before it leaks into the office
    static final int[] FLOOD_AI = {2, 4, 6, 8, 10, 12};   // per hour, rolled out of 20
    static final int[] BLACKOUT_AI = {1, 1, 2, 2, 3, 3};  // per hour, HP deducted per tick

    // ===================== MAP =====================
    static final int W = 1000, H = 640;
    static final String[] CAM_NAMES = {"BBALL COURT", "R1", "R2", "MAIN ROAD", "EAST ROAD", "SARI-SARI", "CARENDERIA"};
    static final Rectangle[] ZONES = {
        new Rectangle(60, 300, 300, 170),   // 0 court   (flood surge starts here)
        new Rectangle(70, 120, 170, 110),   // 1 r1
        new Rectangle(260, 120, 100, 110),  // 2 r2
        new Rectangle(410, 90, 60, 380),    // 3 main road
        new Rectangle(470, 330, 330, 45),   // 4 east road (leads to the office door)
        new Rectangle(500, 110, 100, 70),   // 5 sari-sari
        new Rectangle(620, 110, 160, 70)    // 6 carenderia
    };
    static final Rectangle OFFICE_BOX = new Rectangle(800, 320, 130, 65);

    // ===================== CLICK AREAS =====================
    static final Rectangle BAR = new Rectangle(350, 592, 300, 36);
    static final Rectangle DOOR_HIT = new Rectangle(820, 120, 170, 400);
    static final Rectangle PAD_HIT = new Rectangle(20, 220, 140, 180);
    static final Rectangle PAD_PANEL = new Rectangle(190, 200, 330, 240);
    static final Rectangle REBOOT_BTN = new Rectangle(220, 370, 270, 50);
    static final Rectangle WALL_BTN = new Rectangle(770, 520, 200, 52);

    enum State { MENU, PLAY, OVER, WIN }

    // ===================== GAME STATE =====================
    State state = State.MENU;
    double time, anim;
    double power, hp, hpTimer;
    boolean camUp, doorClosed, padOpen, blackout, captions = true;
    double rebootProg = -1;
    int selCam;
    int floodNode;                  // which zone the flood surge is in (0..4)
    double floodTimer, zoneRoll;
    boolean leak;
    double leakFix, water;          // water: 0..1 level in the office
    final double[] zoneFlood = new double[ZONES.length];
    final double[] wall = new double[ZONES.length];
    String overReason = "", toast = "";
    double toastT;
    long last;
    final Random rng = new Random();
    Clip leakClip;

    public FloodWatch() {
        setPreferredSize(new Dimension(W, H));
        setFocusable(true);
        addKeyListener(this);
        addMouseListener(this);
        last = System.nanoTime();
        new Timer(16, e -> {
            long now = System.nanoTime();
            double dt = Math.min(0.05, (now - last) / 1e9);
            last = now;
            anim += dt;
            toastT -= dt;
            if (state == State.PLAY) update(dt);
            repaint();
        }).start();
    }

    // ===================== LOGIC =====================
    void startGame() {
        state = State.PLAY;
        time = 0; power = 100; hp = HP_MAX; hpTimer = 0;
        camUp = false; doorClosed = false; padOpen = false; blackout = false;
        rebootProg = -1; selCam = 0; floodNode = 0; floodTimer = 0; zoneRoll = 0;
        leak = false; leakFix = 0; water = 0;
        Arrays.fill(zoneFlood, 0);
        Arrays.fill(wall, 0);
        stopLeak();
        toast("12 AM - SURVIVE UNTIL 6 AM");
    }

    void update(double dt) {
        time += dt;
        if (time >= NIGHT) { state = State.WIN; stopLeak(); return; }
        int hr = Math.min(5, (int) (time / HOUR));
        int floodAI = FLOOD_AI[hr], hpAI = BLACKOUT_AI[hr];

        // --- power ---
        if (!blackout) {
            power += 0.3 * dt;
            if (doorClosed) power -= 1.0 * dt;
            if (camUp) power -= 0.45 * dt;
            power = Math.min(100, power);
            if (power <= 0) triggerBlackout("POWER LOST");
        }

        // --- system hit points (Office Hazard 2) ---
        if (!blackout && rebootProg < 0) {
            hpTimer += dt;
            if (hpTimer >= HP_TICK) {
                hpTimer = 0;
                hp -= hpAI;
                beep(180, 120);
                if (hp <= 0) triggerBlackout("SYSTEM ERROR");
            }
        }

        // --- reboot ---
        if (rebootProg >= 0) {
            rebootProg += dt;
            if (rebootProg >= REBOOT_TIME) {
                rebootProg = -1; hp = HP_MAX; hpTimer = 0;
                if (blackout) { blackout = false; power = Math.max(power, 30); }
                toast("SYSTEM ONLINE");
                beep(660, 150);
            }
        }

        // --- Outside Hazard 1.1: flood surge creeping toward the office ---
        floodTimer += dt;
        if (floodTimer >= 6) {
            floodTimer = 0;
            if (!leak && floodNode < 4 && rng.nextInt(20) + 1 <= floodAI && wall[floodNode + 1] <= 0) {
                floodNode++;
                if (floodNode == 4) startLeak();
            }
        }

        // --- Outside Hazard 1.2: flooding zones that need flood walls ---
        zoneRoll += dt;
        if (zoneRoll >= 10) {
            zoneRoll = 0;
            int active = 0;
            for (double z : zoneFlood) if (z > 0) active++;
            if (active < 2 && rng.nextInt(20) + 1 <= floodAI / 2 + 1) {
                int z = rng.nextInt(ZONES.length);
                if (zoneFlood[z] <= 0 && wall[z] <= 0) zoneFlood[z] = 0.001;
            }
        }
        for (int i = 0; i < ZONES.length; i++) {
            if (wall[i] > 0) wall[i] -= dt;
            if (zoneFlood[i] > 0) {
                zoneFlood[i] += dt;
                if (zoneFlood[i] >= ZONE_OVERFLOW) {
                    zoneFlood[i] = 0;
                    if (!leak) startLeak(); else water = Math.min(1, water + 0.1);
                }
            }
        }

        // --- Office Hazard 1: flooding room ---
        if (leak) {
            if (doorClosed) {
                leakFix += dt;
                if (leakFix >= LEAK_FIX) {
                    leak = false; leakFix = 0; stopLeak();
                    if (floodNode == 4) floodNode = 1;
                }
            } else {
                leakFix = 0;
                water += 0.035 * dt;
            }
        } else {
            water = Math.max(0, water - 0.05 * dt);
        }
        if (water >= 1) gameOver("THE OFFICE FLOODED");
    }

    void triggerBlackout(String reason) {
        blackout = true; camUp = false; doorClosed = false; rebootProg = -1; power = 0;
        toast(reason);
        beep(90, 400);
    }

    void gameOver(String reason) {
        overReason = reason;
        state = State.OVER;
        stopLeak();
    }

    void startLeak() {
        if (leak) return;
        leak = true; leakFix = 0;
        try {
            leakClip = makeClip(noise());
            leakClip.loop(Clip.LOOP_CONTINUOUSLY);
        } catch (Exception ignored) { }
    }

    void stopLeak() {
        if (leakClip != null) {
            try { leakClip.stop(); leakClip.close(); } catch (Exception ignored) { }
            leakClip = null;
        }
    }

    void toast(String s) { toast = s; toastT = 3; }

    void toggleCam() {
        if (blackout || rebootProg >= 0) return;
        camUp = !camUp;
        if (camUp) padOpen = false;
    }

    void toggleDoor() {
        if (blackout || rebootProg >= 0 || camUp) return;
        doorClosed = !doorClosed;
        beep(doorClosed ? 240 : 320, 80);
    }

    void togglePad() { if (!camUp) padOpen = !padOpen; }

    void startReboot() {
        if (!padOpen || rebootProg >= 0) return;
        rebootProg = 0; camUp = false; doorClosed = false;
        beep(440, 100);
    }

    void deployWall() {
        if (!camUp || blackout || wall[selCam] > 0) return;
        power -= WALL_COST;
        wall[selCam] = WALL_TIME;
        zoneFlood[selCam] = 0;
        if (floodNode == selCam && !leak) { floodNode = 0; floodTimer = 0; }
        beep(520, 120);
        if (power <= 0) triggerBlackout("POWER LOST");
    }

    // ===================== SOUND =====================
    static final AudioFormat FMT = new AudioFormat(22050, 16, 1, true, false);

    static Clip makeClip(byte[] pcm) throws Exception {
        Clip c = AudioSystem.getClip();
        c.open(FMT, pcm, 0, pcm.length);
        return c;
    }

    static byte[] noise() {   // sloshing / trickling water loop
        Random r = new Random(7);
        int n = 22050;
        byte[] b = new byte[n * 2];
        double lp = 0;
        for (int i = 0; i < n; i++) {
            lp = lp * 0.82 + (r.nextDouble() * 2 - 1) * 0.18;
            double env = 0.55 + 0.45 * Math.sin(2 * Math.PI * 4 * i / 22050.0);
            short v = (short) (lp * env * 0.9 * 32767);
            b[2 * i] = (byte) v;
            b[2 * i + 1] = (byte) (v >> 8);
        }
        return b;
    }

    static void beep(int hz, int ms) {
        new Thread(() -> {
            try {
                int n = 22050 * ms / 1000;
                byte[] b = new byte[n * 2];
                for (int i = 0; i < n; i++) {
                    double env = Math.min(1.0, Math.min(i, n - i) / 400.0);
                    short v = (short) (Math.sin(2 * Math.PI * hz * i / 22050.0) * env * 0.3 * 32767);
                    b[2 * i] = (byte) v;
                    b[2 * i + 1] = (byte) (v >> 8);
                }
                Clip c = makeClip(b);
                c.addLineListener(ev -> { if (ev.getType() == LineEvent.Type.STOP) ev.getLine().close(); });
                c.start();
            } catch (Exception ignored) { }
        }).start();
    }

    // ===================== DRAWING =====================
    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(8, 9, 14));
        g.fillRect(0, 0, W, H);

        if (state == State.MENU) { drawMenu(g); return; }
        if (state == State.OVER) { drawEnd(g, "GAME OVER", overReason, new Color(220, 70, 70)); return; }
        if (state == State.WIN) { drawEnd(g, "6 AM - YOU SURVIVED", "The sun is up and the water is going down.", new Color(110, 220, 140)); return; }

        if (camUp) drawCameras(g); else drawOffice(g);
        drawHud(g);
        drawBar(g);

        if (captions && leak && !camUp) {
            g.setFont(new Font("SansSerif", Font.ITALIC, 15));
            g.setColor(new Color(200, 210, 230));
            center(g, "[ water trickling ]", W / 2, 570);
        }
        if (toastT > 0) {
            g.setFont(new Font("SansSerif", Font.BOLD, 20));
            g.setColor(new Color(255, 255, 255, (int) (255 * Math.min(1, toastT))));
            center(g, toast, W / 2, 105);
        }
    }

    void center(Graphics2D g, String s, int x, int y) {
        g.drawString(s, x - g.getFontMetrics().stringWidth(s) / 2, y);
    }

    void poly(Graphics2D g, Color c, int... p) {
        int n = p.length / 2;
        int[] xs = new int[n], ys = new int[n];
        for (int i = 0; i < n; i++) { xs[i] = p[2 * i]; ys[i] = p[2 * i + 1]; }
        g.setColor(c);
        g.fillPolygon(xs, ys, n);
    }

    void bar(Graphics2D g, int x, int y, int w, int h, double f, Color c) {
        g.setColor(new Color(30, 32, 40));
        g.fillRect(x, y, w, h);
        g.setColor(c);
        g.fillRect(x, y, (int) (w * Math.max(0, Math.min(1, f))), h);
        g.setColor(new Color(170, 175, 185));
        g.drawRect(x, y, w, h);
    }

    void drawMenu(Graphics2D g) {
        g.setColor(new Color(90, 170, 255));
        g.setFont(new Font("SansSerif", Font.BOLD, 64));
        center(g, "FLOOD WATCH", W / 2, 150);
        g.setFont(new Font("SansSerif", Font.PLAIN, 20));
        g.setColor(new Color(200, 205, 215));
        center(g, "Survive the night shift, 12 AM to 6 AM.", W / 2, 195);
        String[] t = {
            "DOOR   - click the right side (or press D). Listen for trickling water, then keep it closed ~3 seconds.",
            "CAMERAS - SPACE or the bar at the bottom. Click a zone (or press 1-7) to look at it.",
            "FLOOD WALL - on camera, press W / click DEPLOY. Blocks flooding zones and the flood surge, but drains power.",
            "REBOOT PAD - click the pad on the left (or press R), then REBOOT (ENTER). Fixes blackouts and refills system HP.",
            "Open door = leak keeps filling the room. Closed door and cameras drain power. No power = blackout.",
            "Press C to toggle captions."
        };
        g.setFont(new Font("SansSerif", Font.PLAIN, 16));
        for (int i = 0; i < t.length; i++) {
            g.setColor(new Color(170, 178, 192));
            center(g, t[i], W / 2, 270 + i * 34);
        }
        g.setFont(new Font("SansSerif", Font.BOLD, 24));
        g.setColor(Color.WHITE);
        if (((int) (anim * 2)) % 2 == 0) center(g, "Press ENTER or click to start", W / 2, 540);
    }

    void drawEnd(Graphics2D g, String title, String sub, Color c) {
        g.setColor(c);
        g.setFont(new Font("SansSerif", Font.BOLD, 56));
        center(g, title, W / 2, 270);
        g.setColor(new Color(210, 214, 224));
        g.setFont(new Font("SansSerif", Font.PLAIN, 22));
        center(g, sub, W / 2, 320);
        g.setFont(new Font("SansSerif", Font.BOLD, 22));
        center(g, "Press ENTER or click to play again", W / 2, 420);
    }

    void drawHud(Graphics2D g) {
        String[] hrs = {"12 AM", "1 AM", "2 AM", "3 AM", "4 AM", "5 AM"};
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 30));
        g.drawString(hrs[Math.min(5, (int) (time / HOUR))], 24, 44);
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.drawString("POWER " + (int) Math.max(0, power) + "%", 24, 66);
        bar(g, 24, 72, 150, 12, power / 100, power > 25 ? new Color(240, 200, 60) : new Color(230, 70, 60));
        g.setColor(Color.WHITE);
        g.drawString("SYSTEM HP", W - 174, 66);
        bar(g, W - 174, 72, 150, 12, hp / HP_MAX, hp > 6 ? new Color(90, 200, 120) : new Color(230, 70, 60));
    }

    void drawBar(Graphics2D g) {
        boolean off = blackout || rebootProg >= 0;
        g.setColor(off ? new Color(40, 40, 46) : new Color(60, 70, 90));
        g.fillRoundRect(BAR.x, BAR.y, BAR.width, BAR.height, 14, 14);
        g.setColor(off ? new Color(110, 110, 120) : Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        center(g, camUp ? "CLOSE CAMERAS [SPACE]" : "CAMERAS [SPACE]", BAR.x + BAR.width / 2, BAR.y + 23);
    }

    // ---------- OFFICE ----------
    void drawOffice(Graphics2D g) {
        poly(g, new Color(18, 20, 28), 0, 0, W, 0, 700, 140, 300, 140);                      // ceiling
        poly(g, new Color(110, 114, 120), 300, 140, 700, 140, 700, 440, 300, 440);             // back wall
        poly(g, new Color(92, 96, 102), 0, 0, 300, 140, 300, 440, 0, H);                      // left wall
        poly(g, new Color(86, 90, 96), W, 0, 700, 140, 700, 440, W, H);                       // right wall
        poly(g, new Color(150, 155, 162), 0, H, W, H, 700, 440, 300, 440);                    // floor
        g.setColor(new Color(120, 124, 130));
        for (int i = 1; i < 6; i++) g.drawLine(300 + i * 400 / 6, 440, i * W / 6, H);          // floor tiles

        g.setColor(new Color(20, 34, 70));
        g.fillRect(400, 190, 110, 130);                                                       // window
        g.setColor(new Color(10, 10, 14));
        g.drawRect(400, 190, 110, 130);
        g.drawRect(401, 191, 108, 128);

        Color box = new Color(139, 98, 58);
        int[][] boxes = {{330, 350, 70, 55}, {410, 335, 60, 70}, {480, 350, 65, 55}, {560, 340, 70, 65}};
        for (int[] b : boxes) { g.setColor(box); g.fillRect(b[0], b[1], b[2], b[3]); g.setColor(new Color(90, 60, 30)); g.drawRect(b[0], b[1], b[2], b[3]); }

        poly(g, new Color(205, 208, 212), 250, 400, 750, 400, 790, 430, 210, 430);            // table top
        g.setColor(new Color(10, 10, 12));
        g.fillRect(210, 430, 580, 18);
        g.fillRect(240, 448, 40, 110);
        g.fillRect(720, 448, 40, 110);

        g.setColor(box);                                                                       // boxes on table
        g.fillRect(620, 328, 80, 74);
        g.fillRect(560, 350, 55, 52);
        g.setColor(new Color(90, 60, 30));
        g.drawRect(620, 328, 80, 74);
        g.drawRect(560, 350, 55, 52);
        g.setFont(new Font("SansSerif", Font.BOLD, 10));
        g.drawString("MoveOn", 630, 370);

        g.setColor(new Color(60, 62, 70));                                                     // fan
        g.drawOval(424, 346, 52, 52);
        g.setStroke(new BasicStroke(3));
        for (int i = 0; i < 3; i++) {
            double a = anim * 9 + i * 2.094;
            g.draw(new Line2D.Double(450, 372, 450 + 22 * Math.cos(a), 372 + 22 * Math.sin(a)));
        }
        g.setStroke(new BasicStroke(1));
        g.fillRect(443, 398, 14, 6);

        // door on the right wall
        Color doorFill = doorClosed ? new Color(116, 126, 138) : new Color(6, 8, 14);
        poly(g, doorFill, 840, 170, 940, 120, 940, 500, 840, 450);
        g.setColor(new Color(235, 190, 30));
        g.setStroke(new BasicStroke(4));
        g.drawPolygon(new int[]{840, 940, 940, 840}, new int[]{170, 120, 500, 450}, 4);
        g.setStroke(new BasicStroke(1));
        if (doorClosed) {
            g.setColor(new Color(90, 98, 108));
            for (int i = 0; i < 9; i++) g.drawLine(842, 190 + i * 30, 938, 148 + i * 40);
        } else {
            g.setColor(new Color(40, 60, 100, 90));
            g.fillRect(870, 200, 50, 220);
        }
        g.setColor(new Color(230, 235, 245));
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.drawString("DOOR [D]", 858, 530);

        // leak drips under the door
        if (leak) {
            g.setColor(new Color(90, 160, 255, 190));
            for (int i = 0; i < 7; i++) {
                double ph = (anim * 1.7 + i * 0.37) % 1.0;
                int x = 850 + i * 12;
                g.fillRect(x, (int) (440 + ph * 70), 3, 10);
            }
        }
        // rising water
        if (water > 0) {
            int top = (int) (H - water * 330);
            g.setColor(new Color(40, 110, 230, 120));
            g.fillRect(0, top, W, H - top);
            g.setColor(new Color(150, 200, 255, 140));
            g.drawLine(0, top, W, top);
        }

        // darkness
        g.setColor(new Color(0, 0, 10, blackout ? 240 : 95));
        g.fillRect(0, 0, W, H);

        drawPad(g);

        if (blackout && padPulse()) {
            g.setColor(new Color(230, 70, 60));
            g.setFont(new Font("SansSerif", Font.BOLD, 40));
            center(g, "BLACKOUT", W / 2, 260);
            g.setFont(new Font("SansSerif", Font.PLAIN, 18));
            center(g, "Open the reboot pad on the left [R]", W / 2, 295);
        }
    }

    boolean padPulse() { return ((int) (anim * 2)) % 2 == 0; }

    void drawPad(Graphics2D g) {
        Color glow = blackout ? new Color(230, 70, 60) : new Color(70, 220, 130);
        g.setColor(new Color(36, 38, 46));
        g.fillRoundRect(PAD_HIT.x, PAD_HIT.y + 20, 110, 130, 10, 10);
        g.setColor(glow);
        g.drawRoundRect(PAD_HIT.x, PAD_HIT.y + 20, 110, 130, 10, 10);
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        g.drawString("REBOOT PAD", PAD_HIT.x + 12, PAD_HIT.y + 50);
        g.drawString("[R]", PAD_HIT.x + 12, PAD_HIT.y + 70);
        if (!padOpen) return;

        g.setColor(new Color(14, 16, 22, 245));
        g.fillRoundRect(PAD_PANEL.x, PAD_PANEL.y, PAD_PANEL.width, PAD_PANEL.height, 16, 16);
        g.setColor(glow);
        g.drawRoundRect(PAD_PANEL.x, PAD_PANEL.y, PAD_PANEL.width, PAD_PANEL.height, 16, 16);
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        g.drawString("SYSTEM REBOOT", PAD_PANEL.x + 20, PAD_PANEL.y + 36);
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.setColor(new Color(200, 205, 215));
        g.drawString(blackout ? "STATUS: SYSTEM ERROR" : "STATUS: ONLINE", PAD_PANEL.x + 20, PAD_PANEL.y + 66);
        g.drawString("Integrity", PAD_PANEL.x + 20, PAD_PANEL.y + 100);
        bar(g, PAD_PANEL.x + 20, PAD_PANEL.y + 108, 290, 14, hp / HP_MAX, hp > 6 ? new Color(90, 200, 120) : new Color(230, 70, 60));
        g.drawString("Rebooting disables cameras and the door for a few seconds.", PAD_PANEL.x + 20, PAD_PANEL.y + 150);
        if (rebootProg >= 0) {
            bar(g, REBOOT_BTN.x, REBOOT_BTN.y, REBOOT_BTN.width, REBOOT_BTN.height, rebootProg / REBOOT_TIME, new Color(70, 140, 230));
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 18));
            center(g, "REBOOTING...", REBOOT_BTN.x + REBOOT_BTN.width / 2, REBOOT_BTN.y + 32);
        } else {
            g.setColor(new Color(50, 130, 80));
            g.fillRoundRect(REBOOT_BTN.x, REBOOT_BTN.y, REBOOT_BTN.width, REBOOT_BTN.height, 12, 12);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 18));
            center(g, "REBOOT [ENTER]", REBOOT_BTN.x + REBOOT_BTN.width / 2, REBOOT_BTN.y + 32);
        }
    }

    // ---------- CAMERAS ----------
    void drawCameras(Graphics2D g) {
        g.setColor(new Color(10, 13, 18));
        g.fillRect(0, 0, W, H);
        for (int i = 0; i < ZONES.length; i++) {
            Rectangle r = ZONES[i];
            boolean road = i == 3 || i == 4;
            g.setColor(i == 0 ? new Color(58, 56, 48) : road ? new Color(34, 37, 44) : new Color(22, 25, 32));
            g.fillRect(r.x, r.y, r.width, r.height);
            g.setColor(new Color(185, 190, 198));
            g.setStroke(new BasicStroke(3));
            g.drawRect(r.x, r.y, r.width, r.height);
            g.setStroke(new BasicStroke(1));
            g.setFont(new Font("Serif", Font.BOLD, i == 3 ? 14 : 20));
            g.setColor(new Color(215, 218, 224));
            if (i == 3) {
                Graphics2D rg = (Graphics2D) g.create();
                rg.rotate(-Math.PI / 2, r.x + 36, r.y + r.height / 2);
                rg.drawString(CAM_NAMES[i], r.x + 36 - 40, r.y + r.height / 2);
                rg.dispose();
            } else {
                center(g, CAM_NAMES[i].toLowerCase(), r.x + r.width / 2, r.y + r.height / 2 + 6);
            }
            g.setFont(new Font("SansSerif", Font.BOLD, 11));
            g.setColor(new Color(130, 140, 160));
            g.drawString("CAM " + (i + 1), r.x + 5, r.y + 14);
        }
        g.setColor(new Color(230, 190, 40));
        g.setStroke(new BasicStroke(3));
        g.drawRect(OFFICE_BOX.x, OFFICE_BOX.y, OFFICE_BOX.width, OFFICE_BOX.height);
        g.setStroke(new BasicStroke(1));
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        center(g, "OFFICE", OFFICE_BOX.x + OFFICE_BOX.width / 2, OFFICE_BOX.y + 38);

        // flooded / walled zones are only visible once you view that camera
        Rectangle s = ZONES[selCam];
        boolean flooding = zoneFlood[selCam] > 0;
        boolean surge = floodNode == selCam;
        if (flooding || surge) {
            g.setColor(new Color(40, 110, 230, 120));
            g.fillRect(s.x, s.y, s.width, s.height);
            g.setColor(new Color(170, 210, 255, 170));
            for (int i = 0; i < 4; i++) {
                int y = s.y + 12 + (int) (((anim * 25) + i * s.height / 4.0) % Math.max(1, s.height - 14));
                g.drawLine(s.x + 4, y, s.x + s.width - 4, y);
            }
        }
        if (wall[selCam] > 0) {
            g.setColor(new Color(255, 140, 20));
            g.fillRect(s.x, s.y, s.width, 6);
            g.fillRect(s.x, s.y + s.height - 6, s.width, 6);
        }
        g.setColor(new Color(60, 220, 230));
        g.setStroke(new BasicStroke(4));
        g.drawRect(s.x - 2, s.y - 2, s.width + 4, s.height + 4);
        g.setStroke(new BasicStroke(1));

        // status panel
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        g.setColor(Color.WHITE);
        g.drawString("CAM " + (selCam + 1) + " - " + CAM_NAMES[selCam], 40, 520);
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        String status = "CLEAR";
        Color sc = new Color(110, 220, 140);
        if (wall[selCam] > 0) { status = "FLOOD WALL UP (" + (int) Math.ceil(wall[selCam]) + "s)"; sc = new Color(255, 160, 40); }
        else if (flooding) { status = "STREET FLOODING!"; sc = new Color(255, 90, 80); }
        else if (surge) { status = "FLOOD SURGE HERE!"; sc = new Color(255, 90, 80); }
        g.setColor(sc);
        g.drawString(status, 40, 548);

        // deploy button
        boolean can = wall[selCam] <= 0;
        g.setColor(can ? new Color(190, 100, 20) : new Color(60, 60, 66));
        g.fillRoundRect(WALL_BTN.x, WALL_BTN.y, WALL_BTN.width, WALL_BTN.height, 12, 12);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        center(g, "DEPLOY FLOOD WALL [W]", WALL_BTN.x + WALL_BTN.width / 2, WALL_BTN.y + 22);
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        center(g, "costs " + (int) WALL_COST + "% power", WALL_BTN.x + WALL_BTN.width / 2, WALL_BTN.y + 40);

        // camera static
        for (int i = 0; i < 60; i++) {
            g.setColor(new Color(255, 255, 255, 12 + rng.nextInt(18)));
            int y = rng.nextInt(H), x = rng.nextInt(W);
            g.drawLine(x, y, x + 20 + rng.nextInt(80), y);
        }
    }

    // ===================== INPUT =====================
    @Override
    public void keyPressed(KeyEvent e) {
        int k = e.getKeyCode();
        if (state != State.PLAY) {
            if (k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE) startGame();
            return;
        }
        if (k == KeyEvent.VK_SPACE) toggleCam();
        else if (k == KeyEvent.VK_D) toggleDoor();
        else if (k == KeyEvent.VK_R) togglePad();
        else if (k == KeyEvent.VK_ENTER) startReboot();
        else if (k == KeyEvent.VK_W) deployWall();
        else if (k == KeyEvent.VK_C) captions = !captions;
        else if (k >= KeyEvent.VK_1 && k <= KeyEvent.VK_7 && camUp) selCam = k - KeyEvent.VK_1;
    }

    @Override
    public void mousePressed(MouseEvent e) {
        Point p = e.getPoint();
        if (state != State.PLAY) { startGame(); return; }
        if (BAR.contains(p)) { toggleCam(); return; }
        if (camUp) {
            for (int i = 0; i < ZONES.length; i++) if (ZONES[i].contains(p)) selCam = i;
            if (WALL_BTN.contains(p)) deployWall();
            return;
        }
        if (padOpen && REBOOT_BTN.contains(p)) startReboot();
        else if (PAD_HIT.contains(p)) togglePad();
        else if (DOOR_HIT.contains(p)) toggleDoor();
    }

    @Override public void keyReleased(KeyEvent e) { }
    @Override public void keyTyped(KeyEvent e) { }
    @Override public void mouseClicked(MouseEvent e) { }
    @Override public void mouseReleased(MouseEvent e) { }
    @Override public void mouseEntered(MouseEvent e) { }
    @Override public void mouseExited(MouseEvent e) { }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Flood Watch");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.add(new FloodWatch());
            f.pack();
            f.setResizable(false);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }
}

