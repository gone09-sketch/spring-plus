# Spring Plus

Spring Boot 기반 일정 관리 REST API 프로젝트입니다.  
회원가입, 로그인, 일정(Todo), 댓글(Comment), 담당자(Manager), 사용자 권한 변경 기능을 제공하며 JWT와 Spring Security를 사용해 인증/인가를 처리합니다.

이 프로젝트는 기존 코드에서 발생하던 트랜잭션 오류, JWT 정보 부족, 동적 검색 조건 부족, 테스트 실패, AOP Pointcut 오류, Cascade 누락, N+1 문제, QueryDSL 전환, Spring Security 도입 과제를 해결한 결과물입니다.

## 기술 스택

- Java 17
- Spring Boot 3.3.3
- Spring Web
- Spring Data JPA
- Hibernate
- Spring Security
- JWT
- QueryDSL
- MySQL
- H2
- Gradle
- Lombok
- JUnit 5
- MockMvc

## 프로젝트 구조

```text
src/main/java/org/example/expert
├── aop
│   └── 관리자 접근 로그 AOP
├── client
│   └── 외부 날씨 API 연동
├── config
│   ├── Spring Security 설정
│   ├── JWT 생성 및 검증
│   ├── JWT 인증 필터
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

## 주요 구현 내용

| 구분 | 주제 | 구현 내용 |
| --- | --- | --- |
| Level 1-1 | `@Transactional` | Todo 저장 메서드가 쓰기 트랜잭션에서 실행되도록 수정했습니다. |
| Level 1-2 | JWT | `User.nickname`을 추가하고 JWT Claim에 닉네임을 포함했습니다. |
| Level 1-3 | JPA | `weather`, `modifiedFrom`, `modifiedTo` 조건으로 Todo 목록을 검색하도록 JPQL을 작성했습니다. |
| Level 1-4 | Controller Test | Todo 미존재 예외 테스트가 실제 예외 응답인 `400 Bad Request`와 일치하도록 수정했습니다. |
| Level 1-5 | AOP | `UserAdminController.changeUserRole()` 실행 전에 관리자 접근 로그가 남도록 Pointcut을 수정했습니다. |
| Level 2-6 | Cascade | Todo 생성 시 작성자가 담당자로 자동 저장되도록 `CascadeType.PERSIST`를 적용했습니다. |
| Level 2-7 | N+1 | 댓글 조회 시 Comment와 User를 fetch join으로 함께 조회하도록 수정했습니다. |
| Level 2-8 | QueryDSL | Todo 단건 조회를 QueryDSL의 `fetchJoin()` 기반으로 변경했습니다. |
| Level 2-9 | Spring Security | 기존 인증 흐름을 Spring Security 기반 JWT 인증/인가 구조로 변경했습니다. |

## 과제별 상세 정리

### 1. `@Transactional` 오류 해결

Todo 저장 API(`/todos`) 호출 시 다음 오류가 발생했습니다.

```text
Connection is read-only. Queries leading to data modification are not allowed
```

원인은 저장 로직이 읽기 전용 트랜잭션에서 실행된 것입니다. `readOnly = true`는 조회 전용 메서드에 사용하는 옵션이므로, `INSERT`, `UPDATE`, `DELETE`가 필요한 메서드에 사용하면 안 됩니다.

```java
@Transactional
public TodoSaveResponse saveTodo(AuthUser authUser, TodoSaveRequest todoSaveRequest) {
    User user = User.fromAuthUser(authUser);
    String weather = weatherClient.getTodayWeather();

    Todo newTodo = new Todo(
            todoSaveRequest.getTitle(),
            todoSaveRequest.getContents(),
            weather,
            user
    );

    Todo savedTodo = todoRepository.save(newTodo);
    ...
}
```

조회 메서드에는 `@Transactional(readOnly = true)`를 유지하고, 저장 메서드에는 일반 `@Transactional`을 적용해 트랜잭션 의도를 분리했습니다.

### 2. JWT에 nickname 추가

`User` 엔티티에 `nickname` 필드를 추가했습니다. 닉네임은 중복 가능해야 하므로 `unique = true`를 설정하지 않았습니다.

```java
private String nickname;
```

회원가입 요청에도 `nickname`을 추가했습니다.

```java
@NotBlank
private String nickname;
```

JWT 생성 시 닉네임을 Claim에 포함했습니다.

```java
public String createToken(Long userId, String email, String nickname, UserRole userRole) {
    return BEARER_PREFIX +
            Jwts.builder()
                    .setSubject(String.valueOf(userId))
                    .claim("email", email)
                    .claim("nickname", nickname)
                    .claim("userRole", userRole)
                    .setExpiration(new Date(date.getTime() + TOKEN_TIME))
                    .setIssuedAt(date)
                    .signWith(key, signatureAlgorithm)
                    .compact();
}
```

닉네임은 화면 표시용 정보입니다. 권한 판단은 닉네임이 아니라 `userRole`을 기준으로 처리합니다.

### 3. Todo 검색 조건 추가

Todo 목록 조회 시 아래 조건을 선택적으로 받을 수 있도록 수정했습니다.

- `weather`
- `modifiedFrom`
- `modifiedTo`

```java
@GetMapping("/todos")
public ResponseEntity<Page<TodoResponse>> getTodos(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(name ="weather", required = false) String weather,
        @RequestParam(name = "modifiedFrom", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime modifiedFrom,
        @RequestParam(name = "modifiedTo", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime modifiedTo
) {
    return ResponseEntity.ok(todoService.getTodos(page, size, weather, modifiedFrom, modifiedTo));
}
```

Repository에서는 JPQL을 사용했습니다.

```java
@Query(
        value = "SELECT t FROM Todo t " +
                "LEFT JOIN FETCH t.user " +
                "WHERE (:weather IS NULL OR t.weather = :weather) " +
                "AND (:modifiedFrom IS NULL OR t.modifiedAt >= :modifiedFrom) " +
                "AND (:modifiedTo IS NULL OR t.modifiedAt <= :modifiedTo) " +
                "ORDER BY t.modifiedAt DESC",
        countQuery = "SELECT COUNT(t) FROM Todo t " +
                "WHERE (:weather IS NULL OR t.weather = :weather) " +
                "AND (:modifiedFrom IS NULL OR t.modifiedAt >= :modifiedFrom) " +
                "AND (:modifiedTo IS NULL OR t.modifiedAt <= :modifiedTo)"
)
Page<Todo> searchTodos(
        @Param("weather") String weather,
        @Param("modifiedFrom") LocalDateTime modifiedFrom,
        @Param("modifiedTo") LocalDateTime modifiedTo,
        Pageable pageable
);
```

검색 조건이 늘어날수록 쿼리 메서드명은 지나치게 길어질 수 있습니다. 그래서 JPQL을 사용해 조건을 명확하게 표현했습니다.

### 4. Todo 단건 조회 실패 테스트 수정

Todo가 존재하지 않을 때 서비스에서는 `InvalidRequestException`을 발생시키고, 전역 예외 처리기는 이를 `400 Bad Request`로 변환합니다.

```java
@ExceptionHandler(InvalidRequestException.class)
public ResponseEntity<Map<String, Object>> invalidRequestExceptionException(InvalidRequestException ex) {
    HttpStatus status = HttpStatus.BAD_REQUEST;
    return getErrorResponse(status, ex.getMessage());
}
```

따라서 컨트롤러 테스트도 실제 응답과 동일하게 `400`과 예외 응답 JSON을 검증하도록 수정했습니다.

```java
mockMvc.perform(get("/todos/{todoId}", todoId))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(HttpStatus.BAD_REQUEST.name()))
        .andExpect(jsonPath("$.code").value(HttpStatus.BAD_REQUEST.value()))
        .andExpect(jsonPath("$.message").value("Todo not found"));
```

Spring Security 적용 이후에는 `@WithMockUser(authorities = "USER")`를 사용해 테스트 요청에 인증 사용자를 넣었습니다.

### 5. AOP 관리자 접근 로그 수정

관리자 권한 변경 API가 실행되기 전에 로그가 남도록 AOP Pointcut을 수정했습니다.

```java
@Before("execution(* org.example.expert.domain.user.controller.UserAdminController.changeUserRole(..))")
public void logBeforeChangeUserRole(JoinPoint joinPoint) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    ...
}
```

AOP는 로깅처럼 여러 메서드에서 반복될 수 있는 공통 기능을 분리할 때 사용합니다. 이 프로젝트에서는 관리자 권한 변경이라는 민감한 작업에 대해 접근 로그를 남기는 용도로 사용했습니다.

### 6. Cascade로 작성자를 담당자로 자동 등록

Todo를 생성할 때 작성자가 자동으로 담당자에 등록되도록 `Todo` 생성자에서 `addManager(user)`를 호출했습니다.

```java
public Todo(String title, String contents, String weather, User user) {
    this.title = title;
    this.contents = contents;
    this.weather = weather;
    this.user = user;
    addManager(user);
}

