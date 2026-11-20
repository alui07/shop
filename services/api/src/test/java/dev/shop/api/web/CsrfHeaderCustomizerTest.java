package dev.shop.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.junit.jupiter.api.Test;

class CsrfHeaderCustomizerTest {

    private static Operation withSessionCookie() {
        return new Operation().addSecurityItem(new SecurityRequirement().addList("sessionCookie"));
    }

    @Test
    void unsafeOperationsThatUseTheSessionCookieAlsoRequireTheCsrfHeaderInTheSameRequirement() {
        Operation post = withSessionCookie();
        Operation put = withSessionCookie();
        Operation get = withSessionCookie();
        Operation publicPost = new Operation();
        Operation otherSecurity = new Operation().addSecurityItem(new SecurityRequirement().addList("other"));
        OpenAPI openApi = new OpenAPI()
                .paths(new Paths()
                        .addPathItem("/a", new PathItem().post(post).put(put).get(get))
                        .addPathItem("/b", new PathItem().post(publicPost).delete(otherSecurity)));

        new CsrfHeaderCustomizer().customise(openApi);

        assertThat(post.getSecurity())
                .singleElement()
                .satisfies(requirement -> assertThat(requirement).containsKeys("sessionCookie", "csrfHeader"));
        assertThat(put.getSecurity().getFirst()).containsKey("csrfHeader");
        assertThat(get.getSecurity().getFirst()).doesNotContainKey("csrfHeader");
        assertThat(publicPost.getSecurity()).isNull();
        assertThat(otherSecurity.getSecurity().getFirst()).doesNotContainKey("csrfHeader");
    }
}
