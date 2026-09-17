package com.dutyscheduler.duty.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.dutyscheduler.duty.solver.ScheduleSolver;
import com.dutyscheduler.duty.rules.ScheduleValidator;

@Configuration
public class SolverConfig {

    @Bean
    public ScheduleSolver scheduleSolver() {
        return new ScheduleSolver();
    }

    @Bean
    public ScheduleValidator scheduleValidator() {
        return new ScheduleValidator();
    }
}
