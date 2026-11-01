package com.vobiz.masking.xml;

import java.util.List;

/**
 * Builds the Vobiz (XML) call-control documents returned from the voice webhooks.
 * Keep responses small and valid — a malformed response drops the call.
 */
public final class VobizXml {

    private VobizXml() {
    }

    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> {
                    if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') sb.append(' ');
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    public static String response(String innerXml) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Response>" + innerXml + "</Response>";
    }

    public static String emptyResponse() {
        return response("");
    }

    public static String speak(String text) {
        return "<Speak>" + escape(text) + "</Speak>";
    }

    public static String hangup() {
        return "<Hangup/>";
    }

    /**
     * Dial one or more numbers. Multiple {@code <Number>} children ring SIMULTANEOUSLY;
     * the first to answer gets bridged. {@code callerId} must be a Vobiz-owned DID (M1),
     * so neither party ever sees the other's real number.
     *
     * @param redirect when false, execution continues with the XML after the Dial
     *                 (used for the voicemail fallback in the same document)
     */
    public static String dial(List<String> numbers, String callerId, int timeoutSec, String actionUrl, boolean redirect) {
        StringBuilder sb = new StringBuilder("<Dial");
        if (callerId != null && !callerId.isBlank()) sb.append(" callerId=\"").append(escape(callerId)).append('"');
        sb.append(" timeout=\"").append(timeoutSec).append('"');
        if (actionUrl != null && !actionUrl.isBlank()) {
            sb.append(" action=\"").append(escape(actionUrl)).append("\" method=\"POST\"");
        }
        sb.append(" redirect=\"").append(redirect).append("\">");
        for (String n : numbers) {
            sb.append("<Number>").append(escape(n)).append("</Number>");
        }
        sb.append("</Dial>");
        return sb.toString();
    }

    /**
     * Record a voicemail. maxLength is the hard ceiling (the 15-30s from the spec),
     * timeout ends the recording after N seconds of silence, finishOnKey lets the
     * caller end early with '#'. The recording URL is POSTed to the callback URL.
     */
    public static String record(String callbackUrl, int maxLengthSec, int silenceTimeoutSec) {
        return "<Record action=\"" + escape(callbackUrl) + "\" callbackUrl=\"" + escape(callbackUrl)
                + "\" method=\"POST\" maxLength=\"" + maxLengthSec + "\" timeout=\"" + silenceTimeoutSec
                + "\" playBeep=\"true\" finishOnKey=\"#\"/>";
    }

    /** "Nobody is free" flow: greeting -> record -> closing -> hangup. */
    public static String voicemailFallback(String greeting, String closing, String recordCallbackUrl,
                                           int maxLengthSec, int silenceTimeoutSec) {
        return speak(greeting)
                + record(recordCallbackUrl, maxLengthSec, silenceTimeoutSec)
                + speak(closing)
                + hangup();
    }

    /** Outbound click-to-call: the agent already answered; bridge to the customer showing M1. */
    public static String bridgeOutbound(String callerId, String to) {
        return response(dial(List.of(to), callerId, 60, null, true) + hangup());
    }

    /**
     * Gather DTMF digits. The digit(s) are POSTed to actionUrl as {@code Digits}.
     * On timeout, execution continues with whatever follows the Gather in the document
     * (typically a {@link #redirect} to a no-input handler).
     */
    public static String gather(String promptXml, String actionUrl, int numDigits, int timeoutSec) {
        StringBuilder sb = new StringBuilder("<Gather numDigits=\"").append(numDigits)
                .append("\" timeout=\"").append(timeoutSec).append("\"");
        if (actionUrl != null && !actionUrl.isBlank()) {
            sb.append(" action=\"").append(escape(actionUrl)).append("\" method=\"POST\"");
        }
        sb.append(">").append(promptXml).append("</Gather>");
        return sb.toString();
    }

    /** Continue call flow at another URL (POSTs the same call params, incl. CallUUID). */
    public static String redirect(String url) {
        return "<Redirect method=\"POST\">" + escape(url) + "</Redirect>";
    }

    public static String pause(int seconds) {
        return "<Pause length=\"" + seconds + "\"/>";
    }

    /** Play a pre-recorded audio file (for later use; prompts are TTS <Speak> today). */
    public static String play(String url) {
        return "<Play>" + escape(url) + "</Play>";
    }
}
