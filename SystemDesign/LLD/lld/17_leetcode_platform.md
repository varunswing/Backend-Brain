# LeetCode Platform - Low Level Design

## 1. Requirements Clarification

### Functional Requirements
- User registration and profile management
- Problem creation and management system
- Code editor with syntax highlighting
- Multi-language code execution engine
- Automated test case validation
- Contest and competition system
- Discussion forums and community features
- Progress tracking and analytics
- Premium subscription management
- Interview preparation tools
- Company-specific problem collections
- Plagiarism detection system
- Real-time collaborative coding

### Non-Functional Requirements
- **Scale**: Support 10M+ users, 1M+ daily submissions
- **Performance**: < 100ms UI response, < 5s code execution
- **Availability**: 99.99% uptime
- **Security**: Secure code execution, user data protection
- **Reliability**: Consistent code execution results
- **Scalability**: Auto-scaling execution infrastructure
- **Global**: Multi-region deployment

## 2. Architecture Overview

### Microservices Architecture
```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│  User Service   │    │Problem Service   │    │Execution Engine │
└─────────────────┘    └──────────────────┘    └─────────────────┘

┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│Submission Svc   │    │ Contest Service  │    │Discussion Svc   │
└─────────────────┘    └──────────────────┘    └─────────────────┘

┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│Analytics Svc    │    │Payment Service   │    │Notification Svc │
└─────────────────┘    └──────────────────┘    └─────────────────┘
```

### Code Execution Flow
```
Code Submission ──▶ Queue ──▶ Execution Container ──▶ Test Cases ──▶ Result
                      │              │                    │
                      ▼              ▼                    ▼
                 Load Balancer   Resource Monitor    Verdict Engine
```

## 3. Database Design

