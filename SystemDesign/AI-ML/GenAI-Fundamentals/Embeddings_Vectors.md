# Embeddings & Vector Search

## What are Embeddings?

**Embeddings** are numerical representations (vectors) of text that capture semantic meaning. Similar meanings = similar vectors.

```
Text → [Embedding Model] → Vector (array of numbers)

"King"    → [0.2, -0.5, 0.8, ..., 0.1]  (1536 dimensions)
"Queen"   → [0.3, -0.4, 0.7, ..., 0.2]  (similar to King!)
"Apple"   → [0.9, 0.1, -0.3, ..., 0.5]  (very different)
```

---

## Why Embeddings Matter

### Semantic Search
Find content by meaning, not just keywords.

```
Query: "How to fix memory leaks in Java"

Keyword Search (bad):
✗ Misses: "Preventing OutOfMemoryError in JVM applications"

Semantic Search (good):
✓ Finds: "Preventing OutOfMemoryError in JVM applications"
✓ Finds: "Java heap space optimization techniques"
✓ Finds: "Garbage collection tuning guide"
```

### Use Cases
- **Document search** - Find relevant documents
- **Recommendations** - Similar products/content
- **Clustering** - Group similar items
- **RAG** - Retrieve context for LLMs
- **Deduplication** - Find similar/duplicate content

---

## How Embeddings Work

### 1. Generate Embedding
```python
from openai import OpenAI

client = OpenAI()

response = client.embeddings.create(
    model="text-embedding-3-small",
    input="How to build a REST API"
)

embedding = response.data[0].embedding
# Returns: [-0.012, 0.034, -0.056, ...] (1536 numbers)
```

### 2. Store in Vector Database
```python
# Store with metadata
vector_db.upsert(
    id="doc_123",
    vector=embedding,
    metadata={
        "title": "REST API Guide",
        "content": "How to build a REST API...",
        "category": "backend"
    }
)
```

### 3. Search by Similarity
```python
# Generate query embedding
query_embedding = get_embedding("API design best practices")

# Find similar documents
results = vector_db.query(
    vector=query_embedding,
    top_k=5,
    filter={"category": "backend"}
)

# Returns top 5 most similar documents
```

---

## Similarity Metrics

### Cosine Similarity (Most Common)
Measures angle between vectors. Range: -1 to 1.

```
cos(A, B) = (A · B) / (||A|| × ||B||)

1.0  = Identical
0.0  = Unrelated  
-1.0 = Opposite
```

### Euclidean Distance
Measures straight-line distance. Lower = more similar.

### Dot Product
For normalized vectors, same as cosine similarity.

---

## Embedding Models

### OpenAI
| Model | Dimensions | Best For |
|-------|------------|----------|
| text-embedding-3-small | 1536 | Cost-effective |
| text-embedding-3-large | 3072 | Higher accuracy |
| text-embedding-ada-002 | 1536 | Legacy |

### Open Source
| Model | Dimensions | Notes |
|-------|------------|-------|
| all-MiniLM-L6-v2 | 384 | Fast, lightweight |
| bge-large-en | 1024 | High quality |
| e5-large-v2 | 1024 | Good balance |

### Cohere
| Model | Dimensions | Notes |
|-------|------------|-------|
| embed-english-v3 | 1024 | Multilingual support |

---

## Vector Databases

### Comparison
| Database | Type | Best For |
|----------|------|----------|
| **Pinecone** | Managed | Production, ease of use |
| **Weaviate** | Self-hosted/Cloud | Hybrid search |
| **Chroma** | Embedded | Prototyping, local dev |
| **Qdrant** | Self-hosted | High performance |
| **Milvus** | Self-hosted | Large scale |
| **pgvector** | PostgreSQL extension | Existing Postgres |

### Pinecone Example
```python
import pinecone

# Initialize
pc = pinecone.Pinecone(api_key="your-key")
index = pc.Index("my-index")

# Upsert vectors
index.upsert(
    vectors=[
        {
            "id": "doc1",
            "values": [0.1, 0.2, ...],
            "metadata": {"title": "Doc 1"}
        }
    ],
    namespace="articles"
)

# Query
results = index.query(
    vector=[0.1, 0.2, ...],
    top_k=5,
    include_metadata=True,
    namespace="articles"
)
```

