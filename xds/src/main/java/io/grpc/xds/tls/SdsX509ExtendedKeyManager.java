/*
 * Copyright 2019 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.xds.tls;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.ssl.SslContext;
import io.netty.util.CharsetUtil;

import javax.crypto.*;
import javax.crypto.spec.PBEKeySpec;
import javax.net.ssl.X509ExtendedKeyManager;
import java.io.*;
import java.net.Socket;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Collection;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SdsX509ExtendedKeyManager extends X509ExtendedKeyManager {
    private static final Logger logger = Logger.getLogger(SdsX509ExtendedKeyManager.class.getName());

    private String fileNamePrivateKey;
    private String fileNameCertChain;
    SdsX509ExtendedKeyManager(String fileName, String certChain) {
        this.fileNamePrivateKey = fileName;
        this.fileNameCertChain = certChain;
    }

    @Override
    public String[] getClientAliases(String s, Principal[] principals) {
        System.out.println("getClientAliases called;");
        return new String[] {fileNamePrivateKey};
    }

    @Override
    public String chooseClientAlias(String[] strings, Principal[] principals, Socket socket) {
        System.out.println("chooseClientAlias called;");
        return fileNamePrivateKey;
    }

    @Override
    public String[] getServerAliases(String s, Principal[] principals) {
        System.out.println("getServerAliases called;");
        return new String[] {fileNamePrivateKey};
    }

    @Override
    public String chooseServerAlias(String s, Principal[] principals, Socket socket) {
        System.out.println("chooseServerAlias called;");
        return fileNamePrivateKey;
    }

    @Override
    public X509Certificate[] getCertificateChain(String s) {
        System.out.println("getCertificateChain called: " + s);
        X509Certificate[] x509Certs = null;
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certs = cf.generateCertificates(new FileInputStream(fileNameCertChain));
            x509Certs = certs.toArray(new X509Certificate[0]);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return x509Certs;
    }

    protected static PKCS8EncodedKeySpec generateKeySpec(char[] password, byte[] key)
            throws IOException, NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeySpecException,
            InvalidKeyException, InvalidAlgorithmParameterException {

        if (password == null) {
            return new PKCS8EncodedKeySpec(key);
        }

        EncryptedPrivateKeyInfo encryptedPrivateKeyInfo = new EncryptedPrivateKeyInfo(key);
        SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(encryptedPrivateKeyInfo.getAlgName());
        PBEKeySpec pbeKeySpec = new PBEKeySpec(password);
        SecretKey pbeKey = keyFactory.generateSecret(pbeKeySpec);

        Cipher cipher = Cipher.getInstance(encryptedPrivateKeyInfo.getAlgName());
        cipher.init(Cipher.DECRYPT_MODE, pbeKey, encryptedPrivateKeyInfo.getAlgParameters());

        return encryptedPrivateKeyInfo.getKeySpec(cipher);
    }

    private static PrivateKey getPrivateKeyFromByteBuffer(ByteBuf encodedKeyBuf, String keyPassword)
            throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeySpecException,
            InvalidAlgorithmParameterException, KeyException, IOException {

        byte[] encodedKey = new byte[encodedKeyBuf.readableBytes()];
        encodedKeyBuf.readBytes(encodedKey).release();

        PKCS8EncodedKeySpec encodedKeySpec = generateKeySpec(
                keyPassword == null ? null : keyPassword.toCharArray(), encodedKey);
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(encodedKeySpec);
        } catch (InvalidKeySpecException ignore) {
            try {
                return KeyFactory.getInstance("DSA").generatePrivate(encodedKeySpec);
            } catch (InvalidKeySpecException ignore2) {
                try {
                    return KeyFactory.getInstance("EC").generatePrivate(encodedKeySpec);
                } catch (InvalidKeySpecException e) {
                    throw new InvalidKeySpecException("Neither RSA, DSA nor EC worked", e);
                }
            }
        }
    }

    private static String readContent(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            for (;;) {
                int ret = in.read(buf);
                if (ret < 0) {
                    break;
                }
                out.write(buf, 0, ret);
            }
            return out.toString(CharsetUtil.US_ASCII.name());
        } finally {
            safeClose(out);
        }
    }

    private static void safeClose(OutputStream out) {
        try {
            out.close();
        } catch (IOException e) {
            logger.warning("Failed to close a stream." + e);
        }
    }

    private static final Pattern KEY_PATTERN = Pattern.compile(
            "-+BEGIN\\s+.*PRIVATE\\s+KEY[^-]*-+(?:\\s|\\r|\\n)+" + // Header
                    "([a-z0-9+/=\\r\\n]+)" +                       // Base64 text
                    "-+END\\s+.*PRIVATE\\s+KEY[^-]*-+",            // Footer
            Pattern.CASE_INSENSITIVE);

    static ByteBuf readPrivateKey(InputStream in) throws KeyException {
        String content;
        try {
            content = readContent(in);
        } catch (IOException e) {
            throw new KeyException("failed to read key input stream", e);
        }

        Matcher m = KEY_PATTERN.matcher(content);
        if (!m.find()) {
            throw new KeyException("could not find a PKCS #8 private key in input stream" +
                    " (see https://netty.io/wiki/sslcontextbuilder-and-private-key.html for more information)");
        }

        ByteBuf base64 = Unpooled.copiedBuffer(m.group(1), CharsetUtil.US_ASCII);
        ByteBuf der = Base64.decode(base64);
        base64.release();
        return der;
    }

    @Override
    public PrivateKey getPrivateKey(String s) {
        System.out.println("getPrivateKey called: " + s);
        //
        try {
            return getPrivateKeyFromByteBuffer(
                    readPrivateKey(new FileInputStream(fileNamePrivateKey)), null);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (NoSuchPaddingException e) {
            e.printStackTrace();
        } catch (InvalidKeySpecException e) {
            e.printStackTrace();
        } catch (InvalidAlgorithmParameterException e) {
            e.printStackTrace();
        } catch (KeyException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        ;
        return null;
    }
}
