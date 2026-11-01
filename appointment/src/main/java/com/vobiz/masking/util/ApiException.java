package com.vobiz.masking.util;

import lombok.Getter;

/** Expected client/server error that maps to an HTTP status + JSON error body. */
@Getter
public class ApiException extends RuntimeException {
    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }
}
