#!/usr/bin/env python3
"""docker-compose.prod.yml 이 «필수 값만 채우면 올라오는 모양» 인지, 그리고 운영에서 지켜야 하는 경계가 그대로인지 점검한다.

`docker compose config` 로 파일을 해석만 한다 — 이미지를 받거나 컨테이너를 띄우지 않는다(도커 데몬이 필요 없다).
운영 compose 는 «상시 EC2 가 없어 돌려 본 적이 없다» 고 파일 머리말이 적고 있다. 그래서 조용히 깨져도 아무도 모른다.

점검하는 것:
  1. 필수 변수(`${X:?…}`) 전부에 값을 주면 해석된다.
  2. 필수 변수를 **하나씩 빼면 그 변수 이름을 말하며 실패한다** — 「필수」 가드가 그대로 살아 있는가. 가드가 사라지면
     운영에서 시크릿이 빈 값으로 뜬다(JWT_SECRET 폴백이 그 사고의 모양이다).
  3. 호스트에 게시되는 포트가 허용 목록과 정확히 같다. 3306(MySQL)·9090(관리 포트)·AI gRPC 가 열리면 실패한다.
  4. 백엔드의 AI_CHANNEL_POOL_SIZE 가 AI 의 AI_WORKER_COUNT 와 같은 값이다(#552 — 어긋나면 세션이 엉뚱한 워커로 간다).
  5. 백엔드·AI 에 메모리·CPU 상한이 실제로 걸린다.

허용 포트를 일부러 바꿀 때는 아래 ALLOWED_PUBLISHED 를 같이 고친다 — 그것이 이 점검의 목적이다(조용히 바뀌지 않게).

사용법:  python3 scripts/ci/check_prod_compose.py [compose 파일 경로]
종료 코드: 0 통과 · 1 실패
"""
import json
import os
import re
import subprocess
import sys
import tempfile

COMPOSE = sys.argv[1] if len(sys.argv) > 1 else "docker-compose.prod.yml"

# 호스트에 게시돼도 되는 (서비스, 호스트 포트). 이 밖의 게시는 사고다.
ALLOWED_PUBLISHED = {("shadowfit-backend", 8080), ("ai-nginx", 8000)}
# 어떤 서비스에서도 게시되면 안 되는 포트와 그 이유 — 위 허용 목록과 별개로 이유를 말해 주려고 둔다.
NEVER_PUBLISHED = {
    3306: "MySQL — 인터넷에 열면 그 자체로 사고다(compose 머리말 2번)",
    9090: "관리(actuator) 포트 — 지표가 안 새는 근거가 «포트 경계» 다(application.yml 의 whitelist 주석)",
    8585: "AI gRPC — Spring 만 같은 네트워크에서 부른다",
    8586: "AI gRPC", 8587: "AI gRPC",
    8001: "AI 워커 HTTP — nginx 가 세션별로 라우팅한다", 8002: "AI 워커 HTTP",
}

# 값이 비어 있으면 운영에서 사고가 되는 변수들 — **반드시 필수(`:?`)여야 한다.** 필수 목록을 파일에서 뽑기만 하면 가드를 지운 변경이
# 목록에서도 같이 사라져 통과해 버린다(`${JWT_SECRET:-폴백}` 으로 바꾸면 «필수 변수» 가 하나 줄 뿐이다). 그래서 고정 목록으로 따로 본다.
# 새 시크릿을 추가하면 여기에도 넣을 것.
MUST_BE_REQUIRED = [
    "MYSQL_ROOT_PASSWORD", "MYSQL_PASSWORD", "DB_PASSWORD",  # DB 자격 증명
    "JWT_SECRET",                                             # 비면(또는 폴백이면) 토큰을 누구나 위조한다
    "INTERNAL_API_TOKEN", "AI_PUBLIC_TOKEN",                  # Spring↔AI 내부 토큰, 앱 번들 토큰
    "BACKEND_MEM_LIMIT", "BACKEND_CPU_LIMIT", "AI_MEM_LIMIT", # 상한 없이 뜨면 호스트가 스래싱한다(#570·#214)
]

