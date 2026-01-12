# Vector Databases Guide

## What is a Vector Database?

A specialized database optimized for storing and querying high-dimensional vectors (embeddings).

```
Traditional DB:           Vector DB:
┌──────────────┐          ┌─────────────────────────┐
│ id │ text    │          │ id │ text   │ vector    │
├────┼─────────┤          ├────┼────────┼───────────┤
│ 1  │ "hello" │          │ 1  │"hello" │[0.1,0.2..]│
│ 2  │ "world" │          │ 2  │"world" │[0.3,0.1..]│
└────┴─────────┘          └────┴────────┴───────────┘

Query: WHERE text = "hello"   Query: Find similar to [0.1,0.2..]
Result: Exact match only      Result: Semantically similar items
```

---

## Vector Database Comparison

| Database | Type | Best For | Scalability |
|----------|------|----------|-------------|
| **Pinecone** | Managed | Production, ease | Excellent |
| **Weaviate** | Self-hosted/Cloud | Hybrid search | Excellent |
| **Chroma** | Embedded | Prototyping | Limited |
| **Qdrant** | Self-hosted | Performance | Excellent |
| **Milvus** | Self-hosted | Large scale | Excellent |
| **pgvector** | PostgreSQL ext | Existing Postgres | Good |
| **Redis** | In-memory | Low latency | Good |

### Decision Matrix

| Need | Recommended |
|------|-------------|
| Quick prototype | Chroma |
| Already using Postgres | pgvector |
| Production + managed | Pinecone |
| Self-hosted + features | Weaviate |
| Maximum performance | Qdrant |
| Massive scale (billions) | Milvus |

---

## Pinecone

### Setup
```bash
pip install pinecone-client
```

### Usage
```python
from pinecone import Pinecone, ServerlessSpec

# Initialize
pc = Pinecone(api_key="your-key")

# Create index
pc.create_index(
    name="my-index",
    dimension=1536,  # Must match embedding size
    metric="cosine",  # or "euclidean", "dotproduct"
    spec=ServerlessSpec(
        cloud="aws",
        region="us-east-1"
    )
)

# Connect to index
index = pc.Index("my-index")

# Upsert vectors
index.upsert(
    vectors=[
        {
            "id": "doc1",
            "values": [0.1, 0.2, ...],  # 1536 dimensions
            "metadata": {"title": "Document 1", "category": "tech"}
        },
        {
            "id": "doc2",
            "values": [0.3, 0.1, ...],
            "metadata": {"title": "Document 2", "category": "science"}
        }
    ],
    namespace="articles"
)

# Query
results = index.query(
    vector=[0.1, 0.2, ...],
    top_k=5,
    include_metadata=True,
    filter={"category": {"$eq": "tech"}},
    namespace="articles"
)

for match in results.matches:
    print(f"ID: {match.id}, Score: {match.score}")
    print(f"Metadata: {match.metadata}")
```

### Metadata Filtering
```python
# Supported operators: $eq, $ne, $gt, $gte, $lt, $lte, $in, $nin

# Exact match
filter={"category": {"$eq": "tech"}}

# Range
filter={"price": {"$gte": 10, "$lte": 100}}

# In list
filter={"tags": {"$in": ["python", "ai"]}}

# AND (implicit)
filter={
    "category": {"$eq": "tech"},
    "price": {"$lt": 50}
}

# OR
filter={
    "$or": [
        {"category": {"$eq": "tech"}},
        {"category": {"$eq": "science"}}
    ]
}
```

---

## Chroma

### Setup
```bash
pip install chromadb
```

### Usage
```python
import chromadb

# In-memory client
client = chromadb.Client()

# Persistent client
client = chromadb.PersistentClient(path="./chroma_db")

# Create collection
collection = client.create_collection(
    name="my_collection",
    metadata={"hnsw:space": "cosine"}  # Distance metric
)

# Add documents (auto-generates embeddings!)
collection.add(
    documents=["Document 1 text", "Document 2 text"],
    metadatas=[{"source": "web"}, {"source": "pdf"}],
    ids=["doc1", "doc2"]
)

# Or add with pre-computed embeddings
collection.add(
    embeddings=[[0.1, 0.2, ...], [0.3, 0.1, ...]],
    metadatas=[{"source": "web"}, {"source": "pdf"}],
    ids=["doc1", "doc2"]
)

# Query by text (auto-embeds query)
results = collection.query(
    query_texts=["search query"],
    n_results=5,
    where={"source": "web"},
    include=["documents", "metadatas", "distances"]
)

# Query by embedding
results = collection.query(
    query_embeddings=[[0.1, 0.2, ...]],
    n_results=5
)
```

### Custom Embedding Function
```python
from chromadb.utils import embedding_functions

# OpenAI embeddings
openai_ef = embedding_functions.OpenAIEmbeddingFunction(
    api_key="your-key",
    model_name="text-embedding-3-small"
)

collection = client.create_collection(
    name="openai_collection",
    embedding_function=openai_ef
)
```

---

## pgvector (PostgreSQL)

### Setup
```sql
-- Enable extension
CREATE EXTENSION vector;

-- Create table with vector column
CREATE TABLE documents (
    id SERIAL PRIMARY KEY,
    content TEXT,
    embedding vector(1536)  -- Dimension must match
);

-- Create index for fast search
CREATE INDEX ON documents USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);
```

