package com.dutyscheduler.duty.api.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The running record: per Trooper, and per day logged.
 */
public record RecordsResponse(List<TrooperRecord> troopers, List<DayRecord> days) {

    /**
     * One Trooper's totals across every logged day.
     *
     * @param active   false for an archived Trooper
     * @param opening  hours from before the app was used
     * @param worked   hours stood on logged sheets
     * @param credited hours credited for logged days not stood
     * @param total    opening + worked + credited
     * @param duties   logged days with at least one hour stood
     * @param nights   logged days with duty in the silent hours, 2200 to 0559
     * @param bus      logged days with a BUS hour
     * @param mc       leave days; a half day is 0.5. {@code ma} counts appointments
     */
    public record TrooperRecord(String name, boolean active, int opening, int worked, int credited, int total,
            int duties, int nights, int bus, double mc, double ol, double al, int ma) {
    }

    /**
     * One logged day.
     *
     * @param credits hours credited that day, by Trooper
     * @param night   who stood the silent hours
     * @param bus     who had a BUS hour
     * @param issues  rule breaks on the logged sheet
     */
    public record DayRecord(LocalDate date, int worked, int credited, Map<String, Integer> credits,
            List<String> night, List<String> bus, int issues) {
    }
}