### PostgreSQL (Primary Database)
```sql
-- Users table
CREATE TABLE users (
    user_id UUID PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    country VARCHAR(100),
    company VARCHAR(200),
    school VARCHAR(200),
    profile_picture_url VARCHAR(500),
    user_status VARCHAR(20) DEFAULT 'active',
    account_type VARCHAR(20) DEFAULT 'free', -- free, premium
    premium_expires_at TIMESTAMP,
    github_username VARCHAR(100),
    linkedin_url VARCHAR(500),
    created_at TIMESTAMP DEFAULT NOW(),
    last_login TIMESTAMP,
    preferences JSONB DEFAULT '{}'
);

-- Problems table
CREATE TABLE problems (
    problem_id UUID PRIMARY KEY,
    problem_slug VARCHAR(200) UNIQUE NOT NULL,
    title VARCHAR(500) NOT NULL,
    description TEXT NOT NULL,
    difficulty VARCHAR(20) NOT NULL, -- Easy, Medium, Hard
    problem_type VARCHAR(50) DEFAULT 'algorithm',
    tags TEXT[] DEFAULT '{}',
    companies TEXT[] DEFAULT '{}', -- Associated companies
    category_id UUID,
    
    -- Statistics
    acceptance_rate DECIMAL(5,2) DEFAULT 0.00,
    total_submissions INTEGER DEFAULT 0,
    accepted_submissions INTEGER DEFAULT 0,
    likes INTEGER DEFAULT 0,
    dislikes INTEGER DEFAULT 0,
    
    -- Content
    constraints TEXT,
    examples JSONB DEFAULT '[]',
    hints JSONB DEFAULT '[]',
    solution_article_id UUID,
    
    -- Metadata
    is_premium BOOLEAN DEFAULT false,
    is_active BOOLEAN DEFAULT true,
    created_by UUID,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    similar_problems UUID[] DEFAULT '{}'
);

-- Test cases
CREATE TABLE test_cases (
    test_case_id UUID PRIMARY KEY,
    problem_id UUID REFERENCES problems(problem_id),
    input_data TEXT NOT NULL,
    expected_output TEXT NOT NULL,
    is_example BOOLEAN DEFAULT false,
    is_hidden BOOLEAN DEFAULT true,
    test_case_order INTEGER DEFAULT 1,
    time_limit_ms INTEGER DEFAULT 2000,
    memory_limit_mb INTEGER DEFAULT 256,
    created_at TIMESTAMP DEFAULT NOW()
);

-- Code submissions
CREATE TABLE submissions (
    submission_id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(user_id),
    problem_id UUID REFERENCES problems(problem_id),
    contest_id UUID, -- NULL for practice submissions
    language VARCHAR(20) NOT NULL,
    code TEXT NOT NULL,
    status VARCHAR(30) DEFAULT 'pending', -- pending, accepted, wrong_answer, tle, mle, compile_error, runtime_error
    runtime_ms INTEGER,
    memory_usage_kb INTEGER,
    test_cases_passed INTEGER DEFAULT 0,
    total_test_cases INTEGER DEFAULT 0,
    error_message TEXT,
    verdict_details JSONB DEFAULT '{}',
    is_public BOOLEAN DEFAULT false,
    submitted_at TIMESTAMP DEFAULT NOW(),
    judged_at TIMESTAMP,
    execution_trace JSONB DEFAULT '{}'
);

-- Contests
CREATE TABLE contests (
    contest_id UUID PRIMARY KEY,
    contest_name VARCHAR(200) NOT NULL,
    contest_type VARCHAR(50) DEFAULT 'weekly', -- weekly, biweekly, monthly, special
    description TEXT,
    start_time TIMESTAMP NOT NULL,
    duration_minutes INTEGER NOT NULL,
    max_participants INTEGER,
    current_participants INTEGER DEFAULT 0,
    contest_status VARCHAR(20) DEFAULT 'upcoming', -- upcoming, running, finished
    is_rated BOOLEAN DEFAULT true,
    is_virtual_allowed BOOLEAN DEFAULT true,
    created_by UUID,
    created_at TIMESTAMP DEFAULT NOW(),
    prize_pool JSONB DEFAULT '{}',
    rules JSONB DEFAULT '{}'
);

-- Contest problems mapping
CREATE TABLE contest_problems (
    contest_id UUID REFERENCES contests(contest_id),
    problem_id UUID REFERENCES problems(problem_id),
    problem_order INTEGER NOT NULL,
    points INTEGER DEFAULT 100,
    PRIMARY KEY (contest_id, problem_id)
);

-- Contest participations
CREATE TABLE contest_participations (
    participation_id UUID PRIMARY KEY,
    contest_id UUID REFERENCES contests(contest_id),
    user_id UUID REFERENCES users(user_id),
    rank INTEGER,
    score INTEGER DEFAULT 0,
    penalty_time INTEGER DEFAULT 0, -- in minutes
    problems_solved INTEGER DEFAULT 0,
    submission_count INTEGER DEFAULT 0,
    participation_type VARCHAR(20) DEFAULT 'official', -- official, virtual
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    UNIQUE(contest_id, user_id)
);

-- Discussion posts
CREATE TABLE discussion_posts (
    post_id UUID PRIMARY KEY,
    problem_id UUID REFERENCES problems(problem_id),
    user_id UUID REFERENCES users(user_id),
    parent_post_id UUID REFERENCES discussion_posts(post_id),
    title VARCHAR(500),
    content TEXT NOT NULL,
    post_type VARCHAR(20) DEFAULT 'discussion', -- solution, discussion, question
    language VARCHAR(20), -- For solution posts
    upvotes INTEGER DEFAULT 0,
    downvotes INTEGER DEFAULT 0,
    is_official BOOLEAN DEFAULT false,
    is_premium BOOLEAN DEFAULT false,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- User problem progress
CREATE TABLE user_progress (
    user_id UUID REFERENCES users(user_id),
    problem_id UUID REFERENCES problems(problem_id),
    status VARCHAR(20) NOT NULL, -- attempted, solved
    best_submission_id UUID REFERENCES submissions(submission_id),
    attempts INTEGER DEFAULT 0,
    first_solved_at TIMESTAMP,
    last_attempted_at TIMESTAMP DEFAULT NOW(),
    notes TEXT,
    is_favorite BOOLEAN DEFAULT false,
    PRIMARY KEY (user_id, problem_id)
);

-- Indexes
CREATE INDEX idx_problems_difficulty ON problems(difficulty);
CREATE INDEX idx_problems_tags ON problems USING GIN(tags);
CREATE INDEX idx_submissions_user_problem ON submissions(user_id, problem_id);
CREATE INDEX idx_submissions_status ON submissions(status);
CREATE INDEX idx_contest_participations_contest ON contest_participations(contest_id, rank);
```