failures = []


def fail(msg):
    failures.append(msg)
    print(f"  ✗ {msg}")


def ok(msg):
    print(f"  ✓ {msg}")


def required_vars(text):
    """`${NAME:?…}` 로 선언된 필수 변수 이름들(중복 제거, 등장 순서 유지)."""
    seen, out = set(), []
    for m in re.finditer(r"\$\{([A-Z][A-Z0-9_]*):\?", text):
        if m.group(1) not in seen:
            seen.add(m.group(1))
            out.append(m.group(1))
    return out


def dummy(name):
    # 숫자·단위 형식이 필요한 자리는 그 형식으로 준다. 나머지는 아무 문자열이면 된다(해석만 하므로).
    if name.endswith("_CPU_LIMIT"):
        return "2"
    if name.endswith("_MEM_LIMIT"):
        return "2g"
    return f"dummy-{name.lower()}"


REFERENCED = set()


def run_config(env_extra, empty_env_file):
    """docker compose config 를 호스트 환경·.env 에 영향받지 않게 돌린다. (returncode, stdout, stderr)"""
    # 호스트 환경은 그대로 두되(도커 플러그인·설정 경로가 거기 있다) compose 파일이 참조하는 변수만 지운다 —
    # 개발자 PC·러너에 우연히 설정된 값 때문에 «필수 가드» 가 통과로 보이는 것을 막는다.
    env = {k: v for k, v in os.environ.items() if k not in REFERENCED}
    env.update(env_extra)
    # --env-file 로 빈 파일을 주면 프로젝트 디렉터리의 .env 를 읽지 않는다.
    cmd = ["docker", "compose", "--env-file", empty_env_file, "-f", COMPOSE, "config", "--format", "json"]
    p = subprocess.run(cmd, env=env, capture_output=True, text=True)
    return p.returncode, p.stdout, p.stderr


