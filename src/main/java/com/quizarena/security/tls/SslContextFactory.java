package com.quizarena.security.tls;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

/** Loads the server private key and certificate chain from a PKCS#12 keystore. */
public final class SslContextFactory {
    private SslContextFactory() {
    }

    public static SSLContext serverContext(TlsServerConfig config) throws IOException, GeneralSecurityException {
        char[] storePassword = config.keyStorePassword();
        char[] keyPassword = config.keyPassword();
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(config.keyStorePath())) {
                store.load(input, storePassword);
            }
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, keyPassword);
            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(keys.getKeyManagers(), null, null);
            return context;
        } finally {
            Arrays.fill(storePassword, '\0');
            Arrays.fill(keyPassword, '\0');
        }
    }
}

