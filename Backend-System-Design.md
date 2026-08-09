# Backend System Design — Assessment Platform

---

## 1. What Is This System?

A **web-based assessment platform** where admins (or team leads / team reps) create tests, assign them to teams, and users (trainees, interns, PPOs) take those tests and see results. Built with **Spring Boot 3.2+ / Java 21**, talking to a **MySQL 8** database, and sending emails via Gmail SMTP.

---

## 2. Technology Stack

| Layer             | Technology                        |
|-------------------|-----------------------------------|
| Framework         | Spring Boot 3.2.4                 |
| Language          | Java 21                           |
| Database          | MySQL 8.0 (via Docker)            |
| ORM               | Hibernate / Spring Data JPA       |
| Auth              | JWT (jjwt library)                |
| Password hashing  | BCrypt                            |
| Email             | Spring Mail (Gmail SMTP)          |
| Validation        | Jakarta Bean Validation           |
| Build tool        | Maven                             |

---

## 3. Database Schema (6 tables + 2 enums)

### Enums (not tables, stored as strings inside tables)

| Enum         | Values                                  | Purpose              |
|--------------|------------------------------------------|-----------------------|
| **Role**     | ADMIN, TRAINEE, INTERN, PPO, TL, TR     | Which kind of user    |
| **TeamType** | DEV, DATA, DEVOPS                        | What kind of team     |

### Tables

#### teams
| Column | Type               | Notes                              |
|--------|--------------------|------------------------------------|
| id     | BIGINT, PK, auto   | Primary key                        |
| name   | VARCHAR, unique     | e.g. "Falconz"                    |
| type   | ENUM (TeamType)     | DEV / DATA / DEVOPS                |

#### users
| Column          | Type               | Notes                                      |
|-----------------|--------------------|--------------------------------------------|
| id              | BIGINT, PK, auto   | Primary key                                |
| name            | VARCHAR, required   | Full name                                  |
| email           | VARCHAR, unique     | Login email                                |
| password        | VARCHAR, required   | BCrypt hashed                              |
| team_id         | FK → teams.id       | Nullable for ADMIN                         |
| role            | ENUM (Role)         | ADMIN / TRAINEE / INTERN / PPO / TL / TR   |
| teamLeadName    | VARCHAR, optional   | Name of team lead                          |
| description     | TEXT, optional      | Bio / notes                                |
| using2FA        | BOOLEAN             | Default false                              |
| twoFactorSecret | VARCHAR             | Stores secret if 2FA enabled               |
| createdAt       | DATETIME            | Auto timestamp                             |

#### tests
| Column            | Type               | Notes                                |
|-------------------|--------------------|------------------------------------- |
| id                | BIGINT, PK, auto   | Primary key                          |
| title             | VARCHAR, required   | Test name                            |
| description       | TEXT                | What the test is about               |
| timeLimitMinutes  | INT, required       | How long users get                   |
| assignedRole      | ENUM (Role)         | Which role can take it               |
| assigned_team_id  | FK → teams.id       | Which team can take it               |
| created_by        | FK → users.id       | Who created it                       |
| resultsReleased   | BOOLEAN             | Default false                        |
| createdAt         | DATETIME            | Auto timestamp                       |

#### questions
| Column       | Type               | Notes                     |
|--------------|--------------------|--------------------------  |
| id           | BIGINT, PK, auto   | Primary key               |
| test_id      | FK → tests.id       | Which test it belongs to  |
| questionText | TEXT, required       | The question itself       |

#### options
| Column      | Type               | Notes                              |
|-------------|--------------------|------------------------------------|
| id          | BIGINT, PK, auto   | Primary key                        |
| question_id | FK → questions.id   | Which question it belongs to      |
| optionText  | VARCHAR, required   | The choice text (e.g. "42")       |
| isCorrect   | BOOLEAN             | Marks if this is a right answer   |