### Redis (Cache & Real-time)
```javascript
// User session and preferences
{
  "user_session:uuid": {
    "user_id": "uuid",
    "username": "john_doe",
    "account_type": "premium",
    "current_language": "python3",
    "theme": "dark",
    "last_activity": "2024-01-01T12:00:00Z"
  }
}

// Problem metadata cache
{
  "problem:slug": {
    "problem_id": "uuid",
    "title": "Two Sum",
    "difficulty": "Easy",
    "acceptance_rate": 45.2,
    "tags": ["Array", "Hash Table"],
    "is_premium": false,
    "cached_at": "2024-01-01T12:00:00Z"
  }
}

// Code execution queue
{
  "execution_queue:priority": [
    {
      "submission_id": "uuid",
      "user_id": "uuid",
      "problem_id": "uuid",
      "language": "python3",
      "priority": 1,
      "queued_at": "2024-01-01T12:00:00Z"
    }
  ]
}

// Real-time contest leaderboard
{
  "contest_leaderboard:uuid": [
    {
      "user_id": "uuid",
      "username": "alice",
      "rank": 1,
      "score": 1200,
      "penalty": 45,
      "problems_solved": 3,
      "last_submission": "2024-01-01T12:00:00Z"
    }
  ]
}

// Code execution locks and status
{
  "execution:uuid": {
    "status": "running",
    "started_at": "2024-01-01T12:00:00Z",
    "container_id": "container-123",
    "timeout_at": "2024-01-01T12:00:05Z"
  }
}
```

## 4. Core Services Implementation

