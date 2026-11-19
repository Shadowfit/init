// 데모 데이터 정의 — 사람 · 모임 · 사진 · 문구. seed.mjs 가 이걸로 SQL 을 만든다.
// 모든 계정 비밀번호는 같다(seed.mjs 의 PASSWORD).

// skill: 평균 싱크로율 기준선, rate: 하루 운동 확률, hour: 주로 운동하는 시각
// streak: 오늘 기준 연속 운동일(0 이면 오늘·어제 안 함), today: 오늘 운동 여부
export const USERS = [
  { id: 1, email: 'admin@shadowfit.kr', username: '운영자', sex: 'MALE', role: 'ADMIN', persona: 'ADVANCED', level: 'EXPERT', h: 176, w: 71, skill: 88, rate: 0.25, hour: 7, streak: 0 },
  { id: 2, email: 'jiho@shadowfit.kr', username: '최지호', sex: 'MALE', persona: 'BEGINNER', level: 'BEGINNER', h: 174, w: 68.4, skill: 78, rate: 0.82, hour: 21, streak: 12, today: true, main: true },
  { id: 3, email: 'seungyeon@shadowfit.kr', username: '유승연', sex: 'FEMALE', persona: 'DIET', level: 'INTERMEDIATE', h: 163, w: 55, skill: 84, rate: 0.7, hour: 7, streak: 3, today: true },
  { id: 4, email: 'chaerin@shadowfit.kr', username: '김채린', sex: 'FEMALE', persona: 'BEGINNER', level: 'BEGINNER', h: 160, w: 52, skill: 76, rate: 0.6, hour: 19, streak: 3, today: true },
  { id: 5, email: 'yewon@shadowfit.kr', username: '고예원', sex: 'FEMALE', persona: 'ADVANCED', level: 'ADVANCED', h: 167, w: 57, skill: 89, rate: 0.85, hour: 6, streak: 8, today: false },
  { id: 6, email: 'heemin@shadowfit.kr', username: '김희민', sex: 'FEMALE', persona: 'REHAB', level: 'STARTER', h: 158, w: 50, skill: 66, rate: 0.12, hour: 20, streak: 0 },
  { id: 7, email: 'mijeong@shadowfit.kr', username: '김미정', sex: 'FEMALE', persona: 'DIET', level: 'BEGINNER', h: 165, w: 61, skill: 70, rate: 0.1, hour: 22, streak: 0 },
  { id: 8, email: 'seojun@shadowfit.kr', username: '박서준', sex: 'MALE', persona: 'ADVANCED', level: 'ADVANCED', h: 181, w: 78, skill: 87, rate: 0.72, hour: 19, streak: 5, today: true },
  { id: 9, email: 'doyun@shadowfit.kr', username: '이도윤', sex: 'MALE', persona: 'BEGINNER', level: 'INTERMEDIATE', h: 177, w: 72, skill: 80, rate: 0.55, hour: 12, streak: 2, today: false },
  { id: 10, email: 'haeun@shadowfit.kr', username: '최하은', sex: 'FEMALE', persona: 'REHAB', level: 'BEGINNER', h: 162, w: 54, skill: 72, rate: 0.5, hour: 10, streak: 1, today: true },
  { id: 11, email: 'minjae@shadowfit.kr', username: '정민재', sex: 'MALE', persona: 'DIET', level: 'INTERMEDIATE', h: 175, w: 84, skill: 75, rate: 0.48, hour: 20, streak: 0 },
  { id: 12, email: 'sua@shadowfit.kr', username: '강수아', sex: 'FEMALE', persona: 'BEGINNER', level: 'BEGINNER', h: 161, w: 53, skill: 74, rate: 0.62, hour: 18, streak: 4, today: true },
  { id: 13, email: 'yujin@shadowfit.kr', username: '한유진', sex: 'FEMALE', role: 'ADMIN', persona: 'ADVANCED', level: 'EXPERT', h: 168, w: 58, skill: 91, rate: 0.78, hour: 6, streak: 6, today: true },
  { id: 14, email: 'taeyang@shadowfit.kr', username: '오태양', sex: 'MALE', persona: 'ADVANCED', level: 'INTERMEDIATE', h: 179, w: 75, skill: 82, rate: 0.45, hour: 9, streak: 0 },
  { id: 15, email: 'jimin@shadowfit.kr', username: '서지민', sex: 'NONE', persona: 'REHAB', level: 'STARTER', h: 170, w: 64, skill: 68, rate: 0.4, hour: 11, streak: 2, today: true },
  { id: 16, email: 'gaon@shadowfit.kr', username: '문가온', sex: 'MALE', persona: 'REHAB', level: 'BEGINNER', h: 173, w: 70, skill: 71, rate: 0.35, hour: 16, streak: 0 },
  { id: 17, email: 'haram@shadowfit.kr', username: '임하람', sex: 'FEMALE', persona: 'REHAB', level: 'BEGINNER', h: 159, w: 51, skill: 69, rate: 0.42, hour: 14, streak: 1, today: false },
  { id: 18, email: 'woojin@shadowfit.kr', username: '신우진', sex: 'MALE', persona: 'ADVANCED', level: 'ADVANCED', h: 183, w: 80, skill: 86, rate: 0.66, hour: 8, streak: 7, today: true },
  { id: 19, email: 'soyul@shadowfit.kr', username: '배소율', sex: 'FEMALE', persona: 'DIET', level: 'INTERMEDIATE', h: 164, w: 56, skill: 79, rate: 0.52, hour: 17, streak: 0 },
  { id: 20, email: 'minseo@shadowfit.kr', username: '조민서', sex: 'FEMALE', persona: 'BEGINNER', level: null, h: null, w: null, skill: 0, rate: 0, hour: 0, streak: 0, joinedDaysAgo: 0, noOnboarding: true },
  { id: 21, email: 'bora@shadowfit.kr', username: '황보라', sex: 'FEMALE', persona: 'ADVANCED', level: 'EXPERT', h: 166, w: 57, skill: 90, rate: 0.3, hour: 7, streak: 0, joinedDaysAgo: 9 },
  { id: 22, email: 'junho@shadowfit.kr', username: '노준호', sex: 'MALE', persona: 'BEGINNER', level: 'BEGINNER', h: 172, w: 69, skill: 73, rate: 0.25, hour: 23, streak: 0, joinedDaysAgo: 20 },
];