#### submissions (unique constraint on user_id + test_id — one attempt per person)
| Column     | Type               | Notes                     |
|------------|--------------------|--------------------------  |
| id         | BIGINT, PK, auto   | Primary key               |
| user_id    | FK → users.id       | Who submitted             |
| test_id    | FK → tests.id       | Which test                |
| startTime  | DATETIME            | When they started         |
| endTime    | DATETIME            | When they finished        |
| score      | INT                 | How many correct          |
| totalMarks | INT                 | Total questions           |

#### answers
| Column             | Type               | Notes                                   |
|--------------------|--------------------|-----------------------------------------|
| id                 | BIGINT, PK, auto   | Primary key                             |
| submission_id      | FK → submissions.id | Which submission                       |
| question_id        | FK → questions.id   | Which question                         |
| selected_option_id | FK → options.id     | Which option user picked (nullable)    |

### Relationships Diagram (simplified)

```
Team  ──1:N──  User
Team  ──1:N──  Test
User  ──1:N──  Test (createdBy)
Test  ──1:N──  Question
Question ──1:N──  Option
User + Test ──1:1──  Submission (one attempt per user per test)
Submission ──1:N──  Answer
Answer ──N:1──  Question
Answer ──N:1──  Option
```

---

## 4. Layered Architecture

The backend follows a classic 4-layer pattern:

```
┌───────────────────────────────────────────────┐
│  CONTROLLER LAYER (receives HTTP requests)    │
│  AuthController, AdminController,             │
│  TestController, ResultController             │
├───────────────────────────────────────────────┤
│  SERVICE LAYER (business logic)               │
│  AuthService, AdminService, TestService,      │
│  OtpService, EmailService                     │
├───────────────────────────────────────────────┤
│  REPOSITORY LAYER (database queries)          │
│  UserRepository, TeamRepository,              │
│  TestRepository, QuestionRepository,          │
│  OptionRepository, SubmissionRepository,      │
│  AnswerRepository                             │
├───────────────────────────────────────────────┤
│  ENTITY LAYER (database table models)         │
│  User, Team, Test, Question, Option,          │
│  Submission, Answer, Role, TeamType           │
└───────────────────────────────────────────────┘
```

**In simple words:**
- **Controller** = the door. Receives the HTTP request, calls service, returns JSON.
- **Service** = the brain. Contains all rules and logic.
- **Repository** = the database helper. Knows how to read/write data.
- **Entity** = the shape of data. Maps directly to a database table.

---

## 5. Security Layer — How Login Works

```
┌─────────────┐      ┌──────────────────┐      ┌──────────────────┐
│  Frontend    ────►│  JwtAuthFilter   │────►│  SecurityConfig  │
│  sends       │     │  (checks token)  │     │  (checks roles)  │
│  JWT token   │     └──────────────────┘     └──────────────────┘
│  in header   │
└─────────────┘
```

### Step-by-step login flow:

1. **User sends email + password** to `POST /api/auth/login`.
2. **AuthService** uses Spring's `AuthenticationManager` to verify password against BCrypt hash in DB.
3. **If 2FA is ON**: generates 6-digit OTP, stores it in memory (`ConcurrentHashMap`) with a 5-minute expiry, sends it via email. Returns `requires2FA: true` — no token yet.
4. **If 2FA is OFF** (or after OTP is verified): **JwtUtil** creates a JWT token containing:
   - `subject` = user's email
   - `role` = ADMIN / TRAINEE / etc.
   - `userId` = their database ID
   - `teamId` = their team's database ID
   - Expires in 24 hours (86400000 ms)
5. Token is signed using HMAC-SHA with a Base64-encoded secret key.
6. **Frontend stores token in localStorage**, sends it as `Authorization: Bearer <token>` on every request.

### How every request is protected:

1. **JwtAuthenticationFilter** (runs before every request):
   - Reads `Authorization` header
   - Extracts and validates the JWT
   - Loads user from DB using email from token
   - Sets Spring Security authentication context
2. **SecurityConfig** URL rules decide who can access what:

