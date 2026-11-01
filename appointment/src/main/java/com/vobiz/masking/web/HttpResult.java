package com.vobiz.masking.web;

/** Framework-free HTTP result, so handlers can be tested without Spring MVC. */
public record HttpResult(int status, String contentType, String body) {

    public static HttpResult xml(int status, String body) {
        return new HttpResult(status, "text/xml; charset=UTF-8", body);
    }

    public static HttpResult json(int status, String body) {
        return new HttpResult(status, "application/json; charset=UTF-8", body);
    }
}
