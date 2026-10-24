package com.dutyscheduler.duty.export;

import com.dutyscheduler.duty.domain.DutyDay;
import com.dutyscheduler.duty.domain.Post;
import com.dutyscheduler.duty.domain.Schedule;
import com.dutyscheduler.duty.domain.Trooper;
import com.dutyscheduler.duty.solver.ScheduleSolver;
import com.dutyscheduler.duty.solver.SolveRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Reads pixels back. Text isn't checked since fonts vary. */
class SheetImageTest {

    private static final List<Trooper> NINE = List.of(
            Trooper.of("TROOPER_A"), Trooper.of("TROOPER_B"), Trooper.of("TROOPER_C"), Trooper.of("TROOPER_D"),
            Trooper.of("TROOPER_E"), Trooper.of("TROOPER_F"), Trooper.of("TROOPER_G"),
            Trooper.stayOut("TROOPER_H"), Trooper.stayOut("TROOPER_I"));
    private static final DutyDay THURSDAY = DutyDay.of(LocalDate.of(2026, 9, 10));

    // The layout, as SheetImage draws it.
    private static final int PAD = 24, NAME_W = 150, HOUR_W = 34, TALLY_W = 46, ROW_H = 26, HEAD_H = 22;
    private static final int FIRST_ROW_Y = PAD + 30 + 22 + 14 + HEAD_H;

    private static Schedule solve(Integer turnout) {
        SolveRequest request = SolveRequest.of(THURSDAY, NINE, NINE.subList(0, 3)).withTurnout(turnout);
        return new ScheduleSolver().solve(request).schedule();
    }

    private static BufferedImage decode(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    /** Just inside a cell's top-left corner, in image pixels. */
    private static Color groundAt(BufferedImage image, int row, int slot) {
        int s = SheetImage.SCALE;
        return new Color(image.getRGB((PAD + NAME_W + slot * HOUR_W + 3) * s, (FIRST_ROW_Y + row * ROW_H + 5) * s));
    }

    @Test
    @DisplayName("PNG at 2x the layout size")
    void sizeFollowsTheSheet() throws IOException {
        Schedule sheet = solve(null);
        int rows = (int) sheet.rows().stream().filter(r -> r.hours() > 0).count();

        BufferedImage image = decode(SheetImage.png(sheet, null, Map.of(), "admin", Instant.now()));

        int s = SheetImage.SCALE;
        assertEquals((PAD * 2 + NAME_W + DutyDay.SLOT_COUNT * HOUR_W + 2 * TALLY_W) * s, image.getWidth());
        assertEquals((PAD + 30 + 22 + 14 + HEAD_H + rows * ROW_H + 18 + 16 + PAD) * s, image.getHeight());
    }

    @Test
    @DisplayName("cells are coloured by post")
    void cellsMatchTheSheet() throws IOException {
        Schedule sheet = solve(null);
        List<Schedule.Row> rows = sheet.rows().stream().filter(r -> r.hours() > 0).toList();

        BufferedImage image = decode(SheetImage.png(sheet, null, Map.of(), "admin", Instant.now()));

        for (int r = 0; r < rows.size(); r++) {
            for (int slot = 0; slot < DutyDay.SLOT_COUNT; slot++) {
                Post post = rows.get(r).at(slot);
                Color expected = post == null ? Color.WHITE : SheetImage.POST_COLOURS.get(post)[0];
                assertEquals(expected, groundAt(image, r, slot),
                        rows.get(r).trooper().name() + " at slot " + slot + " should be " + (post == null ? "empty" : post));
            }
        }
    }

    @Test
    @DisplayName("fixed turnout gets an MP row")
    void mpRow() throws IOException {
        Schedule sheet = solve(5);
        int rows = (int) sheet.rows().stream().filter(r -> r.hours() > 0).count();
        List<List<Post>> mp = SheetImage.shortfall(sheet);
        int slot = 0;
        while (mp.get(slot).isEmpty()) {
            slot++;
        }

        BufferedImage image = decode(SheetImage.png(sheet, 5, Map.of(), "admin", Instant.now()));

        assertEquals((PAD + 30 + 22 + 14 + HEAD_H + (rows + 1) * ROW_H + 18 + 16 + PAD) * SheetImage.SCALE, image.getHeight(),
                "one row more than the Troopers: the MP team's");
        assertEquals(SheetImage.MP_COLOURS[0], groundAt(image, rows, slot));
    }

    @Test
    @DisplayName("free hours on leave show the kind")
    void leaveHoursAreDrawn() throws IOException {
        Schedule sheet = solve(null);
        Schedule.Row first = sheet.rows().stream().filter(r -> r.hours() > 0).findFirst().orElseThrow();
        int free = 0;
        while (first.at(free) != null) {
            free++;
        }
        String[] hours = new String[DutyDay.SLOT_COUNT];
        hours[free] = "MA";

        BufferedImage image = decode(SheetImage.png(sheet, null,
                Map.of(first.trooper().name(), Arrays.asList(hours)), "admin", Instant.now()));

        assertEquals(SheetImage.AWAY_COLOURS[0], groundAt(image, 0, free));
    }

    @Test
    @DisplayName("deterministic")
    void deterministic() {
        Schedule sheet = solve(null);
        Instant at = Instant.parse("2026-09-10T12:00:00Z");
        assertArrayEquals(SheetImage.png(sheet, null, Map.of(), "admin", at), SheetImage.png(sheet, null, Map.of(), "admin", at));
    }

    @Test
    @DisplayName("subtitle counts Troopers with hours")
    void subtitleCountsOnDuty() {
        Schedule solved = solve(null);
        Schedule.Builder builder = Schedule.builder(THURSDAY);
        solved.rows().forEach(r -> builder.row(r.trooper(), r.posts()));
        Schedule withAway = builder.empty(new Trooper("TROOPER_Z", true)).build();

        String line = SheetImage.subtitle(THURSDAY, withAway.rows(), null, 0);
        assertTrue(line.contains("47 hours over 9 Troopers"), line);
    }

    @Test
    @DisplayName("hour lines, heavier at midnight")
    void gridLines() throws IOException {
        Schedule sheet = solve(null);
        List<Schedule.Row> rows = sheet.rows().stream().filter(r -> r.hours() > 0).toList();
        BufferedImage image = decode(SheetImage.png(sheet, null, Map.of(), "admin", Instant.now()));
        int s = SheetImage.SCALE;

        // On an hour edge with nobody on either side.
        int checked = 0;
        for (int r = 0; r < rows.size(); r++) {
            for (int edge = 1; edge < DutyDay.SLOT_COUNT; edge++) {
                if (rows.get(r).at(edge - 1) != null || rows.get(r).at(edge) != null) {
                    continue;
                }
                Color expected = edge == 4 ? SheetImage.GRID_STRONG : SheetImage.GRID;
                Color actual = new Color(image.getRGB((PAD + NAME_W + edge * HOUR_W) * s,
                        (FIRST_ROW_Y + r * ROW_H + ROW_H / 2) * s));
                assertEquals(expected, actual, rows.get(r).trooper().name() + " at the edge before slot " + edge);
                checked++;
            }
        }
        assertTrue(checked > 50, "enough empty edges to mean something: " + checked);
    }
}
