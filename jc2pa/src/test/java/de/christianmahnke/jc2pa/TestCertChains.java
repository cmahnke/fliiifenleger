// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.jc2pa;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * Runtime-generated EC P-256 CA plus end-entity certificate chains for
 * tests (throwaway credentials, never valid outside the test JVM).
 *
 * <p>The profile mirrors what c2pa's certificate checker requires:
 * X.509v3, ECDSA-with-SHA256 on P-256, validity window covering now, EE
 * with {@code digitalSignature} key usage, an allowed EKU
 * ({@code emailProtection}), subject/authority key identifiers, and an
 * organization (O) attribute on both subjects. A bare self-signed
 * end-entity certificate is rejected, hence the minimal two-certificate
 * chain.
 */
final class TestCertChains {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private TestCertChains() {
    }

    /**
     * @param commonName CN base for the certificates.
     * @return {@code [chainPem, keyPem, caPem]}: PEM-encoded chain
     *         (end-entity first, then CA), PKCS#8 end-entity private key,
     *         and the CA certificate alone (usable as a trust anchor).
     */
    static byte[][] generateEs256Chain(String commonName) throws Exception {
        SecureRandom random = new SecureRandom();
        JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();
        Date notBefore = new Date(System.currentTimeMillis() - 3_600_000L);
        Date notAfter = new Date(System.currentTimeMillis() + 365L * 24 * 3_600_000L);

        // ── CA (self-signed) ────────────────────────────────────────────
        // Note: the subject needs an organization (O) attribute — c2pa
        // reports a missing O misleadingly as claimSignature.mismatch at
        // validation time.
        KeyPair caKeys = ecP256KeyPair(random);
        X500Name caName = new X500Name("CN=" + commonName + " Test CA,O=" + commonName);
        SubjectKeyIdentifier caSki = extUtils.createSubjectKeyIdentifier(caKeys.getPublic());
        AuthorityKeyIdentifier caAki = extUtils.createAuthorityKeyIdentifier(caKeys.getPublic());

        JcaX509v3CertificateBuilder caBuilder = new JcaX509v3CertificateBuilder(
            caName, new BigInteger(64, random), notBefore, notAfter, caName,
            caKeys.getPublic());
        caBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        caBuilder.addExtension(Extension.keyUsage, true,
            new KeyUsage(KeyUsage.keyCertSign | KeyUsage.digitalSignature | KeyUsage.cRLSign));
        caBuilder.addExtension(Extension.subjectKeyIdentifier, false, caSki);
        caBuilder.addExtension(Extension.authorityKeyIdentifier, false, caAki);
        X509Certificate caCert = signCert(caBuilder, caKeys.getPrivate());
        caCert.verify(caKeys.getPublic());

        // ── End-entity (CA-signed) ──────────────────────────────────────
        KeyPair eeKeys = ecP256KeyPair(random);
        X500Name eeName = new X500Name("CN=" + commonName + ",O=" + commonName);
        SubjectKeyIdentifier eeSki = extUtils.createSubjectKeyIdentifier(eeKeys.getPublic());
        AuthorityKeyIdentifier eeAki = extUtils.createAuthorityKeyIdentifier(caKeys.getPublic());

        JcaX509v3CertificateBuilder eeBuilder = new JcaX509v3CertificateBuilder(
            caName, new BigInteger(64, random), notBefore, notAfter, eeName,
            eeKeys.getPublic());
        eeBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        eeBuilder.addExtension(Extension.keyUsage, true,
            new KeyUsage(KeyUsage.digitalSignature));
        eeBuilder.addExtension(Extension.extendedKeyUsage, false,
            new ExtendedKeyUsage(KeyPurposeId.id_kp_emailProtection));
        eeBuilder.addExtension(Extension.subjectKeyIdentifier, false, eeSki);
        eeBuilder.addExtension(Extension.authorityKeyIdentifier, false, eeAki);
        X509Certificate eeCert = signCert(eeBuilder, caKeys.getPrivate());
        eeCert.verify(caKeys.getPublic());

        String chainPem = new String(pem("CERTIFICATE", eeCert.getEncoded()), StandardCharsets.UTF_8)
            + new String(pem("CERTIFICATE", caCert.getEncoded()), StandardCharsets.UTF_8);
        return new byte[][]{
            chainPem.getBytes(StandardCharsets.UTF_8),
            pem("PRIVATE KEY", eeKeys.getPrivate().getEncoded()),
            pem("CERTIFICATE", caCert.getEncoded()),
        };
    }

    private static KeyPair ecP256KeyPair(SecureRandom random) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"), random);
        return kpg.generateKeyPair();
    }

    private static X509Certificate signCert(
            JcaX509v3CertificateBuilder builder, java.security.PrivateKey signerKey)
            throws Exception {
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(signerKey);
        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(holder);
    }

    private static byte[] pem(String type, byte[] der) throws IOException {
        StringWriter out = new StringWriter();
        try (PemWriter writer = new PemWriter(out)) {
            writer.writeObject(new PemObject(type, der));
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
}