### Chroma Example (Local)
```python
import chromadb

# Create client
client = chromadb.Client()

# Create collection
collection = client.create_collection("documents")

# Add documents (auto-embeds!)
collection.add(
    documents=["Doc 1 content", "Doc 2 content"],
    ids=["doc1", "doc2"],
    metadatas=[{"type": "article"}, {"type": "blog"}]
)

# Query
results = collection.query(
    query_texts=["search query"],
    n_results=5
)
```

---

## Chunking Strategies

Large documents need to be split into chunks for embedding.

### Fixed Size Chunking
```python
def chunk_text(text, chunk_size=500, overlap=50):
    chunks = []
    start = 0
    while start < len(text):
        end = start + chunk_size
        chunks.append(text[start:end])
        start = end - overlap  # Overlap for context
    return chunks
```

### Semantic Chunking
Split by logical units (paragraphs, sections, sentences).

```python
from langchain.text_splitter import RecursiveCharacterTextSplitter

splitter = RecursiveCharacterTextSplitter(
    chunk_size=1000,
    chunk_overlap=200,
    separators=["\n\n", "\n", ".", " "]  # Priority order
)

chunks = splitter.split_text(document)
```

### Best Practices
| Content Type | Chunk Size | Overlap |
|--------------|------------|---------|
| Code | 500-1000 | 50-100 |
| Documentation | 1000-2000 | 200 |
| Articles | 500-1500 | 100-200 |
| Q&A pairs | Keep intact | N/A |

---

## Architecture Patterns

### Basic Semantic Search
```
User Query → Embedding Model → Vector DB Query → Results
```

### Hybrid Search (Keyword + Semantic)
```
User Query ─┬─→ Keyword Search (BM25) ─┬─→ Rerank → Results
            └─→ Semantic Search ───────┘
```

### RAG Pipeline
```
User Query → Embed → Vector Search → Top K Docs → LLM with Context → Answer
```

---

## Code Example: Complete Search Pipeline

```python
from openai import OpenAI
import chromadb

# Initialize
openai_client = OpenAI()
chroma_client = chromadb.Client()
collection = chroma_client.create_collection("knowledge_base")

# 1. Index documents
def index_document(doc_id: str, content: str, metadata: dict):
    # Generate embedding
    response = openai_client.embeddings.create(
        model="text-embedding-3-small",
        input=content
    )
    embedding = response.data[0].embedding
    
    # Store in vector DB
    collection.add(
        ids=[doc_id],
        embeddings=[embedding],
        documents=[content],
        metadatas=[metadata]
    )

# 2. Search
def search(query: str, top_k: int = 5) -> list:
    # Embed query
    response = openai_client.embeddings.create(
        model="text-embedding-3-small",
        input=query
    )
    query_embedding = response.data[0].embedding
    
    # Search
    results = collection.query(
        query_embeddings=[query_embedding],
        n_results=top_k,
        include=["documents", "metadatas", "distances"]
    )
    
    return results

# Usage
index_document("1", "REST API design principles...", {"type": "guide"})
index_document("2", "GraphQL vs REST comparison...", {"type": "article"})

results = search("How to design APIs")
```

---

## Interview Questions

**Q: What are embeddings and why are they useful?**
> Numerical vector representations of text that capture semantic meaning. Useful for: semantic search, recommendations, RAG, clustering. Similar meanings have similar vectors.

**Q: How would you choose chunk size for embeddings?**
> Balance between: too small (loses context) vs too large (dilutes relevance). Consider: content type, query patterns, model context limits. Typically 500-1500 characters with 10-20% overlap.

**Q: Cosine similarity vs Euclidean distance?**
> Cosine measures angle (direction), Euclidean measures magnitude. Cosine better for semantic similarity as it's scale-invariant. Use cosine for text, Euclidean for spatial data.

---

## Quick Reference

| Concept | Description |
|---------|-------------|
| **Embedding** | Vector representation of text |
| **Dimensions** | Size of vector (e.g., 1536) |
| **Cosine Similarity** | Measures semantic similarity |
| **Chunking** | Splitting docs for embedding |
| **Top-K** | Number of results to return |
| **Hybrid Search** | Keyword + semantic combined |
