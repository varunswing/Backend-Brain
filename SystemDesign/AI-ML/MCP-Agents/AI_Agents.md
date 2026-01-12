# AI Agents: Building Autonomous Systems

## What is an AI Agent?

An **AI Agent** is an autonomous system that can perceive its environment, make decisions, and take actions to achieve goals.

```
Traditional LLM:
  Input → Process → Output (single interaction)

AI Agent:
  Goal → Plan → Act → Observe → Reflect → Repeat until done
```

---

## Agent Architecture

### Basic Agent Loop
```
┌─────────────────────────────────────────────────────────┐
│                     AGENT LOOP                           │
├─────────────────────────────────────────────────────────┤
│                                                          │
│   ┌─────────┐    ┌─────────┐    ┌─────────┐            │
│   │  Plan   │───→│   Act   │───→│ Observe │            │
│   └────┬────┘    └─────────┘    └────┬────┘            │
│        │                             │                  │
│        │         ┌─────────┐         │                  │
│        └─────────│ Reflect │←────────┘                  │
│                  └────┬────┘                            │
│                       │                                 │
│              Is goal achieved?                          │
│              NO ↓        YES ↓                          │
│           Continue       Complete                       │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

### Components
```
┌─────────────────────────────────────────────────────────┐
│                       AI AGENT                           │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  ┌──────────────┐     ┌──────────────┐                  │
│  │    Brain     │     │    Memory    │                  │
│  │    (LLM)     │←───→│   (Context)  │                  │
│  └──────┬───────┘     └──────────────┘                  │
│         │                                               │
│         ▼                                               │
│  ┌──────────────┐     ┌──────────────┐                  │
│  │   Planning   │     │    Tools     │                  │
│  │   Module     │────→│  (Actions)   │                  │
│  └──────────────┘     └──────────────┘                  │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

---

## Types of Agents

### 1. ReAct Agent (Reason + Act)
Interleaves reasoning with actions.

```
Question: What's the population of the capital of France?

Thought: I need to find the capital of France first.
Action: search("capital of France")
Observation: Paris is the capital of France.

Thought: Now I need to find the population of Paris.
Action: search("population of Paris")
Observation: Paris has a population of about 2.1 million.

Thought: I have the answer now.
Answer: The population of Paris (capital of France) is about 2.1 million.
```

```python
def react_agent(question: str, tools: dict, llm) -> str:
    context = f"Question: {question}\n"
    
    for i in range(max_iterations):
        # Get next thought/action from LLM
        response = llm.invoke(REACT_PROMPT.format(context=context))
        
        if "Answer:" in response:
            return extract_answer(response)
        
        # Parse and execute action
        action, action_input = parse_action(response)
        observation = tools[action](action_input)
        
        # Add to context
        context += f"""
Thought: {extract_thought(response)}
Action: {action}({action_input})
Observation: {observation}
"""
    
    return "Could not find answer"
```

### 2. Plan-and-Execute Agent
Creates full plan before execution.

```
Task: Create a blog post about AI trends and publish it

Plan:
1. Research current AI trends
2. Outline blog structure
3. Write introduction
4. Write main sections
5. Add conclusion
6. Review and edit
7. Publish to blog

Execution:
[Step 1] Searching for AI trends 2024...
[Step 2] Creating outline...
...
[Step 7] Publishing to blog... Done!
```

### 3. Multi-Agent Systems
Multiple specialized agents working together.

```
┌─────────────────────────────────────────────────────────┐
│                   ORCHESTRATOR AGENT                     │
│               (Coordinates and delegates)                │
└─────────────────────────┬───────────────────────────────┘
                          │
        ┌─────────────────┼─────────────────┐
        │                 │                 │
        ▼                 ▼                 ▼
┌───────────────┐ ┌───────────────┐ ┌───────────────┐
│   Researcher  │ │    Writer     │ │    Editor     │
│    Agent      │ │    Agent      │ │    Agent      │
│ (Gathers info)│ │(Creates draft)│ │(Reviews/fixes)│
└───────────────┘ └───────────────┘ └───────────────┘
```

---

## Building Agents with LangChain

### Simple ReAct Agent
```python
from langchain.agents import create_react_agent, AgentExecutor
from langchain.tools import Tool
from langchain_openai import ChatOpenAI

# Define tools
tools = [
    Tool(
        name="Search",
        func=search_function,
        description="Search the web for information"
    ),
    Tool(
        name="Calculator",
        func=calculate,
        description="Perform mathematical calculations"
    )
]

# Create agent
llm = ChatOpenAI(model="gpt-4o", temperature=0)
agent = create_react_agent(llm, tools, prompt)

# Create executor
agent_executor = AgentExecutor(
    agent=agent,
    tools=tools,
    verbose=True,
    max_iterations=10
)

# Run
result = agent_executor.invoke({"input": "What is 25 * 4 + 10?"})
```

### Agent with Memory
```python
from langchain.memory import ConversationBufferMemory

memory = ConversationBufferMemory(
    memory_key="chat_history",
    return_messages=True
)

agent_executor = AgentExecutor(
    agent=agent,
    tools=tools,
    memory=memory,  # Add memory
    verbose=True
)

# Agent remembers previous conversations
result1 = agent_executor.invoke({"input": "My name is John"})
result2 = agent_executor.invoke({"input": "What's my name?"})
# Output: "Your name is John"
```

