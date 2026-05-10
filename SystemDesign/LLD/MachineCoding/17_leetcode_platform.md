# LeetCode Platform - LLD / Machine Coding Interview Study Document

---

## 1. Problem Statement

Design a coding platform (like LeetCode) where users can solve algorithmic problems by submitting code in multiple languages. The system must execute user code in a sandboxed environment, run it against predefined test cases, enforce time and memory limits, and determine pass/fail verdicts. Support both practice mode (binary scoring) and contest mode (time-penalty scoring with leaderboards).

---

## 2. Requirements

### Functional Requirements

| ID | Requirement |
|----|-------------|
| FR1 | User registration, login, and profile management |
| FR2 | Browse problems by difficulty, tags, and search |
| FR3 | Submit code in multiple languages (Java, Python, C++) |
| FR4 | Execute code in isolated sandbox with time/memory limits |
| FR5 | Run submissions against test cases (sample + hidden) |
| FR6 | Return verdict: ACCEPTED, WRONG_ANSWER, TLE, MLE, RUNTIME_ERROR |
| FR7 | Practice mode: binary pass/fail scoring |
| FR8 | Contest mode: register, submit, leaderboard with time penalty |
| FR9 | View submission history and per-test-case results |
| FR10 | Leaderboard updates on accepted contest submissions |

### Non-Functional Requirements

| ID | Requirement |
|----|-------------|
| NFR1 | **Security**: Code must run in isolated container; no filesystem/network access |
| NFR2 | **Performance**: Code execution < 5s per test case; UI response < 200ms |
| NFR3 | **Reliability**: Consistent verdicts; no cross-contamination between submissions |
| NFR4 | **Scalability**: Support 1M+ daily submissions via horizontal scaling |
| NFR5 | **Extensibility**: Easy to add new languages and scoring modes |

---

## 3. Database Design with Explanations

