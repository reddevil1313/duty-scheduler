package com.dutyscheduler.duty.api;

import com.dutyscheduler.duty.persistence.AppUserEntity;
import com.dutyscheduler.duty.persistence.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The owner, teams, invite links and members, the way people use them: every
 * account here signs in through the real login and works in its own session,
 * and every account but the owner's is made by accepting an invite.
 */
@SpringBootTest(properties = {
        "owner.bootstrap.username=root",
        "owner.bootstrap.password=root-password" })
@AutoConfigureMockMvc
@Testcontainers
class TeamsIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AppUserRepository users;

    @Autowired
    ApplicationRunner bootstrapOwner;

    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void onlyTheOwner() {
        // Everything but the owner, so each test starts from a server with no teams.
        jdbc.execute("DELETE FROM invite");
        jdbc.execute("DELETE FROM app_user WHERE role <> 'OWNER'");
        jdbc.execute("TRUNCATE hour_credit, duty_assignment, duty_sheet, absence, trooper CASCADE");
        jdbc.execute("DELETE FROM team");
    }

    // ---- helpers ------------------------------------------------------------

    private MockHttpSession signIn(String username, String password) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/session/login").with(csrf())
                        .param("username", username).param("password", password))
                .andExpect(status().isNoContent())
                .andReturn().getRequest().getSession();
    }

    private static MockHttpServletRequestBuilder in(MockHttpSession session, MockHttpServletRequestBuilder request) {
        return request.session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(MockHttpServletRequestBuilder request, int status) throws Exception {
        String body = mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse()
                .getContentAsString();
        return json.readValue(body, Map.class);
    }

    /** As the owner: a team, and the token of the invite for its first admin. */
    @SuppressWarnings("unchecked")
    private String newTeam(String name) throws Exception {
        Map<String, Object> created = read(in(signIn("root", "root-password"), post("/api/teams"))
                .content("{\"name\":\"" + name + "\"}"), 201);
        return (String) ((Map<String, Object>) created.get("invite")).get("token");
    }

    private void accept(String token, String username) throws Exception {
        mvc.perform(post("/api/invites/" + token + "/accept").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","displayName":"%s","password":"long-enough-password"}
                        """.formatted(username, username)))
                .andExpect(status().isCreated());
    }

    /** A team with an admin signed in, ready to invite. */
    private MockHttpSession adminOf(String team, String username) throws Exception {
        accept(newTeam(team), username);
        return signIn(username, "long-enough-password");
    }

    private String invite(MockHttpSession admin, String role) throws Exception {
        return (String) read(in(admin, post("/api/invites")).content("{\"role\":\"" + role + "\"}"), 201)
                .get("token");
    }

    // ---- the owner ----------------------------------------------------------

    @Test
    @DisplayName("the owner is made from the environment once, and a restart leaves their password alone")
    void ownerBootstrap() throws Exception {
        AppUserEntity owner = users.findByUsername("root").orElseThrow();
        assertThat(owner.getRole()).isEqualTo("OWNER");
        assertThat(owner.getTeamId()).isNull();

        String hash = owner.getPasswordHash();
        bootstrapOwner.run(new DefaultApplicationArguments());
        assertThat(users.findByUsername("root")).map(AppUserEntity::getPasswordHash).hasValue(hash);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE role = 'OWNER'", Long.class)).isOne();
    }

    @Test
    @DisplayName("the owner has no team: the session says so, and every team endpoint refuses them")
    void ownerSeesNoTeamData() throws Exception {
        MockHttpSession owner = signIn("root", "root-password");
        mvc.perform(get("/api/session").session(owner))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.team").value(nullValue()));
        for (String path : new String[] { "/api/troopers", "/api/absences", "/api/records",
                "/api/schedules/2026-09-10", "/api/members", "/api/invites" }) {
            mvc.perform(get(path).session(owner)).andExpect(status().isForbidden());
        }
        mvc.perform(in(owner, post("/api/schedules/generate"))
                .content("{\"date\":\"2026-09-10\",\"nightGroup\":[\"TROOPER_A\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the owner makes a team with a first-admin invite; names are unique ignoring case")
    void ownerMakesTeams() throws Exception {
        MockHttpSession owner = signIn("root", "root-password");
        Map<String, Object> created = read(in(owner, post("/api/teams")).content("{\"name\":\" Alpha \"}"), 201);
        assertThat(created).extractingByKey("team").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("name", "Alpha").containsEntry("admins", 0);
        assertThat(created).extractingByKey("invite").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("role", "ADMIN").containsKey("token");

        mvc.perform(in(owner, post("/api/teams")).content("{\"name\":\"ALPHA\"}")).andExpect(status().isConflict());
        mvc.perform(get("/api/teams").session(owner))
                .andExpect(jsonPath("$[*].name", contains("Alpha")));
    }

    @Test
    @DisplayName("only the owner lists or makes teams")
    void teamsAreTheOwners() throws Exception {
        MockHttpSession admin = adminOf("Alpha", "alpha_admin");
        mvc.perform(get("/api/teams").session(admin)).andExpect(status().isForbidden());
        mvc.perform(in(admin, post("/api/teams")).content("{\"name\":\"Mine\"}")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the owner can invite into an existing team, and a missing team is a 404")
    void ownerInvitesIntoATeam() throws Exception {
        newTeam("Alpha");
        MockHttpSession owner = signIn("root", "root-password");
        long id = jdbc.queryForObject("SELECT id FROM team WHERE name = 'Alpha'", Long.class);
        String token = (String) read(in(owner, post("/api/teams/" + id + "/invites")).content("{\"role\":\"ADMIN\"}"), 201)
                .get("token");
        mvc.perform(get("/api/invites/" + token)).andExpect(jsonPath("$.team").value("Alpha"));
        mvc.perform(in(owner, post("/api/teams/999999/invites")).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isNotFound());
    }

    // ---- invite links -------------------------------------------------------

    @Test
    @DisplayName("an invite link shows its team and role to anyone, and accepting it makes a signed-in member")
    void acceptAnInvite() throws Exception {
        String token = newTeam("Alpha");
        mvc.perform(get("/api/invites/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.team").value("Alpha"))
                .andExpect(jsonPath("$.role").value("ADMIN"));

        accept(token, "alpha_admin");
        MockHttpSession session = signIn("alpha_admin", "long-enough-password");
        mvc.perform(get("/api/session").session(session))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.team").value("Alpha"));
        // In the team, which starts with an empty roster.
        mvc.perform(get("/api/troopers").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("a link works once; used, unknown and expired links are the same 404")
    void linksDie() throws Exception {
        String token = newTeam("Alpha");
        accept(token, "alpha_admin");
        mvc.perform(get("/api/invites/" + token)).andExpect(status().isNotFound());
        mvc.perform(post("/api/invites/" + token + "/accept").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"second\",\"displayName\":\"Second\",\"password\":\"long-enough-password\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/invites/not-a-real-token")).andExpect(status().isNotFound());

        String expiring = invite(signIn("alpha_admin", "long-enough-password"), "USER");
        jdbc.update("UPDATE invite SET expires_at = now() - interval '1 minute' WHERE used_at IS NULL");
        mvc.perform(get("/api/invites/" + expiring)).andExpect(status().isNotFound());
        assertThat(users.findByUsername("second")).isEmpty();
    }

    @Test
    @DisplayName("a taken username is a 409 and leaves the invite usable; a bad username or short password is a 400")
    void acceptingRefusals() throws Exception {
        String token = newTeam("Alpha");
        accept(token, "alpha_admin");
        String next = invite(signIn("alpha_admin", "long-enough-password"), "USER");

        mvc.perform(post("/api/invites/" + next + "/accept").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alpha_admin\",\"displayName\":\"X\",\"password\":\"long-enough-password\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/invites/" + next + "/accept").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"Has Spaces\",\"displayName\":\"X\",\"password\":\"long-enough-password\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/invites/" + next + "/accept").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newcomer\",\"displayName\":\"X\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                // The message written on the request type, as a problem document.
                .andExpect(jsonPath("$.detail").value("A password is at least 10 characters."));

        // Still open after all three.
        mvc.perform(get("/api/invites/" + next)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("USER"));
        accept(next, "newcomer");
        assertThat(users.findByUsername("newcomer")).map(AppUserEntity::getRole).hasValue("USER");
    }

    @Test
    @DisplayName("an admin sees the team's pending invites without their tokens, and can revoke one")
    void pendingAndRevoke() throws Exception {
        MockHttpSession admin = adminOf("Alpha", "alpha_admin");
        String token = invite(admin, "USER");

        mvc.perform(get("/api/invites").session(admin))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[0].createdBy").value("alpha_admin"))
                .andExpect(jsonPath("$[0].token").value(nullValue()));
        long id = jdbc.queryForObject("SELECT id FROM invite WHERE used_at IS NULL", Long.class);

        // Another team's admin cannot revoke it.
        MockHttpSession other = adminOf("Bravo", "bravo_admin");
        mvc.perform(in(other, delete("/api/invites/" + id))).andExpect(status().isNotFound());
        mvc.perform(get("/api/invites/" + token)).andExpect(status().isOk());

        mvc.perform(in(admin, delete("/api/invites/" + id))).andExpect(status().isNoContent());
        mvc.perform(get("/api/invites/" + token)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a USER cannot invite or see invites")
    void usersDoNotInvite() throws Exception {
        MockHttpSession admin = adminOf("Alpha", "alpha_admin");
        accept(invite(admin, "USER"), "alpha_user");
        MockHttpSession user = signIn("alpha_user", "long-enough-password");
        mvc.perform(in(user, post("/api/invites")).content("{\"role\":\"USER\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/invites").session(user)).andExpect(status().isForbidden());
    }

    // ---- members ------------------------------------------------------------

    @Test
    @DisplayName("an admin lists the team's accounts, and only the team's")
    void members() throws Exception {
        MockHttpSession admin = adminOf("Alpha", "alpha_admin");
        accept(invite(admin, "USER"), "alpha_user");
        adminOf("Bravo", "bravo_admin");

        mvc.perform(get("/api/members").session(admin))
                .andExpect(jsonPath("$[*].username", contains("alpha_admin", "alpha_user")))
                .andExpect(jsonPath("$[0].you").value(true))
                .andExpect(jsonPath("$[1].you").value(false));
    }

    @Test
    @DisplayName("a team keeps an admin: the last cannot be demoted, and nobody removes themselves")
    void keepAnAdmin() throws Exception {
        MockHttpSession admin = adminOf("Alpha", "alpha_admin");
        long self = users.findByUsername("alpha_admin").orElseThrow().getId();

        mvc.perform(in(admin, put("/api/members/" + self)).content("{\"role\":\"USER\"}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(in(admin, delete("/api/members/" + self))).andExpect(status().isUnprocessableContent());

        // With a second admin, the first may step down.
        accept(invite(admin, "ADMIN"), "alpha_second");
        mvc.perform(in(admin, put("/api/members/" + self)).content("{\"role\":\"USER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    @DisplayName("a removed account's session ends at its next request; a demoted admin's too")
    void sessionsFollowTheAccount() throws Exception {
        MockHttpSession admin = adminOf("Alpha", "alpha_admin");
        accept(invite(admin, "USER"), "alpha_user");
        accept(invite(admin, "ADMIN"), "alpha_second");
        MockHttpSession user = signIn("alpha_user", "long-enough-password");
        MockHttpSession second = signIn("alpha_second", "long-enough-password");
        mvc.perform(get("/api/troopers").session(user)).andExpect(status().isOk());

        long userId = users.findByUsername("alpha_user").orElseThrow().getId();
        mvc.perform(in(admin, delete("/api/members/" + userId))).andExpect(status().isNoContent());
        mvc.perform(get("/api/troopers").session(user)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/session").session(user)).andExpect(jsonPath("$.authenticated").value(false));

        long secondId = users.findByUsername("alpha_second").orElseThrow().getId();
        mvc.perform(in(admin, put("/api/members/" + secondId)).content("{\"role\":\"USER\"}")).andExpect(status().isOk());
        mvc.perform(in(second, post("/api/invites")).content("{\"role\":\"USER\"}")).andExpect(status().isUnauthorized());
        // Signing in again gives the new role.
        mvc.perform(get("/api/session").session(signIn("alpha_second", "long-enough-password")))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    @DisplayName("another team's member is a 404 to change or remove")
    void othersMembers() throws Exception {
        MockHttpSession alpha = adminOf("Alpha", "alpha_admin");
        adminOf("Bravo", "bravo_admin");
        long bravo = users.findByUsername("bravo_admin").orElseThrow().getId();
        mvc.perform(in(alpha, put("/api/members/" + bravo)).content("{\"role\":\"USER\"}")).andExpect(status().isNotFound());
        mvc.perform(in(alpha, delete("/api/members/" + bravo))).andExpect(status().isNotFound());
        assertThat(users.findByUsername("bravo_admin")).map(AppUserEntity::getRole).hasValue("ADMIN");
    }
}