### Problem Service
```python
class ProblemService:
    def __init__(self, db_client, cache_client, search_service):
        self.db = db_client
        self.cache = cache_client
        self.search_service = search_service
    
    async def create_problem(self, problem_data: dict, created_by: str) -> Problem:
        """Create a new coding problem"""
        try:
            # Generate unique slug
            slug = await self.generate_problem_slug(problem_data['title'])
            
            # Create problem
            problem = Problem(
                problem_id=str(uuid.uuid4()),
                problem_slug=slug,
                title=problem_data['title'],
                description=problem_data['description'],
                difficulty=problem_data['difficulty'],
                tags=problem_data.get('tags', []),
                companies=problem_data.get('companies', []),
                examples=problem_data.get('examples', []),
                constraints=problem_data.get('constraints', ''),
                is_premium=problem_data.get('is_premium', False),
                created_by=created_by,
                created_at=datetime.utcnow()
            )
            
            await self.db.create_problem(problem)
            
            # Create test cases
            test_cases = problem_data.get('test_cases', [])
            await self.create_test_cases(problem.problem_id, test_cases)
            
            # Index for search
            await self.search_service.index_problem(problem)
            
            # Clear cache
            await self.cache.delete(f"problem:{slug}")
            
            return problem
            
        except Exception as e:
            logger.error(f"Problem creation failed: {str(e)}")
            raise ProblemServiceError(f"Problem creation failed: {str(e)}")
    
    async def get_problem_by_slug(self, slug: str, user_id: str = None) -> dict:
        """Get problem details with user context"""
        try:
            # Try cache first
            cache_key = f"problem:{slug}"
            cached_problem = await self.cache.get(cache_key)
            
            if cached_problem:
                problem_data = json.loads(cached_problem)
            else:
                # Get from database
                problem = await self.db.get_problem_by_slug(slug)
                if not problem:
                    raise ProblemNotFoundError("Problem not found")
                
                problem_data = problem.to_dict()
                
                # Cache for 1 hour
                await self.cache.setex(cache_key, 3600, json.dumps(problem_data))
            
            # Add user-specific data
            if user_id:
                user_progress = await self.db.get_user_progress(user_id, problem_data['problem_id'])
                problem_data['user_progress'] = user_progress.to_dict() if user_progress else None
                
                # Check if user has premium access
                user = await self.db.get_user(user_id)
                problem_data['has_access'] = (
                    not problem_data['is_premium'] or
                    (user.account_type == 'premium' and user.premium_expires_at > datetime.utcnow())
                )
            
            # Get example test cases
            example_test_cases = await self.db.get_example_test_cases(problem_data['problem_id'])
            problem_data['examples'] = [tc.to_dict() for tc in example_test_cases]
            
            return problem_data
            
        except Exception as e:
            logger.error(f"Problem retrieval failed: {str(e)}")
            raise ProblemServiceError(f"Problem retrieval failed: {str(e)}")
    
    async def search_problems(self, search_params: dict, user_id: str = None) -> dict:
        """Search problems with filters"""
        try:
            # Build search query
            query_filters = {}
            
            if search_params.get('difficulty'):
                query_filters['difficulty'] = search_params['difficulty']
            
            if search_params.get('tags'):
                query_filters['tags'] = search_params['tags']
            
            if search_params.get('companies'):
                query_filters['companies'] = search_params['companies']
            
            # Check premium access
            user_has_premium = False
            if user_id:
                user = await self.db.get_user(user_id)
                user_has_premium = (
                    user.account_type == 'premium' and 
                    user.premium_expires_at > datetime.utcnow()
                )
            
            if not user_has_premium:
                query_filters['is_premium'] = False
            
            # Execute search
            search_results = await self.search_service.search_problems(
                query=search_params.get('query', ''),
                filters=query_filters,
                page=search_params.get('page', 1),
                limit=search_params.get('limit', 20)
            )
            
            # Add user progress data
            if user_id and search_results['problems']:
                problem_ids = [p['problem_id'] for p in search_results['problems']]
                user_progress_map = await self.db.get_user_progress_batch(user_id, problem_ids)
                
                for problem in search_results['problems']:
                    progress = user_progress_map.get(problem['problem_id'])
                    problem['user_status'] = progress.status if progress else None
            
            return search_results
            
        except Exception as e:
            logger.error(f"Problem search failed: {str(e)}")
            raise ProblemServiceError(f"Problem search failed: {str(e)}")
```