```sql
-- =============================================================================
-- USERS
-- WHY exists: Core entity for authentication, ownership of submissions, contest
--             participation, and leaderboard identity.
-- WHY FK: None (root entity).
-- WHY index: username/email UNIQUE for login; created_at for admin queries.
-- WHY structure: password_hash (never store plaintext); account_type for premium
--                gating; JSONB preferences for flexible user settings.
-- =============================================================================
CREATE TABLE users (
    user_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username        VARCHAR(50) UNIQUE NOT NULL,
    email           VARCHAR(255) UNIQUE NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    display_name    VARCHAR(100),
    account_type    VARCHAR(20) DEFAULT 'free',  -- free, premium
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_email ON users(email);


-- =============================================================================
-- PROBLEMS
-- WHY exists: Stores problem metadata and content. Problems are immutable in
--             practice; edits create new versions (not modeled here for simplicity).
-- WHY FK: created_by -> users (audit trail).
-- WHY index: difficulty + tags for filtering; slug for URL lookup; is_active for
--            soft-delete filtering.
-- WHY structure: slug for SEO-friendly URLs; difficulty as enum-like; tags array
--                for multi-tag filter; acceptance stats denormalized for fast reads.
-- =============================================================================
CREATE TABLE problems (
    problem_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug                VARCHAR(200) UNIQUE NOT NULL,
    title               VARCHAR(500) NOT NULL,
    description         TEXT NOT NULL,
    difficulty          VARCHAR(20) NOT NULL,  -- EASY, MEDIUM, HARD
    tags                TEXT[] DEFAULT '{}',
    acceptance_rate     DECIMAL(5,2) DEFAULT 0,
    total_submissions   INTEGER DEFAULT 0,
    accepted_submissions INTEGER DEFAULT 0,
    time_limit_ms       INTEGER DEFAULT 2000,
    memory_limit_mb     INTEGER DEFAULT 256,
    is_active           BOOLEAN DEFAULT true,
    created_by          UUID REFERENCES users(user_id),
    created_at          TIMESTAMP DEFAULT NOW(),
    updated_at          TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_problems_difficulty ON problems(difficulty);
CREATE INDEX idx_problems_slug ON problems(slug);
CREATE INDEX idx_problems_tags ON problems USING GIN(tags);
CREATE INDEX idx_problems_active ON problems(is_active) WHERE is_active = true;


-- =============================================================================
-- TEST_CASES
-- WHY exists: Each problem has multiple test cases. Hidden cases prevent users
--             from gaming the system; sample cases help debugging.
-- WHY FK: problem_id -> problems (cascade delete when problem removed).
-- WHY index: problem_id for fetching all cases for a problem; (problem_id, order)
--            for deterministic execution order.
-- WHY structure: input/output as TEXT for flexibility (JSON, arrays, etc.);
--                is_sample vs is_hidden; order for consistent run sequence;
--                per-case time/memory overrides (nullable, fallback to problem).
-- =============================================================================
CREATE TABLE test_cases (
    test_case_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    problem_id      UUID NOT NULL REFERENCES problems(problem_id) ON DELETE CASCADE,
    input_data      TEXT NOT NULL,
    expected_output TEXT NOT NULL,
    is_sample       BOOLEAN DEFAULT false,
    is_hidden       BOOLEAN DEFAULT true,
    "order"         INTEGER NOT NULL DEFAULT 1,
    time_limit_ms   INTEGER,  -- NULL = use problem default
    memory_limit_mb INTEGER,  -- NULL = use problem default
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_test_cases_problem ON test_cases(problem_id);
CREATE UNIQUE INDEX idx_test_cases_problem_order ON test_cases(problem_id, "order");


-- =============================================================================
-- CONTESTS (must be created before submissions due to FK)
-- WHY exists: Time-bounded competitions with start/end, problems, and scoring.
-- WHY FK: created_by -> users (admin/author).
-- WHY index: status for filtering upcoming/running/finished; start_time for
--            scheduling and "active now" queries.
-- WHY structure: duration_minutes for flexible contest length; status for
--                registration window (upcoming), execution (running), closed.
-- =============================================================================
CREATE TABLE contests (
    contest_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    start_time      TIMESTAMP NOT NULL,
    duration_minutes INTEGER NOT NULL,
    status          VARCHAR(20) DEFAULT 'upcoming',  -- upcoming, running, finished
    created_by      UUID REFERENCES users(user_id),
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_contests_status ON contests(status);
CREATE INDEX idx_contests_start_time ON contests(start_time);


-- =============================================================================
-- SUBMISSIONS
-- WHY exists: Records every code submission. Central entity for execution flow,
--             verdict tracking, and analytics.
-- WHY FK: user_id, problem_id for ownership and problem context; contest_id
--         nullable (NULL = practice) references contests.
-- WHY index: (user_id, problem_id) for user's submission history; status for
--            filtering; submitted_at for time-ordered queries; contest_id for
--            contest-specific leaderboard aggregation.
-- WHY structure: status follows state machine (PENDING->RUNNING->terminal);
--                code stored as-is; runtime_ms/memory_kb from execution;
--                contest_id NULL = practice, non-NULL = contest submission.
-- =============================================================================
CREATE TABLE submissions (
    submission_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(user_id),
    problem_id      UUID NOT NULL REFERENCES problems(problem_id),
    contest_id      UUID REFERENCES contests(contest_id),  -- NULL for practice
    language        VARCHAR(20) NOT NULL,
    code            TEXT NOT NULL,
    status          VARCHAR(30) DEFAULT 'PENDING',
    runtime_ms      INTEGER,
    memory_kb       INTEGER,
    test_cases_passed INTEGER DEFAULT 0,
    total_test_cases  INTEGER DEFAULT 0,
    error_message   TEXT,
    submitted_at    TIMESTAMP DEFAULT NOW(),
    judged_at       TIMESTAMP
);

CREATE INDEX idx_submissions_user_problem ON submissions(user_id, problem_id);
CREATE INDEX idx_submissions_status ON submissions(status);
CREATE INDEX idx_submissions_submitted_at ON submissions(submitted_at DESC);
CREATE INDEX idx_submissions_contest ON submissions(contest_id) WHERE contest_id IS NOT NULL;


-- =============================================================================
-- SUBMISSION_RESULTS
-- WHY exists: Normalize per-test-case results. Enables "which test case failed"
--            without storing full verdict_details JSON; supports partial scoring.
-- WHY FK: submission_id, test_case_id for traceability.
-- WHY index: submission_id for fetching all results of a submission.
-- WHY structure: actual_output stored only on failure (save space); status
--                 per case (PASSED, FAILED, TLE, MLE, RUNTIME_ERROR).
-- =============================================================================
CREATE TABLE submission_results (
    result_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id   UUID NOT NULL REFERENCES submissions(submission_id) ON DELETE CASCADE,
    test_case_id    UUID NOT NULL REFERENCES test_cases(test_case_id),
    status          VARCHAR(30) NOT NULL,  -- PASSED, FAILED, TLE, MLE, RUNTIME_ERROR
    runtime_ms      INTEGER,
    memory_kb       INTEGER,
    actual_output   TEXT,  -- Only populated on failure for debugging
    error_message   TEXT,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_submission_results_submission ON submission_results(submission_id);


-- =============================================================================
-- CONTEST_REGISTRATIONS
-- WHY exists: Many-to-many between users and contests. Tracks who participates,
--             rank, score, penalty for leaderboard.
-- WHY FK: contest_id, user_id for referential integrity.
-- WHY index: (contest_id, rank) for leaderboard; (contest_id, user_id) unique
--            to prevent double registration.
-- WHY structure: score/penalty_time updated on each AC; problems_solved count;
--                UNIQUE(contest_id, user_id) prevents duplicate registration.
-- =============================================================================
CREATE TABLE contest_registrations (
    registration_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    contest_id        UUID NOT NULL REFERENCES contests(contest_id) ON DELETE CASCADE,
    user_id           UUID NOT NULL REFERENCES users(user_id),
    score             INTEGER DEFAULT 0,
    penalty_time_min  INTEGER DEFAULT 0,
    problems_solved   INTEGER DEFAULT 0,
    rank              INTEGER,
    registered_at     TIMESTAMP DEFAULT NOW(),
    UNIQUE(contest_id, user_id)
);

CREATE INDEX idx_contest_registrations_contest ON contest_registrations(contest_id);
CREATE INDEX idx_contest_registrations_leaderboard ON contest_registrations(contest_id, score DESC, penalty_time_min ASC);
```

