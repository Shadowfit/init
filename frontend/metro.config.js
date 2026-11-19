// 개발 전용 API 프록시 (2026-10-06).
//
// 폰 시연에서 앱은 Metro(8081)로는 열리는데 PC 방화벽(Public 프로필)이 도커 포트(백엔드
// 18080·AI 18000)로 들어오는 폰 접속을 막아 모든 API 호출이 «응답 없음(status undefined)»
// 으로 실패했다. 방화벽 규칙을 건드리지 않으려고, 폰이 이미 접속 가능한 Metro 서버가 대신
// 받아 PC 안(localhost)의 백엔드/AI로 전달한다.
//
//   /__backend/**  ->  localhost:EXPO_PUBLIC_BACKEND_PORT (기본 8080)
//   /__ai/**       ->  localhost:EXPO_PUBLIC_AI_PORT      (기본 8000)
//
// 앱 쪽은 EXPO_PUBLIC_API_VIA_METRO=1 일 때만 이 경로를 쓴다(services/devProxy.ts).
// 릴리스 번들에는 Metro 가 없으므로 이 파일은 개발 서버에만 영향을 준다.
const { getDefaultConfig } = require('expo/metro-config');
const http = require('http');

const config = getDefaultConfig(__dirname);

const RULES = [
  { prefix: '/__backend', port: Number(process.env.EXPO_PUBLIC_BACKEND_PORT || 8080) },
  { prefix: '/__ai', port: Number(process.env.EXPO_PUBLIC_AI_PORT || 8000) },
];

const previous = config.server && config.server.enhanceMiddleware;
config.server = {
  ...(config.server || {}),
  enhanceMiddleware: (middleware, metroServer) => {
    const base = previous ? previous(middleware, metroServer) : middleware;
    return (req, res, next) => {
      const rule = RULES.find((r) => req.url === r.prefix || req.url.startsWith(r.prefix + '/') || req.url.startsWith(r.prefix + '?'));
      if (!rule) return base(req, res, next);

      // origin/referer 는 지운다 — 웹(PC 브라우저)에서 오면 Spring CORS 가 낯선 Origin 을 403 으로
      // 거절하는데, 프록시 입장에서는 같은 출처 요청이라 필요 없는 헤더다.
      const headers = { ...req.headers, host: `localhost:${rule.port}` };
      delete headers.origin;
      delete headers.referer;

      const upstream = http.request(
        { host: 'localhost', port: rule.port, method: req.method, path: req.url.slice(rule.prefix.length) || '/', headers },
        (upstreamRes) => {
          res.writeHead(upstreamRes.statusCode || 502, upstreamRes.headers);
          upstreamRes.pipe(res);
        },
      );
      upstream.on('error', (error) => {
        res.statusCode = 502;
        res.setHeader('Content-Type', 'application/json');
        res.end(JSON.stringify({ message: `dev proxy -> localhost:${rule.port} 실패: ${error.message}` }));
      });
      req.pipe(upstream);
    };
  },
};

module.exports = config;
