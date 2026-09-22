package com.smartdelivery.user.security;

import com.nimbusds.jose.jwk.JWK;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtKeyProviderTest {

    @Test
    void generatesAThrowawayKeyPairWhenNoneIsConfigured() {
        JwtKeyProvider provider = new JwtKeyProvider(JwtTestFixtures.properties());

        assertThat(provider.privateKey()).isNotNull();
        assertThat(provider.activeKid()).isEqualTo(JwtTestFixtures.KID);
        assertThat(provider.publicJwkSet().getKeys()).hasSize(1);
    }

    @Test
    void loadsAConfiguredPkcs8PrivateKeyAndDerivesItsPublicHalf() throws Exception {
        KeyPair keyPair = rsaKeyPair();

        JwtKeyProvider provider = new JwtKeyProvider(JwtTestFixtures.properties(
                new JwtProperties.SigningKey(JwtTestFixtures.KID, privateKeyPem(keyPair), "")));

        // Deriving it rather than requiring it configured is what lets a deployment
        // supply one PEM instead of keeping two in step.
        assertThat(provider.publicJwkSet().getKeyByKeyId(JwtTestFixtures.KID)
                .toRSAKey().toRSAPublicKey().getModulus())
                .isEqualTo(((RSAPublicKey) keyPair.getPublic()).getModulus());
        assertThat(provider.privateKey().getModulus())
                .isEqualTo(((RSAPrivateKey) keyPair.getPrivate()).getModulus());
    }

    @Test
    void publishesRetiredKeysAlongsideTheActiveOne() {
        KeyPair active = rsaKeyPair();
        KeyPair retired = rsaKeyPair();

        JwtKeyProvider provider = new JwtKeyProvider(JwtTestFixtures.properties(
                new JwtProperties.SigningKey("active", privateKeyPem(active), ""),
                List.of(new JwtProperties.RetiredKey("retired", publicKeyPem(retired)))));

        // This is what makes rotation possible without a flag day: tokens signed by the
        // old key still verify while they are outstanding.
        assertThat(provider.publicJwkSet().getKeys()).extracting(JWK::getKeyID)
                .containsExactlyInAnyOrder("active", "retired");
        assertThat(provider.activeKid()).isEqualTo("active");
    }

    @Test
    void theJwkSetNeverContainsPrivateKeyMaterial() {
        JwtKeyProvider provider = new JwtKeyProvider(JwtTestFixtures.properties(
                new JwtProperties.SigningKey(JwtTestFixtures.KID, privateKeyPem(rsaKeyPair()), "")));

        assertThat(provider.publicJwkSet().getKeys()).allSatisfy(key -> assertThat(key.isPrivate()).isFalse());
        // The serialized form is what actually leaves the process, so assert on that too:
        // "d" is the RSA private exponent, "p"/"q" its prime factors.
        String json = provider.publicJwkSet().toString(true);
        assertThat(json).doesNotContain("\"d\"").doesNotContain("\"p\"").doesNotContain("\"q\"");
    }

    @Test
    void refusesToStartOnUnusableKeyMaterial() {
        assertThatThrownBy(() -> new JwtKeyProvider(JwtTestFixtures.properties(
                new JwtProperties.SigningKey(JwtTestFixtures.KID, "-----BEGIN PRIVATE KEY-----\nnope\n-----END PRIVATE KEY-----", ""))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PKCS#8");
    }

    static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String privateKeyPem(KeyPair keyPair) {
        return pem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
    }

    static String publicKeyPem(KeyPair keyPair) {
        return pem("PUBLIC KEY", keyPair.getPublic().getEncoded());
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }
}