---

## 4. Design Patterns

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** | `JudgeStrategy` (SandboxJudge, DockerJudge) | Swap execution environments without changing orchestration logic. Local sandbox for dev, Docker for prod. |
| **Strategy** | `ScoringStrategy` (BinaryScoring, PartialScoring, ContestScoring) | Different scoring rules for practice vs contest. Open/closed for extension. |
| **Observer** | `SubmissionObserver` (LeaderboardObserver, AchievementObserver, AnalyticsObserver) | Decouple post-verdict side effects. Add new observers without modifying JudgeService. |
| **Factory** | `JudgeFactory` | Create language-specific judge configurations. Encapsulates language→image/command mapping. |
| **State** | `Submission` status transitions | Enforce valid state machine: PENDING→RUNNING→(ACCEPTED|WRONG_ANSWER|TLE|MLE|RUNTIME_ERROR). |
| **Template Method** | Judge execution flow | Define skeleton: compile→run→compare; subclasses override compile step for compiled languages. |
| **Dependency Injection** | JudgeService constructor | Inject JudgeStrategy, ScoringStrategy, Observers. Testable, swappable implementations. |

---

## 5. SOLID Principles

| Principle | How |
|-----------|-----|
| **S**ingle Responsibility | `JudgeService` orchestrates; `JudgeStrategy` executes; `ScoringStrategy` scores; `SubmissionObserver` reacts. Each class has one reason to change. |
| **O**pen/Closed | New languages via `JudgeFactory`; new scoring via `ScoringStrategy`; new side effects via `SubmissionObserver`—extend without modifying core. |
| **L**iskov Substitution | Any `JudgeStrategy` implementation can replace another; any `ScoringStrategy` works in `JudgeService` without breaking contracts. |
| **I**nterface Segregation | `JudgeStrategy`, `ScoringStrategy`, `SubmissionObserver` are small, focused interfaces. Clients depend only on what they need. |
| **D**ependency Inversion | `JudgeService` depends on `JudgeStrategy` and `ScoringStrategy` abstractions, not concrete SandboxJudge/ContestScoring. |

