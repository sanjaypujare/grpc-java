package io.grpc.xds.internal.certprovider;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import io.grpc.Status;
import io.grpc.internal.ExponentialBackoffPolicy;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MeshCAVerifier {
    public static void main(final String[] args) {
        if (args.length != 1) {
            System.out.println("Provide STS token as 1 arg");
            System.exit(1);
        }
        final Object[] values = new Object[3];
        CertificateProvider.DistributorWatcher watcher = new CertificateProvider.DistributorWatcher();
        watcher.addWatcher(new CertificateProvider.Watcher() {
            @Override
            public void updateCertificate(PrivateKey key, List<X509Certificate> certChain) {
                values[0] = key;
                values[1] = certChain;
            }

            @Override
            public void updateTrustedRoots(List<X509Certificate> trustedRoots) {
                values[2] = trustedRoots;
            }

            @Override
            public void onError(Status errorStatus) {
                System.out.println("error =" + errorStatus);
            }
        });

        GoogleCredentials oauth2Creds = new GoogleCredentials() {
            @Override
            public AccessToken refreshAccessToken() {
                return new AccessToken(args[0], new Date(System.currentTimeMillis() + 5000L));
            }
        };
        MeshCaCertificateProvider provider = new MeshCaCertificateProvider(watcher, true,
                "meshca.googleapis.com",
                "https://container.googleapis.com/v1/projects/meshca-unit-test/locations/us-west2-a/clusters/meshca-cluster",
                TimeUnit.HOURS.toSeconds(9L), 2048, "RSA", "SHA256withRSA",
                MeshCaCertificateProvider.ChannelFactory.getInstance(), new ExponentialBackoffPolicy.Provider(), TimeUnit.HOURS.toSeconds(1L), 4, oauth2Creds);

        provider.refreshCertificate();
        System.out.println("Put breakpoint at this line to check values");
    }
}
