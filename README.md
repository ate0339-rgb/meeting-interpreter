# 미팅 통역 노트 (Meeting Interpreter Notes)

영어 미팅을 폰에서 실시간으로 받아 적고(위), 한국어로 번역해(아래) 미팅별로 기록하는 웹앱입니다.

- 실시간 영어 인식: 브라우저 내장 음성 인식 (Android Chrome, iOS Safari)
- 번역: Google 번역(기본), ChatGPT 또는 Claude(선택)
- 미팅 중 질문하기, 단어 탭해서 뜻 보기·단어장 저장, 미팅 후 AI 분석
- ChatGPT 연결: [Sign in with ChatGPT](https://developers.openai.com/siwc/token-sharing-open-source) 오픈소스 클라이언트 흐름. 로그인 토큰은 저장하지 않고 앱이 열려 있는 동안만 메모리에 둡니다.
- 미팅 기록은 각자 기기의 브라우저 저장소에만 남습니다. 서버로 보내지 않습니다.

단일 `index.html` 정적 앱이라 빌드 과정이 없습니다.
