package com.dutyscheduler.duty.export;

import com.dutyscheduler.duty.domain.DayDemand;
import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.Post;
import com.dutyscheduler.duty.domain.Schedule;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A duty sheet as a PNG, for sending on.
 *
 * <p>
 * Drawn with Java2D, which ships with the JDK, so there is nothing to install
 * and nothing to keep in step with the board's CSS — the colours are chosen
 * here for paper and phone screens, light and high-contrast, rather than copied
 * from the dark board. It draws exactly the sheet it is given; logging that
 * same sheet is the caller's job, done after the drawing succeeds.
 *
 * <p>
 * No Spring and no persistence: give it a sheet, get bytes.
 */
public final class SheetImage {

    /**
     * Drawn at twice its layout size. A sheet is sent on to phones and zoomed
     * into there, and at 1x the hour labels blur; every coordinate below is in
     * layout pixels, and the scale is applied once, to the whole drawing.
     */
    static final int SCALE = 2;

    private static final int PAD = 24;
    private static final int NAME_W = 150;
    private static final int HOUR_W = 34;
    private static final int TALLY_W = 46;
    private static final int ROW_H = 26;
    private static final int HEAD_H = 22;

    private static final Color INK = new Color(0x14202C);
    private static final Color DIM = new Color(0x64757F);
    private static final Color LINE = new Color(0xCCD6DF);
    private static final Color PAPER = Color.WHITE;
    private static final Color BAND = new Color(0xF3F6F9);

    /** Post colours: a light ground and a dark ink, each pair well above 4.5:1. */
    static final Map<Post, Color[]> POST_COLOURS = new EnumMap<>(Map.of(
            Post.AP, new Color[] { new Color(0xDDE4F8), new Color(0x2D4699) },
            Post.GG, new Color[] { new Color(0xD4EAE5), new Color(0x125A4E) },
            Post.PAC, new Color[] { new Color(0xF8E6D2), new Color(0x7D480C) },
            Post.BUS, new Color[] { new Color(0xF7DDE2), new Color(0x7A2C3D) }));
    static final Color[] MP_COLOURS = { new Color(0xEFE3FA), new Color(0x5B3590) };

    private static final String[] DAYS = { "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun" };
    private static final String[] MONTHS = { "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep",
            "Oct", "Nov", "Dec" };

    private SheetImage() {
    }