---

## 6. Code Implementation in Java

### Enums

```java
/** Difficulty levels for problems. Used for filtering and display. */
public enum Difficulty {
    EASY, MEDIUM, HARD
}

/**
 * Submission lifecycle and verdict states.
 * State machine: PENDING -> RUNNING -> (ACCEPTED | WRONG_ANSWER | TLE | MLE | RUNTIME_ERROR)
 */
public enum SubmissionStatus {
    PENDING,      // Queued, not yet executed
    RUNNING,      // Currently executing
    ACCEPTED,     // All test cases passed
    WRONG_ANSWER, // Output mismatch
    TLE,          // Time limit exceeded
    MLE,          // Memory limit exceeded
    RUNTIME_ERROR // Exception / non-zero exit
}

/** Supported languages for code execution. */
public enum Language {
    JAVA, PYTHON, CPP
}
```

### Models (OOP: Encapsulation, Immutability, State Machine)

```java
import java.time.Instant;
import java.util.List;

/**
 * Immutable problem entity.
 * WHY: Problems are read-heavy; immutability prevents accidental mutation during
 *      execution. Stats (acceptance_rate) updated separately via repository.
 */
public final class Problem {
    private final String id;
    private final String slug;
    private final String title;
    private final String description;
    private final Difficulty difficulty;
    private final int timeLimitMs;
    private final int memoryLimitMb;

    public Problem(String id, String slug, String title, String description,
                   Difficulty difficulty, int timeLimitMs, int memoryLimitMb) {
        this.id = id;
        this.slug = slug;
        this.title = title;
        this.description = description;
        this.difficulty = difficulty;
        this.timeLimitMs = timeLimitMs;
        this.memoryLimitMb = memoryLimitMb;
    }

    public String getId() { return id; }
    public String getSlug() { return slug; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public Difficulty getDifficulty() { return difficulty; }
    public int getTimeLimitMs() { return timeLimitMs; }
    public int getMemoryLimitMb() { return memoryLimitMb; }
}

/**
 * Test case: input, expected output, limits.
 */
public final class TestCase {
    private final String id;
    private final String problemId;
    private final String inputData;
    private final String expectedOutput;
    private final boolean isSample;
    private final Integer timeLimitMs;  // null = use problem default
    private final Integer memoryLimitMb;
    private final int order;

    public TestCase(String id, String problemId, String inputData, String expectedOutput,
                    boolean isSample, Integer timeLimitMs, Integer memoryLimitMb, int order) {
        this.id = id;
        this.problemId = problemId;
        this.inputData = inputData;
        this.expectedOutput = expectedOutput;
        this.isSample = isSample;
        this.timeLimitMs = timeLimitMs;
        this.memoryLimitMb = memoryLimitMb;
        this.order = order;
    }

    public String getId() { return id; }
    public String getProblemId() { return problemId; }
    public String getInputData() { return inputData; }
    public String getExpectedOutput() { return expectedOutput; }
    public boolean isSample() { return isSample; }
    public Integer getTimeLimitMs() { return timeLimitMs; }
    public Integer getMemoryLimitMb() { return memoryLimitMb; }
    public int getOrder() { return order; }
}

/**
 * Submission with state machine for status.
 * WHY: Encapsulation - status transitions validated internally.
 *      Invalid transitions (e.g. PENDING -> ACCEPTED) throw.
 */
public class Submission {
    private final String id;
    private final String userId;
    private final String problemId;
    private final String contestId;  // null = practice
    private final Language language;
    private final String code;
    private SubmissionStatus status;
    private Integer runtimeMs;
    private Integer memoryKb;
    private int testCasesPassed;
    private int totalTestCases;
    private String errorMessage;
    private final Instant submittedAt;
    private Instant judgedAt;

    public Submission(String id, String userId, String problemId, String contestId,
                      Language language, String code, Instant submittedAt) {
        this.id = id;
        this.userId = userId;
        this.problemId = problemId;
        this.contestId = contestId;
        this.language = language;
        this.code = code;
        this.status = SubmissionStatus.PENDING;
        this.submittedAt = submittedAt;
    }

    /** State machine: only valid transitions allowed. */
    public void transitionTo(SubmissionStatus newStatus) {
        if (!isValidTransition(status, newStatus)) {
            throw new IllegalStateException("Invalid transition: " + status + " -> " + newStatus);
        }
        this.status = newStatus;
        if (isTerminal(newStatus)) {
            this.judgedAt = Instant.now();
        }
    }

    private boolean isValidTransition(SubmissionStatus from, SubmissionStatus to) {
        if (from == SubmissionStatus.PENDING && to == SubmissionStatus.RUNNING) return true;
        if (from == SubmissionStatus.RUNNING && isTerminal(to)) return true;
        return false;
    }

    private boolean isTerminal(SubmissionStatus s) {
        return s == SubmissionStatus.ACCEPTED || s == SubmissionStatus.WRONG_ANSWER
            || s == SubmissionStatus.TLE || s == SubmissionStatus.MLE
            || s == SubmissionStatus.RUNTIME_ERROR;
    }

    public void setExecutionResult(int runtimeMs, int memoryKb, int passed, int total, String errorMessage) {
        this.runtimeMs = runtimeMs;
        this.memoryKb = memoryKb;
        this.testCasesPassed = passed;
        this.totalTestCases = total;
        this.errorMessage = errorMessage;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getProblemId() { return problemId; }
    public String getContestId() { return contestId; }
    public Language getLanguage() { return language; }
    public String getCode() { return code; }
    public SubmissionStatus getStatus() { return status; }
    public Integer getRuntimeMs() { return runtimeMs; }
    public Integer getMemoryKb() { return memoryKb; }
    public int getTestCasesPassed() { return testCasesPassed; }
    public int getTotalTestCases() { return totalTestCases; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getJudgedAt() { return judgedAt; }
    public boolean isContestSubmission() { return contestId != null; }
}
```

