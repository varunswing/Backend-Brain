# RAG (Retrieval Augmented Generation)

## What is RAG?

**RAG** combines retrieval systems with LLMs to provide accurate, up-to-date, and source-grounded responses.

```
Traditional LLM:
  Query → LLM → Response (may hallucinate)

RAG:
  Query → Retrieve Relevant Docs → LLM + Context → Grounded Response
```

---

## Why RAG?

| Problem | RAG Solution |
|---------|--------------|
| LLM knowledge cutoff | Retrieve current information |
| Hallucinations | Ground responses in real data |
| No source citation | Include references |
| Generic answers | Domain-specific context |
| Privacy concerns | Keep data in your control |

### RAG vs Fine-tuning

| Aspect | RAG | Fine-tuning |
|--------|-----|-------------|
| **Setup time** | Hours | Days/weeks |
| **Cost** | Low | High |
| **Updates** | Add new docs anytime | Retrain model |
| **Best for** | Facts, current info | Style, behavior |
| **Hallucination** | Reduced | May still occur |

---

## RAG Architecture

### Basic Pipeline
```
┌─────────────────────────────────────────────────────────────┐
│                        INDEXING PHASE                        │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│   Documents → Chunking → Embedding → Vector Database         │
│                                                              │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                        QUERY PHASE                           │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│   User Query → Embed → Vector Search → Top K Chunks          │
│                                           │                  │
│                                           ▼                  │
│                              Prompt + Context → LLM → Answer │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## Implementation Steps

### Step 1: Document Ingestion
```python
from langchain.document_loaders import (
    PyPDFLoader,
    TextLoader,
    WebBaseLoader
)
from langchain.text_splitter import RecursiveCharacterTextSplitter

# Load documents
pdf_loader = PyPDFLoader("document.pdf")
documents = pdf_loader.load()

# Split into chunks
splitter = RecursiveCharacterTextSplitter(
    chunk_size=1000,
    chunk_overlap=200
)
chunks = splitter.split_documents(documents)
```

### Step 2: Create Embeddings & Store
```python
from langchain.embeddings import OpenAIEmbeddings
from langchain.vectorstores import Chroma

# Initialize embedding model
embeddings = OpenAIEmbeddings(model="text-embedding-3-small")

# Create vector store
vectorstore = Chroma.from_documents(
    documents=chunks,
    embedding=embeddings,
    persist_directory="./chroma_db"
)
```

### Step 3: Retrieval
```python
# Create retriever
retriever = vectorstore.as_retriever(
    search_type="similarity",  # or "mmr" for diversity
    search_kwargs={"k": 5}
)

# Retrieve relevant documents
relevant_docs = retriever.get_relevant_documents("How do I deploy to AWS?")
```

### Step 4: Generate Response
```python
from langchain.chat_models import ChatOpenAI
from langchain.chains import RetrievalQA

# Create QA chain
llm = ChatOpenAI(model="gpt-4o", temperature=0)

qa_chain = RetrievalQA.from_chain_type(
    llm=llm,
    chain_type="stuff",  # or "map_reduce" for large contexts
    retriever=retriever,
    return_source_documents=True
)

# Query
result = qa_chain({"query": "How do I deploy to AWS?"})
print(result["result"])
print(result["source_documents"])
```

---

## Advanced RAG Patterns

### 1. Hybrid Search
Combine keyword and semantic search.

```python
from langchain.retrievers import BM25Retriever, EnsembleRetriever

# Keyword retriever
bm25_retriever = BM25Retriever.from_documents(chunks)

# Semantic retriever
semantic_retriever = vectorstore.as_retriever()

# Combine with weights
ensemble_retriever = EnsembleRetriever(
    retrievers=[bm25_retriever, semantic_retriever],
    weights=[0.3, 0.7]  # 30% keyword, 70% semantic
)
```

### 2. Query Transformation
Improve retrieval by rewriting queries.

```python
# HyDE: Hypothetical Document Embedding
def hyde_transform(query: str, llm) -> str:
    prompt = f"""Write a short document that would answer this question:
    Question: {query}
    Document:"""
    
    hypothetical_doc = llm.invoke(prompt)
    return hypothetical_doc  # Embed this instead of query

# Multi-query: Generate multiple search queries
def multi_query(query: str, llm) -> list:
    prompt = f"""Generate 3 different versions of this question
    for better search results:
    Original: {query}
    Alternatives:"""
    
    alternatives = llm.invoke(prompt)
    return [query] + alternatives.split("\n")
```

### 3. Reranking
Re-score retrieved documents for relevance.

```python
from sentence_transformers import CrossEncoder

