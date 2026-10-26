package com.example.mockbank.api;

import com.example.mockbank.model.AuditEventType;
import com.example.mockbank.service.BankService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SessionController.class)
class SessionControllerTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BankService bankService;

    @Test
    void signsInWithTheDemoCredentials() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mvc.perform(post("/api/session").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operatorId\":\"operator\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operatorId").value("operator"));

        assertThat(session.getAttribute("operatorId")).isEqualTo("operator");
        verify(bankService).audit(AuditEventType.OPERATOR_SIGNED_IN, "operator", null, "Operator signed in");
    }

    @Test
    void rejectsWrongCredentialsWithoutStartingASession() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mvc.perform(post("/api/session").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operatorId\":\"operator\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Invalid operator ID or password."));

        assertThat(session.getAttribute("operatorId")).isNull();
        verify(bankService).audit(AuditEventType.OPERATOR_SIGN_IN_FAILED, null, null, "Rejected sign-in attempt");
    }

    @Test
    void currentSessionIs401WhenNobodyIsSignedIn() throws Exception {
        mvc.perform(get("/api/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void currentSessionReturnsTheSignedInOperator() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("operatorId", "operator");

        mvc.perform(get("/api/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operatorId").value("operator"));
    }

    @Test
    void signOutEndsTheSessionAndAuditsIt() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("operatorId", "operator");

        mvc.perform(delete("/api/session").session(session)).andExpect(status().isNoContent());

        assertThat(session.isInvalid()).isTrue();
        verify(bankService).audit(AuditEventType.OPERATOR_SIGNED_OUT, "operator", null, "Operator signed out");
    }

    @Test
    void malformedJsonIsABadRequest() throws Exception {
        mvc.perform(post("/api/session").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verifyNoInteractions(bankService);
    }
}
