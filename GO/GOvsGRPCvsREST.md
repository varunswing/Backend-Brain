# API Protocol Wars: When We Use gRPC, GraphQL, and REST at Scale

**At MakeMyTrip, we don't do "gRPC vs GraphQL vs REST" — we do "gRPC AND GraphQL AND REST." Here's why each protocol earned its place in our production architecture.**

---

## The Reality Check

When building a hotel booking platform that handles millions of requests daily, the "which API protocol should we use?" debate becomes less about religion and more about pragmatism. After implementing all three protocols across our microservices architecture, here's what we learned about when to use each one.

Our architecture spans multiple services:
- **Orchestrator Service**: Orchestration layer with 68+ gRPC service definitions
- **API Gateway**: GraphQL gateway with 363+ schema files
- **Inventory Service**: Hybrid REST/gRPC inventory management
- **Supply Service**: Core hotel supply backend

Each protocol solves different problems. Let's break down the real trade-offs.

---

## gRPC: The Internal Workhorse

### When We Use It
**Service-to-service communication** where performance and type safety are non-negotiable.

### Real Implementation
When the Orchestrator needs to call a backend service via gRPC:

```go
// Simplified gRPC client call example
func CallBackendService(ctx context.Context, request *pb.Request) (*pb.Response, error) {
    // Create gRPC context with timeout
    ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
    defer cancel()

    // Make the gRPC call
    start := time.Now()
    response, err := grpcClient.ProcessData(ctx, request)
    
    // Record metrics
    duration := time.Since(start)
    log.Printf("gRPC call took %v", duration)
    
    if err != nil {
        return nil, fmt.Errorf("grpc call failed: %w", err)
    }
    
    return response, nil
}
```

### Why gRPC Here?
1. **Type Safety**: Proto definitions prevent field mismatch errors
2. **Performance**: ~2-5ms latency for internal calls
3. **Code Generation**: Client/server stubs auto-generated from `.proto` files
4. **Bi-directional Streaming**: Perfect for real-time inventory updates

### The Proto Contract
```protobuf
// Example proto definition
service DataService {
    rpc ProcessData (DataRequest) returns (DataResponse);
    rpc StreamData (stream DataRequest) returns (stream DataResponse);
}

message DataRequest {
    string id = 1;
    string type = 2;
    map<string, string> metadata = 3;
    repeated Item items = 4;
}

message Item {
    string key = 1;
    string value = 2;
    Timestamp created_at = 3;
}
```

### Gotchas We Hit
- **Debugging**: Binary protocol makes network inspection harder (solved with gRPC reflection)
- **Browser Support**: No native browser support (we use gRPC-Web for clients)
- **Breaking Changes**: Proto changes require careful versioning

---

## GraphQL: The Client Whisperer

### When We Use It
**Mobile and web applications** where clients need flexible, efficient data fetching.

### Real Implementation
Our API Gateway serves as the GraphQL layer, aggregating data from multiple backend services:

```graphql
# Example GraphQL schema
type Query {
    getItem(id: ID!): Item
    
    searchItems(filters: SearchInput): [Item]
    
    getUserData(userId: ID!): UserData
}

type Item {
    id: ID!
    name: String!
    details: ItemDetails
    metadata: [KeyValue]
}

type ItemDetails {
    description: String
    price: Float
    available: Boolean
}
```

### The GraphQL Server Setup
```go
// Simplified GraphQL server setup
func SetupGraphQL() http.Handler {
    schema := graphql.MustParseSchema(schemaString, &resolver{})
    
    return &relay.Handler{
        Schema: schema,
    }
}

func main() {
    http.Handle("/graphql", SetupGraphQL())
    log.Fatal(http.ListenAndServe(":8080", nil))
}
```

### Why GraphQL Here?
1. **Flexible Queries**: Clients can request exactly what they need
2. **Reduced Round Trips**: Single query aggregates data from multiple sources
3. **Strong Typing**: Schema provides compile-time guarantees
4. **Developer Experience**: GraphQL Playground for API exploration

### Example Query
```graphql
# Single request fetches multiple related data
query GetItemDetails($itemId: ID!) {
    getItem(id: $itemId) {
        id
        name
        details {
            description
            price
            available
        }
        metadata {
            key
            value
        }
    }
}
```

### Gotchas We Hit
- **N+1 Queries**: Resolved with DataLoader pattern
- **Resolver Complexity**: 116 resolver files to manage
- **Caching**: More complex than REST (solved with persisted queries)

---

## REST: The Reliable Veteran

### When We Use It
**Partner integrations, webhooks, and simple CRUD operations** where compatibility matters most.

### Real Implementation
A typical REST API setup with standard CRUD operations:

```go
// Simple REST API server
func main() {
    router := gin.Default()
    
    // Apply common middleware
    router.Use(middleware.Logger())
    router.Use(middleware.Auth())
    
    // Define REST endpoints
    api := router.Group("/api/v1")
    {
        api.GET("/items", handlers.ListItems)
        api.GET("/items/:id", handlers.GetItem)
        api.POST("/items", handlers.CreateItem)
        api.PUT("/items/:id", handlers.UpdateItem)
        api.DELETE("/items/:id", handlers.DeleteItem)
    }
    
    router.Run(":8080")
}
```

