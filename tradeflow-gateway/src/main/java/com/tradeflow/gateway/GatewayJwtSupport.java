package com.tradeflow.gateway;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** WebFlux网关独立验签工具；只持有公钥，不能签发用户令牌。 */
public final class GatewayJwtSupport {
    private GatewayJwtSupport() { }

    /** 固定RS256、issuer、audience与15分钟有效期上限；未知用途的令牌拒绝使用。 */
    public static JwtDecoder decoder(String publicKeyPath, String issuer) throws Exception {
        String pem = Files.readString(Path.of(publicKeyPath)).replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        var key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
        var decoder = NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<java.util.List<String>>("aud", a -> a != null && a.contains("tradeflow-user-api")),
                jwt -> validClaims(jwt) ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "用户令牌内容不合法", null))));
        return decoder;
    }

    /** 必填时间、用途与版本严格校验；JWT过期不等于远端业务失败。 */
    private static boolean validClaims(Jwt jwt) {
        Object version = jwt.getClaims().get("token_version");
        return jwt.getSubject() != null && jwt.getSubject().matches("[1-9][0-9]{0,18}")
                && "access".equals(jwt.getClaimAsString("token_use")) && version instanceof Number n && n.longValue() >= 0
                && jwt.getIssuedAt() != null && jwt.getExpiresAt() != null
                && !jwt.getIssuedAt().isAfter(java.time.Instant.now().plusSeconds(60))
                && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                && java.time.Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).getSeconds() <= 900;
    }
}
