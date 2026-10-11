package io.jenkins.plugins.asyntai;

/** A failed call to the Asyntai API. The status is 0 when the host could not be reached. */
public class ApiException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int status;

    public ApiException(String message, int status) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    /** A bad key, a plan limit or a rate limit hits every remaining file the same way. */
    public boolean isAccountRejection() {
        return status == 401 || status == 403 || status == 429;
    }
}
