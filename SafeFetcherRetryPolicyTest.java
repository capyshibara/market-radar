package com.marketradar.fetch;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;

/** Lightweight policy regression test; does not perform network I/O. */
public class SafeFetcherRetryPolicyTest {
    public static void main(String[] args) {
        check(SafeFetcher.isTransientNetworkFailure(new HttpConnectTimeoutException("timed out")),
                "connect timeout is transient");
        check(SafeFetcher.isTransientNetworkFailure(new ConnectException("connection reset")),
                "connection failure is transient");
        check(SafeFetcher.isTransientNetworkFailure(new IOException("EOF")),
                "plain temporary I/O failure is retried within the fixed cap");
        check(!SafeFetcher.isTransientNetworkFailure(new UnknownHostException("bad host")),
                "DNS failure is never retried");
        check(!SafeFetcher.isTransientNetworkFailure(new ProtocolException("bad protocol")),
                "protocol failure is never retried");
        System.out.println("SafeFetcherRetryPolicyTest: ALL PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("Failed: " + message);
    }
}