### Code Execution Service
```python
class CodeExecutionService:
    def __init__(self, container_manager, queue_service, result_service):
        self.container_manager = container_manager
        self.queue_service = queue_service
        self.result_service = result_service
        self.language_configs = {
            'python3': {
                'image': 'python:3.9-alpine',
                'timeout': 5000,  # 5 seconds
                'memory_limit': 256,  # 256 MB
                'compile_command': None,
                'run_command': 'python3 solution.py'
            },
            'java': {
                'image': 'openjdk:11-jdk-slim',
                'timeout': 10000,  # 10 seconds  
                'memory_limit': 512,  # 512 MB
                'compile_command': 'javac Solution.java',
                'run_command': 'java Solution'
            },
            'cpp': {
                'image': 'gcc:9',
                'timeout': 8000,  # 8 seconds
                'memory_limit': 512,
                'compile_command': 'g++ -o solution solution.cpp -std=c++17',
                'run_command': './solution'
            }
        }
    
    async def execute_submission(self, submission_id: str) -> dict:
        """Execute code submission with test cases"""
        try:
            # Get submission details
            submission = await self.db.get_submission(submission_id)
            if not submission:
                raise SubmissionNotFoundError("Submission not found")
            
            # Get problem and test cases
            problem = await self.db.get_problem(submission.problem_id)
            test_cases = await self.db.get_test_cases(submission.problem_id)
            
            # Get language configuration
            lang_config = self.language_configs.get(submission.language)
            if not lang_config:
                raise UnsupportedLanguageError(f"Language {submission.language} not supported")
            
            # Update submission status
            await self.db.update_submission_status(submission_id, 'running')
            
            # Create execution container
            container = await self.container_manager.create_container(
                image=lang_config['image'],
                memory_limit=lang_config['memory_limit'],
                timeout=lang_config['timeout']
            )
            
            try:
                # Execute code against test cases
                execution_results = await self.run_test_cases(
                    container, submission.code, test_cases, lang_config
                )
                
                # Process results
                verdict = await self.determine_verdict(execution_results)
                
                # Update submission with results
                await self.update_submission_results(submission_id, verdict, execution_results)
                
                # Update user progress
                if verdict['status'] == 'accepted':
                    await self.update_user_progress(
                        submission.user_id, 
                        submission.problem_id, 
                        'solved',
                        submission_id
                    )
                
                return verdict
                
            finally:
                # Clean up container
                await self.container_manager.cleanup_container(container.id)
                
        except Exception as e:
            await self.db.update_submission_status(submission_id, 'system_error')
            logger.error(f"Code execution failed for submission {submission_id}: {str(e)}")
            raise ExecutionServiceError(f"Code execution failed: {str(e)}")
    
    async def run_test_cases(self, container, code: str, test_cases: List[TestCase], 
                           lang_config: dict) -> List[dict]:
        """Run code against all test cases"""
        results = []
        
        # Write code to container
        await container.write_file('solution.' + self.get_file_extension(lang_config), code)
        
        # Compile if needed
        if lang_config.get('compile_command'):
            compile_result = await container.execute_command(
                lang_config['compile_command'],
                timeout=30000  # 30 seconds for compilation
            )
            
            if compile_result['exit_code'] != 0:
                return [{
                    'status': 'compile_error',
                    'error_message': compile_result['stderr'],
                    'test_case_id': None
                }]
        
        # Run each test case
        for i, test_case in enumerate(test_cases):
            try:
                # Write input to file
                await container.write_file('input.txt', test_case.input_data)
                
                # Execute code
                run_result = await container.execute_command(
                    f"{lang_config['run_command']} < input.txt",
                    timeout=test_case.time_limit_ms,
                    memory_limit=test_case.memory_limit_mb
                )
                
                # Compare output
                actual_output = run_result['stdout'].strip()
                expected_output = test_case.expected_output.strip()
                
                test_result = {
                    'test_case_id': test_case.test_case_id,
                    'test_case_order': i + 1,
                    'status': 'passed' if actual_output == expected_output else 'failed',
                    'runtime_ms': run_result['runtime_ms'],
                    'memory_usage_kb': run_result['memory_usage_kb'],
                    'exit_code': run_result['exit_code'],
                    'expected_output': expected_output,
                    'actual_output': actual_output
                }
                
                if run_result['exit_code'] != 0:
                    test_result['status'] = 'runtime_error'
                    test_result['error_message'] = run_result['stderr']
                elif run_result['timeout']:
                    test_result['status'] = 'time_limit_exceeded'
                elif run_result['memory_exceeded']:
                    test_result['status'] = 'memory_limit_exceeded'
                
                results.append(test_result)
                
                # Stop on first failure for efficiency
                if test_result['status'] != 'passed':
                    break
                    
            except Exception as e:
                results.append({
                    'test_case_id': test_case.test_case_id,
                    'test_case_order': i + 1,
                    'status': 'execution_error',
                    'error_message': str(e)
                })
                break
        
        return results
    
    async def determine_verdict(self, execution_results: List[dict]) -> dict:
        """Determine final verdict based on test case results"""
        if not execution_results:
            return {'status': 'system_error', 'message': 'No execution results'}
        
        # Check for compilation errors
        if execution_results[0].get('status') == 'compile_error':
            return {
                'status': 'compile_error',
                'message': execution_results[0]['error_message']
            }
        
        # Count results
        total_cases = len(execution_results)
        passed_cases = sum(1 for r in execution_results if r['status'] == 'passed')
        
        # Determine verdict
        if passed_cases == total_cases:
            max_runtime = max(r['runtime_ms'] for r in execution_results)
            max_memory = max(r['memory_usage_kb'] for r in execution_results)
            
            return {
                'status': 'accepted',
                'test_cases_passed': passed_cases,
                'total_test_cases': total_cases,
                'runtime_ms': max_runtime,
                'memory_usage_kb': max_memory,
                'message': 'All test cases passed!'
            }
        else:
            # Find first failed case
            first_failure = next(r for r in execution_results if r['status'] != 'passed')
            
            verdict_map = {
                'failed': 'wrong_answer',
                'time_limit_exceeded': 'time_limit_exceeded',
                'memory_limit_exceeded': 'memory_limit_exceeded',
                'runtime_error': 'runtime_error',
                'execution_error': 'runtime_error'
            }
            
            return {
                'status': verdict_map.get(first_failure['status'], 'wrong_answer'),
                'test_cases_passed': passed_cases,
                'total_test_cases': total_cases,
                'failed_test_case': first_failure.get('test_case_order', 1),
                'message': first_failure.get('error_message', 'Wrong answer')
            }
```

