
package io.grpc.examples.helloworldtls;

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import javax.net.ssl.KeyManagerFactory;
import java.security.cert.X509Certificate;
import java.io.FileInputStream;
import java.io.IOException;

// for now we won't use this....
public class DynamicFactories {

    private KeyManagerFactory kmf;
    String certChainFilePath;
    String privateKeyFilePath;
    String trustCertCollectionFilePath;

    public DynamicFactories(String certChainFilePath,
                            String privateKeyFilePath,
                            String trustCertCollectionFilePath) {
        this.certChainFilePath = certChainFilePath;
        this.privateKeyFilePath = privateKeyFilePath;
        this.trustCertCollectionFilePath = trustCertCollectionFilePath;
    }

    public void init()
    		throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException, UnrecoverableKeyException {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(
                new FileInputStream(certChainFilePath));
        String principalName = cert.getSubjectX500Principal().getName();
        ks.setCertificateEntry(principalName, cert);
        kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, null);
    }

    public KeyManagerFactory getKeyManagerFactory() {
        return kmf;
    }

}