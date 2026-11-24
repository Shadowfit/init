/**
 * 운동 화면 — 뒤로가기 확인 (PR #913).
 *
 * 운동 중에 그냥 나가면 세션이 서버에 «진행 중» 으로 남는다. 그래서 운동 중 뒤로가기는 먼저 묻고,
 * 종료를 고르면 기존 종료 경로(세션 종료 → 리포트)로 간다. 이 파일은 그 동작을 화면 단위로 고정한다.
 *
 * 카메라·라우터·서버 호출은 가짜로 대체한다 — 여기서 보는 것은 «이 화면의 분기» 이지 카메라나 서버가 아니다.
 * 파일 위치가 app/ 밖인 이유: expo-router 는 app/ 아래 파일을 전부 라우트로 만든다.
 */
import { BackHandler } from 'react-native';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react-native';

// 모듈 목은 호이스팅되므로 변수 이름이 mock 으로 시작해야 팩토리에서 참조할 수 있다.
const mockBack = jest.fn();
const mockReplace = jest.fn();
const mockAlert = jest.fn();

jest.mock('expo-router', () => {
  const React = require('react');
  return {
    useRouter: () => ({ back: mockBack, replace: mockReplace, push: jest.fn() }),
    useLocalSearchParams: () => ({}),
    // 화면이 보이는 동안만 도는 효과 — 테스트에서는 마운트 시 한 번 실행하면 된다.
    useFocusEffect: (cb: () => void | (() => void)) => React.useEffect(cb, [cb]),
  };
});

jest.mock('expo-camera', () => {
  const { View } = require('react-native');
  return {
    CameraView: ({ children }: { children?: React.ReactNode }) => <View>{children}</View>,
    useCameraPermissions: () => [{ granted: true, canAskAgain: true }, jest.fn()],
  };
});

jest.mock('expo-haptics', () => ({
  notificationAsync: jest.fn(() => Promise.resolve()),
  NotificationFeedbackType: { Error: 'error' },
}));

jest.mock('@/utils/alert', () => ({ Alert: { alert: (...args: unknown[]) => mockAlert(...args) } }));

jest.mock('@/components/exercise/MannequinDemo', () => () => null);

jest.mock('@/lib/motion/recorder', () => ({
  motionRecorder: { start: jest.fn(), push: jest.fn(), stop: jest.fn() },
}));

jest.mock('@/services/aiService', () => ({
  aiConfigError: null,
  // 프레임 전송은 끝나지 않는 약속으로 둔다 — 이 테스트의 관심사가 아니다.
  aiService: { detectPose: jest.fn(() => new Promise(() => {})) },
}));

const mockStartSession = jest.fn();
const mockEndSession = jest.fn();
jest.mock('@/services/exercisesService', () => ({
  exercisesService: {
    startSession: (...a: unknown[]) => mockStartSession(...a),
    endSession: (...a: unknown[]) => mockEndSession(...a),
    getActiveSession: jest.fn(() => Promise.resolve({ status: 204, data: null })),
    reattachSession: jest.fn(),
    getFeedbackTemplates: jest.fn(() => Promise.resolve({ data: [] })),
  },
}));

import ExerciseScreen from '@/app/(tabs)/exercise';

process.env.EXPO_PUBLIC_AI_PUBLIC_TOKEN = 'test-token';

let hardwareBack: (() => boolean) | null = null;

beforeEach(() => {
  jest.clearAllMocks();
  hardwareBack = null;
  mockStartSession.mockResolvedValue({
    data: { sessionId: 7, sessionNonce: 'nonce', aiWorkerIndex: 0, exerciseId: 1 },
  });
  mockEndSession.mockResolvedValue({});
  // 하드웨어 뒤로가기 핸들러를 붙잡아 테스트에서 직접 누른다.
  jest.spyOn(BackHandler, 'addEventListener').mockImplementation((_event: string, handler: (...args: any[]) => boolean | null | undefined) => {
    hardwareBack = handler as () => boolean;
    return { remove: jest.fn() };
  });
});

