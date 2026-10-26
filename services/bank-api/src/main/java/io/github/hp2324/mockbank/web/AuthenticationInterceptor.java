package io.github.hp2324.mockbank.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/** Rejects /api calls without a signed-in operator; the error handler turns this into a JSON 401. */
class AuthenticationInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        OperatorSession.require(request.getSession(false));
        return true;
    }
}
