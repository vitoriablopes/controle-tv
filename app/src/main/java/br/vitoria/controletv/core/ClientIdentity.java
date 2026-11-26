package br.vitoria.controletv.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * Identidade do celular perante a TV: uma chave RSA e um certificado
 * autoassinado. A TV memoriza este certificado no pareamento, então ele
 * precisa ser o mesmo para sempre (até a pessoa "esquecer" a TV).
 */
public final class ClientIdentity {
    private static final String ALIAS = "controletv";

    private final PrivateKey privateKey;
    private final X509Certificate certificate;

    private ClientIdentity(PrivateKey privateKey, X509Certificate certificate) {
        this.privateKey = privateKey;
        this.certificate = certificate;
    }

    public X509Certificate certificate() {
        return certificate;
    }

    public RSAPublicKey publicKey() {
        return (RSAPublicKey) certificate.getPublicKey();
    }

    /** Chave privada (PKCS#8) para guardar. */
    public byte[] encodedKey() {
        return privateKey.getEncoded();
    }

    /** Certificado (DER) para guardar. */
    public byte[] encodedCertificate() throws GeneralSecurityException {
        return certificate.getEncoded();
    }

    public static ClientIdentity load(byte[] pkcs8Key, byte[] derCert) throws GeneralSecurityException {
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8Key));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(derCert));
        return new ClientIdentity(key, cert);
    }

    public static ClientIdentity generate(String commonName) throws GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        byte[] sha256WithRsa = seq(oid(new int[]{1, 2, 840, 113549, 1, 1, 11}), new byte[]{0x05, 0x00});
        byte[] name = seq(set(seq(oid(new int[]{2, 5, 4, 3}),
                tlv(0x0C, commonName.getBytes(StandardCharsets.UTF_8)))));
        long now = System.currentTimeMillis();
        byte[] validity = seq(
                utcTime(new Date(now - 24L * 3600 * 1000)),
                utcTime(new Date(now + 3650L * 24 * 3600 * 1000)));
        byte[] serial = tlv(0x02, BigInteger.valueOf(now).toByteArray());
        byte[] version = tlv(0xA0, tlv(0x02, new byte[]{2})); // v3

        // basicConstraints CA:TRUE, pathlen 0 — igual ao certificado que outras implementações usam
        byte[] basicConstraints = seq(oid(new int[]{2, 5, 29, 19}),
                tlv(0x04, seq(new byte[]{0x01, 0x01, (byte) 0xFF}, new byte[]{0x02, 0x01, 0x00})));
        byte[] extensions = tlv(0xA3, seq(basicConstraints));

        byte[] tbs = seq(version, serial, sha256WithRsa, name, validity, name, kp.getPublic().getEncoded(),
                extensions);

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(tbs);
        byte[] signature = sig.sign();
        byte[] bitString = new byte[signature.length + 1];
        System.arraycopy(signature, 0, bitString, 1, signature.length);

        byte[] der = seq(tbs, sha256WithRsa, tlv(0x03, bitString));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
        return new ClientIdentity(kp.getPrivate(), cert);
    }

    // ------------------------------------------------------------------ TLS

    /**
     * Fábrica de conexões TLS que sempre apresenta o nosso certificado e aceita
     * o certificado da TV (que é autoassinado — a segurança vem do pareamento).
     */
    public SSLSocketFactory socketFactory() throws GeneralSecurityException {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[]{new FixedKeyManager()}, new TrustManager[]{new AcceptTvCertificate()},
                new SecureRandom());
        return ctx.getSocketFactory();
    }

    private final class FixedKeyManager extends X509ExtendedKeyManager {
        @Override public String[] getClientAliases(String keyType, Principal[] issuers) { return new String[]{ALIAS}; }
        @Override public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) { return ALIAS; }
        @Override public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) { return ALIAS; }
        @Override public String[] getServerAliases(String keyType, Principal[] issuers) { return null; }
        @Override public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) { return null; }
        @Override public X509Certificate[] getCertificateChain(String alias) { return new X509Certificate[]{certificate}; }
        @Override public PrivateKey getPrivateKey(String alias) { return privateKey; }
    }

    private static final class AcceptTvCertificate implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    // ------------------------------------------------------------------ DER

    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 6);
        out.write(tag);
        int len = content.length;
        if (len < 0x80) {
            out.write(len);
        } else if (len < 0x100) {
            out.write(0x81);
            out.write(len);
        } else if (len < 0x10000) {
            out.write(0x82);
            out.write(len >> 8);
            out.write(len & 0xFF);
        } else {
            out.write(0x83);
            out.write(len >> 16);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    private static byte[] seq(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, concat(parts));
    }

    private static byte[] oid(int[] arcs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            int v = arcs[i];
            byte[] tmp = new byte[5];
            int n = 0;
            do {
                tmp[n++] = (byte) (v & 0x7F);
                v >>>= 7;
            } while (v != 0);
            for (int j = n - 1; j >= 0; j--) out.write(tmp[j] | (j > 0 ? 0x80 : 0));
        }
        return tlv(0x06, out.toByteArray());
    }

    private static byte[] utcTime(Date date) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(date).getBytes(StandardCharsets.US_ASCII));
    }
}