| URL Pattern        | Who Can Access                   |
|--------------------|----------------------------------|
| `/api/auth/**`     | Everyone (no login needed)       |
| `/api/public/**`   | Everyone (no login needed)       |
| `/api/admin/**`    | ADMIN, TL, TR only               |
| `/api/tests/**`    | TRAINEE, INTERN, PPO, TL, TR     |
| `/api/results/**`  | TRAINEE, INTERN, PPO, TL, TR     |
| Everything else    | Must be logged in                |

### Password storage:
- BCrypt hashing (one-way). Even if the database is stolen, passwords can't be reversed.

### CustomUserDetails:
- Wraps the `User` entity into Spring Security's format
- Adds authority `ROLE_TL`, `ROLE_ADMIN`, etc. so Spring can check permissions

---

## 6. API Endpoints — What Each URL Does

### 6A. Auth APIs (`/api/auth`) — No login required

| Method | URL                    | What It Does |
|--------|------------------------|--------------|
| POST   | `/api/auth/register`   | Creates a new user account. Picks a team, picks a role, sets password. Returns JWT token immediately. |
| POST   | `/api/auth/login`      | Checks email + password. If 2FA is on, sends OTP email and returns `requires2FA: true`. Otherwise returns JWT token. |
| POST   | `/api/auth/verify-otp` | Takes email + 6-digit OTP. If valid and not expired, returns JWT token. |

### 6B. Test APIs (`/api/tests`) — For trainees/interns/PPOs

| Method | URL                      | What It Does |
|--------|--------------------------|--------------|
| GET    | `/api/tests`             | Returns all tests assigned to the current user's team + role. Also tells if user already submitted each test. |
| GET    | `/api/tests/{id}`        | Returns full test with questions and options. **Blocks** if: user already submitted, results already released, or user not assigned to this test. |
| POST   | `/api/tests/{id}/submit` | Submits answers. Calculates score. **Blocks** if: already submitted or results released. Only one attempt per user. |

### 6C. Results APIs (`/api/results`)

| Method | URL             | What It Does |
|--------|-----------------|--------------|
| GET    | `/api/results`  | Returns all submissions where results have been released. Includes full answer key (which you picked, which was correct). |

### 6D. Admin APIs (`/api/admin`) — For ADMIN / TL / TR

| Method | URL                                | What It Does |
|--------|------------------------------------|--------------|
| POST   | `/api/admin/tests`                 | Creates a new test with questions + options. Assigns to a team + role. |
| GET    | `/api/admin/tests`                 | Lists all tests. **TL/TR see only their team's tests; ADMIN sees all.** |
| GET    | `/api/admin/tests/{id}/submissions`| Lists all submissions for a specific test with scores. |
| POST   | `/api/admin/tests/{id}/release`    | Marks results as released. Sends email to every user who submitted, with their score and answer key. **Blocks further attempts.** |
| GET    | `/api/admin/users`                 | Lists all users. **TL/TR see only their team's members; ADMIN sees all.** |
| PUT    | `/api/admin/users/{id}/role`       | Changes a user's role (e.g. promote TRAINEE → INTERN). |
| GET    | `/api/admin/teams`                 | Lists all teams. |

---

## 7. Core Business Logic — The Rules

### 7A. Test Creation (AdminService.createTest)

```
Admin/TL/TR clicks "Create Test"
    │
    ├── Validate: team exists, role is valid
    │
    ├── Build Test object with title, description, time limit
    │
    ├── For each question:
    │     ├── Create Question with text
    │     └── For each option:
    │           └── Create Option with text + isCorrect flag
    │
    └── Save everything in one transaction (cascade saves all children)
```

- A test is assigned to **one team** and **one role** (e.g. "Falconz" + "TRAINEE").
- Only users matching both team AND role will see the test.
- Supports **multi-correct questions** (more than one option can have `isCorrect = true`).

### 7B. Taking a Test (TestService.getTestById + submitTest)