---

## Agent Design Patterns

### 1. Tool Selection Pattern
Let agent choose from available tools.

```python
TOOL_SELECTION_PROMPT = """
You have access to these tools:
{tools}

To use a tool, respond with:
Tool: <tool_name>
Input: <tool_input>

Question: {question}
"""
```

### 2. Self-Reflection Pattern
Agent evaluates its own outputs.

```python
def self_reflect(response: str, llm) -> str:
    critique = llm.invoke(f"""
    Review this response and identify any issues:
    
    Response: {response}
    
    Issues found:
    """)
    
    if "no issues" in critique.lower():
        return response
    
    improved = llm.invoke(f"""
    Original: {response}
    Issues: {critique}
    
    Improved response:
    """)
    
    return improved
```

### 3. Human-in-the-Loop Pattern
Require approval for certain actions.

```python
def execute_with_approval(action: str, args: dict) -> str:
    sensitive_actions = ["delete", "send_email", "make_payment"]
    
    if action in sensitive_actions:
        print(f"Agent wants to: {action}({args})")
        approval = input("Approve? (y/n): ")
        if approval.lower() != 'y':
            return "Action cancelled by user"
    
    return execute_tool(action, args)
```

### 4. Fallback Pattern
Handle failures gracefully.

```python
def agent_with_fallback(query: str) -> str:
    try:
        return primary_agent.invoke(query)
    except ToolExecutionError as e:
        # Try alternative approach
        return fallback_agent.invoke(query)
    except MaxIterationsExceeded:
        # Ask for human help
        return "I couldn't complete this task. Here's what I tried..."
```

---

## Memory Systems

### Short-term Memory (Context)
Current conversation history.

```python
messages = [
    {"role": "user", "content": "Hello"},
    {"role": "assistant", "content": "Hi!"},
    {"role": "user", "content": "What did I just say?"}
]
```

### Long-term Memory (Vector Store)
Persistent storage for past interactions.

```python
from langchain.memory import VectorStoreRetrieverMemory

# Store important information
memory = VectorStoreRetrieverMemory(
    retriever=vectorstore.as_retriever(search_kwargs={"k": 5})
)

# Retrieve relevant past context
relevant_memories = memory.load_memory_variables(
    {"input": "What do you know about my preferences?"}
)
```

### Working Memory
Scratchpad for current task.

```python
class WorkingMemory:
    def __init__(self):
        self.scratchpad = []
        self.intermediate_results = {}
    
    def add_step(self, thought: str, action: str, result: str):
        self.scratchpad.append({
            "thought": thought,
            "action": action,
            "result": result
        })
    
    def get_context(self) -> str:
        return "\n".join([
            f"Step {i+1}: {s['thought']}"
            for i, s in enumerate(self.scratchpad)
        ])
```

---

## Evaluation

### Metrics
| Metric | Description |
|--------|-------------|
| **Task Completion** | Did agent achieve goal? |
| **Efficiency** | Number of steps/tool calls |
| **Accuracy** | Correctness of results |
| **Cost** | API calls and tokens used |

### Testing
```python
def test_agent(agent, test_cases: list) -> dict:
    results = []
    
    for test in test_cases:
        start_time = time.time()
        
        try:
            result = agent.invoke(test["input"])
            success = evaluate_result(result, test["expected"])
        except Exception as e:
            success = False
            result = str(e)
        
        results.append({
            "input": test["input"],
            "expected": test["expected"],
            "actual": result,
            "success": success,
            "time": time.time() - start_time
        })
    
    return {
        "success_rate": sum(r["success"] for r in results) / len(results),
        "avg_time": sum(r["time"] for r in results) / len(results),
        "details": results
    }
```

---

## Best Practices

### Do's ✅
- Define clear, bounded tasks
- Implement robust error handling
- Add human oversight for critical actions
- Log all agent decisions
- Set iteration limits
- Test extensively

### Don'ts ❌
- Give agents unlimited access
- Skip validation of tool outputs
- Ignore cost implications
- Deploy without monitoring
- Trust agent decisions blindly

---

## Interview Questions

**Q: What is an AI agent and how does it differ from a chatbot?**
> AI agent can take autonomous actions to achieve goals, using tools and making decisions over multiple steps. Chatbot is single turn Q&A. Agent = goal-oriented + tools + planning.

**Q: Explain the ReAct pattern.**
> ReAct (Reason + Act) interleaves reasoning with actions. Agent thinks about what to do, takes action, observes result, reflects, and continues until task complete.

**Q: How do you make agents safe?**
> Human-in-the-loop for sensitive actions, input validation, output filtering, rate limits, sandboxed execution, comprehensive logging, defined scope limits.

---

## Quick Reference

| Concept | Description |
|---------|-------------|
| **Agent** | Autonomous AI that takes actions |
| **ReAct** | Reason + Act pattern |
| **Tool** | Function agent can call |
| **Planning** | Breaking goal into steps |
| **Memory** | Context storage (short/long term) |
| **Orchestrator** | Agent that coordinates others |
| **Human-in-loop** | Human approval for actions |
