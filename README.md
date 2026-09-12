# BRAVO On-Device Transcribe v0.7.2

목표: Samsung Recordings/Call의 m4a 한 건을 외부 서버/API Key 없이 기기 안에서 한국어 텍스트로 변환.

테스트 순서
1. Recordings/Call 선택
2. 최근 녹음 불러오기
3. 김도경 54초 등 짧은 녹음 선택
4. 기기내 한국어 모델 진단
5. 선택 녹음 → 온디바이스 전사
6. 삼성 음성 녹음 앱의 기존 전사문과 비교

구조
m4a(AAC) → MediaCodec decode → mono 16kHz PCM16 → ParcelFileDescriptor pipe →
ML Kit GenAI Speech Recognition Basic(ko-KR) → 화면에 transcript

주의
- ML Kit speech recognition 1.0.0-alpha1 기반 기술검증판.
- Basic ko-KR은 beta 언어.
- Google 사양상 파일 입력은 실시간 속도로 공급해야 하므로 54초 통화는 약 54초 소요.
- 최초 모델 준비/다운로드 시 네트워크가 필요할 수 있으나 인식 자체는 온디바이스를 목표로 함.
- alpha API이므로 Galaxy 환경에 따라 모델 상태/SDK 동작을 실제 기기에서 검증해야 함.


## v0.7.2 build fix
- Kotlin 버튼 click-listener 괄호 오류 수정
- ML Kit alpha1 공식 옵션명 `preferredMode`로 수정
- alpha 응답 타입 변화에 견디도록 기타 응답은 `else` 처리


## v0.7.2 workflow fix
- GitHub Actions `setup-gradle` 단계의 cache restore를 비활성화했습니다.
- 사용자가 본 `Error: read ECONNRESET`은 소스 컴파일 오류가 아니라 GitHub/네트워크 캐시 복원 단계 오류입니다.
- 빌드 명령은 `--no-daemon`으로 실행합니다.
