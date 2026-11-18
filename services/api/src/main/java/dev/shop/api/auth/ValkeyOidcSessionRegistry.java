package dev.shop.api.auth;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.client.oidc.authentication.logout.OidcLogoutToken;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionInformation;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionRegistry;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Maps Keycloak's {@code sid} and {@code sub} to app session ids in Valkey, so any API replica can end a session on a
 * back-channel logout. Only {@code {sessionId, sub, sid, iss}} is stored, never a token, cookie or CSRF value. Keys
 * expire with the absolute session timeout.
 */
@Component
class ValkeyOidcSessionRegistry implements OidcSessionRegistry {

    private static final String SESSION_KEY = "shop:oidc:session:";
    private static final String SID_KEY = "shop:oidc:sid:";
    private static final String SUB_KEY = "shop:oidc:sub:";
    private static final String SUB = "sub";
    private static final String SID = "sid";
    private static final String ISS = "iss";
    private static final String NO_SID = "";

    private final StringRedisTemplate redis;
    private final Duration timeToLive;

    ValkeyOidcSessionRegistry(StringRedisTemplate redis, ShopAuthProperties properties) {
        this.redis = redis;
        this.timeToLive = properties.absoluteSessionTimeout();
    }

    @Override
    public void saveSessionInformation(OidcSessionInformation information) {
        String sessionId = information.getSessionId();
        OidcUser user = information.getPrincipal();
        String sid = user.getClaimAsString(SID) == null ? NO_SID : user.getClaimAsString(SID);
        redis.opsForHash()
                .putAll(
                        SESSION_KEY + sessionId,
                        Map.of(
                                SUB,
                                user.getSubject(),
                                SID,
                                sid,
                                ISS,
                                user.getIssuer().toString()));
        redis.expire(SESSION_KEY + sessionId, timeToLive);
        addToSet(SUB_KEY + user.getSubject(), sessionId);
        if (!sid.isEmpty()) {
            addToSet(SID_KEY + sid, sessionId);
        }
    }

    @Override
    public OidcSessionInformation removeSessionInformation(String sessionId) {
        Map<Object, Object> record = redis.opsForHash().entries(SESSION_KEY + sessionId);
        if (record.isEmpty()) {
            return null;
        }
        redis.delete(SESSION_KEY + sessionId);
        redis.opsForSet().remove(SUB_KEY + record.get(SUB), sessionId);
        if (!NO_SID.equals(record.get(SID))) {
            redis.opsForSet().remove(SID_KEY + record.get(SID), sessionId);
        }
        return information(sessionId, record);
    }

    /**
     * Matches on {@code sid} when the token has one, otherwise on {@code sub}. Either way the stored subject (when the
     * token carries one) and issuer must agree, so a token cannot end another user's session.
     */
    @Override
    public Iterable<OidcSessionInformation> removeSessionInformation(OidcLogoutToken token) {
        String sid = token.getSessionId();
        String subject = token.getSubject();
        Set<String> candidates = sid != null
                ? redis.opsForSet().members(SID_KEY + sid)
                : redis.opsForSet().members(SUB_KEY + subject);
        List<OidcSessionInformation> removed = new ArrayList<>();
        for (String sessionId : candidates) {
            Map<Object, Object> record = redis.opsForHash().entries(SESSION_KEY + sessionId);
            if (!record.isEmpty() && matches(record, token)) {
                OidcSessionInformation information = removeSessionInformation(sessionId);
                if (information != null) {
                    removed.add(information);
                }
            }
        }
        return removed;
    }

    private static boolean matches(Map<Object, Object> record, OidcLogoutToken token) {
        boolean sameSubject = token.getSubject() == null || token.getSubject().equals(record.get(SUB));
        return sameSubject && token.getIssuer().toString().equals(record.get(ISS));
    }

    private void addToSet(String key, String sessionId) {
        redis.opsForSet().add(key, sessionId);
        redis.expire(key, timeToLive);
    }

    private static OidcSessionInformation information(String sessionId, Map<Object, Object> record) {
        OidcIdToken idToken = OidcIdToken.withTokenValue("registry-only")
                .subject(record.get(SUB).toString())
                .claim(SID, record.get(SID))
                .issuer(record.get(ISS).toString())
                .build();
        return new OidcSessionInformation(sessionId, Map.of(), new DefaultOidcUser(List.of(), idToken));
    }
}