### Strategy: JudgeStrategy

```java
/**
 * Strategy pattern: Swap execution environments.
 * SandboxJudge = local process (dev); DockerJudge = container (prod).
 */
public interface JudgeStrategy {
    ExecutionResult execute(String code, List<TestCase> testCases, Language language,
                            int defaultTimeLimitMs, int defaultMemoryLimitMb);
}

/** Local execution - for development. Less isolation, faster. */
public class SandboxJudge implements JudgeStrategy {
    @Override
    public ExecutionResult execute(String code, List<TestCase> testCases, Language language,
                                   int defaultTimeLimitMs, int defaultMemoryLimitMb) {
        // Write code to temp file, run with ProcessBuilder, enforce limits via
        // timeout + memory monitoring. Compare stdout to expectedOutput.
        // Return ExecutionResult with status, runtime, memory per case.
        throw new UnsupportedOperationException("Implement: write file, run, compare");
    }
}

/**
 * Docker-based execution - production.
 * WHY: Network disabled, read-only FS, resource limits, no privilege escalation.
 */
public class DockerJudge implements JudgeStrategy {
    private final DockerClient dockerClient;

    public DockerJudge(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    @Override
    public ExecutionResult execute(String code, List<TestCase> testCases, Language language,
                                   int defaultTimeLimitMs, int defaultMemoryLimitMb) {
        String image = getImageFor(language);
        ContainerConfig config = ContainerConfig.builder()
            .image(image)
            .networkDisabled(true)
            .readOnlyRootFs(true)
            .memoryLimitMb(defaultMemoryLimitMb)
            .cpuQuota(50000)  // 50% CPU
            .build();

        try (Container container = dockerClient.createContainer(config)) {
            container.writeFile(getSourceFileName(language), code);
            if (needsCompilation(language)) {
                CompileResult compile = container.run(getCompileCommand(language), 30_000);
                if (!compile.isSuccess()) {
                    return ExecutionResult.compileError(compile.getStderr());
                }
            }

            List<TestCaseResult> results = new ArrayList<>();
            for (TestCase tc : testCases) {
                int timeLimit = tc.getTimeLimitMs() != null ? tc.getTimeLimitMs() : defaultTimeLimitMs;
                int memLimit = tc.getMemoryLimitMb() != null ? tc.getMemoryLimitMb() : defaultMemoryLimitMb;
                RunResult run = container.run(getRunCommand(language), tc.getInputData(), timeLimit, memLimit);

                TestCaseResult tcr = compareOutput(run, tc.getExpectedOutput(), timeLimit, memLimit);
                results.add(tcr);
                if (!tcr.isPassed()) break;  // Stop on first failure
            }
            return ExecutionResult.fromTestCaseResults(results);
        }
    }

    private TestCaseResult compareOutput(RunResult run, String expected, int timeLimit, int memLimit) {
        if (run.isTimeout()) return TestCaseResult.tle();
        if (run.isMemoryExceeded()) return TestCaseResult.mle();
        if (run.getExitCode() != 0) return TestCaseResult.runtimeError(run.getStderr());
        boolean passed = normalize(run.getStdout()).equals(normalize(expected));
        return passed ? TestCaseResult.passed(run.getRuntimeMs(), run.getMemoryKb())
                     : TestCaseResult.wrongAnswer(run.getStdout());
    }

    private String normalize(String s) { return s != null ? s.trim() : ""; }
    private String getImageFor(Language lang) { /* ... */ return ""; }
    private String getSourceFileName(Language lang) { /* ... */ return ""; }
    private boolean needsCompilation(Language lang) { return lang == Language.JAVA || lang == Language.CPP; }
    private String getCompileCommand(Language lang) { /* ... */ return ""; }
    private String getRunCommand(Language lang) { /* ... */ return ""; }
}

/** DTO for execution output. */
public class ExecutionResult {
    private final SubmissionStatus status;
    private final List<TestCaseResult> testCaseResults;
    private final String errorMessage;
    private final int totalRuntimeMs;
    private final int maxMemoryKb;

    public static ExecutionResult compileError(String msg) { /* ... */ return null; }
    public static ExecutionResult fromTestCaseResults(List<TestCaseResult> results) { /* ... */ return null; }
    // getters...
}

public class TestCaseResult {
    private final boolean passed;
    private final String status;  // PASSED, FAILED, TLE, MLE, RUNTIME_ERROR
    private final Integer runtimeMs;
    private final Integer memoryKb;
    private final String actualOutput;
    private final String errorMessage;
    // static factory methods: passed(), wrongAnswer(), tle(), mle(), runtimeError()
}
```

