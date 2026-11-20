package dev.shop.api.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class CurrentUserTest {

    private static Authentication signedIn(String subject, String fullName, String preferredUsername) {
        OidcUser user = mock(OidcUser.class);
        when(user.getSubject()).thenReturn(subject);
        when(user.getFullName()).thenReturn(fullName);
        when(user.getPreferredUsername()).thenReturn(preferredUsername);
        return new TestingAuthenticationToken(user, null);
    }

    @Test
    void usesTheSubjectAndTheFullName() {
        CurrentUser user = CurrentUser.of(signedIn("sub-1", "Alex Admin", "alex"));

        assertThat(user).isEqualTo(new CurrentUser("sub-1", "Alex Admin"));
    }

    @Test
    void fallsBackToThePreferredUsernameAndThenToTheSubject() {
        assertThat(CurrentUser.of(signedIn("sub-1", null, "alex")).displayName())
                .isEqualTo("alex");
        assertThat(CurrentUser.of(signedIn("sub-1", "  ", "alex")).displayName())
                .isEqualTo("alex");
        assertThat(CurrentUser.of(signedIn("sub-1", null, null)).displayName()).isEqualTo("sub-1");
    }

    @Test
    void removesControlAndInvisibleCharactersFromTheName() {
        CurrentUser user = CurrentUser.of(signedIn("sub-1", "A‮l\u0007e\nx", null));

        assertThat(user.displayName()).isEqualTo("Alex");
    }

    @Test
    void aNameOfOnlyDisallowedCharactersFallsBackToTheNextCandidate() {
        assertThat(CurrentUser.of(signedIn("sub-1", "​​", "alex")).displayName())
                .isEqualTo("alex");
    }

    @Test
    void cutsTheNameAtTwoHundredCodePointsWithoutSplittingASurrogatePair() {
        String pair = "😀";
        String name = "a".repeat(199) + pair + "tail";

        CurrentUser user = CurrentUser.of(signedIn("sub-1", name, null));

        assertThat(user.displayName()).isEqualTo("a".repeat(199) + pair);
        assertThat(CurrentUser.of(signedIn("sub-1", "b".repeat(200), null)).displayName())
                .hasSize(200);
    }

    @Test
    void refusesAnythingThatIsNotAnOidcUserWithASubject() {
        assertThatThrownBy(() -> CurrentUser.of(null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> CurrentUser.of(UsernamePasswordAuthenticationToken.unauthenticated("u", "p")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> CurrentUser.of(signedIn(null, "A", "a"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> CurrentUser.of(signedIn(" ", "A", "a"))).isInstanceOf(AccessDeniedException.class);
    }

    private static Authentication withEmail(String email, Object emailVerified) {
        OidcUser user = mock(OidcUser.class);
        when(user.getSubject()).thenReturn("sub-1");
        when(user.getEmail()).thenReturn(email);
        when(user.getClaims()).thenReturn(emailVerified == null ? Map.of() : Map.of("email_verified", emailVerified));
        return new TestingAuthenticationToken(user, null);
    }

    @Test
    void keepsTheEmailOnlyWhenTheProviderSaysItIsVerified() {
        assertThat(CurrentUser.of(withEmail("a@example.test", true)).verifiedEmail())
                .isEqualTo("a@example.test");
        assertThat(CurrentUser.of(withEmail("a@example.test", false)).verifiedEmail())
                .isNull();
        assertThat(CurrentUser.of(withEmail("a@example.test", null)).verifiedEmail())
                .isNull();
    }

    @Test
    void aStringTrueIsNotAVerifiedClaim() {
        assertThat(CurrentUser.of(withEmail("a@example.test", "true")).verifiedEmail())
                .isNull();
    }

    @Test
    void dropsAnEmailThatIsMissingTooLongOrNotShapedLikeAnAddress() {
        String longest = "a".repeat(242) + "@example.test";
        assertThat(longest).hasSize(255);

        assertThat(CurrentUser.of(withEmail(null, true)).verifiedEmail()).isNull();
        assertThat(CurrentUser.of(withEmail(longest, true)).verifiedEmail()).isNull();
        assertThat(CurrentUser.of(withEmail(longest.substring(1), true)).verifiedEmail())
                .hasSize(254);
        assertThat(CurrentUser.of(withEmail("no-at-sign", true)).verifiedEmail())
                .isNull();
        assertThat(CurrentUser.of(withEmail("two words@example.test", true)).verifiedEmail())
                .isNull();
        assertThat(CurrentUser.of(withEmail("a@example.test\r\nBcc: x@y.test", true))
                        .verifiedEmail())
                .isNull();
        for (String control : new String[] {"a\u0000b@example.test", "a@exa\u0085mple.test", "a@example.test\u2028"}) {
            assertThat(CurrentUser.of(withEmail(control, true)).verifiedEmail())
                    .as(control)
                    .isNull();
        }
    }

    @Test
    void theTwoArgumentConstructorHasNoEmail() {
        assertThat(new CurrentUser("s", "n").verifiedEmail()).isNull();
    }
}
