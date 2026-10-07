package com.iitm.beacon.testsupport;

import static com.iitm.beacon.testsupport.Csrf.csrfField;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Brings a browser session to a login's code step the way a browser gets
 * there: by posting the email step. The email typed there is carried to the
 * code step in the session, never in the URL, so a test can't simply open
 * {@code /…/login/code?email=…} any more.
 */
public final class LoginCodeSteps {

    public static final String VISITOR_CODE_PAGE = "/submissions/login/code";
    public static final String ADMIN_CODE_PAGE = "/admin/login/code";

    private LoginCodeSteps() {
    }

    /** A new session that has just asked for a visitor login code for {@code email}. */
    public static MockHttpSession visitorAskedForACode(MockMvc mockMvc, String email) throws Exception {
        return askedForACode(mockMvc, "/submissions/login", email, new MockHttpSession());
    }

    /** A new session that has just asked for an admin login code for {@code email}. */
    public static MockHttpSession adminAskedForACode(MockMvc mockMvc, String email) throws Exception {
        return askedForACode(mockMvc, "/admin/login/request", email, new MockHttpSession());
    }

    /**
     * A GET of {@code path} — a plain one, except for a login's code page,
     * which is opened in a session that has just asked for a code for {@code
     * a@example.com} (without one, the code page sends the browser back to
     * the email step). For tests about what every page renders.
     */
    public static MockHttpServletRequestBuilder page(MockMvc mockMvc, String path) throws Exception {
        return switch (path) {
            case VISITOR_CODE_PAGE -> get(path).session(visitorAskedForACode(mockMvc, "a@example.com"));
            case ADMIN_CODE_PAGE -> get(path).session(adminAskedForACode(mockMvc, "a@example.com"));
            default -> get(path);
        };
    }

    /** {@code session}, after asking for a code for {@code email} at the email step posting to {@code path}. */
    public static MockHttpSession askedForACode(MockMvc mockMvc, String path, String email, MockHttpSession session)
            throws Exception {
        mockMvc.perform(post(path).with(csrfField()).param("email", email).session(session))
                .andExpect(status().isFound());
        return session;
    }
}
