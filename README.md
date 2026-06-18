# SPRING PLUS

Spring Boot 기반 일정 관리 REST API 프로젝트입니다.  
회원 인증, 일정 관리, 댓글, 담당자, 관리자 권한 변경 기능을 제공하며 JWT와 Spring Security로 인증/인가를 처리합니다.

## 구현 사항

- 회원가입 및 로그인
- JWT 기반 인증/인가
- Spring Security `SecurityFilterChain` 적용
- `BCryptPasswordEncoder` 기반 비밀번호 암호화
- 인증 사용자 정보 주입
- 관리자 API 접근 권한 제한
- 관리자 권한 변경 전 접근 로그 기록
- 일정 생성 및 단건/목록 조회
- 일정 생성 시 작성자를 기본 담당자로 등록
- 날씨와 수정일 범위 기준 일정 검색
- QueryDSL 기반 동적 검색
- Todo 단건 조회 시 fetch join 적용
- 댓글 조회 N+1 문제 개선
- 댓글 등록 및 조회
- 담당자 등록, 조회, 삭제
- 사용자 조회 및 비밀번호 변경
- 공통 예외 응답 처리

## 기술 스택

- Java 17
- Spring Boot 3.3.3
- Spring Web
- Spring Data JPA
- Spring Security
- QueryDSL
- JWT
- MySQL
- H2
- Gradle
- Lombok
- JUnit 5

## 프로젝트 구조

```text
src/main/java/org/example/expert
├── aop
│   └── 관리자 접근 로그 AOP
├── client
│   └── 외부 날씨 API 연동
├── config
│   ├── JWT 인증 필터
│   ├── Spring Security 설정
│   ├── QueryDSL 설정
│   └── 전역 예외 처리
└── domain
    ├── auth
    ├── comment
    ├── common
    ├── manager
    ├── todo
    └── user
```

## 실행 전 설정

로컬 실행을 위해 다음 설정이 필요합니다.

- 데이터베이스 URL
- 데이터베이스 사용자명
- 데이터베이스 비밀번호
- JWT secret key

민감한 값은 README나 Git에 기록하지 않고, 로컬 설정 파일 또는 환경 변수로 관리하는 것을 권장합니다.

예시:

```yaml
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}

jwt:
  secret:
    key: ${JWT_SECRET_KEY}
```

## 실행 방법

```bash
./gradlew bootRun
```

Windows 환경:

```bash
gradlew.bat bootRun
```

테스트 실행:

```bash
./gradlew test
```

## 인증 방식

`/auth/signup`, `/auth/signin`은 인증 없이 접근할 수 있습니다.  
그 외 API는 로그인 또는 회원가입 응답으로 받은 JWT를 `Authorization` 헤더에 담아 요청해야 합니다.

```http
Authorization: Bearer <token>
```

관리자 API는 `ADMIN` 권한을 가진 사용자만 접근할 수 있습니다.

## API

### Auth

| Method | URL | 설명 | 인증 |
| --- | --- | --- | --- |
| POST | `/auth/signup` | 회원가입 | 불필요 |
| POST | `/auth/signin` | 로그인 | 불필요 |

회원가입 요청:

```json
{
  "email": "user@example.com",
  "password": "Password123",
  "nickname": "user",
  "userRole": "USER"
}
```

로그인 요청:

```json
{
  "email": "user@example.com",
  "password": "Password123"
}
```

응답:

```json
{
  "bearerToken": "Bearer <token>"
}
```

### User

| Method | URL | 설명 | 인증 |
| --- | --- | --- | --- |
| GET | `/users/{userId}` | 사용자 조회 | 필요 |
| PUT | `/users` | 비밀번호 변경 | 필요 |
| PATCH | `/admin/users/{userId}` | 사용자 권한 변경 | ADMIN |

비밀번호 변경 요청:

```json
{
  "oldPassword": "Password123",
  "newPassword": "NewPassword123"
}
```

사용자 권한 변경 요청:

```json
{
  "role": "ADMIN"
}
```

### Todo

| Method | URL | 설명 | 인증 |
| --- | --- | --- | --- |
| POST | `/todos` | 일정 생성 | 필요 |
| GET | `/todos` | 일정 목록 조회 | 필요 |
| GET | `/todos/{todoId}` | 일정 단건 조회 | 필요 |

일정 생성 요청:

```json
{
  "title": "일정 제목",
  "contents": "일정 내용"
}
```

일정 목록 조회 쿼리 파라미터:

| 이름 | 기본값 | 설명 |
| --- | --- | --- |
| `page` | `1` | 페이지 번호 |
| `size` | `10` | 페이지 크기 |
| `weather` | 없음 | 날씨 조건 |
| `modifiedFrom` | 없음 | 수정일 시작 범위 |
| `modifiedTo` | 없음 | 수정일 종료 범위 |

요청 예시:

```http
GET /todos?page=1&size=10&weather=Sunny&modifiedFrom=2026-06-01T00:00:00&modifiedTo=2026-06-30T23:59:59
```

### Comment

| Method | URL | 설명 | 인증 |
| --- | --- | --- | --- |
| POST | `/todos/{todoId}/comments` | 댓글 등록 | 필요 |
| GET | `/todos/{todoId}/comments` | 댓글 조회 | 필요 |

댓글 등록 요청:

```json
{
  "contents": "댓글 내용"
}
```

### Manager

| Method | URL | 설명 | 인증 |
| --- | --- | --- | --- |
| POST | `/todos/{todoId}/managers` | 담당자 등록 | 필요 |
| GET | `/todos/{todoId}/managers` | 담당자 조회 | 필요 |
| DELETE | `/todos/{todoId}/managers/{managerId}` | 담당자 삭제 | 필요 |

담당자 등록 및 삭제는 해당 일정을 생성한 사용자만 수행할 수 있습니다.

담당자 등록 요청:

```json
{
  "managerUserId": 2
}
```

## 예외 응답

예외 응답은 다음 형식을 사용합니다.

```json
{
  "status": "BAD_REQUEST",
  "code": 400,
  "message": "Todo not found"
}
```

| 상태 코드 | 설명 |
| --- | --- |
| 400 | 잘못된 요청 |
| 401 | 인증 실패 |
| 403 | 권한 없음 |
| 500 | 서버 오류 |

## 외부 API

일정 생성 시 외부 날씨 API에서 오늘 날짜의 날씨를 조회해 일정에 저장합니다.  
외부 API 응답 실패 또는 오늘 날짜에 해당하는 날씨 데이터가 없을 경우 서버 오류가 발생할 수 있습니다.
