package com.shadowfit.service.exercise;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 관리자 업로드 기준 영상 저장 위치 — {@code application.yml} 의 {@code reference-video} 블록. 이 클래스는 받기만 한다.
 *
 * <p>키가 둘인 이유: 영상은 Spring 이 쓰고 AI 가 읽는데(공유 bind mount, docker-compose.yml), 둘이 같은 파일을
 * 보더라도 <b>경로 문자열은 다를 수 있다.</b> 호스트 {@code bootRun} + AI 컨테이너 조합이면 Spring 은
 * {@code E:\...} 에 쓰고 AI 는 {@code /data/...} 로 읽는다. DB·gRPC 에는 각자 루트를 붙이기 전의
 * 상대 경로만 오간다(V23 주석).
 */
@Component
@ConfigurationProperties(prefix = "reference-video")
@Getter
@Setter
public class ReferenceVideoProperties {

    /** Spring 이 파일을 쓰는 루트. 상대 경로면 프로세스 작업 디렉터리 기준이다. */
    private String dir = "./data/reference-videos";

    /**
     * AI 컨테이너가 같은 루트를 보는 경로. 비우면 «같은 파일시스템» 으로 보고 {@link #dir} 의 <b>절대 경로</b>를
     * 쓴다({@code ReferenceVideoStorage.aiPath}) — 전부 compose 로 띄우거나(양쪽 다 {@code /data/reference-videos})
     * 둘 다 호스트에서 돌릴 때가 그렇다. 호스트 bootRun + AI 컨테이너 조합에서만 값을 준다.
     */
    private String aiDir;
}