private void addManager(User user) {
    this.managers.add(new Manager(user, this));
}
```

그리고 Todo가 저장될 때 Manager도 함께 저장되도록 Cascade를 설정했습니다.

```java
@OneToMany(
        mappedBy = "todo",
        cascade = {CascadeType.PERSIST, CascadeType.REMOVE},
        orphanRemoval = true
)
private List<Manager> managers = new ArrayList<>();
```

Cascade는 부모 엔티티의 저장/삭제 작업을 자식 엔티티에 전파하는 기능입니다. 여기서는 Todo를 만들 때 Manager도 같이 만들어지는 구조이므로 `Todo -> Manager` 방향으로 Cascade를 적용했습니다.

### 7. 댓글 조회 N+1 문제 해결

댓글 목록을 조회한 뒤 각 댓글의 작성자(User)를 사용할 때, 댓글 개수만큼 User 조회 쿼리가 추가로 발생할 수 있습니다. 이를 N+1 문제라고 합니다.

이를 해결하기 위해 Comment와 User를 fetch join으로 함께 조회했습니다.

```java
@Query("SELECT c FROM Comment c JOIN FETCH c.user WHERE c.todo.id = :todoId")
List<Comment> findByTodoIdWithUser(@Param("todoId") Long todoId);
```

댓글 응답 DTO를 만들 때 `comment.getUser()`를 사용하므로, 처음부터 User를 함께 조회하는 것이 성능상 유리합니다.

### 8. QueryDSL로 Todo 단건 조회 변경

기존 JPQL 기반 Todo 단건 조회를 QueryDSL 기반으로 변경했습니다.

```java
@Override
public Optional<Todo> findByIdWithUser(Long todoId) {
    Todo foundTodo = queryFactory
            .selectFrom(todo)
            .join(todo.user, user).fetchJoin()
            .where(todo.id.eq(todoId))
            .fetchOne();

    return Optional.ofNullable(foundTodo);
}
```

QueryDSL은 문자열이 아니라 Java 코드로 쿼리를 작성합니다. 그래서 필드명 변경이나 타입 오류를 컴파일 시점에 더 쉽게 발견할 수 있습니다.

또한 `fetchJoin()`을 사용해 Todo와 User를 함께 조회하므로 단건 조회에서도 N+1 문제가 발생하지 않도록 했습니다.

### 9. Spring Security 기반 인증/인가 적용

기존 JWT 인증 흐름을 Spring Security 기반으로 변경했습니다.

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/auth/**").permitAll()
                    .requestMatchers("/error", "/error/**").permitAll()
                    .requestMatchers("/admin/**").hasAuthority(UserRole.ADMIN.name())
                    .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
}
```