async function startWorkout() {
  await render(<ExerciseScreen />);
  await fireEvent.press(screen.getByLabelText('운동 시작'));
  await waitFor(() => expect(screen.getByLabelText('운동 종료')).toBeTruthy());
}

describe('운동 화면 뒤로가기', () => {
  it('운동을 시작하기 전에는 묻지 않고 바로 뒤로 간다', async () => {
    await render(<ExerciseScreen />);

    await fireEvent.press(screen.getByLabelText('뒤로가기'));

    expect(mockBack).toHaveBeenCalledTimes(1);
    expect(mockAlert).not.toHaveBeenCalled();
  });

  it('운동 중에 뒤로 가려 하면 종료할지 먼저 묻고, 아직 나가지 않는다', async () => {
    await startWorkout();

    await fireEvent.press(screen.getByLabelText('뒤로가기'));

    expect(mockAlert).toHaveBeenCalledTimes(1);
    expect(mockAlert.mock.calls[0][0]).toBe('운동을 끝낼까요?');
    expect(mockBack).not.toHaveBeenCalled();
    expect(mockEndSession).not.toHaveBeenCalled();
  });

  it('«계속 운동하기» 는 아무것도 하지 않는다', async () => {
    await startWorkout();
    await fireEvent.press(screen.getByLabelText('뒤로가기'));

    const buttons = mockAlert.mock.calls[0][2] as { text: string; style?: string; onPress?: () => void }[];
    const keepGoing = buttons.find((b) => b.text === '계속 운동하기');

    expect(keepGoing?.style).toBe('cancel');
    expect(keepGoing?.onPress).toBeUndefined();
    expect(mockEndSession).not.toHaveBeenCalled();
    expect(screen.getByLabelText('운동 종료')).toBeTruthy(); // 여전히 운동 중
  });

  it('«종료하고 리포트 보기» 를 고르면 세션을 끝내고 리포트로 간다', async () => {
    await startWorkout();
    await fireEvent.press(screen.getByLabelText('뒤로가기'));

    const buttons = mockAlert.mock.calls[0][2] as { text: string; onPress?: () => void }[];
    // 확인창 버튼 콜백은 화면 밖(네이티브 Alert)에서 불리지만 상태를 바꾸므로 act 로 감싼다
    await act(async () => {
      buttons.find((b) => b.text === '종료하고 리포트 보기')?.onPress?.();
    });

    await waitFor(() => expect(mockEndSession).toHaveBeenCalledWith(7));
    await waitFor(() => expect(mockReplace).toHaveBeenCalledWith('/report/7'));
  });

  it('안드로이드 하드웨어 뒤로가기도 운동 중에는 같은 확인을 거친다', async () => {
    await startWorkout();

    expect(hardwareBack).not.toBeNull();
    const handled = hardwareBack!();

    expect(handled).toBe(true); // true = 기본 동작(화면 이탈)을 막았다
    expect(mockAlert.mock.calls[0][0]).toBe('운동을 끝낼까요?');
    expect(mockBack).not.toHaveBeenCalled();
  });

  it('운동 전에는 하드웨어 뒤로가기를 가로채지 않는다', async () => {
    await render(<ExerciseScreen />);

    expect(hardwareBack).toBeNull();
  });
});

describe('운동 화면 격자 안내선', () => {
  it('격자 버튼을 누를 때마다 켜졌다 꺼진다', async () => {
    await render(<ExerciseScreen />);
    const grid = screen.getByLabelText('격자 안내선');
    expect(grid.props.accessibilityState?.selected).toBe(false);

    await fireEvent.press(grid);
    expect(screen.getByLabelText('격자 안내선').props.accessibilityState?.selected).toBe(true);

    await fireEvent.press(screen.getByLabelText('격자 안내선'));
    expect(screen.getByLabelText('격자 안내선').props.accessibilityState?.selected).toBe(false);
  });
});
