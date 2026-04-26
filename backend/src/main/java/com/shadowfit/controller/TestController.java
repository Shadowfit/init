package com.shadowfit.controller;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TestController { // 파일명과 클래스명 일치

    private final BCryptPasswordEncoder encoder;

    // 1. 생성자 이름을 클래스명(TestController)과 똑같이 맞춥니다!
    public TestController(BCryptPasswordEncoder encoder) {
        this.encoder = encoder;
    }

    // 2. 주소는 효재님이 편한 걸로 쓰셔도 됩니다.
    @GetMapping("/check-my-hash")
    public String check(@RequestParam String password) {
        // 서버의 BCryptPasswordEncoder가 직접 암호화한 값 반환
        return encoder.encode(password);
    }
}