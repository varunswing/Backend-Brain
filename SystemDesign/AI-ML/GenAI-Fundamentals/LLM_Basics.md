# LLM Fundamentals for Developers

## What is an LLM?

**Large Language Model (LLM)** - A neural network trained on massive text data to understand and generate human-like text.

```
Input (Prompt) → [LLM] → Output (Completion)
"Explain REST API" → [GPT-4] → "REST API is..."
```

---

## Key Concepts

### 1. Tokens
LLMs process text as **tokens**, not words.

```
"Hello world" → ["Hello", " world"] → 2 tokens
"API" → ["API"] → 1 token
"ChatGPT" → ["Chat", "G", "PT"] → 3 tokens

Rule of thumb: ~4 characters = 1 token
1000 tokens ≈ 750 words
```

**Why it matters:**
- Pricing is per token
- Context window limits tokens
- Long words = more tokens = higher cost

### 2. Context Window
Maximum tokens the model can process at once.

| Model | Context Window |
|-------|----------------|
| GPT-3.5 | 16K tokens |
| GPT-4 | 128K tokens |
| Claude 3 | 200K tokens |
| Gemini 1.5 | 1M tokens |

```
Context = System Prompt + User Messages + Assistant Responses

If context > window:
  - Oldest messages truncated
  - Or error returned
```

### 3. Temperature
Controls randomness/creativity of output.

```
Temperature = 0.0  → Deterministic, same output every time
Temperature = 0.7  → Balanced (default)
Temperature = 1.0+ → Creative, unpredictable

Use cases:
- Code generation: 0.0-0.2 (precise)
- Creative writing: 0.7-0.9 (varied)
- Factual Q&A: 0.0-0.3 (accurate)
```

### 4. Tokens Limits
```
max_tokens = Maximum tokens in response
             (NOT including prompt)

Example:
  Prompt: 500 tokens
  max_tokens: 1000
  Total possible: 1500 tokens
```

---

## How LLMs Work (Simplified)

### Transformer Architecture
```
Input Text
    ↓
[Tokenization] → Tokens
    ↓
[Embedding] → Vectors
    ↓
[Self-Attention] → Understanding context
    ↓
[Feed Forward] → Processing
    ↓
[Output Layer] → Probability distribution
    ↓
Next Token Prediction
```

### Key Insight: Next Token Prediction
LLMs predict the most likely next token, repeatedly.

```
"The capital of France is" → "Paris" (highest probability)
                          → "a" (lower probability)
                          → "beautiful" (even lower)
```

---

## LLM Capabilities & Limitations

### ✅ What LLMs Are Good At
- Text generation and summarization
- Translation and rephrasing
- Code generation and explanation
- Question answering (with context)
- Creative writing
- Format conversion (JSON, XML, etc.)

### ❌ What LLMs Struggle With
- **Math**: Often wrong on complex calculations
- **Real-time info**: Knowledge cutoff date
- **Factual accuracy**: Can hallucinate
- **Consistency**: May contradict itself
- **Long context**: May "forget" early info
- **Reasoning**: Complex multi-step logic

---

## Model Comparison

### OpenAI Models
| Model | Best For | Speed | Cost |
|-------|----------|-------|------|
| GPT-4o | Complex tasks | Fast | $$$ |
| GPT-4o-mini | Good balance | Fast | $ |
| GPT-4 Turbo | Long context | Medium | $$ |
| o1 | Reasoning | Slow | $$$$ |

### Anthropic Models
| Model | Best For | Context |
|-------|----------|---------|
| Claude 3.5 Sonnet | Coding, analysis | 200K |
| Claude 3 Opus | Complex reasoning | 200K |
| Claude 3 Haiku | Fast, cheap | 200K |

### Open Source
| Model | Size | Use Case |
|-------|------|----------|
| Llama 3.1 | 8B-405B | General purpose |
| Mistral | 7B-8x22B | Fast inference |
| CodeLlama | 7B-70B | Code generation |

---

## API Basics

### OpenAI API
```python
from openai import OpenAI

client = OpenAI(api_key="your-key")

response = client.chat.completions.create(
    model="gpt-4o",
    messages=[
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Explain REST APIs"}
    ],
    temperature=0.7,
    max_tokens=500
)

print(response.choices[0].message.content)
```

### Anthropic API
```python
import anthropic

client = anthropic.Anthropic(api_key="your-key")

response = client.messages.create(
    model="claude-3-5-sonnet-20241022",
    max_tokens=1024,
    messages=[
        {"role": "user", "content": "Explain REST APIs"}
    ]
)

print(response.content[0].text)
```

---

## Cost Optimization

### Strategies
1. **Use smaller models** for simple tasks
2. **Limit max_tokens** when possible
3. **Cache responses** for repeated queries
4. **Batch requests** when applicable
5. **Use streaming** for better UX (same cost)

### Pricing Example (GPT-4o)
```
Input: $2.50 / 1M tokens
Output: $10.00 / 1M tokens

For 1000 requests (500 tokens each):
Input cost: 500K tokens × $2.50/1M = $1.25
Output cost: 500K tokens × $10/1M = $5.00
Total: $6.25 for 1000 requests
```

---

## Interview Questions

**Q: What is the difference between tokens and words?**
> Tokens are the fundamental units LLMs process. Words may be split into multiple tokens. "ChatGPT" = 3 tokens. This affects cost and context limits.

**Q: Why do LLMs hallucinate?**
> LLMs predict statistically likely tokens, not factual truth. They have no real-world knowledge verification. Solutions: RAG, fact-checking, grounding.

**Q: When would you choose GPT-4 vs Claude vs open source?**
> GPT-4 for general tasks, Claude for long documents/coding, open source for privacy/cost control. Consider cost, latency, and specific capabilities.

---

## Quick Reference

| Concept | Definition |
|---------|------------|
| **Token** | Basic unit of text processing |
| **Context Window** | Max tokens model can handle |
| **Temperature** | Randomness control (0-1+) |
| **System Prompt** | Instructions for model behavior |
| **Completion** | Model's generated response |
| **Hallucination** | Confidently wrong output |
| **Grounding** | Connecting to real data |
