package ai.gradpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import ai.gradpath.domain.CareerOfficer;
import ai.gradpath.domain.Student;
import ai.gradpath.security.SessionCookies;
import ai.gradpath.service.AuthService;
import jakarta.servlet.http.Cookie;

/** Role-based route protection, CSRF, cookies, onboarding gate and page rendering through the full web stack. */
@AutoConfigureMockMvc
class WebSecurityIT extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;

    private Cookie session(Object user) {
        return new Cookie(SessionCookies.NAME, auth.startSession((ai.gradpath.domain.User) user, false));
    }

    @Test
    void landingIsPublicAndProtectedPagesRedirectToSignIn() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Get started")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-password-toggle=\"password\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("aria-controls=\"si-pw\"")));
        mvc.perform(get("/student")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/?signin=required"));
        mvc.perform(get("/officer/reports")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/messages")).andExpect(status().is3xxRedirection());
    }

    @Test
    void securityHeadersAreSent() throws Exception {
        mvc.perform(get("/")).andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'self'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void studentsCannotOpenOfficerPagesAndViceVersa() throws Exception {
        Cookie stu = session(student("Role Student"));
        Cookie off = session(officer("Role Officer"));
        mvc.perform(get("/officer").cookie(stu)).andExpect(status().isForbidden());
        mvc.perform(get("/officer/reports/students.csv").cookie(stu)).andExpect(status().isForbidden());
        mvc.perform(get("/student/readiness").cookie(off)).andExpect(status().isForbidden());
        mvc.perform(get("/student").cookie(stu)).andExpect(status().isOk());
        mvc.perform(get("/officer").cookie(off)).andExpect(status().isOk());
    }

    @Test
    void everyStudentPageRenders() throws Exception {
        Cookie stu = session(student("Page Student"));
        for (String p : new String[] {"/student", "/student/profile", "/student/profile/edit", "/student/readiness",
                "/student/skill-gap", "/student/careers", "/student/careers/data-analyst", "/student/jobs",
                "/student/applications", "/student/resume", "/student/goals", "/student/assistant", "/messages", "/rules", "/model"}) {
            MvcResult r = mvc.perform(get(p).cookie(stu)).andReturn();
            assertThat(r.getResponse().getStatus()).as(p).isEqualTo(200);
        }
    }

    @Test
    void everyOfficerPageRenders() throws Exception {
        CareerOfficer o = officer("Page Officer");
        job(o, true, false);
        Student s = student("Directory Student");
        Cookie off = session(o);
        for (String p : new String[] {"/officer", "/officer/students", "/officer/students/" + s.getId(), "/officer/applications",
                "/officer/jobs", "/officer/jobs/new", "/officer/reports", "/officer/profile", "/messages", "/rules", "/model"}) {
            MvcResult r = mvc.perform(get(p).cookie(off)).andReturn();
            assertThat(r.getResponse().getStatus()).as(p).isEqualTo(200);
        }
        MvcResult csv = mvc.perform(get("/officer/reports/students.csv").cookie(off)).andExpect(status().isOk()).andReturn();
        String body = csv.getResponse().getContentAsString();
        assertThat(body).contains("Record ID").doesNotContain(s.getEmail()).doesNotContain("Directory Student")
                .doesNotContain(s.getPhone());
    }

    @Test
    void postsWithoutCsrfAreRejected() throws Exception {
        Cookie stu = session(student("Csrf Student"));
        mvc.perform(post("/student/goals").cookie(stu).param("title", "No token goal")).andExpect(status().isForbidden());
        mvc.perform(post("/student/goals").cookie(stu).with(csrf()).param("title", "With token goal"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void loginSetsHttpOnlySameSiteCookieAndLogoutRevokes() throws Exception {
        Student s = student("Cookie Student");
        MvcResult r = mvc.perform(post("/login").with(csrf()).param("email", s.getEmail()).param("password", "Passw0rd1")
                .param("remember", "true")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/student")).andReturn();
        String setCookie = r.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(SessionCookies.NAME))
                .findFirst().orElseThrow();
        assertThat(setCookie).contains("HttpOnly").contains("SameSite=Lax").contains("Max-Age=2592000");
        String token = setCookie.substring(SessionCookies.NAME.length() + 1, setCookie.indexOf(';'));
        mvc.perform(post("/logout").with(csrf()).cookie(new Cookie(SessionCookies.NAME, token))).andExpect(status().is3xxRedirection());
        assertThat(auth.resolve(token)).isEmpty();
    }

    @Test
    void wrongPasswordShowsGenericError() throws Exception {
        Student s = student("Wrong Pw");
        mvc.perform(post("/login").with(csrf()).param("email", s.getEmail()).param("password", "nope12345"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/#get-started"));
    }

    @Test
    void newStudentsAreSentToOnboarding() throws Exception {
        Student fresh = new Student();
        fresh.setName("Fresh Student");
        fresh.setEmail(uniqueEmail("fresh"));
        fresh.setPasswordHash(passwords.hash("Passw0rd1"));
        users.saveAndFlush(fresh);
        Cookie c = session(fresh);
        mvc.perform(get("/student/jobs").cookie(c)).andExpect(redirectedUrl("/onboarding"));
        mvc.perform(get("/onboarding").cookie(c)).andExpect(status().isOk());
    }

    @Test
    void readinessAndOfficerPagesShowTheSecondaryModelEstimate() throws Exception {
        Student s = student("Model Student");
        mvc.perform(get("/student/readiness").cookie(session(s))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Data-driven placement estimate")));
        Cookie off = session(officer("Model Officer"));
        mvc.perform(get("/officer/students/" + s.getId()).cookie(off)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Model placement estimate")));
        mvc.perform(get("/model").cookie(off)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Candidate model comparison")));
    }

    @Test
    void whatIfIsComputedButNeverSaved() throws Exception {
        Student s = student("WhatIf Student");
        Cookie c = session(s);
        String json = "{\"cgpa\":4.0,\"projects\":7,\"internships\":5,\"workMonths\":0,\"confidentSkills\":8,"
                + "\"learningSkills\":0,\"certifications\":4,\"communication\":10,\"extracurricular\":10}";
        mvc.perform(post("/student/readiness/what-if").cookie(c).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("\"score\":100.0")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"modelPercent\"")));
        String invalid = json.replace("\"cgpa\":4.0", "\"cgpa\":9.0");
        mvc.perform(post("/student/readiness/what-if").cookie(c).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest());
        Student reloaded = (Student) users.findById(s.getId()).orElseThrow();
        assertThat(reloaded.getCgpa()).isEqualTo(3.4);
        assertThat(reloaded.getProjects()).isEqualTo(3);
    }
}
