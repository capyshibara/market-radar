package com.marketradar.fetch;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.InputStream;
import java.net.Socket;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;

/**
 * Build a standard PKIX SSL context that supplies one publisher-omitted intermediate.
 * The platform trust manager still validates the augmented chain to a normal trusted
 * root and HttpClient still performs hostname verification. This is not a trust-all
 * manager and is used only for explicitly selected hosts whose public servers
 * omit their issuing intermediate.
 */
final class PinnedIntermediateSslContext {
    private PinnedIntermediateSslContext() {}

    static SSLContext create(String classpathCertificate) {
        try (InputStream input = PinnedIntermediateSslContext.class
                .getResourceAsStream(classpathCertificate)) {
            if (input == null) throw new IllegalStateException(
                    "Pinned intermediate resource is missing: " + classpathCertificate);
            X509Certificate intermediate = (X509Certificate) CertificateFactory
                    .getInstance("X.509").generateCertificate(input);
            TrustManagerFactory factory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            X509ExtendedTrustManager platform = Arrays.stream(factory.getTrustManagers())
                    .filter(X509ExtendedTrustManager.class::isInstance)
                    .map(X509ExtendedTrustManager.class::cast)
                    .findFirst().orElseThrow(() -> new IllegalStateException(
                            "Platform X509ExtendedTrustManager is unavailable"));

            X509ExtendedTrustManager completing = new X509ExtendedTrustManager() {
                private X509Certificate[] complete(X509Certificate[] chain) {
                    if (chain == null || chain.length == 0) return chain;
                    X509Certificate last = chain[chain.length - 1];
                    boolean alreadyPresent = Arrays.stream(chain)
                            .anyMatch(cert -> cert.equals(intermediate));
                    if (alreadyPresent || !last.getIssuerX500Principal()
                            .equals(intermediate.getSubjectX500Principal())) return chain;
                    X509Certificate[] augmented = Arrays.copyOf(chain, chain.length + 1);
                    augmented[chain.length] = intermediate;
                    return augmented;
                }

                @Override public void checkClientTrusted(X509Certificate[] chain, String authType,
                                                          Socket socket) throws CertificateException {
                    platform.checkClientTrusted(chain, authType, socket);
                }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType,
                                                          Socket socket) throws CertificateException {
                    platform.checkServerTrusted(complete(chain), authType, socket);
                }
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType,
                                                          SSLEngine engine) throws CertificateException {
                    platform.checkClientTrusted(chain, authType, engine);
                }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType,
                                                          SSLEngine engine) throws CertificateException {
                    platform.checkServerTrusted(complete(chain), authType, engine);
                }
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    platform.checkClientTrusted(chain, authType);
                }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    platform.checkServerTrusted(complete(chain), authType);
                }
                @Override public X509Certificate[] getAcceptedIssuers() {
                    return platform.getAcceptedIssuers();
                }
            };

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new javax.net.ssl.TrustManager[]{completing}, new SecureRandom());
            return context;
        } catch (Exception error) {
            throw new IllegalStateException("Unable to initialise pinned intermediate PKIX context", error);
        }
    }
}
