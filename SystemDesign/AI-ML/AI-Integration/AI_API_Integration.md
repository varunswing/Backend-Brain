# AI API Integration Guide

## Overview

How to integrate AI capabilities into your applications using various provider APIs.

---

## Provider Comparison

| Provider | Best For | Models | Pricing |
|----------|----------|--------|---------|
| **OpenAI** | General, code | GPT-4o, o1 | Pay-per-token |
| **Anthropic** | Long docs, safety | Claude 3.5 | Pay-per-token |
| **Google** | Multimodal | Gemini 1.5 | Pay-per-token |
| **Cohere** | Enterprise, RAG | Command R+ | Pay-per-token |
| **Local** | Privacy, cost | Llama, Mistral | Infrastructure |

---

## OpenAI Integration

### Setup
```bash
pip install openai
```

### Chat Completion
```python
from openai import OpenAI

client = OpenAI(api_key="your-key")  # or OPENAI_API_KEY env var

# Basic completion
response = client.chat.completions.create(
    model="gpt-4o",
    messages=[
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Explain REST APIs in 2 sentences."}
    ],
    temperature=0.7,
    max_tokens=500
)

print(response.choices[0].message.content)
```

### Streaming
```python
stream = client.chat.completions.create(
    model="gpt-4o",
    messages=[{"role": "user", "content": "Tell me a story"}],
    stream=True
)

for chunk in stream:
    if chunk.choices[0].delta.content:
        print(chunk.choices[0].delta.content, end="")
```

### Vision (Image Input)
```python
import base64

def encode_image(image_path):
    with open(image_path, "rb") as f:
        return base64.b64encode(f.read()).decode('utf-8')

response = client.chat.completions.create(
    model="gpt-4o",
    messages=[{
        "role": "user",
        "content": [
            {"type": "text", "text": "What's in this image?"},
            {"type": "image_url", "image_url": {
                "url": f"data:image/jpeg;base64,{encode_image('photo.jpg')}"
            }}
        ]
    }]
)
```

### Embeddings
```python
response = client.embeddings.create(
    model="text-embedding-3-small",
    input="Your text here"
)
embedding = response.data[0].embedding  # List of 1536 floats
```

---

## Anthropic (Claude) Integration

### Setup
```bash
pip install anthropic
```

### Chat Completion
```python
import anthropic

client = anthropic.Anthropic(api_key="your-key")

response = client.messages.create(
    model="claude-3-5-sonnet-20241022",
    max_tokens=1024,
    system="You are a helpful assistant.",  # System prompt separate
    messages=[
        {"role": "user", "content": "Explain REST APIs in 2 sentences."}
    ]
)

print(response.content[0].text)
```

### Streaming
```python
with client.messages.stream(
    model="claude-3-5-sonnet-20241022",
    max_tokens=1024,
    messages=[{"role": "user", "content": "Tell me a story"}]
) as stream:
    for text in stream.text_stream:
        print(text, end="")
```

### Vision
```python
import base64

with open("image.png", "rb") as f:
    image_data = base64.standard_b64encode(f.read()).decode("utf-8")

response = client.messages.create(
    model="claude-3-5-sonnet-20241022",
    max_tokens=1024,
    messages=[{
        "role": "user",
        "content": [
            {
                "type": "image",
                "source": {
                    "type": "base64",
                    "media_type": "image/png",
                    "data": image_data
                }
            },
            {"type": "text", "text": "What's in this image?"}
        ]
    }]
)
```

---

## Google Gemini Integration

### Setup
```bash
pip install google-generativeai
```

### Chat Completion
```python
import google.generativeai as genai

genai.configure(api_key="your-key")
model = genai.GenerativeModel('gemini-1.5-pro')

response = model.generate_content("Explain REST APIs")
print(response.text)

# With system instruction
model = genai.GenerativeModel(
    'gemini-1.5-pro',
    system_instruction="You are a helpful coding assistant."
)
```

### Streaming
```python
response = model.generate_content("Tell me a story", stream=True)
for chunk in response:
    print(chunk.text, end="")
```

---

## Local LLMs (Ollama)

### Setup
```bash
# Install Ollama
curl -fsSL https://ollama.ai/install.sh | sh

# Pull a model
ollama pull llama3.1

# Python client
pip install ollama
```

### Usage
```python
import ollama

# Generate
response = ollama.generate(
    model='llama3.1',
    prompt='Explain REST APIs'
)
print(response['response'])

# Chat
response = ollama.chat(
    model='llama3.1',
    messages=[
        {"role": "user", "content": "Explain REST APIs"}
    ]
)
print(response['message']['content'])
```

### OpenAI-Compatible API
```python
# Ollama exposes OpenAI-compatible endpoint
from openai import OpenAI

client = OpenAI(
    base_url="http://localhost:11434/v1",
    api_key="ollama"  # Not used but required
)

response = client.chat.completions.create(
    model="llama3.1",
    messages=[{"role": "user", "content": "Hello"}]
)
```