### Contest Service
```python
class ContestService:
    def __init__(self, db_client, redis_client, notification_service):
        self.db = db_client
        self.redis = redis_client
        self.notification_service = notification_service
    
    async def create_contest(self, contest_data: dict, created_by: str) -> Contest:
        """Create a new contest"""
        try:
            contest = Contest(
                contest_id=str(uuid.uuid4()),
                contest_name=contest_data['name'],
                contest_type=contest_data.get('type', 'weekly'),
                description=contest_data.get('description', ''),
                start_time=datetime.fromisoformat(contest_data['start_time']),
                duration_minutes=contest_data['duration_minutes'],
                max_participants=contest_data.get('max_participants'),
                is_rated=contest_data.get('is_rated', True),
                created_by=created_by,
                created_at=datetime.utcnow()
            )
            
            await self.db.create_contest(contest)
            
            # Add problems to contest
            problems = contest_data.get('problems', [])
            for i, problem_data in enumerate(problems):
                await self.db.add_contest_problem(
                    contest.contest_id,
                    problem_data['problem_id'],
                    i + 1,  # problem_order
                    problem_data.get('points', 100)
                )
            
            return contest
            
        except Exception as e:
            logger.error(f"Contest creation failed: {str(e)}")
            raise ContestServiceError(f"Contest creation failed: {str(e)}")
    
    async def register_user_for_contest(self, contest_id: str, user_id: str) -> dict:
        """Register user for contest"""
        try:
            # Check if contest exists and is open for registration
            contest = await self.db.get_contest(contest_id)
            if not contest:
                raise ContestNotFoundError("Contest not found")
            
            if contest.start_time <= datetime.utcnow():
                raise ContestRegistrationError("Contest has already started")
            
            # Check participation limit
            if contest.max_participants and contest.current_participants >= contest.max_participants:
                raise ContestRegistrationError("Contest is full")
            
            # Check if user already registered
            existing_participation = await self.db.get_contest_participation(contest_id, user_id)
            if existing_participation:
                raise ContestRegistrationError("User already registered")
            
            # Create participation record
            participation = ContestParticipation(
                participation_id=str(uuid.uuid4()),
                contest_id=contest_id,
                user_id=user_id,
                participation_type='official'
            )
            
            await self.db.create_contest_participation(participation)
            
            # Update participant count
            await self.db.increment_contest_participants(contest_id)
            
            # Send confirmation notification
            await self.notification_service.send_contest_registration_confirmation(
                user_id, contest
            )
            
            return {
                'contest_id': contest_id,
                'user_id': user_id,
                'registration_status': 'confirmed'
            }
            
        except Exception as e:
            logger.error(f"Contest registration failed: {str(e)}")
            raise ContestServiceError(f"Contest registration failed: {str(e)}")
    
    async def get_contest_leaderboard(self, contest_id: str, page: int = 1, 
                                    limit: int = 100) -> dict:
        """Get contest leaderboard with pagination"""
        try:
            # Try Redis cache first for active contests
            cache_key = f"contest_leaderboard:{contest_id}"
            cached_leaderboard = await self.redis.get(cache_key)
            
            if cached_leaderboard:
                leaderboard_data = json.loads(cached_leaderboard)
                
                # Paginate results
                start_idx = (page - 1) * limit
                end_idx = start_idx + limit
                paginated_results = leaderboard_data['rankings'][start_idx:end_idx]
                
                return {
                    'contest_id': contest_id,
                    'total_participants': len(leaderboard_data['rankings']),
                    'page': page,
                    'limit': limit,
                    'rankings': paginated_results,
                    'last_updated': leaderboard_data['last_updated']
                }
            
            # Get from database if not cached
            leaderboard = await self.db.get_contest_leaderboard(
                contest_id, page, limit
            )
            
            return leaderboard
            
        except Exception as e:
            logger.error(f"Leaderboard retrieval failed: {str(e)}")
            raise ContestServiceError(f"Leaderboard retrieval failed: {str(e)}")
    
    async def update_contest_standings(self, contest_id: str, user_id: str, 
                                     submission_result: dict):
        """Update contest standings after submission"""
        try:
            # Get participation record
            participation = await self.db.get_contest_participation(contest_id, user_id)
            if not participation:
                return
            
            # Calculate new score and penalties
            if submission_result['verdict']['status'] == 'accepted':
                # Get problem points
                problem_points = await self.db.get_contest_problem_points(
                    contest_id, submission_result['problem_id']
                )
                
                # Calculate time penalty (minutes since contest start)
                contest = await self.db.get_contest(contest_id)
                time_penalty = int((datetime.utcnow() - contest.start_time).total_seconds() / 60)
                
                # Update participation
                new_score = participation.score + problem_points
                new_penalty = participation.penalty_time + time_penalty
                
                await self.db.update_contest_participation(participation.participation_id, {
                    'score': new_score,
                    'penalty_time': new_penalty,
                    'problems_solved': participation.problems_solved + 1
                })
            
            # Update real-time leaderboard
            await self.update_realtime_leaderboard(contest_id)
            
        except Exception as e:
            logger.error(f"Contest standings update failed: {str(e)}")
    
    async def update_realtime_leaderboard(self, contest_id: str):
        """Update real-time leaderboard in Redis"""
        try:
            # Get current standings from database
            standings = await self.db.get_contest_standings(contest_id)
            
            # Format for cache
            leaderboard_data = {
                'contest_id': contest_id,
                'rankings': [
                    {
                        'rank': i + 1,
                        'user_id': standing.user_id,
                        'username': standing.username,
                        'score': standing.score,
                        'penalty_time': standing.penalty_time,
                        'problems_solved': standing.problems_solved,
                        'last_submission_time': standing.last_submission_time.isoformat() if standing.last_submission_time else None
                    }
                    for i, standing in enumerate(standings)
                ],
                'last_updated': datetime.utcnow().isoformat()
            }
            
            # Cache for 30 seconds during active contests
            cache_key = f"contest_leaderboard:{contest_id}"
            await self.redis.setex(cache_key, 30, json.dumps(leaderboard_data))
            
        except Exception as e:
            logger.error(f"Leaderboard cache update failed: {str(e)}")
```