def main():
    with open(COMPOSE, encoding="utf-8") as f:
        text = f.read()
    req = required_vars(text)
    REFERENCED.update(re.findall(r"\$\{([A-Z][A-Z0-9_]*)", text))
    print(f"{COMPOSE}: 필수 변수 {len(req)}개 — {', '.join(req)}")
    if not req:
        fail("필수 변수(`${X:?…}`)가 하나도 없다 — 시크릿 가드가 사라졌거나 파일 형식이 바뀌었다")
        return

    print("\n0. 반드시 필수여야 하는 변수가 아직 필수다")
    for name in MUST_BE_REQUIRED:
        if name not in req:
            fail(f"{name} 가 더 이상 필수(`${{{name}:?…}}`)가 아니다 — 폴백이나 빈 값으로 운영에 뜰 수 있다")
    if all(n in req for n in MUST_BE_REQUIRED):
        ok(f"{len(MUST_BE_REQUIRED)}개 모두 필수")

    with tempfile.NamedTemporaryFile("w", suffix=".env", delete=False) as tf:
        empty_env = tf.name
    try:
        full = {n: dummy(n) for n in req}

        print("\n1. 필수 변수를 모두 주면 해석된다")
        rc, out, err = run_config(full, empty_env)
        if rc != 0:
            fail(f"해석에 실패했다(종료 코드 {rc}):\n{err.strip()[:600]}")
            return
        ok("해석 성공")
        cfg = json.loads(out)

        print("\n2. 필수 변수를 하나씩 빼면 그 이름을 말하며 실패한다")
        for name in req:
            rc, _, err = run_config({k: v for k, v in full.items() if k != name}, empty_env)
            if rc == 0:
                fail(f"{name} 없이도 해석된다 — 필수 가드가 사라졌다(운영에서 빈 값으로 뜬다)")
            elif name not in err:
                fail(f"{name} 없이 실패했지만 메시지에 변수 이름이 없다(원인이 가려진다): {err.strip()[:200]}")
            else:
                ok(f"{name} 없으면 거절")

        print("\n3. 호스트에 게시되는 포트")
        published = set()
        for svc, body in cfg.get("services", {}).items():
            for p in body.get("ports", []) or []:
                pub = p.get("published")
                if pub is not None and str(pub) != "":
                    published.add((svc, int(pub)))
        for svc, port in sorted(published):
            if port in NEVER_PUBLISHED:
                fail(f"{svc} 가 {port} 을 호스트에 게시한다 — {NEVER_PUBLISHED[port]}")
        extra = published - ALLOWED_PUBLISHED
        missing = ALLOWED_PUBLISHED - published
        if extra:
            fail(f"허용 목록에 없는 게시 포트: {sorted(extra)} — 의도한 것이면 ALLOWED_PUBLISHED 를 같이 고칠 것")
        if missing:
            fail(f"있어야 할 게시 포트가 없다: {sorted(missing)} — 앱이 서버에 못 붙는다")
        if not extra and not missing and not any(p in NEVER_PUBLISHED for _, p in published):
            ok(f"게시 포트 = {sorted(published)}")

        print("\n4. AI 채널 풀 크기 = AI 워커 수")
        backend_env = cfg["services"]["shadowfit-backend"].get("environment", {})
        ai_env = cfg["services"]["shadowfit-ai"].get("environment", {})
        pool, workers = backend_env.get("AI_CHANNEL_POOL_SIZE"), ai_env.get("AI_WORKER_COUNT")
        if pool is None or workers is None:
            fail(f"AI_CHANNEL_POOL_SIZE({pool}) 또는 AI_WORKER_COUNT({workers}) 가 해석 결과에 없다")
        elif str(pool) != str(workers):
            fail(f"어긋났다: 백엔드 AI_CHANNEL_POOL_SIZE={pool} · AI AI_WORKER_COUNT={workers}")
        else:
            ok(f"둘 다 {pool}")
        # 기본값이 아닌 값으로도 같이 움직이는가 — 한쪽만 하드코딩되면 기본값에서는 우연히 같아 보인다.
        rc, out2, _ = run_config({**full, "AI_WORKER_COUNT": "5"}, empty_env)
        if rc == 0:
            c2 = json.loads(out2)["services"]
            p5 = c2["shadowfit-backend"]["environment"].get("AI_CHANNEL_POOL_SIZE")
            w5 = c2["shadowfit-ai"]["environment"].get("AI_WORKER_COUNT")
            if str(p5) == "5" and str(w5) == "5":
                ok("AI_WORKER_COUNT=5 로 바꾸면 둘 다 5 로 따라온다")
            else:
                fail(f"AI_WORKER_COUNT=5 인데 백엔드 {p5} · AI {w5} — 한쪽이 값에 묶여 있다")
        else:
            fail("AI_WORKER_COUNT=5 로 해석하지 못했다")

        print("\n5. 메모리·CPU 상한")
        for svc in ("shadowfit-backend", "shadowfit-ai"):
            body = cfg["services"][svc]
            mem = body.get("mem_limit")
            cpus = body.get("cpus")
            if not mem or str(mem) in ("0", ""):
                fail(f"{svc} 의 mem_limit 이 걸리지 않는다(={mem})")
            elif svc == "shadowfit-backend" and not cpus:
                fail(f"{svc} 의 cpus 가 걸리지 않는다(={cpus})")
            else:
                ok(f"{svc}: mem_limit={mem}" + (f" cpus={cpus}" if cpus else ""))
    finally:
        os.unlink(empty_env)


if __name__ == "__main__":
    main()
    print()
    if failures:
        print(f"실패 {len(failures)}건")
        sys.exit(1)
    print("통과")
