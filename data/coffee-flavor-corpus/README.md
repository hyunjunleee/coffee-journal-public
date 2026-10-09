# 커피 향미 코퍼스 (coffee-flavor-corpus)

외부 API 없이 기기에서 돌릴 작은 언어 모델(약 1B)을 학습시키려고 모으는 텍스트입니다. 이 모델은 커피 향미 표현에서 그 커피의 컵노트를 추정해야 합니다. 이 폴더의 데이터는 그 모델의 mid-training용입니다.

- 1차 목표는 **한국어 단어 약 100,000개**입니다. 단어는 공백으로 나눈 어절을 뜻하며, `lang`이 `ko` 또는 `mixed`인 항목의 `words`를 더해 셉니다.
- 수집은 여러 에이전트가 영역(lane)을 나눠 병렬로 합니다. 각 에이전트는 100개를 모을 때마다 `shards/<lane>-NNNN.jsonl` 하나로 커밋하고 푸시합니다. 커밋 메시지에는 `[skip ci]`가 붙습니다.
- 도구는 [`tools/corpus/corpus.py`](../../tools/corpus/corpus.py)입니다.

## 항목 형식 (JSON Lines)
```json
{"id": "ko-roaster-0001-001", "lane": "ko-roaster", "lang": "ko", "kind": "cup_notes",
 "text": "자스민의 꽃향과 복숭아의 단맛, 홍차 같은 여운", "excerpt": true,
 "cup_notes": ["자스민", "복숭아", "홍차"],
 "subject": {"coffee": "에티오피아 예가체프 코체레", "process": "워시드", "roaster": "…"},
 "source_url": "https://…", "source_title": "…", "publisher": "…",
 "license": "copyrighted-excerpt", "words": 9, "retrieved": "2026-10-09"}
```
| 필드 | 뜻 |
|---|---|
| `lang` | `ko` · `en` · `mixed`(한국어 문장에 영어 노트가 섞인 경우 등) |
| `kind` | `cup_notes`(상품·로트의 노트와 짧은 설명), `tasting_review`(특정 커피의 시음·커핑 서술), `lexicon`(향미 용어 정의), `flavor_wheel`(휠·분류 구조), `education`(관능 평가·가공·로스팅이 향미에 주는 영향 등 설명), `article`, `forum`, `competition`(대회·CoE 심사 노트) |
| `text` | 내용. 아래 라이선스 정책을 따릅니다. |
| `cup_notes` | 출처가 그 커피에 직접 적은 노트를 그대로 옮긴 것. 없으면 `[]`. 설명(`text`) → 노트(`cup_notes`) 쌍은 나중에 SFT 데이터로도 씁니다. |
| `subject` | 무엇에 관한 글인지(선택): `coffee`, `origin`, `region`, `variety`, `process`, `roast`, `roaster` |
| `source_url` | 그 내용이 있는 페이지 주소. `source_title`, `publisher`도 함께 적습니다. |
| `license` | 아래 목록 중 하나 |
| `words` · `retrieved` · `id` · `lane` | 도구가 채웁니다. |

## 라이선스 정책 (이 저장소는 공개입니다)
- **전문 수록**은 재배포를 허락한 라이선스일 때만 합니다(항목당 최대 600단어, 긴 글은 나눠 싣습니다).
  - 허용 라이선스: `CC BY 4.0` · `CC BY 3.0` · `CC BY 2.0 KR` · `CC BY-SA 4.0` · `CC BY-SA 3.0` · `CC BY-SA 2.0 KR` · `CC BY-NC 4.0` · `CC BY-NC-SA 4.0` · `CC BY-NC-SA 2.0 KR` · `CC0` · `public-domain` · `KOGL-1` · `KOGL-2`(공공누리 1·2유형)
  - 예: 위키백과, 국립국어원 우리말샘, 공공누리 표시가 있는 공공 자료, CC 라이선스 논문.
- **그 밖의 페이지**(로스터리 상품 설명, 블로그, 잡지, 리뷰 사이트 등)는 `copyrighted-excerpt`로 표시합니다.
  - 그 커피의 향미를 말하는 **짧은 인용**(최대 60단어)과, 출처가 적은 컵노트 목록만 싣습니다.
  - 출처 주소를 반드시 남깁니다.
- 수집 도구는 `robots.txt`가 막는 페이지를 가져오지 않습니다. 요청은 1초에 한 번 이하로 보냅니다.
- 개인 정보(이름 외의 연락처 등)와 로그인이 필요한 페이지는 싣지 않습니다.

## 도구
```sh
python3 tools/corpus/corpus.py fetch URL          # 페이지 본문(JSON). robots.txt가 막으면 거절
python3 tools/corpus/corpus.py wiki ko 커피        # 위키백과 문서 평문(CC BY-SA 4.0)
python3 tools/corpus/corpus.py seen URL           # 이미 실은 주소인지
python3 tools/corpus/corpus.py stage LANE FILE    # 후보(JSON Lines) 검증·중복 제거 후 대기열에
python3 tools/corpus/corpus.py commit LANE        # 대기열이 100개가 되면 샤드로 커밋·푸시 (끝낼 때 --flush)
python3 tools/corpus/corpus.py stats              # 항목·단어 수, lane·종류·라이선스별
```
중복은 정규화한 본문(소문자, 문장부호와 연속 공백 제거)이 같은지로 판단합니다.