## 5. Security and Anti-Cheating

### Code Execution Security
```python
class SecureExecutionManager:
    def __init__(self, docker_client):
        self.docker = docker_client
        
    async def create_secure_container(self, language: str, memory_limit: int, 
                                    time_limit: int) -> Container:
        """Create secure isolated container for code execution"""
        try:
            container_config = {
                'image': self.get_language_image(language),
                'mem_limit': f'{memory_limit}m',
                'cpu_period': 100000,
                'cpu_quota': 50000,  # 50% CPU limit
                'network_mode': 'none',  # No network access
                'read_only': True,
                'user': 'nobody',
                'security_opt': ['no-new-privileges:true'],
                'cap_drop': ['ALL'],
                'tmpfs': {'/tmp': 'size=100m,exec,nosuid,nodev,noatime'},
                'pids_limit': 64,  # Limit process count
                'ulimits': [
                    {'name': 'nofile', 'soft': 64, 'hard': 64},
                    {'name': 'nproc', 'soft': 32, 'hard': 32}
                ]
            }
            
            container = await self.docker.containers.create(**container_config)
            
            # Set up execution timeout
            asyncio.create_task(self.monitor_container_timeout(container.id, time_limit))
            
            return container
            
        except Exception as e:
            logger.error(f"Secure container creation failed: {str(e)}")
            raise

class PlagiarismDetectionService:
    def __init__(self, db_client, similarity_analyzer):
        self.db = db_client
        self.analyzer = similarity_analyzer
    
    async def check_submission_similarity(self, submission_id: str) -> dict:
        """Check submission against other submissions for plagiarism"""
        try:
            submission = await self.db.get_submission(submission_id)
            
            # Get recent submissions for same problem (excluding user's own)
            recent_submissions = await self.db.get_recent_submissions(
                submission.problem_id,
                exclude_user_id=submission.user_id,
                limit=1000,
                time_window_hours=24
            )
            
            # Analyze code similarity
            similarity_results = []
            
            for other_submission in recent_submissions:
                similarity_score = await self.analyzer.calculate_similarity(
                    submission.code,
                    other_submission.code,
                    submission.language
                )
                
                if similarity_score > 0.8:  # High similarity threshold
                    similarity_results.append({
                        'similar_submission_id': other_submission.submission_id,
                        'similar_user_id': other_submission.user_id,
                        'similarity_score': similarity_score,
                        'submission_time_diff': abs(
                            (submission.submitted_at - other_submission.submitted_at).total_seconds()
                        )
                    })
            
            # Flag for review if suspicious
            if similarity_results:
                await self.flag_for_plagiarism_review(submission_id, similarity_results)
            
            return {
                'submission_id': submission_id,
                'suspicious_similarities': len(similarity_results),
                'requires_review': len(similarity_results) > 0
            }
            
        except Exception as e:
            logger.error(f"Plagiarism check failed: {str(e)}")
            return {'submission_id': submission_id, 'requires_review': False}
```