JWT 인증 필터에서는 토큰을 검증한 뒤 `SecurityContextHolder`에 인증 정보를 저장합니다.

```java
UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(authUser, null, authorityList);

SecurityContextHolder.getContext().setAuthentication(authentication);
```

기존 `@Auth` 애노테이션은 제거하지 않고, Spring Security의 `@AuthenticationPrincipal`을 감싸는 방식으로 변경했습니다.

```java
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@AuthenticationPrincipal
public @interface Auth {
}
```

이렇게 하면 컨트롤러 코드를 크게 바꾸지 않으면서도 내부 인증 방식은 Spring Security 표준 구조를 사용할 수 있습니다.

## API

### Auth

| Method | URL | 설명 | 인증 |
| --- | --- | --- | --- |
| `POST` | `/auth/signup` | 회원가입 | 불필요 |
| `POST` | `/auth/signin` | 로그인 | 불필요 |

회원가입 요청 예시:

```json
{
  "email": "user@example.com",
  "password": "Password123",
  "nickname": "user",
  "userRole": "USER"
}
```

로그인 요청 예시:

```json
{
  "email": "user@example.com",
  "password": "Password123"
}
```

응답 예시:

```json
{
  "bearerToken": "Bearer <token>"
}
```

### User

| Method | URL | 설명 | 인증 |
| --- | --- | --- | --- |
| `GET` | `/users/{userId}` | 사용자 조회 | 필요 |
| `PUT` | `/users` | 비밀번호 변경 | 필요 |
| `PATCH` | `/admin/users/{userId}` | 사용자 권한 변경 | ADMIN |

비밀번호 변경 요청 예시:

```json
{
  "oldPassword": "Password123",
  "newPassword": "NewPassword123"
}
```

권한 변경 요청 예시:

```json
{
  "role": "ADMIN"
}
```

### Todo

| Method | URL | 설명 | 인증 |
| --- | --- | --- | --- |
| `POST` | `/todos` | 일정 생성 | 필요 |
| `GET` | `/todos` | 일정 목록 조회 | 필요 |
| `GET` | `/todos/{todoId}` | 일정 단건 조회 | 필요 |

