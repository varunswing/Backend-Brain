# AI Tools & Frameworks Ecosystem

## Overview

A guide to the key tools, frameworks, and platforms in the AI ecosystem for developers.

---

## Development Tools

### AI-Powered IDEs

| Tool | Best For | Key Features |
|------|----------|--------------|
| **Cursor** | Full AI IDE | Chat, code completion, agent |
| **GitHub Copilot** | VS Code/JetBrains | Inline suggestions |
| **Codeium** | Free alternative | Multiple IDE support |
| **Tabnine** | Privacy-focused | On-prem option |

### Tips for Using AI Coding Assistants
1. **Write clear comments** - AI uses them as context
2. **Use descriptive names** - Better suggestions
3. **Provide examples** - In comments or test files
4. **Review carefully** - AI can introduce bugs
5. **Iterate prompts** - Refine for better results

---

## LLM Frameworks

### LangChain
Most popular framework for building LLM applications.

```python
# Core concepts
from langchain.chat_models import ChatOpenAI
from langchain.chains import LLMChain
from langchain.prompts import ChatPromptTemplate

# Basic chain
llm = ChatOpenAI(model="gpt-4o")
prompt = ChatPromptTemplate.from_template("Explain {topic}")
chain = prompt | llm
result = chain.invoke({"topic": "REST APIs"})

# With memory
from langchain.memory import ConversationBufferMemory
memory = ConversationBufferMemory()

# With tools
from langchain.agents import create_react_agent
agent = create_react_agent(llm, tools, prompt)
```

**Use for**: RAG, agents, chains, complex workflows

### LlamaIndex
Specialized for connecting LLMs to data.

```python
from llama_index.core import VectorStoreIndex, SimpleDirectoryReader

# Load and index documents
documents = SimpleDirectoryReader("./data").load_data()
index = VectorStoreIndex.from_documents(documents)

# Query
query_engine = index.as_query_engine()
response = query_engine.query("What is the main topic?")
```

**Use for**: Document Q&A, knowledge bases, data indexing

### Comparison

| Feature | LangChain | LlamaIndex |
|---------|-----------|------------|
| Focus | General LLM apps | Data/RAG |
| Flexibility | High | Medium |
| Learning curve | Steep | Moderate |
| Documentation | Extensive | Good |
| Best for | Agents, complex chains | Document Q&A |

---

## Vector Database Clients

### Quick Setup Comparison

```python
# Chroma (Local/Embedded)
import chromadb
client = chromadb.Client()
collection = client.create_collection("docs")

# Pinecone (Cloud)
from pinecone import Pinecone
pc = Pinecone(api_key="key")
index = pc.Index("my-index")

# Weaviate (Cloud/Self-hosted)
import weaviate
client = weaviate.connect_to_wcs(cluster_url="url", auth_credentials=auth)

# pgvector (PostgreSQL)
# Just add extension to existing Postgres
```

---

## Local LLM Tools

### Ollama
Run LLMs locally with simple commands.

```bash
# Install
curl -fsSL https://ollama.ai/install.sh | sh

# Run models
ollama run llama3.1         # Chat interface
ollama run codellama        # Code-focused
ollama run mistral          # Fast and capable

# Pull models
ollama pull llama3.1:70b    # Larger model
ollama pull nomic-embed-text # Embeddings
```

```python
import ollama

# Generate
response = ollama.generate(model='llama3.1', prompt='Hello')

# Chat
response = ollama.chat(model='llama3.1', messages=[
    {'role': 'user', 'content': 'Hello'}
])

# Embeddings
response = ollama.embeddings(model='nomic-embed-text', prompt='Hello')
```

### LM Studio
GUI application for running local models.
- Download and run models with UI
- OpenAI-compatible API server
- Easy model comparison

### vLLM
High-performance inference server.

```bash
pip install vllm

# Serve model
python -m vllm.entrypoints.openai.api_server \
    --model meta-llama/Llama-3.1-8B-Instruct
```

---

## Prompt Management

### LangSmith
Observability and testing for LLM apps.

```python
from langsmith import traceable

@traceable
def my_llm_function(input_text):
    return llm.invoke(input_text)
```

### Promptfoo
Prompt testing and evaluation.

```yaml
# promptfooconfig.yaml
providers:
  - openai:gpt-4o
  - anthropic:claude-3-5-sonnet

prompts:
  - "Summarize: {{text}}"
  - "TL;DR: {{text}}"

tests:
  - vars:
      text: "Long article..."
    assert:
      - type: contains
        value: "key point"
```

