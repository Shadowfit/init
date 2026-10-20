package com.shadowfit.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프로파일 규약의 계약 (application.yml 머리말, redesign-from-scratch-2026-09-11.md F1).
 *
 * <p><b>규약</b>: 프로파일 없는 기본값이 «어디서 돌아도 안전한 쪽» 이고, 개발 편의(Swagger·SQL 로그·
 * DEBUG 로그)는 dev 프로파일만 켠다. prod 는 그것들을 되켤 수 없다.
 *
 * <p><b>왜 테스트로 박는가</b>: 이 저장소가 반복해서 당한 부류가 «dev 기본값이 prod 로 새는 것» 이다 —
 * CORS·Swagger 노출(08-11 회고 ⑩⑪), 그리고 {@code application.properties} 의 {@code show-sql=true} 가
 * 프로파일 없이 운영까지 새던 것(이 규약을 만든 계기). 누가 편의를 위해 base 에 {@code enabled: true}
 * 나 {@code ${SPRINGDOC_ENABLED:false}} 같은 env var 게이트를 되살리면 여기서 걸린다.
 *
 * <p>{@link ProdProfileConfigTest} 처럼 컨텍스트를 띄우지 않고 yml 을 텍스트 수준에서 읽는다 — base 는
 * MySQL 을 물고, 여기서 재려는 것은 «이 파일들이 무엇을 약속하는가» 뿐이다. 그래서 값은 플레이스홀더가
 * 풀리지 않은 원문이다: {@code false} 여야지 {@code ${X:false}} 면 안 된다(env 로 열리는 문이므로).
 *
 * <p>클래스패스가 아니라 <b>{@code src/main/resources} 경로로 직접</b> 읽는다 — 테스트 클래스패스에서는
 * {@code src/test/resources/application.yml} 이 main 의 것을 가리므로({@code ClassPathResource("application.yml")}
 * 은 테스트용 파일을 준다) 검사 대상이 바뀐다. 테스트는 backend 모듈 디렉터리에서 돈다(Gradle 기본).
 */
class ProfileSafetyContractTest {

    private static final String MAIN_RESOURCES = "src/main/resources/";

    private static PropertySource<?> load(String file) throws IOException {
        FileSystemResource res = new FileSystemResource(MAIN_RESOURCES + file);
        assertThat(res.exists()).as(res.getPath() + " 이 있어야 한다").isTrue();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(file, res);
        assertThat(sources).as(file + " 이 비어 있으면 안 된다").isNotEmpty();
        return sources.get(0);
    }

    private static String str(PropertySource<?> src, String key) {
        Object v = src.getProperty(key);
        return v == null ? null : v.toString();
    }

    @Test
    @DisplayName("base 는 프로파일을 기본 활성화하지 않는다 — «prod 가 곧 컨테이너» 였던 예전 상태로 돌아가지 않게")
    void baseActivatesNoProfile() throws IOException {
        assertThat(str(load("application.yml"), "spring.profiles.active"))
                .as("프로파일 없는 기동이 곧 안전 기본이어야 한다. 기본 활성 프로파일이 있으면 «프로파일 없음» 이 사라진다")
                .isNull();
    }

    @Test
    @DisplayName("🔴 base 는 Swagger 를 끄고, env var 로도 못 켠다")
    void baseSwaggerIsHardOff() throws IOException {
        PropertySource<?> base = load("application.yml");
        for (String key : List.of("springdoc.api-docs.enabled", "springdoc.swagger-ui.enabled")) {
            assertThat(str(base, key))
                    .as(key + " — 리터럴 false 여야 한다. ${...:false} 는 env 로 열리는 문이다")
                    .isEqualTo("false");
        }
    }

    @Test
    @DisplayName("base 는 SQL 로그·패키지 DEBUG 로그를 갖지 않는다 — 그건 dev 의 것")
    void baseHasNoDevConveniences() throws IOException {
        PropertySource<?> base = load("application.yml");
        assertThat(str(base, "spring.jpa.show-sql")).as("show-sql 은 dev 프로파일에만").isNull();
        assertThat(str(base, "logging.level.com.shadowfit")).as("패키지 DEBUG 는 dev 프로파일에만").isNull();
    }

    @Test
    @DisplayName("dev 가 편의를 켠다 — 규약의 다른 반쪽")
    void devTurnsConveniencesOn() throws IOException {
        PropertySource<?> dev = load("application-dev.yml");
        assertThat(str(dev, "springdoc.api-docs.enabled")).isEqualTo("true");
        assertThat(str(dev, "springdoc.swagger-ui.enabled")).isEqualTo("true");
        assertThat(str(dev, "spring.jpa.show-sql")).isEqualTo("true");
    }

    @Test
    @DisplayName("prod 는 dev 편의를 되켤 수 없다")
    void prodCannotReenableConveniences() throws IOException {
        PropertySource<?> prod = load("application-prod.yml");
        for (String key : List.of("springdoc.api-docs.enabled", "springdoc.swagger-ui.enabled", "spring.jpa.show-sql")) {
            assertThat(str(prod, key)).as(key + " 가 prod 에 있으면 안 된다").isNull();
        }
    }

    @Test
    @DisplayName("application.properties 는 없다 — yml 을 조용히 덮어쓰던 두 번째 설정 파일")
    void noPropertiesFileBesideYml() {
        assertThat(new FileSystemResource(MAIN_RESOURCES + "application.properties").exists())
                .as(".properties 가 같은 폴더에 있으면 같은 키에서 yml 을 이긴다 — 2026-07-15·07-25 두 번 당했다. 다시 만들지 말 것")
                .isFalse();
    }
}
