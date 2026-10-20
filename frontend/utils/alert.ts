import { Alert as NativeAlert, Platform, type AlertButton, type AlertOptions } from 'react-native';

// react-native-web 의 Alert.alert 는 **빈 함수**다 — 웹에서는 창도 안 뜨고 버튼 onPress 도 영영 안 불린다.
// (로그아웃·탈퇴처럼 «확인창의 버튼 안에서만 실제 동작» 하는 코드는 웹에서 아무 반응이 없게 된다.)
// 그래서 같은 시그니처로 감싼다: 네이티브는 그대로, 웹은 window.alert / window.confirm.
//
// 웹 매핑 규칙
//   버튼 0~1개 → window.alert 후 그 버튼의 onPress
//   버튼 2개+  → window.confirm. [확인] = cancel 이 아닌 마지막 버튼, [취소] = style:'cancel' 버튼
//   (confirm 은 선택지가 둘뿐이라 버튼 3개 이상이면 가운데 것들은 고를 수 없다 — 지금 앱엔 그런 곳이 없다)
function alert(title: string, message?: string, buttons?: AlertButton[], options?: AlertOptions): void {
  if (Platform.OS !== 'web') {
    NativeAlert.alert(title, message, buttons, options);
    return;
  }

  const text = [title, message].filter(Boolean).join('\n\n');

  if (!buttons || buttons.length <= 1) {
    window.alert(text);
    buttons?.[0]?.onPress?.();
    return;
  }

  const cancel = buttons.find((b) => b.style === 'cancel');
  const confirm = [...buttons].reverse().find((b) => b.style !== 'cancel') ?? buttons[buttons.length - 1];
  if (window.confirm(text)) confirm.onPress?.();
  else cancel?.onPress?.();
}

export const Alert = { alert };