## 6. Analytics and Insights

### User Analytics Service
```python
class UserAnalyticsService:
    def __init__(self, db_client, cache_client):
        self.db = db_client
        self.cache = cache_client
    
    async def generate_user_profile_insights(self, user_id: str) -> dict:
        """Generate comprehensive user insights"""
        try:
            # Basic stats
            user_stats = await self.db.get_user_statistics(user_id)
            
            # Problem-solving patterns
            solving_patterns = await self.analyze_solving_patterns(user_id)
            
            # Strength/weakness analysis
            topic_analysis = await self.analyze_topic_performance(user_id)
            
            # Progress tracking
            progress_timeline = await self.get_progress_timeline(user_id)
            
            # Contest performance
            contest_performance = await self.get_contest_performance(user_id)
            
            return {
                'user_id': user_id,
                'overall_stats': {
                    'total_problems_solved': user_stats['problems_solved'],
                    'total_submissions': user_stats['total_submissions'],
                    'acceptance_rate': user_stats['acceptance_rate'],
                    'current_streak': user_stats['current_streak'],
                    'max_streak': user_stats['max_streak']
                },
                'difficulty_breakdown': user_stats['difficulty_breakdown'],
                'solving_patterns': solving_patterns,
                'topic_analysis': topic_analysis,
                'progress_timeline': progress_timeline,
                'contest_performance': contest_performance,
                'generated_at': datetime.utcnow().isoformat()
            }
            
        except Exception as e:
            logger.error(f"User analytics generation failed: {str(e)}")
            raise AnalyticsError(f"Analytics generation failed: {str(e)}")
```

This comprehensive LeetCode platform design provides secure code execution, contest management, plagiarism detection, and detailed analytics with scalable architecture to handle millions of users and submissions.