### Strategy: ScoringStrategy

```java
/**
 * Strategy pattern: Different scoring for practice vs contest.
 */
public interface ScoringStrategy {
    int calculateScore(Submission submission, ExecutionResult result);
}

/** Practice: 100 if AC, 0 otherwise. */
public class BinaryScoring implements ScoringStrategy {
    @Override
    public int calculateScore(Submission submission, ExecutionResult result) {
        return result.getStatus() == SubmissionStatus.ACCEPTED ? 100 : 0;
    }
}

/** Partial: score proportional to test cases passed. */
public class PartialScoring implements ScoringStrategy {
    @Override
    public int calculateScore(Submission submission, ExecutionResult result) {
        int total = result.getTestCaseResults().size();
        long passed = result.getTestCaseResults().stream().filter(TestCaseResult::isPassed).count();
        return total > 0 ? (int) (100 * passed / total) : 0;
    }
}

/**
 * Contest: points per problem, time penalty for late AC.
 * WHY: Encourages both correctness and speed.
 */
public class ContestScoring implements ScoringStrategy {
    private final int pointsPerProblem;
    private final int penaltyPerMinute;

    public ContestScoring(int pointsPerProblem, int penaltyPerMinute) {
        this.pointsPerProblem = pointsPerProblem;
        this.penaltyPerMinute = penaltyPerMinute;
    }

    @Override
    public int calculateScore(Submission submission, ExecutionResult result) {
        if (result.getStatus() != SubmissionStatus.ACCEPTED) return 0;
        int minutesSinceStart = (int) (submission.getSubmittedAt().getEpochSecond() / 60);
        int penalty = minutesSinceStart * penaltyPerMinute;
        return Math.max(0, pointsPerProblem - penalty);
    }
}
```

