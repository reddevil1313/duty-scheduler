package com.dutyscheduler.duty;

import com.dutyscheduler.duty.persistence.TeamEntity;
import com.dutyscheduler.duty.persistence.TeamRepository;
import com.dutyscheduler.duty.persistence.TeamScope;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Every Spring test method runs as one team, "Test team", opened before its
 * {@code @BeforeEach} and closed after it.
 *
 * <p>
 * The tests seed rosters straight through the repositories, with nobody
 * signed in, and most of them make requests as a {@code @WithMockUser}, whose
 * principal names no team. Both fall back to the thread's scope, so with this
 * they work as one team exactly as they did before there were teams. A test
 * about teams signs in with real accounts, whose own team takes precedence
 * over this scope — which is the property it is there to prove.
 *
 * <p>
 * Registered in {@code META-INF/spring.factories}, so it joins the default
 * listeners for every Spring test without each class asking for it.
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
        // Early, so the scope is open before anything that touches the database.
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