reranker = CrossEncoder('cross-encoder/ms-marco-MiniLM-L-6-v2')

def rerank(query: str, documents: list, top_k: int = 3):
    # Score each document
    pairs = [(query, doc.page_content) for doc in documents]
    scores = reranker.predict(pairs)
    
    # Sort by score
    ranked = sorted(zip(documents, scores), key=lambda x: x[1], reverse=True)
    return [doc for doc, score in ranked[:top_k]]
```

### 4. Contextual Compression
Extract only relevant parts from retrieved documents.

```python
from langchain.retrievers import ContextualCompressionRetriever
from langchain.retrievers.document_compressors import LLMChainExtractor

compressor = LLMChainExtractor.from_llm(llm)
compression_retriever = ContextualCompressionRetriever(
    base_compressor=compressor,
    base_retriever=retriever
)
```

### 5. Self-RAG (Self-Reflective)
Let LLM decide if retrieval is needed.

```python
def self_rag(query: str, retriever, llm):
    # Step 1: Decide if retrieval needed
    needs_retrieval = llm.invoke(
        f"Does this question need external information? "
        f"Answer YES or NO: {query}"
    )
    
    if "YES" in needs_retrieval:
        # Step 2: Retrieve
        docs = retriever.get_relevant_documents(query)
        context = "\n".join([d.page_content for d in docs])
        
        # Step 3: Generate with context
        response = llm.invoke(
            f"Context: {context}\n\nQuestion: {query}"
        )
    else:
        # Direct answer
        response = llm.invoke(query)
    
    return response
```

---

## RAG Prompt Template

```python
RAG_PROMPT = """You are a helpful assistant. Answer the question based on 
the provided context. If the context doesn't contain the answer, say 
"I don't have information about that."

Context:
{context}

Question: {question}

Instructions:
- Only use information from the context
- Cite sources when possible (e.g., "According to [Source]...")
- Be concise but thorough
- If unsure, say so

Answer:"""
```

---

## Evaluation Metrics

### Retrieval Metrics
| Metric | Description |
|--------|-------------|
| **Recall@K** | % of relevant docs in top K |
| **Precision@K** | % of top K that are relevant |
| **MRR** | Mean Reciprocal Rank |
| **NDCG** | Normalized Discounted Cumulative Gain |

### Generation Metrics
| Metric | Description |
|--------|-------------|
| **Faithfulness** | Does answer match context? |
| **Answer Relevancy** | Does answer address query? |
| **Context Relevancy** | Is retrieved context useful? |

### Evaluation with RAGAS
```python
from ragas import evaluate
from ragas.metrics import faithfulness, answer_relevancy, context_relevancy

result = evaluate(
    dataset,
    metrics=[faithfulness, answer_relevancy, context_relevancy]
)
print(result)
```

---

## Common Issues & Solutions

| Issue | Solution |
|-------|----------|
| **Missing context** | Increase K, better chunking |
| **Irrelevant retrieval** | Improve embeddings, add reranking |
| **Hallucination despite RAG** | Stronger prompts, lower temperature |
| **Slow response** | Cache embeddings, use smaller models |
| **Context too long** | Compression, summarization |

---

## Production Checklist

- [ ] **Chunking strategy** tested and optimized
- [ ] **Embedding model** selected and tested
- [ ] **Vector database** scaled appropriately
- [ ] **Retrieval** evaluated with metrics
- [ ] **Prompt template** tested for edge cases
- [ ] **Caching** implemented for common queries
- [ ] **Monitoring** for quality and latency
- [ ] **Fallback** for retrieval failures

---

## Interview Questions

**Q: What is RAG and when would you use it?**
> RAG retrieves relevant documents to augment LLM generation. Use when: need current/private data, reduce hallucinations, require sources, domain-specific knowledge.

**Q: RAG vs Fine-tuning - when to use which?**
> RAG for: facts, current info, easy updates. Fine-tuning for: style changes, specialized behavior, when RAG retrieval is slow. Often combine both.

**Q: How do you handle context window limits in RAG?**
> 1) Better chunking, 2) Reranking to get most relevant, 3) Compression to extract key parts, 4) Map-reduce for very large contexts, 5) Use models with larger context.

---

## Quick Reference

| Component | Purpose | Options |
|-----------|---------|---------|
| **Chunking** | Split docs | Fixed, semantic, recursive |
| **Embedding** | Vectorize | OpenAI, Cohere, open source |
| **Vector DB** | Store/search | Pinecone, Chroma, Weaviate |
| **Retrieval** | Find relevant | Similarity, hybrid, MMR |
| **Reranking** | Improve order | Cross-encoder, Cohere |
| **Generation** | Create answer | GPT-4, Claude, Llama |