### Observer: SubmissionObserver

```java
/**
 * Observer pattern: React to submission verdict without coupling JudgeService
 * to leaderboard, achievements, analytics.
 */
public interface SubmissionObserver {
    void onSubmissionJudged(Submission submission, ExecutionResult result);
}

public class LeaderboardObserver implements SubmissionObserver {
    private final ContestRegistrationRepository registrationRepo;

    public LeaderboardObserver(ContestRegistrationRepository registrationRepo) {
        this.registrationRepo = registrationRepo;
    }

    @Override
    public void onSubmissionJudged(Submission submission, ExecutionResult result) {
        if (!submission.isContestSubmission() || result.getStatus() != SubmissionStatus.ACCEPTED)
            return;
        registrationRepo.updateScore(submission.getContestId(), submission.getUserId(),
            result.getTotalRuntimeMs(), result.getMaxMemoryKb());
    }
}

public class AchievementObserver implements SubmissionObserver {
    @Override
    public void onSubmissionJudged(Submission submission, ExecutionResult result) {
        if (result.getStatus() == SubmissionStatus.ACCEPTED) {
            // Check: first AC, streak, difficulty milestones, etc.
        }
    }
}

public class AnalyticsObserver implements SubmissionObserver {
    @Override
    public void onSubmissionJudged(Submission submission, ExecutionResult result) {
        // Log to analytics pipeline: problem_id, language, status, runtime, etc.
    }
}
```

### Factory: JudgeFactory

```java
/**
 * Factory pattern: Create judge config per language.
 * WHY: Centralizes language→image/commands; easy to add new languages.
 */
public interface JudgeFactory {
    JudgeConfig getConfig(Language language);
}

public class JudgeConfig {
    private final String dockerImage;
    private final String sourceFileName;
    private final String compileCommand;
    private final String runCommand;
    private final boolean needsCompilation;
    // constructor, getters
}

public class DefaultJudgeFactory implements JudgeFactory {
    private static final Map<Language, JudgeConfig> CONFIGS = Map.of(
        Language.JAVA, new JudgeConfig("openjdk:11", "Solution.java", "javac Solution.java", "java Solution", true),
        Language.PYTHON, new JudgeConfig("python:3.9", "solution.py", null, "python3 solution.py", false),
        Language.CPP, new JudgeConfig("gcc:9", "solution.cpp", "g++ -o solution solution.cpp", "./solution", true)
    );

    @Override
    public JudgeConfig getConfig(Language language) {
        JudgeConfig config = CONFIGS.get(language);
        if (config == null) throw new UnsupportedLanguageException(language.toString());
        return config;
    }
}
```

### JudgeService (Orchestrator with DI)