### Python Usage
```python
import psycopg2
from pgvector.psycopg2 import register_vector

conn = psycopg2.connect("postgresql://...")
register_vector(conn)

cur = conn.cursor()

# Insert
embedding = [0.1, 0.2, ...]  # 1536 dimensions
cur.execute(
    "INSERT INTO documents (content, embedding) VALUES (%s, %s)",
    ("Document text", embedding)
)

# Query (cosine similarity)
query_embedding = [0.1, 0.2, ...]
cur.execute("""
    SELECT content, 1 - (embedding <=> %s) AS similarity
    FROM documents
    ORDER BY embedding <=> %s
    LIMIT 5
""", (query_embedding, query_embedding))

results = cur.fetchall()
```

### Distance Operators
```sql
<->   -- L2 distance (Euclidean)
<#>   -- Inner product (negative)
<=>   -- Cosine distance

-- Examples
ORDER BY embedding <-> query_embedding    -- Closest by L2
ORDER BY embedding <=> query_embedding    -- Most similar by cosine
```

---

## Weaviate

### Setup
```bash
pip install weaviate-client
```

### Usage
```python
import weaviate

# Connect to Weaviate Cloud
client = weaviate.connect_to_wcs(
    cluster_url="your-cluster-url",
    auth_credentials=weaviate.auth.AuthApiKey("your-key")
)

# Or local
client = weaviate.connect_to_local()

# Create collection
collection = client.collections.create(
    name="Document",
    vectorizer_config=weaviate.Configure.Vectorizer.text2vec_openai(),
    properties=[
        weaviate.Property(name="content", data_type=weaviate.DataType.TEXT),
        weaviate.Property(name="category", data_type=weaviate.DataType.TEXT)
    ]
)

# Insert (auto-vectorizes)
collection.data.insert(
    properties={"content": "Document text", "category": "tech"}
)

# Query
response = collection.query.near_text(
    query="search term",
    limit=5,
    filters=weaviate.Filter.by_property("category").equal("tech")
)

for obj in response.objects:
    print(obj.properties)
```

### Hybrid Search
```python
# Combines keyword (BM25) and vector search
response = collection.query.hybrid(
    query="search term",
    alpha=0.5,  # 0 = keyword only, 1 = vector only
    limit=5
)
```

---

## Index Types

### HNSW (Hierarchical Navigable Small World)
- **Best for**: Most use cases
- **Pros**: Fast queries, good recall
- **Cons**: Memory-intensive

### IVF (Inverted File Index)
- **Best for**: Large datasets, memory constraints
- **Pros**: Lower memory, fast build
- **Cons**: Lower recall than HNSW

### Flat (Brute Force)
- **Best for**: Small datasets (<10K vectors)
- **Pros**: 100% recall, no tuning
- **Cons**: Slow for large datasets

---

## Distance Metrics

| Metric | Use Case | Range |
|--------|----------|-------|
| **Cosine** | Text embeddings | 0 to 2 |
| **Euclidean (L2)** | Spatial data | 0 to ∞ |
| **Dot Product** | Normalized vectors | -∞ to ∞ |

```python
import numpy as np

def cosine_similarity(a, b):
    return np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b))

def euclidean_distance(a, b):
    return np.linalg.norm(a - b)

def dot_product(a, b):
    return np.dot(a, b)
```

---

## Best Practices

### 1. Dimension Matching
```python
# Your vectors must match index dimension
index_dimension = 1536  # Defined at creation
embedding_dimension = 1536  # From your model

assert len(your_embedding) == index_dimension
```

### 2. Batch Operations
```python
# Bad: One at a time
for doc in documents:
    index.upsert([doc])

# Good: Batch
batch_size = 100
for i in range(0, len(documents), batch_size):
    batch = documents[i:i+batch_size]
    index.upsert(batch)
```

### 3. Metadata Strategy
```python
# Store searchable metadata
metadata = {
    "title": "Document Title",
    "category": "tech",
    "date": "2024-01-15",
    "source": "url"
}

# Don't store large text in metadata - use IDs to reference
# Bad
metadata = {"full_content": "very long text..."}

# Good
metadata = {"content_id": "doc123"}  # Look up full content separately
```

### 4. Namespace/Collection Organization
```python
# Separate by use case
index.upsert(vectors, namespace="user_documents")
index.upsert(vectors, namespace="product_catalog")

# Query specific namespace
results = index.query(vector, namespace="user_documents")
```

---

## Interview Questions

**Q: When would you use pgvector vs Pinecone?**
> pgvector: already using Postgres, want to keep stack simple, moderate scale (<10M vectors). Pinecone: managed service, high scale, don't want ops burden, need advanced features.

**Q: How do you choose between HNSW and IVF indexes?**
> HNSW: prioritize query speed and recall, have enough memory. IVF: memory constraints, very large datasets, can accept slightly lower recall. HNSW is default choice for most.

**Q: How do you evaluate vector search quality?**
> Recall@K (% relevant docs in top K), Precision@K, NDCG. Also latency (p50, p99), throughput, and business metrics like user satisfaction.

---

## Quick Reference

| Operation | Pinecone | Chroma | pgvector |
|-----------|----------|--------|----------|
| Insert | `upsert()` | `add()` | `INSERT` |
| Query | `query()` | `query()` | `SELECT ORDER BY <=>` |
| Filter | `filter={}` | `where={}` | `WHERE` clause |
| Delete | `delete()` | `delete()` | `DELETE` |
