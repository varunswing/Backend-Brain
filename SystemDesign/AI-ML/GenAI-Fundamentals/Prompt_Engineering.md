# Prompt Engineering Guide

## What is Prompt Engineering?

The art of crafting inputs to get optimal outputs from LLMs. Good prompts = better results, lower costs, fewer errors.

---

## Core Principles

### 1. Be Specific and Clear
```
❌ Bad: "Write about APIs"
✅ Good: "Write a 200-word explanation of REST APIs for junior developers, 
         including 3 key principles with examples"
```

### 2. Provide Context
```
❌ Bad: "Fix this code"
✅ Good: "Fix this Python function that should return the sum of a list,
         but currently returns None for empty lists:
         [code here]"
```

### 3. Specify Output Format
```
❌ Bad: "List some databases"
✅ Good: "List 5 popular databases in this JSON format:
         {name, type, best_for}"
```

---

## Prompt Patterns

### 1. Role Pattern
Assign a persona to the model.

```
You are a senior software architect with 15 years of experience.
Review this system design and provide feedback on:
1. Scalability concerns
2. Single points of failure
3. Cost optimization opportunities

[design details]
```

### 2. Chain of Thought (CoT)
Ask the model to think step by step.

```
Solve this problem step by step:

A system receives 1000 requests per second. Each request takes 50ms 
to process. How many servers do we need if each server can handle 
100 concurrent requests?

Think through each step before giving the final answer.
```

### 3. Few-Shot Learning
Provide examples of desired output.

```
Convert these sentences to SQL queries:

Example 1:
Input: "Find all users older than 25"
Output: SELECT * FROM users WHERE age > 25;

Example 2:
Input: "Count orders from last month"
Output: SELECT COUNT(*) FROM orders WHERE created_at >= DATE_SUB(NOW(), INTERVAL 1 MONTH);

Now convert:
Input: "Get the top 5 products by sales"
Output:
```

### 4. Output Format Specification
```
Analyze this code for security vulnerabilities.

Return your response in this exact JSON format:
{
  "vulnerabilities": [
    {
      "type": "string",
      "severity": "high|medium|low",
      "line": number,
      "description": "string",
      "fix": "string"
    }
  ],
  "overall_risk": "high|medium|low",
  "summary": "string"
}
```

### 5. Constraints Pattern
Set boundaries for the response.

```
Explain microservices architecture.

Constraints:
- Maximum 3 paragraphs
- Use simple language (no jargon)
- Include one real-world analogy
- End with 3 key takeaways
```

---

## Advanced Techniques

### Self-Consistency
Ask the same question multiple ways, take the majority answer.

```
# Query 1
"What's 17 × 24? Show your work."

# Query 2  
"Calculate: 17 multiplied by 24. Break it down."

# Query 3
"17 × 24 = ? Use the distributive property."

# Take the most common answer
```

### Tree of Thoughts
Explore multiple reasoning paths.

```
Consider this system design problem from 3 different perspectives:

1. SCALABILITY PERSPECTIVE: How would you design this focusing on handling 
   10x traffic growth?

2. COST PERSPECTIVE: How would you design this to minimize infrastructure 
   costs?

3. RELIABILITY PERSPECTIVE: How would you design this for 99.99% uptime?

After analyzing all three, provide a balanced recommendation.
```

### ReAct (Reason + Act)
Combine reasoning with actions.

```
You have access to these tools:
- search(query): Search the web
- calculate(expression): Do math
- lookup(term): Get definitions

Question: What's the population of Tokyo multiplied by 2?

Think step by step, using tools when needed:

Thought: I need to find Tokyo's population first
Action: search("Tokyo population 2024")
Observation: Tokyo population is approximately 14 million
Thought: Now I multiply by 2
Action: calculate(14000000 * 2)
Observation: 28000000
Answer: 28 million
```

---

## System Prompts

### Structure
```
[Role] - Who the AI should be
[Context] - Background information
[Task] - What to do
[Format] - How to respond
[Constraints] - Limitations
[Examples] - Sample outputs (optional)
```

### Example: Code Review Assistant
```
ROLE:
You are a senior code reviewer at a top tech company.

CONTEXT:
You are reviewing pull requests for a Node.js backend service.
The codebase follows these standards:
- ESLint with Airbnb config
- Jest for testing
- Express.js framework

TASK:
Review the provided code and identify:
1. Bugs and potential issues
2. Performance concerns
3. Security vulnerabilities
4. Code style violations
5. Suggestions for improvement

FORMAT:
For each issue found:
- Line number
- Severity (Critical/Major/Minor)
- Description
- Suggested fix with code example

CONSTRAINTS:
- Be constructive, not harsh
- Prioritize issues by severity
- Maximum 10 issues per review
- Always explain WHY something is an issue
```

---

## Common Mistakes

### ❌ Avoid These

**1. Vague Instructions**
```
❌ "Make it better"
✅ "Improve readability by: adding comments, using descriptive 
   variable names, and breaking into smaller functions"
```

**2. Contradictory Requirements**
```
❌ "Be brief but explain everything in detail"
✅ "Provide a 2-paragraph summary, then detailed explanation in 
   bullet points"
```

**3. Missing Context**
```
❌ "Why doesn't this work?" [code without error message]
✅ "This Python code throws 'KeyError: user_id' on line 15 when 
   processing empty responses. Here's the code and error: [details]"
```

**4. No Format Specification**
```
❌ "List the advantages"
✅ "List 5 advantages as bullet points, each with a one-sentence 
   explanation"
```

---

## Prompt Templates

### Code Generation
```
Write a [language] function that:

Purpose: [what it does]
Input: [parameters with types]
Output: [return value with type]
Requirements:
- [requirement 1]
- [requirement 2]

Include:
- Input validation
- Error handling
- JSDoc/docstring comments
- 2-3 unit test examples
```

### System Design
```
Design a [system type] with these requirements:

Functional Requirements:
1. [requirement]
2. [requirement]

Non-Functional Requirements:
- Scale: [users/requests]
- Latency: [target]
- Availability: [target]

Provide:
1. High-level architecture diagram (ASCII)
2. Key components and their responsibilities
3. Data flow explanation
4. Database schema
5. API endpoints
6. Trade-offs discussion
```

### Debug Assistance
```
Help me debug this issue:

**Environment**: [language, framework, version]
**Expected Behavior**: [what should happen]
**Actual Behavior**: [what actually happens]
**Error Message**: [full error if any]
**Code**: 
```[code]```

**What I've Tried**:
- [attempt 1]
- [attempt 2]

Analyze the issue and provide:
1. Root cause
2. Step-by-step fix
3. Explanation of why this fixes it
```

---

## Interview Questions

**Q: What is prompt engineering and why does it matter?**
> Crafting inputs to get optimal LLM outputs. Matters because: better results, lower costs (fewer tokens), consistent outputs, reduced hallucinations.

**Q: Explain few-shot vs zero-shot prompting.**
> Zero-shot: No examples, just instructions. Few-shot: Include examples of desired output. Few-shot improves accuracy for complex/specific formats.

**Q: How do you reduce hallucinations through prompting?**
> 1) Ask model to cite sources, 2) Use "I don't know" as valid option, 3) Chain of thought reasoning, 4) RAG with real data, 5) Lower temperature.

---

## Quick Reference

| Technique | When to Use |
|-----------|-------------|
| Role Pattern | Specialized expertise needed |
| Chain of Thought | Complex reasoning |
| Few-Shot | Specific output format |
| Constraints | Control response length/style |
| Output Format | Structured data (JSON, etc.) |
| Self-Consistency | Critical decisions |
