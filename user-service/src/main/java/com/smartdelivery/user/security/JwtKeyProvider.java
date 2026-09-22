package com.smartdelivery.user.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Resolves the RSA key material user-service signs with, and the public half it
 * publishes (ADR 007).
 *
 * Two things this deliberately does not do. It does not read the key from the database
 * or generate one per instance in a real deployment: several replicas of user-service
 * must sign with the *same* key, or a token minted by one would fail verification
 * against a JWKS served by another. And it does not silently fall back to a generated
 * key without saying so -- a service that quietly invents its own signing key is a
 * service whose tokens stop working the moment it restarts, which is a miserable thing
 * to diagnose from the other side.
 */
@Component
public class JwtKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyProvider.class);
    private static final int DEV_KEY_SIZE = 2048;

    private final String activeKid;
    private final RSAPrivateKey privateKey;
    private final JWKSet publicJwkSet;

    public JwtKeyProvider(JwtProperties properties) {
        JwtProperties.SigningKey signingKey = properties.signingKey();
        this.activeKid = signingKey.kid();

        RSAPublicKey activePublicKey;
        if (signingKey.privateKey().isBlank()) {
            KeyPair devKeyPair = generateDevKeyPair();
            this.privateKey = (RSAPrivateKey) devKeyPair.getPrivate();
            activePublicKey = (RSAPublicKey) devKeyPair.getPublic();
            log.warn("""
                    NO SIGNING KEY CONFIGURED -- generated a throwaway RSA key pair for kid '{}'. \
                    This is for local development only. Every token this instance issues becomes \
                    invalid when it restarts, and a second instance would sign with a different \
                    key entirely. Set jwt.signing-key.private-key (JWT_PRIVATE_KEY) to a PKCS#8 \
                    PEM in any environment that is not one developer's laptop.""", this.activeKid);
        } else {
            this.privateKey = parsePrivateKey(signingKey.privateKey());
            activePublicKey = signingKey.publicKey().isBlank()
                    ? derivePublicKey(this.privateKey)
                    : parsePublicKey(signingKey.publicKey());
        }

        List<RSAKey> published = new ArrayList<>();
        published.add(toJwk(activePublicKey, this.activeKid));
        for (JwtProperties.RetiredKey retired : properties.retiredKeys()) {
            published.add(toJwk(parsePublicKey(retired.publicKey()), retired.kid()));
        }
        this.publicJwkSet = new JWKSet(List.copyOf(published));
    }

    /** The {@code kid} stamped into every token this service signs. */
    public String activeKid() {
        return activeKid;
    }

    public RSAPrivateKey privateKey() {
        return privateKey;
    }

    /**
     * The active key plus every retired one still worth publishing -- public halves
     * only. {@link JWKSet#toJSONObject()} on a set built from {@code toPublicJWK()}
     * cannot emit private parameters, which is the property the JWKS endpoint's test
     * asserts rather than assumes.
     */
    public JWKSet publicJwkSet() {
        return publicJwkSet;
    }

    private static RSAKey toJwk(RSAPublicKey publicKey, String kid) {
        return new RSAKey.Builder(publicKey)
                .keyID(kid)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
                .build()
                .toPublicJWK();
    }

    private static KeyPair generateDevKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(DEV_KEY_SIZE);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA key generation is not available on this JVM", e);
        }
    }

    private static RSAPrivateKey parsePrivateKey(String pem) {
        try {
            var keySpec = new PKCS8EncodedKeySpec(decodePem(pem, "PRIVATE KEY"));
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(keySpec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException e) {
            throw new IllegalStateException("jwt.signing-key.private-key is not a valid PKCS#8 RSA PEM", e);
        }
    }

    private static RSAPublicKey parsePublicKey(String pem) {
        try {
            var keySpec = new X509EncodedKeySpec(decodePem(pem, "PUBLIC KEY"));
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(keySpec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException e) {
            throw new IllegalStateException("A configured JWT public key is not a valid X.509 RSA PEM", e);
        }
    }

    /**
     * An RSA private key already carries the modulus and the public exponent, so a
     * deployment only ever has to supply the private PEM -- one fewer thing to keep in
     * step across environments.
     */
    private static RSAPublicKey derivePublicKey(RSAPrivateKey privateKey) {
        try {
            var crtKey = (java.security.interfaces.RSAPrivateCrtKey) privateKey;
            var spec = new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent());
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (ClassCastException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(
                    "Cannot derive the public key from the configured private key; supply "
                            + "jwt.signing-key.public-key explicitly", e);
        }
    }

    /** Tolerates the PEM header/footer, any line wrapping, and surrounding whitespace. */
    private static byte[] decodePem(String pem, String label) {
        String body = pem
                .replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