### Router Organization
```go
// Organizing routes by resource
func RegisterItemRoutes(router *gin.RouterGroup) {
    router.GET("", ListItems)
    router.GET("/:id", GetItem)
    router.POST("", CreateItem)
    router.PUT("/:id", UpdateItem)
}

func RegisterUserRoutes(router *gin.RouterGroup) {
    router.GET("/:id/profile", GetProfile)
    router.POST("/:id/preferences", UpdatePreferences)
}
```

### Why REST Here?
1. **Universal Compatibility**: Every HTTP client works out of the box
2. **Easy Debugging**: `curl` and browser tools are sufficient
3. **Well-Understood**: Decades of best practices and patterns
4. **Stateless**: Perfect for webhooks and callbacks

### Gotchas We Hit
- **Overfetching**: Clients get more data than needed
- **Versioning**: `/api/v1` vs `/api/v2` management overhead
- **Multiple Requests**: Need separate calls for related resources

---

## The Hybrid Architecture: Best of All Worlds

### Running gRPC AND REST Simultaneously

One of our most interesting patterns is running both protocols on the same service:

```go
// Hybrid server supporting both gRPC and HTTP
func StartHybridServer(grpcServer *grpc.Server, httpHandler http.Handler) {
    // Route requests based on protocol
    handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        if r.ProtoMajor == 2 && strings.HasPrefix(
            r.Header.Get("Content-Type"), "application/grpc") {
            // Route to gRPC
            grpcServer.ServeHTTP(w, r)
        } else {
            // Route to HTTP/REST
            httpHandler.ServeHTTP(w, r)
        }
    })
    
    log.Fatal(http.ListenAndServe(":8080", handler))
}
```

**Why?** 
- Internal services call us via gRPC (fast, type-safe)
- Partner systems call us via REST (compatible, simple)
- Same business logic, different protocol adapters

---

## Our Decision Framework

| Scenario | Protocol | Reason |
|----------|----------|--------|
| Internal microservice communication | **gRPC** | Performance + type safety |
| Mobile/web client API | **GraphQL** | Flexibility + reduced bandwidth |
| Partner/third-party integration | **REST** | Compatibility + simplicity |
| Real-time bidirectional updates | **gRPC Streaming** | Native streaming support |
| Public developer API | **REST + GraphQL** | Best developer experience |
| Admin dashboards | **GraphQL** | Complex aggregation queries |

---

## Performance Reality Check

Real latencies from our production monitoring:

- **gRPC (internal service-to-service)**: ~2-5ms
- **GraphQL (mobile client request)**: ~50-200ms (includes orchestration of multiple backend calls)
- **REST (partner API)**: ~100-500ms (network + processing + legacy systems)

**Key Insight**: Protocol choice is rarely the bottleneck. Database queries, network latency, and business logic matter more.

---

## Lessons Learned

### 1. Don't Marry One Protocol
We run all three in production. Each solves different problems:
- gRPC for the "fast lane" between our services
- GraphQL for our mobile/web apps
- REST for everything that needs "just work"

### 2. Protocol ≠ Architecture  
Good architecture transcends protocol choice. We've seen:
- Terrible gRPC services (tight coupling, poor error handling)
- Beautiful REST APIs (proper hypermedia, great documentation)
- Messy GraphQL implementations (N+1 queries everywhere)

Design matters more than protocol.

### 3. Tooling Wins
Choose based on ecosystem support:
- **gRPC**: Excellent IDE support, built-in code generation
- **GraphQL**: Amazing playground, introspection tools
- **REST**: Universal HTTP tooling, Postman, curl

### 4. Context Matters
Mobile app needs ≠ partner API needs ≠ internal service needs

Don't force one protocol everywhere.

### 5. Migration Is Gradual
We didn't "switch" to gRPC or GraphQL. We:
1. Started with REST (everything worked)
2. Added gRPC for performance-critical paths
3. Introduced GraphQL for mobile app flexibility
4. Kept REST for partner APIs

Each protocol was adopted where it added value.

---

## Quick Decision Guide

**Start here:**

1. **Building internal microservices?**
   → Use **gRPC** (unless you have a good reason not to)

2. **Building a mobile/web app with complex data needs?**
   → Use **GraphQL** (aggregation layer over your services)

3. **Exposing APIs to third parties?**
   → Use **REST** (maximum compatibility)

4. **Need all three?**
   → **That's okay!** We do.

---

## Conclusion

The "gRPC vs GraphQL vs REST" debate is a false choice. In a real production system at scale, you'll likely need all three:

- **gRPC** for high-performance internal communication
- **GraphQL** for flexible client-facing APIs  
- **REST** for compatibility and simplicity

Each protocol has earned its place in our architecture by solving real problems. The key is knowing when to use which tool.

**Final advice**: Start simple (REST), add complexity only when you have a concrete problem to solve (performance → gRPC, flexibility → GraphQL), and don't be afraid to run multiple protocols side-by-side.

---

**About the Author**: This article is based on real production experience running MakeMyTrip's hotel booking platform, serving millions of requests daily across 120+ microservices using gRPC, GraphQL, and REST protocols.

**Tech Stack**: Go, gRPC, GraphQL (gqlgen), Gin framework, Protocol Buffers, Kafka

**Read Time**: 4 minutes