// 모임 — owner 는 OWNER, members 는 가입 순서. left 는 탈퇴한 멤버.
export const GROUPS = [
  { id: 1, code: 'MORN7SQT', name: '아침 7시 스쿼트단', description: '우리 진짜 거북목 되지 말자 · 매일 아침 스쿼트 30개', owner: 2, members: [3, 4, 5, 6, 7], createdDaysAgo: 38 },
  { id: 2, code: 'NECKFREE', name: '거북목 탈출 클럽', description: '하루 15분, 자세부터 바로', owner: 3, members: [2, 8, 9, 10], createdDaysAgo: 33, left: [22] },
  { id: 3, code: 'LEGDAY9P', name: '퇴근 후 하체 데이', description: '저녁 7시~9시 각자 하고 인증!', owner: 8, members: [11, 12, 13, 14], createdDaysAgo: 29 },
  { id: 4, code: 'SLOWREHB', name: '재활 천천히 모임', description: '무리하지 않기, 대신 꾸준히', owner: 10, members: [15, 16, 17], createdDaysAgo: 25 },
  { id: 5, code: 'WKNDRUN5', name: '주말 러너스 & 스쿼트', description: '토요일 아침 한강, 평일엔 집에서 스쿼트', owner: 18, members: [19, 20, 14, 21], createdDaysAgo: 18 },
];