```bash
npx promptfoo eval
```

---

## Embedding Services

### OpenAI
```python
from openai import OpenAI
client = OpenAI()
response = client.embeddings.create(
    model="text-embedding-3-small",
    input="Your text"
)
```

### Cohere
```python
import cohere
co = cohere.Client("api-key")
response = co.embed(
    texts=["Your text"],
    model="embed-english-v3.0"
)
```

### Sentence Transformers (Local)
```python
from sentence_transformers import SentenceTransformer

model = SentenceTransformer('all-MiniLM-L6-v2')
embeddings = model.encode(["Your text"])
```

### Comparison

| Service | Dimensions | Speed | Cost |
|---------|------------|-------|------|
| OpenAI small | 1536 | Fast | $ |
| OpenAI large | 3072 | Fast | $$ |
| Cohere | 1024 | Fast | $ |
| all-MiniLM | 384 | Fast | Free |
| BGE-large | 1024 | Medium | Free |

---

## AI Observability

### Weights & Biases (W&B)
ML experiment tracking.

```python
import wandb

wandb.init(project="my-llm-app")
wandb.log({"prompt": prompt, "response": response, "latency": latency})
```

### Helicone
OpenAI proxy with analytics.

```python
from openai import OpenAI

client = OpenAI(
    base_url="https://oai.hconeai.com/v1",
    default_headers={"Helicone-Auth": "Bearer <key>"}
)
```

### Langfuse
Open-source LLM observability.

```python
from langfuse import Langfuse

langfuse = Langfuse()

trace = langfuse.trace(name="my-trace")
span = trace.span(name="llm-call")
# ... your LLM call
span.end()
```

---

## Deployment Platforms

### Serverless
- **Modal** - Python-first, easy GPU access
- **Replicate** - Run models via API
- **AWS Bedrock** - Managed foundation models
- **Google Vertex AI** - GCP's AI platform

### Self-hosted
- **vLLM** - High-performance serving
- **Text Generation Inference** - HuggingFace
- **Triton** - NVIDIA inference server

---

## AI Safety & Guardrails

### Guardrails AI
```python
from guardrails import Guard
from guardrails.validators import ValidRange

guard = Guard.from_string(
    validators=[ValidRange(min=0, max=100)]
)

result = guard.parse("The score is 85")
```

### NVIDIA NeMo Guardrails
```yaml
# config.yml
models:
  - type: main
    engine: openai
    model: gpt-4

rails:
  input:
    flows:
      - check jailbreak
  output:
    flows:
      - check output
```

---

## Quick Start Recommendations

### For Beginners
1. **Start with**: Cursor or Copilot for coding
2. **Learn**: Prompt engineering basics
3. **Try**: OpenAI API directly
4. **Prototype with**: Chroma + LangChain

### For Production Apps
1. **Vector DB**: Pinecone or Weaviate
2. **Framework**: LangChain or custom
3. **Observability**: LangSmith or Langfuse
4. **Testing**: Promptfoo + unit tests

### For Privacy/Cost-Sensitive
1. **Local LLM**: Ollama + Llama 3.1
2. **Embeddings**: Sentence Transformers
3. **Vector DB**: Chroma or pgvector
4. **Framework**: LlamaIndex

---

## Learning Resources

### Official Docs
- [LangChain](https://python.langchain.com)
- [LlamaIndex](https://docs.llamaindex.ai)
- [OpenAI Cookbook](https://cookbook.openai.com)
- [Anthropic Docs](https://docs.anthropic.com)

### Courses
- DeepLearning.AI - Prompt Engineering
- LangChain Academy
- HuggingFace NLP Course

### Communities
- r/LocalLLaMA (Reddit)
- LangChain Discord
- AI Twitter/X

---

## Interview Questions

**Q: What frameworks would you use for a RAG application?**
> LangChain or LlamaIndex for orchestration, vector DB (Pinecone for managed, Chroma for prototype), embedding model (OpenAI or open source). LlamaIndex is more specialized for data/RAG.

**Q: How do you run LLMs locally?**
> Ollama for easy setup, vLLM for production performance. Models: Llama 3.1, Mistral, CodeLlama. Consider: GPU memory (8GB+ for 7B models), quantization for smaller footprint.

**Q: What's important for LLM observability?**
> Track: prompts, responses, latency, token usage, costs, errors. Tools: LangSmith, Langfuse, W&B. Also: user feedback, A/B testing, quality metrics.