```
User clicks "Take Test"
    │
    ├── Check: is user's team + role matching the test? → if NO, block
    ├── Check: has user already submitted? → if YES, block
    ├── Check: are results released? → if YES, block (test is closed)
    │
    └── Return test with questions + options (correct answers hidden)

User submits answers
    │
    ├── Same 3 checks again (double protection)
    │
    ├── For each question:
    │     ├── Find selected option(s)
    │     ├── Find correct option(s) from DB
    │     ├── Compare: all selected = all correct? → score +1
    │     └── Save Answer record(s)
    │
    └── Save Submission with total score
```

**Scoring logic (multi-correct support):**
- Gets all correct option IDs for the question
- Gets all user-selected option IDs
- Answer is correct ONLY if: number of selections = number of correct options, AND the correct set contains all selected ones
- Partial credit: NO. All-or-nothing per question.

### 7C. Releasing Results (AdminService.releaseResults)

```
Admin clicks "Release Results"
    │
    ├── Check: not already released → if already, throw error
    │
    ├── Set test.resultsReleased = true
    │
    ├── For each submission on this test:
    │     ├── Build answer key text (question, user's answer, correct answer)
    │     └── Send email to user with score + answer key
    │
    └── From now on, no new users can take this test
```

### 7D. Team Scoping for TL / TR

```
TL or TR calls GET /api/admin/tests
    │
    ├── Is role TL or TR? → YES
    │     └── Only return tests where assigned_team_id = user's team_id
    │
    └── Is role ADMIN? → Return ALL tests

Same logic for GET /api/admin/users:
    ├── TL/TR → only users in same team
    └── ADMIN → all users
```

---

## 8. OTP System (OtpService)

```
┌──────────────────────────────────────────────┐
│  In-Memory OTP Store (ConcurrentHashMap)     │
│                                              │
│  Key: email → Value: { otp, expiresAt }      │
│                                              │
│  generateOtp("user@email.com")               │
│    → creates 6-digit random code             │
│    → stores with 5-minute expiry             │
│    → returns OTP string                      │
│                                              │
│  verifyOtp("user@email.com", "123456")       │
│    → checks if exists, not expired, matches  │
│    → if valid: removes from store, returns T │
│    → if invalid: returns false               │
└──────────────────────────────────────────────┘
```

**Important:** OTPs are stored **in memory only**. If you restart the server, all pending OTPs are lost. This is fine for a single-server setup.

---

## 9. Email System (EmailService)

- Uses **Gmail SMTP** (port 587 with TLS)
- All emails are sent **asynchronously** (`@Async`) so the user doesn't wait
- Two types of emails:
  1. **OTP email**: "Your OTP code is: 123456. Expires in 5 minutes."
  2. **Result email**: "Your score for [Test Title]: X/Y" + full answer key

The `@EnableAsync` on the main application class enables this async behavior.

---

## 10. Error Handling (GlobalExceptionHandler)

Every error is caught and returned as a clean JSON response:

| Exception                        | HTTP Status | When It Happens                                  |
|----------------------------------|-------------|--------------------------------------------------|
| `ResourceNotFoundException`      | 404         | User, test, team, question not found             |
| `BadRequestException`            | 400         | Invalid role, test closed, already submitted     |
| `UnauthorizedException`          | 401         | Invalid/expired OTP                              |
| `DuplicateResourceException`     | 409         | Email already registered                         |
| `BadCredentialsException`        | 401         | Wrong password                                   |
| `AccessDeniedException`          | 403         | Wrong role trying to access admin URL            |
| `MethodArgumentNotValidException`| 400         | Form validation failed (empty name, bad email)   |
| Any other `Exception`            | 500         | Unexpected server error                          |

All responses follow the same shape:
```json
{
  "success": true/false,
  "message": "...",
  "data": { ... }
}
```

---

## 11. Data Seeding (DataSeeder)

When the app starts for the first time:
1. Creates **5 default teams**: Falconz, Beyonders, Eternals, La Masia's, Ariba
2. Creates **1 default admin**: `admin@assessment.com` / `admin123`

This runs via `CommandLineRunner` — only if the database is empty (checks count / email existence first).

---

## 12. Configuration (application.yml)

