package com.iitm.beacon.common.error;

/**
 * What the site's HTML error page ({@code templates/error/page.html}) says
 * for an HTTP status: a fixed title and one plain sentence, never anything
 * taken from an exception (NFR-ERROR-TRANSPARENCY). A status without its own
 * text gets the generic client-error text (4xx) or the server-error text
 * (anything else).
 *
 * @param status  the HTTP status the page is answered with
 * @param title   the page's title and heading
 * @param message one sentence saying what happened and what to do
 */
public record ErrorPage(int status, String title, String message) {

    /** The Thymeleaf template that renders the page. */
    public static final String TEMPLATE = "error/page";

    private static final String SERVER_ERROR_TITLE = "Something went wrong";
    private static final String SERVER_ERROR_MESSAGE =
            "An unexpected error occurred on our side. Please try again later.";

    public static ErrorPage forStatus(int status) {
        return switch (status) {
            case 400 -> new ErrorPage(status, "Bad request",
                    "This request couldn't be understood. Check the link or the form and try again.");
            case 403 -> new ErrorPage(status, "Access denied",
                    "This request was refused. Reload the page and try again.");
            case 404 -> new ErrorPage(status, "Page not found",
                    "There's no page at this address. It may have been moved or removed.");
            case 405 -> new ErrorPage(status, "Action not allowed",
                    "This page doesn't accept that kind of request.");
            case 406 -> new ErrorPage(status, "Format not available",
                    "This page can't be sent in a format your browser accepts.");
            case 415 -> new ErrorPage(status, "Unsupported format",
                    "This page can't read data sent in that format.");
            default -> status >= 400 && status < 500
                    ? new ErrorPage(status, "Request not completed",
                            "This request couldn't be completed. Go back and try again.")
                    : new ErrorPage(status, SERVER_ERROR_TITLE, SERVER_ERROR_MESSAGE);
        };
    }
}
