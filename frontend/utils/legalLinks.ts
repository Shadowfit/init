import { Linking } from 'react-native';
import { Alert } from '@/utils/alert';
import { LEGAL_URL, type LegalDocument } from '@/constants/consent';

const TITLE: Record<LegalDocument, string> = {
  terms: '이용약관',
  privacy: '개인정보 처리방침',
};

/**
 * 약관·처리방침을 연다. 주소가 설정되어 있지 않으면(문서가 아직 게시되지 않았다) 그 사실을 알려 준다.
 * 동의 화면의 «보기» 가 쓴다 — 동의하는 사람이 문서를 볼 수 있어야 동의가 의미가 있다.
 */
export async function openLegalDocument(doc: LegalDocument): Promise<void> {
  const url = LEGAL_URL[doc];
  if (!url) {
    Alert.alert(TITLE[doc], '문서가 아직 게시되지 않았어요.');
    return;
  }
  try {
    await Linking.openURL(url);
  } catch {
    Alert.alert(TITLE[doc], '문서를 열 수 없어요. 잠시 후 다시 시도해주세요.');
  }
}