| Setting        | Value                                               | Purpose              |
|----------------|-----------------------------------------------------|----------------------|
| DB URL         | `jdbc:mysql://localhost:3306/assessment_platform`   | MySQL connection     |
| DB auto-create | `createDatabaseIfNotExist=true`                     | Creates DB if missing|
| JPA ddl-auto   | `update`                                            | Auto-creates/updates tables from entities |
| JWT secret     | Base64 encoded key                                  | Signs tokens         |
| JWT expiry     | 86400000 ms (24 hours)                              | Token lifetime       |
| OTP expiry     | 5 minutes                                           | Code lifetime        |
| CORS origins   | `localhost:5173`, `localhost:3000`                   | Frontend dev servers |
| Server port    | 8080                                                | Backend port         |

---

## 13. Request Flow — Full Picture

```
Frontend (React, port 5173)
    │
    │  HTTP request with JWT in Authorization header
    │
    ▼
┌──────────────────────────────────────────┐
│  CORS Filter                             │  ← allows localhost:5173
├──────────────────────────────────────────┤
│  JwtAuthenticationFilter                 │  ← validates token, loads user
├──────────────────────────────────────────┤
│  Spring Security URL Rules               │  ← checks role matches URL
├──────────────────────────────────────────┤
│  Controller                              │  ← receives request
│    ↓                                     │
│  Service                                 │  ← runs business logic
│    ↓                                     │
│  Repository                              │  ← queries database
│    ↓                                     │
│  MySQL Database                          │  ← stores/returns data
├──────────────────────────────────────────┤
│  GlobalExceptionHandler                  │  ← catches any errors
├──────────────────────────────────────────┤
│  JSON Response → Frontend                │
└──────────────────────────────────────────┘
```

---

## 14. File Structure Summary

```
backend/src/main/java/com/assessment/platform/
│
├── AssessmentPlatformApplication.java    ← Entry point, enables @Async
│
├── config/
│   ├── SecurityConfig.java              ← JWT filter chain, URL permissions, CORS, BCrypt
│   └── DataSeeder.java                  ← Creates default teams + admin on startup
│
├── controller/
│   ├── AuthController.java              ← Register, Login, Verify OTP
│   ├── AdminController.java             ← Create test, list tests/users/teams, release results, change role
│   ├── TestController.java              ← Get assigned tests, get test by ID, submit test
│   └── ResultController.java            ← Get my released results
│
├── service/
│   ├── AuthService.java                 ← Registration, login, OTP verification logic
│   ├── AdminService.java                ← Test creation, result release, user management, team scoping
│   ├── TestService.java                 ← Test fetching, submission, scoring, result mapping
│   ├── OtpService.java                  ← In-memory OTP generation + verification
│   └── EmailService.java               ← Async email sending (OTP + results)
│
├── security/
│   ├── JwtUtil.java                     ← Token creation + parsing + validation
│   ├── JwtAuthenticationFilter.java     ← Intercepts every request, validates JWT
│   ├── CustomUserDetails.java           ← Wraps User entity for Spring Security
│   └── CustomUserDetailsService.java    ← Loads user from DB by email
│
├── entity/
│   ├── User.java, Team.java, Test.java, Question.java, Option.java,
│   ├── Submission.java, Answer.java
│   ├── Role.java (enum), TeamType.java (enum)
│
├── repository/
│   ├── UserRepository, TeamRepository, TestRepository, QuestionRepository,
│   ├── OptionRepository, SubmissionRepository, AnswerRepository
│
├── dto/
│   ├── request/   ← LoginRequest, RegisterRequest, CreateTestRequest, SubmitTestRequest, etc.
│   └── response/  ← ApiResponse, AuthResponse, TestResponse, SubmissionResponse, etc.
│
└── exception/
    ├── BadRequestException, ResourceNotFoundException,
    ├── UnauthorizedException, DuplicateResourceException
    └── GlobalExceptionHandler
```

---

*Document generated from codebase analysis of the Assessment Platform backend.*
