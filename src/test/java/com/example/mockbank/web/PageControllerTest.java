package com.example.mockbank.web;

import com.example.mockbank.model.AuditEventType;
import com.example.mockbank.service.BankService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PageController.class)
class PageControllerTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BankService bankService;

    @ParameterizedTest
    @ValueSource(strings = {"/", "/login", "/members/search", "/members/12345", "/members/12345/subaccounts/new", "/ops/audit", "/ops/reports"})
    void clientSideRoutesServeTheReactApp(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void logoutEndsTheSessionAndReturnsToSignIn() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("operatorId", "operator");

        mvc.perform(get("/logout").session(session)).andExpect(redirectedUrl("/login"));

        assertThat(session.isInvalid()).isTrue();
        verify(bankService).audit(AuditEventType.OPERATOR_SIGNED_OUT, "operator", null, "Operator signed out");
    }

    @Test
    void expireSessionSimulatesATimeoutForAutomationDrills() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("operatorId", "operator");

        mvc.perform(get("/debug/expire-session").session(session)).andExpect(redirectedUrl("/login?reason=expired"));

        assertThat(session.isInvalid()).isTrue();
    }
}