```java
/**
 * Orchestrator: Coordinates judge, scoring, persistence, observers.
 * WHY DI: Testable; swap SandboxJudge/DockerJudge, BinaryScoring/ContestScoring.
 */
public class JudgeService {
    private final JudgeStrategy judgeStrategy;
    private final ScoringStrategy scoringStrategy;
    private final JudgeFactory judgeFactory;
    private final SubmissionRepository submissionRepo;
    private final ProblemRepository problemRepo;
    private final TestCaseRepository testCaseRepo;
    private final List<SubmissionObserver> observers;

    public JudgeService(JudgeStrategy judgeStrategy, ScoringStrategy scoringStrategy,
                        JudgeFactory judgeFactory, SubmissionRepository submissionRepo,
                        ProblemRepository problemRepo, TestCaseRepository testCaseRepo,
                        List<SubmissionObserver> observers) {
        this.judgeStrategy = judgeStrategy;
        this.scoringStrategy = scoringStrategy;
        this.judgeFactory = judgeFactory;
        this.submissionRepo = submissionRepo;
        this.problemRepo = problemRepo;
        this.testCaseRepo = testCaseRepo;
        this.observers = observers;
    }

    public void judgeSubmission(String submissionId) {
        Submission submission = submissionRepo.findById(submissionId).orElseThrow();
        Problem problem = problemRepo.findById(submission.getProblemId()).orElseThrow();
        List<TestCase> testCases = testCaseRepo.findByProblemId(problem.getId());

        submission.transitionTo(SubmissionStatus.RUNNING);
        submissionRepo.save(submission);

        try {
            ExecutionResult result = judgeStrategy.execute(
                submission.getCode(),
                testCases,
                submission.getLanguage(),
                problem.getTimeLimitMs(),
                problem.getMemoryLimitMb()
            );

            submission.setExecutionResult(
                result.getTotalRuntimeMs(),
                result.getMaxMemoryKb(),
                result.getTestCasesPassed(),
                result.getTotalTestCases(),
                result.getErrorMessage()
            );
            submission.transitionTo(result.getStatus());
            submissionRepo.save(submission);

            int score = scoringStrategy.calculateScore(submission, result);
            // Persist score if contest...

            for (SubmissionObserver obs : observers) {
                obs.onSubmissionJudged(submission, result);
            }
        } catch (Exception e) {
            submission.transitionTo(SubmissionStatus.RUNTIME_ERROR);
            submission.setExecutionResult(0, 0, 0, testCases.size(), e.getMessage());
            submissionRepo.save(submission);
        }
    }
}
```

### Test Case Comparison Logic (Focus Area)

```java
/**
 * Robust comparison: trim whitespace, handle trailing newlines.
 * For strict mode: exact match. For flexible: ignore trailing newlines.
 */
public final class OutputComparator {
    public static boolean compare(String actual, String expected) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;
        return normalize(actual).equals(normalize(expected));
    }

    private static String normalize(String s) {
        return s.trim().replaceAll("\\s+", " ");
    }
}

/**
 * Time limit: use ProcessBuilder with timeout, or Docker --memory/--cpus.
 * Memory limit: Docker --memory; or JVM -Xmx for Java.
 */
```

---

## 7. Edge Cases & Tests

| # | Edge Case | Test / Handling |
|---|-----------|-----------------|
| 1 | **Empty output** | Expected "" vs actual "" → PASS. Actual "  \n" → normalize and compare. |
| 2 | **Trailing newline / whitespace** | Use `trim()` or `normalize()`; document in problem statement. |
| 3 | **TLE on first test case** | Stop execution immediately; return TLE verdict; do not run remaining cases. |
| 4 | **MLE** | Docker `--memory` or OS limits; detect OOM and return MLE. |
| 5 | **Compile error** | Do not run any test case; return RUNTIME_ERROR or dedicated COMPILE_ERROR. |
| 6 | **Infinite loop** | Enforce time limit per test case; kill process after timeout. |
| 7 | **Malicious code (file/network)** | Docker: `network_mode: none`, `read_only: true`, drop capabilities. |
| 8 | **Contest submission after end** | Reject or mark as practice; validate `submittedAt` < contest end. |

---

## 8. Summary

| Aspect | Summary |
|--------|---------|
| **Core flow** | Submit → Queue → Judge (Strategy) → Compare outputs → Score (Strategy) → Notify (Observer) |
| **Security** | Docker isolation, no network, read-only FS, resource limits |
| **Scoring** | Binary (practice), Partial, Contest (time penalty) |
| **Patterns** | Strategy (Judge, Scoring), Observer (post-verdict), Factory (language config) |
| **SOLID** | SRP per class, OCP via strategies/observers, DIP via DI |
| **State** | Submission: PENDING → RUNNING → terminal verdict |
| **DB** | users, problems, test_cases, submissions, submission_results, contests, contest_registrations |