일정 생성 요청 예시:

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
| `POST` | `/todos/{todoId}/comments` | 댓글 등록 | 필요 |
| `GET` | `/todos/{todoId}/comments` | 댓글 조회 | 필요 |

댓글 등록 요청 예시:

```json
{
  "contents": "댓글 내용"
}
```

### Manager

| Method | URL | 설명 | 인증 |
| --- | --- | --- | --- |
| `POST` | `/todos/{todoId}/managers` | 담당자 등록 | 필요 |
| `GET` | `/todos/{todoId}/managers` | 담당자 조회 | 필요 |
| `DELETE` | `/todos/{todoId}/managers/{managerId}` | 담당자 삭제 | 필요 |

담당자 등록 및 삭제는 해당 일정을 생성한 사용자만 수행할 수 있습니다.

담당자 등록 요청 예시:

```json
{
  "managerUserId": 2
}
```

## 인증 방식

`/auth/signup`, `/auth/signin`은 인증 없이 접근할 수 있습니다.  
그 외 API는 JWT를 `Authorization` 헤더에 담아 요청해야 합니다.

```http
Authorization: Bearer <token>
```

관리자 API(`/admin/**`)는 `ADMIN` 권한을 가진 사용자만 접근할 수 있습니다.

## 예외 응답

전역 예외 처리기는 아래 형식으로 예외 응답을 반환합니다.

```json
{
  "status": "BAD_REQUEST",
  "code": 400,
  "message": "Todo not found"
}
```

| 상태 코드 | 설명 |
| --- | --- |
| `400` | 잘못된 요청 |
| `401` | 인증 실패 |
| `403` | 권한 없음 |
| `500` | 서버 오류 |

## 외부 날씨 API

Todo 생성 시 `WeatherClient`가 외부 날씨 API를 호출해 오늘 날짜의 날씨를 조회하고, 조회된 날씨 값을 Todo의 `weather` 필드에 저장합니다.

```java
String weather = weatherClient.getTodayWeather();
```

외부 API 응답이 실패하거나 오늘 날짜에 해당하는 날씨 데이터가 없으면 `ServerException`이 발생합니다.

## 실행 전 설정

로컬 실행을 위해 데이터베이스와 JWT Secret Key 설정이 필요합니다.

민감한 값은 Git에 올리지 않고 환경 변수 또는 로컬 설정 파일로 관리하는 것을 권장합니다.

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

Windows PowerShell 기준:

```powershell
.\gradlew.bat bootRun
```

Mac 또는 Linux 기준:

```bash
./gradlew bootRun
```

## 테스트 실행

Windows PowerShell 기준:

```powershell
.\gradlew.bat test
```

IntelliJ IDEA에서는 테스트 클래스 또는 테스트 메서드 왼쪽의 실행 버튼을 눌러 개별 테스트를 실행할 수 있습니다.

## 커밋 히스토리 기준 구현 흐름

| 커밋 | 내용 |
| --- | --- |
| `d7b76b9` | Todo 생성과 조회 트랜잭션 설정 분리 |
| `dff06dc` | 회원 인증 흐름에 닉네임 정보 추가 |
| `c6b7310` | 날씨와 수정일 기준 할 일 검색 추가 |
| `3c810ab` | Todo 단건 조회 예외 HTTP 코드 테스트 수정 |
| `95f92b8` | 관리자 권한 변경 전 접근 로그 기록 |
| `cbdaa69` | Todo 생성 시 작성자 담당자 등록 누락 수정 |
| `b3e3942` | 댓글 조회 N+1 문제 개선 |
| `2e9abcd` | Todo 단건 조회 QueryDSL fetch join 적용 |
| `5fd08fa` | Spring Security 기반 JWT 인증/인가 적용 |
| `8e3d6bb` | 인증 사용자 주입을 `@AuthenticationPrincipal` 기반으로 변경 |
| `f728bb7` | Spring Security `PasswordEncoder`로 비밀번호 암호화 변경 |
| `427aa60` | Spring Security 적용에 따른 TodoController 테스트 인증 추가 |

## 학습 포인트

- 트랜잭션은 읽기 전용과 쓰기 작업을 명확히 구분해야 합니다.
- JWT에는 화면 표시용 정보와 권한 판단용 정보를 구분해서 담아야 합니다.
- 검색 조건이 늘어날수록 JPQL 또는 QueryDSL을 사용하는 것이 유지보수에 유리합니다.
- N+1 문제는 연관 엔티티를 사용할 때 자주 발생하므로 fetch join을 고려해야 합니다.
- Cascade는 생명주기가 함께 움직이는 엔티티 사이에 제한적으로 적용해야 합니다.
- Spring Security를 사용하면 인증/인가 흐름을 프레임워크 표준 구조로 관리할 수 있습니다.