// 피드 공유 사진 — file 은 저장 이름(UUID). hero 는 큰 그림, stamp 는 오른쪽 위 배지.
export const PHOTOS = [
  { file: '3f0d5a62-7b1e-4c2a-9a51-5e8f0c1a2b01', title: '새벽 공원 스쿼트', sub: 'MORNING SET · 06:40', stamp: '30 REPS', hero: '🏋️‍♀️',
    bg: 'linear-gradient(180deg,#ffb38a 0%,#ff8a8a 45%,#7a5fc7 100%)', suns: [[540, 70, 170, '#ffe7a3']], hills: [[0, 190, '#5b4a9e'], [-40, 150, '#3d3270']], sides: [[80, 330, '🌳', 90], [640, 340, '🌳', 80]] },
  { file: '3f0d5a62-7b1e-4c2a-9a51-5e8f0c1a2b02', title: '거실 홈트 완료', sub: 'LIVING ROOM · 21:10', stamp: '3 SETS', hero: '🧘',
    bg: 'linear-gradient(180deg,#f6eadb 0%,#efd9bf 100%)', floor: '#d8b98f', ink: '#3b2a16', sides: [[70, 120, '🪴', 96], [630, 140, '🛋️', 92]], stampBg: '#3b2a16', stampInk: '#ffe9c8' },
  { file: '3f0d5a62-7b1e-4c2a-9a51-5e8f0c1a2b03', title: '한강 러닝 후 스쿼트', sub: 'HAN RIVER · 07:20', stamp: '5.2 KM', hero: '🏃‍♂️',
    bg: 'linear-gradient(180deg,#9fd8ff 0%,#5aa9e6 55%,#2d6fb3 100%)', suns: [[90, 60, 110, '#fff6c9']], hills: [[0, 120, '#2a5d93'], [-30, 90, '#1e4775']], sides: [[600, 90, '🌉', 120]] },
  { file: '3f0d5a62-7b1e-4c2a-9a51-5e8f0c1a2b04', title: '오늘도 매트 위로', sub: 'STRETCH · 22:05', stamp: 'DAY 12', hero: '🤸',
    bg: 'linear-gradient(160deg,#e3dcff 0%,#c4b5fd 60%,#a78bfa 100%)', floor: '#8b74e8', sides: [[80, 90, '✨', 70], [650, 120, '🌙', 80]] },
  { file: '3f0d5a62-7b1e-4c2a-9a51-5e8f0c1a2b05', title: '하체 데이 인증', sub: 'GYM · 19:40', stamp: 'PR!', hero: '💪',
    bg: 'radial-gradient(circle at 50% 30%,#2a3122 0%,#14170f 70%)', floor: '#1e2219', suns: [[300, 40, 200, 'rgba(202,255,0,.25)']], blur: 30, sides: [[80, 250, '🏋️', 90], [630, 260, '🔥', 80]], stampBg: '#caff00' },
  { file: '3f0d5a62-7b1e-4c2a-9a51-5e8f0c1a2b06', title: '퇴근하고 바로', sub: 'AFTER WORK · 20:30', stamp: '25 REPS', hero: '🦵',
    bg: 'linear-gradient(180deg,#2b1d52 0%,#5b2a86 55%,#a4508b 100%)', hills: [[0, 170, '#1b1236']], sides: [[60, 300, '🏙️', 120], [600, 290, '🌃', 120]], heroTop: 120 },
  { file: '3f0d5a62-7b1e-4c2a-9a51-5e8f0c1a2b07', title: '점심 산책 + 스쿼트', sub: 'LUNCH BREAK · 12:30', stamp: '8,214 걸음', hero: '🚶‍♀️',
    bg: 'linear-gradient(180deg,#d7f5c9 0%,#9fdc8a 60%,#5fae5a 100%)', hills: [[0, 140, '#4f9a4b'], [-30, 110, '#3d7f3a']], sides: [[70, 120, '🌳', 110], [620, 150, '🌳', 100], [380, 60, '☀️', 70]] },
  { file: '3f0d5a62-7b1e-4c2a-9a51-5e8f0c1a2b08', title: '비 와도 홈트', sub: 'RAINY DAY · 18:50', stamp: '20 REPS', hero: '🏠',
    bg: 'linear-gradient(180deg,#b9c6d6 0%,#8a9bb0 100%)', floor: '#6b7a8f', sides: [[90, 60, '🌧️', 90], [600, 70, '🌧️', 80], [330, 330, '🏋️', 90]], heroTop: 110, heroSize: 180 },
  { file: '3f0d5a62-7b1e-4c2a-9a51-5e8f0c1a2b09', title: '주말 등산 끝!', sub: 'WEEKEND · 10:15', stamp: '812 m', hero: '⛰️',
    bg: 'linear-gradient(180deg,#bfe9e4 0%,#7cc9bf 100%)', hills: [[0, 230, '#3f8f85'], [-40, 170, '#2c6d65']], sides: [[600, 60, '☁️', 90], [90, 90, '☁️', 70]], heroTop: 90 },
  { file: '3f0d5a62-7b1e-4c2a-9a51-5e8f0c1a2b10', title: '재활 3주차', sub: 'SLOW & STEADY · 11:00', stamp: '무릎 OK', hero: '🧎',
    bg: 'linear-gradient(180deg,#e6fbf3 0%,#b5ecd8 100%)', floor: '#8fd7bd', ink: '#1f4d3d', sides: [[80, 110, '🍃', 80], [640, 110, '💧', 70]], stampBg: '#1f4d3d', stampInk: '#e6fbf3' },
  { file: '3f0d5a62-7b1e-4c2a-9a51-5e8f0c1a2b11', title: '연속 7일 달성', sub: 'STREAK · 07:05', stamp: '🔥 7 DAYS', hero: '🏅',
    bg: 'radial-gradient(circle at 50% 40%,#fff1b8 0%,#ffcf4a 55%,#f39b1f 100%)', ink: '#5a3200', sides: [[90, 90, '✨', 80], [620, 110, '🎉', 80]], stampBg: '#5a3200', stampInk: '#fff1b8' },
  { file: '3f0d5a62-7b1e-4c2a-9a51-5e8f0c1a2b12', title: '친구랑 같이 하니까', sub: 'WITH FRIENDS · 19:00', stamp: '2명 완주', hero: '👯‍♀️',
    bg: 'linear-gradient(180deg,#ffd6cc 0%,#ff9e8a 100%)', floor: '#f07d68', sides: [[80, 120, '🎶', 80], [640, 120, '💗', 80]] },
];

// 응원 문구 — 앱의 정형 칩과 같은 문장
export const CHEERS = ['😄 오늘도 힘내', '👏 칭찬해요!', '🔥 응원해요', '😅 고생했어', '💪 할 수 있다!', '😆 다음에 같이 운동하자', '😮 대단해!', '💯 100점 드립니다', '💗', '😎 진정한 운동인', '👏 칭찬해요! 💪 할 수 있다!', '🔥'];

export const MOODS = ['GREAT', 'GOOD', 'GOOD', 'NORMAL', 'GREAT'];
export const MEMOS = [
  '퇴근하고 바로 했더니 개운하다', '무릎 안쪽으로 모이는 거 신경 썼음', '오늘은 깊이가 좀 얕았던 듯', '12일 연속! 내일도 하자',
  '승연이 응원 받고 힘냄', '허리 펴는 게 확실히 편해졌다', '피곤했지만 15분만 하자고 시작함', '세트 사이 쉬는 시간 줄여봄',
];
