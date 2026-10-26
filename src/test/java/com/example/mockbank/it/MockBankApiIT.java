package com.example.mockbank.it;

import com.example.mockbank.support.PostgresIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The operator workflow the UI and automation drive, end to end through HTTP into PostgreSQL. */
@SpringBootTest(properties = "mockbank.kafka.enabled=false")
@AutoConfigureMockMvc
class MockBankApiIT extends PostgresIntegrationTest {
    @Autowired
    private MockMvc mvc;

    private MockHttpSession signIn() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/session").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operatorId\":\"operator\",\"password\":\"password\"}"))
                .andExpect(status().isOk());
        return session;
    }

    @Test
    void operatorOpensASubAccountAndSeesItInTheAuditLogAndReports() throws Exception {
        MockHttpSession session = signIn();

        mvc.perform(get("/api/members/lookup").param("memberId", "12345").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Jordan Lee"));

        String body = mvc.perform(post("/api/members/12345/subaccounts").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productName\":\"Savings Plus\",\"initialDeposit\":\"100.00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.checkpoint").value("CONFIRM-SUBACCOUNT-CREATED"))
                .andReturn().getResponse().getContentAsString();
        String accountNumber = JsonPath.read(body, "$.accountNumber");

        mvc.perform(get("/api/audit-events").param("limit", "1").session(session))
                .andExpect(jsonPath("$[0].type").value("SUBACCOUNT_OPENED"))
                .andExpect(jsonPath("$[0].accountNumber").value(accountNumber));

        mvc.perform(get("/api/reports/subaccount-openings").param("limit", "1").session(session))
                .andExpect(jsonPath("$[0].accountNumber").value(accountNumber))
                .andExpect(jsonPath("$[0].openedBy").value("operator"));
    }

    @Test
    void businessOutcomesComeBackAsTypedErrors() throws Exception {
        MockHttpSession session = signIn();

        mvc.perform(get("/api/members/lookup").param("memberId", "00000").session(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
        mvc.perform(post("/api/members/77777/subaccounts").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productName\":\"Savings Plus\",\"initialDeposit\":\"100.00\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        mvc.perform(post("/api/members/12345/subaccounts").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productName\":\"Savings Plus\",\"initialDeposit\":\"7500\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HUMAN_APPROVAL_REQUIRED"));

        mvc.perform(get("/api/audit-events").param("limit", "2").session(session))
                .andExpect(jsonPath("$[0].type").value("HUMAN_APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$[1].type").value("PERMISSION_DENIED"));
    }

    @Test
    void aMissingStaticFileIsA404NotAnApiError() throws Exception {
        mvc.perform(get("/favicon-that-does-not-exist.png")).andExpect(status().isNotFound());
    }

    @Test
    void anExpiredSessionGets401() throws Exception {
        MockHttpSession session = signIn();
        mvc.perform(get("/debug/expire-session").session(session));

        mvc.perform(get("/api/members/12345").session(session)).andExpect(status().isUnauthorized());
    }
}