    /**
     * @param turnout  the fixed turnout, or null for the free split. With one,
     *                 the posts nobody is on are drawn as the MP team's row.
     * @param loggedBy who logged it, for the footer
     * @param loggedAt when, for the footer
     */
    public static byte[] png(Schedule sheet, Integer turnout, String loggedBy, Instant loggedAt) {
        DutyDay day = sheet.day();
        List<Schedule.Row> rows = sheet.rows().stream().filter(r -> r.hours() > 0).toList();
        List<List<Post>> mp = turnout == null ? List.of() : shortfall(sheet);
        boolean hasMp = mp.stream().anyMatch(p -> !p.isEmpty());

        int gridW = NAME_W + DutyDay.SLOT_COUNT * HOUR_W + 2 * TALLY_W;
        int gridRows = rows.size() + (hasMp ? 1 : 0);
        int width = PAD * 2 + gridW;
        int height = PAD + 30 + 22 + 14 + HEAD_H + gridRows * ROW_H + 18 + 16 + PAD;

        BufferedImage image = new BufferedImage(width * SCALE, height * SCALE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.scale(SCALE, SCALE);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(PAPER);
            g.fillRect(0, 0, width, height);

            int y = PAD;
            g.setColor(INK);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
            g.drawString("Duty sheet · " + longDate(day.date()), PAD, y + 20);
            y += 30;

            String subtitle = subtitle(day, rows, turnout, mp.stream().mapToInt(List::size).sum());
            g.setColor(DIM);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            g.drawString(subtitle, PAD, y + 14);
            y += 22 + 14;

            int x0 = PAD;
            drawHeader(g, day, x0, y);
            y += HEAD_H;
            for (Schedule.Row row : rows) {
                drawRow(g, row, x0, y);
                y += ROW_H;
            }
            if (hasMp) {
                drawMpRow(g, mp, x0, y);
                y += ROW_H;
            }
            g.setColor(LINE);
            g.setStroke(new BasicStroke(1));
            g.drawRect(x0, y - gridRows * ROW_H - HEAD_H, gridW, gridRows * ROW_H + HEAD_H);

            g.setColor(DIM);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.drawString("Logged by " + loggedBy + " · " + stamp(loggedAt), PAD, y + 26);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * The line under the title. Counts the Troopers with an hour on the sheet,
     * as the board's summary does, so a row for someone away all of it — on
     * leave, or a stay-out on a weekend — is not counted as someone on duty.
     */
    static String subtitle(DutyDay day, List<Schedule.Row> rows, Integer turnout, int mpHours) {
        int hours = rows.stream().mapToInt(Schedule.Row::hours).sum();
        long onDuty = rows.stream().filter(r -> r.hours() > 0).count();
        String split = turnout == null ? "free split" : turnout + "h each";
        return "2000 " + shortDate(day.date()) + " to 1959 " + shortDate(day.date().plusDays(1))
                + " · " + hours + " hours over " + onDuty + " Trooper" + (onDuty == 1 ? "" : "s") + " · " + split
                + (mpHours > 0 ? " · " + mpHours + " post-hour" + (mpHours == 1 ? "" : "s") + " to the MP team" : "");
    }

    private static void drawHeader(Graphics2D g, DutyDay day, int x0, int y) {
        g.setColor(BAND);
        g.fillRect(x0, y, NAME_W + DutyDay.SLOT_COUNT * HOUR_W + 2 * TALLY_W, HEAD_H);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        g.setColor(DIM);
        g.drawString("Trooper", x0 + 8, y + 15);
        for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
            centre(g, day.slot(slot).label().substring(0, 2), x0 + NAME_W + slot * HOUR_W, HOUR_W, y + 15);
        }
        int tx = x0 + NAME_W + DutyDay.SLOT_COUNT * HOUR_W;
        centre(g, "Hrs", tx, TALLY_W, y + 15);
        centre(g, "Span", tx + TALLY_W, TALLY_W, y + 15);
    }

    private static void drawRow(Graphics2D g, Schedule.Row row, int x0, int y) {
        g.setColor(LINE);
        g.drawLine(x0, y, x0 + NAME_W + DutyDay.SLOT_COUNT * HOUR_W + 2 * TALLY_W, y);
        g.setColor(INK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        String name = row.trooper().name() + (row.trooper().stayOut() ? "  SO" : "");
        g.drawString(name, x0 + 8, y + 17);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
            Post post = row.at(slot);
            if (post == null) {
                continue;
            }
            Color[] colours = POST_COLOURS.get(post);
            int cx = x0 + NAME_W + slot * HOUR_W;
            g.setColor(colours[0]);
            g.fillRect(cx + 1, y + 3, HOUR_W - 2, ROW_H - 6);
            g.setColor(colours[1]);
            centre(g, post.name(), cx, HOUR_W, y + 17);
        }
        int tx = x0 + NAME_W + DutyDay.SLOT_COUNT * HOUR_W;
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        g.setColor(INK);
        centre(g, String.valueOf(row.hours()), tx, TALLY_W, y + 17);
        centre(g, String.valueOf(row.span()), tx + TALLY_W, TALLY_W, y + 17);
    }

    private static void drawMpRow(Graphics2D g, List<List<Post>> mp, int x0, int y) {
        g.setColor(LINE);
        g.drawLine(x0, y, x0 + NAME_W + DutyDay.SLOT_COUNT * HOUR_W + 2 * TALLY_W, y);
        g.setColor(DIM);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        g.drawString("MP TEAM", x0 + 8, y + 17);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
            List<Post> posts = mp.get(slot);
            if (posts.isEmpty()) {
                continue;
            }
            int cx = x0 + NAME_W + slot * HOUR_W;
            g.setColor(MP_COLOURS[0]);
            g.fillRect(cx + 1, y + 3, HOUR_W - 2, ROW_H - 6);
            g.setColor(MP_COLOURS[1]);
            String label = posts.size() == 1 ? posts.get(0).name() : posts.get(0).name() + "+" + (posts.size() - 1);
            centre(g, label, cx, HOUR_W, y + 17);
        }
        int total = mp.stream().mapToInt(List::size).sum();
        g.setColor(INK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        centre(g, String.valueOf(total), x0 + NAME_W + DutyDay.SLOT_COUNT * HOUR_W, TALLY_W, y + 17);
    }

    /** Per hour, the posts nobody is on: the MP team's share of a fixed-turnout sheet. */
    static List<List<Post>> shortfall(Schedule sheet) {
        DayDemand demand = DayDemand.of(sheet.day());
        List<List<Post>> out = new ArrayList<>();
        for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
            List<Post> missing = new ArrayList<>();
            for (Post post : Post.values()) {
                final int s = slot;
                long have = sheet.rows().stream().filter(r -> r.at(s) == post).count();
                for (long k = have; k < demand.at(slot).getOrDefault(post, 0); k++) {
                    missing.add(post);
                }
            }
            out.add(missing);
        }
        return out;
    }

    private static void centre(Graphics2D g, String text, int x, int w, int baseline) {
        FontMetrics m = g.getFontMetrics();
        g.drawString(text, x + (w - m.stringWidth(text)) / 2, baseline);
    }

    // Spelled out by hand, as the board does: locale data differs between JDKs
    // ("Sept" or "Sep"), and a sheet should not change its spelling with them.
    private static String shortDate(LocalDate date) {
        return DAYS[date.getDayOfWeek().getValue() - 1] + " " + date.getDayOfMonth() + " " + MONTHS[date.getMonthValue() - 1];
    }

    private static String longDate(LocalDate date) {
        return shortDate(date) + " " + date.getYear();
    }

    private static String stamp(Instant at) {
        ZonedDateTime t = at.atZone(ZoneId.systemDefault());
        return shortDate(t.toLocalDate()) + " " + t.getYear() + " "
                + String.format("%02d%02d", t.getHour(), t.getMinute());
    }
}
