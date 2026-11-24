import { render, screen, fireEvent } from '@testing-library/react-native';
import LoadError from '@/components/ui/LoadError';

describe('LoadError', () => {
  it('실패 문구와 «다시 시도» 를 보여 준다', async () => {
    await render(<LoadError message="모임을 불러오지 못했어요." onRetry={() => {}} />);

    expect(screen.getByText('모임을 불러오지 못했어요.')).toBeTruthy();
    expect(screen.getByText('다시 시도')).toBeTruthy();
  });

  it('문구를 안 주면 일반 안내를 쓴다', async () => {
    await render(<LoadError onRetry={() => {}} />);

    expect(screen.getByText(/불러오지 못했어요/)).toBeTruthy();
  });

  it('«다시 시도» 를 누르면 onRetry 가 한 번 불린다', async () => {
    const onRetry = jest.fn();
    await render(<LoadError onRetry={onRetry} />);

    await fireEvent.press(screen.getByText('다시 시도'));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('스크린리더가 오류로 읽도록 alert 역할을 가진다', async () => {
    await render(<LoadError onRetry={() => {}} />);

    expect(screen.getByRole('alert')).toBeTruthy();
  });
});