---

## Error Handling

### Retry Pattern
```python
import time
from openai import RateLimitError, APITimeoutError

def call_with_retry(func, max_retries=3, base_delay=1):
    for attempt in range(max_retries):
        try:
            return func()
        except RateLimitError:
            delay = base_delay * (2 ** attempt)  # Exponential backoff
            time.sleep(delay)
        except APITimeoutError:
            if attempt == max_retries - 1:
                raise
            time.sleep(base_delay)
    raise Exception("Max retries exceeded")

# Usage
response = call_with_retry(
    lambda: client.chat.completions.create(...)
)
```

### Comprehensive Error Handling
```python
from openai import (
    APIConnectionError,
    RateLimitError,
    APIStatusError,
    AuthenticationError
)

try:
    response = client.chat.completions.create(...)
except AuthenticationError:
    print("Invalid API key")
except RateLimitError:
    print("Rate limit exceeded - wait and retry")
except APIConnectionError:
    print("Network error - check connection")
except APIStatusError as e:
    print(f"API error: {e.status_code} - {e.message}")
```

---

## Cost Optimization

### 1. Use Appropriate Models
```python
# Cheap: Simple tasks
simple_response = client.chat.completions.create(
    model="gpt-4o-mini",  # Cheaper
    messages=[{"role": "user", "content": "Summarize this"}]
)

# Expensive: Complex reasoning
complex_response = client.chat.completions.create(
    model="gpt-4o",  # More capable
    messages=[{"role": "user", "content": "Debug this complex issue"}]
)
```

### 2. Limit Output Tokens
```python
response = client.chat.completions.create(
    model="gpt-4o",
    messages=[...],
    max_tokens=200  # Limit response length
)
```

### 3. Cache Responses
```python
import hashlib
from functools import lru_cache

def get_cache_key(messages: list) -> str:
    return hashlib.md5(str(messages).encode()).hexdigest()

# Simple in-memory cache
response_cache = {}

def cached_completion(messages: list):
    key = get_cache_key(messages)
    if key in response_cache:
        return response_cache[key]
    
    response = client.chat.completions.create(
        model="gpt-4o",
        messages=messages
    )
    response_cache[key] = response
    return response
```

### 4. Batch Requests
```python
# Instead of multiple calls
texts = ["text1", "text2", "text3"]

# Single batch call for embeddings
response = client.embeddings.create(
    model="text-embedding-3-small",
    input=texts  # Pass all at once
)
embeddings = [e.embedding for e in response.data]
```

---

## Production Patterns

### 1. Rate Limiting
```python
import time
from threading import Lock

class RateLimiter:
    def __init__(self, requests_per_minute: int):
        self.rpm = requests_per_minute
        self.requests = []
        self.lock = Lock()
    
    def wait_if_needed(self):
        with self.lock:
            now = time.time()
            self.requests = [r for r in self.requests if now - r < 60]
            
            if len(self.requests) >= self.rpm:
                sleep_time = 60 - (now - self.requests[0])
                time.sleep(sleep_time)
            
            self.requests.append(time.time())

limiter = RateLimiter(requests_per_minute=60)

def rate_limited_call():
    limiter.wait_if_needed()
    return client.chat.completions.create(...)
```

### 2. Request Timeout
```python
from openai import OpenAI

client = OpenAI(
    timeout=30.0,  # 30 second timeout
)

# Or per-request
response = client.chat.completions.create(
    model="gpt-4o",
    messages=[...],
    timeout=60.0
)
```

### 3. Logging
```python
import logging

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

def logged_completion(messages):
    logger.info(f"Request: {len(str(messages))} chars")
    
    response = client.chat.completions.create(
        model="gpt-4o",
        messages=messages
    )
    
    logger.info(f"Response: {response.usage.total_tokens} tokens")
    logger.info(f"Cost: ${calculate_cost(response.usage):.4f}")
    
    return response
```

---

## Interview Questions

**Q: How do you handle rate limits in production?**
> Exponential backoff with jitter, request queuing, rate limiter implementation, fallback to alternative models, caching frequent queries.

**Q: OpenAI vs Anthropic - when to use which?**
> OpenAI: general tasks, good tool calling, widespread support. Anthropic: longer context, better for sensitive content, stronger reasoning. Choose based on: context needs, safety requirements, cost.

**Q: How do you optimize AI API costs?**
> Use smaller models for simple tasks, limit max_tokens, cache responses, batch requests, implement prompt caching, monitor and alert on spending.

---

## Quick Reference

| Task | OpenAI | Anthropic | Gemini |
|------|--------|-----------|--------|
| Chat | `chat.completions.create()` | `messages.create()` | `generate_content()` |
| Stream | `stream=True` | `.stream()` | `stream=True` |
| Embed | `embeddings.create()` | (Use Cohere/Voyage) | (Use Gecko) |
| Vision | `gpt-4o` + image | `claude-3` + image | `gemini-pro-vision` |
