package com.dutyscheduler.duty;

import com.dutyscheduler.duty.persistence.TeamEntity;
import com.dutyscheduler.duty.persistence.TeamRepository;
import com.dutyscheduler.duty.persistence.TeamScope;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Runs every Spring test method in "Test team", so repository seeding and
 * {@code @WithMockUser} requests share a team. Registered in
 * {@code META-INF/spring.factories}.
 */
public class TestTeamListener implements TestExecutionListener, Ordered {

    public static final String TEAM = "Test team";

    private static final String SCOPE = TestTeamListener.class.getName() + ".scope";

    @Override
    public void beforeTestMethod(TestContext context) {
        TeamRepository teams = context.getApplicationContext().getBean(TeamRepository.class);
        long team = teams.findByNameIgnoreCase(TEAM).orElseGet(() -> teams.save(new TeamEntity(TEAM))).getId();
        context.setAttribute(SCOPE, TeamScope.open(team));
    }

    @Override
    public void afterTestMethod(TestContext context) {
        if (context.removeAttribute(SCOPE) instanceof TeamScope.Opened scope) {
            scope.close();
        }
    }

    /** The test team id, for a test that needs to name it. */
    public static long id(TeamRepository teams) {
        return teams.findByNameIgnoreCase(TEAM).orElseThrow().getId();
    }

    @Override
    public int getOrder() {

        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
