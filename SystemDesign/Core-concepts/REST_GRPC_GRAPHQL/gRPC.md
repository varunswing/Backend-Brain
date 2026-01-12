## 1. gRPC Communication Patterns

gRPC supports different types of communication patterns beyond just typical request-response. Here's a clear explanation of each type:

---

### 1. **Regular gRPC Requests** (Unary RPC)
- **Pattern:** One request → One response
- **Use case:** Typical HTTP-like API calls
- **Example:**
  ```proto
  rpc GetUser(GetUserRequest) returns (GetUserResponse);
  ```

✅ Server receives a single request and sends a single response.

---

### 2. **Client-Side Streaming**
- **Pattern:** Client sends a stream of data → Server responds once
- **Use case:** File upload, bulk insert, telemetry
- **Example:**
  ```proto
  rpc UploadLogs(stream LogEntry) returns (UploadStatus);
  ```

✅ Server receives multiple messages and replies only once after processing.

---

### 3. **Server-Side Streaming**
- **Pattern:** Client sends one request → Server sends a stream of responses
- **Use case:** Downloading data chunks, live data feed, paginated data
- **Example:**
  ```proto
  rpc ListUsers(UserFilter) returns (stream User);
  ```

✅ Client waits while server streams many responses.

---

### 4. **Bi-Directional Streaming**
- **Pattern:** Both client and server send streams to each other
- **Use case:** Real-time chat, live collaboration, multiplayer games
- **Example:**
  ```proto
  rpc Chat(stream Message) returns (stream Message);
  ```

✅ True two-way communication. Both can send anytime.

---

### 5. **Client-Initiated or Client-Sourced RPC**
- Not an official gRPC category, but often refers to:
  - **Client creating a long-lived connection**
  - **Client pushing events, telemetry, or requests asynchronously**
- Typically built using:
  - **Client-side streaming**
  - **Bi-directional streaming**

✅ Used when **client drives the flow** of interaction.

---

### 🧠 Summary Table:

| Type                     | Client Sends | Server Sends | Use Case                     |
|--------------------------|--------------|--------------|------------------------------|
| Unary                    | 1            | 1            | Simple API calls             |
| Client-side Streaming    | many         | 1            | File uploads, logs           |
| Server-side Streaming    | 1            | many         | Large data fetch             |
| Bi-directional Streaming | many         | many         | Chat, games, live updates    |
| Client-Initiated Flow    | varies       | varies       | Events from client side      |

---

## 2. gRPC Communication Examples in Go

Here are **Go-based gRPC examples** for all 4 core types of gRPC requests using Protocol Buffers (`.proto`) and Go code.

---

### ✅ 1. Unary RPC (Request → Response)

**`.proto`:**
```proto
service UserService {
  rpc GetUser (UserRequest) returns (UserResponse);
}
```

**Go Server:**
```go
func (s *userServiceServer) GetUser(ctx context.Context, req *pb.UserRequest) (*pb.UserResponse, error) {
    return &pb.UserResponse{Id: req.Id, Name: "John Doe"}, nil
}
```

---

### ✅ 2. Client-Side Streaming

**`.proto`:**
```proto
service LogService {
  rpc UploadLogs(stream LogEntry) returns (UploadStatus);
}
```

**Go Server:**
```go
func (s *logServiceServer) UploadLogs(stream pb.LogService_UploadLogsServer) error {
    var count int
    for {
        logEntry, err := stream.Recv()
        if err == io.EOF {
            return stream.SendAndClose(&pb.UploadStatus{Message: fmt.Sprintf("%d logs received", count)})
        }
        count++
        log.Println(logEntry.Message)
    }
}
```

---

### ✅ 3. Server-Side Streaming

**`.proto`:**
```proto
service ReportService {
  rpc StreamReports(ReportRequest) returns (stream Report);
}
```

**Go Server:**
```go
func (s *reportServiceServer) StreamReports(req *pb.ReportRequest, stream pb.ReportService_StreamReportsServer) error {
    for i := 1; i <= 5; i++ {
        stream.Send(&pb.Report{Id: int32(i), Title: fmt.Sprintf("Report %d", i)})
        time.Sleep(1 * time.Second)
    }
    return nil
}
```

---

### ✅ 4. Bi-Directional Streaming

**`.proto`:**
```proto
service ChatService {
  rpc Chat(stream Message) returns (stream Message);
}
```

**Go Server:**
```go
func (s *chatServiceServer) Chat(stream pb.ChatService_ChatServer) error {
    for {
        msg, err := stream.Recv()
        if err == io.EOF {
            return nil
        }
        response := &pb.Message{Sender: "Server", Text: "Echo: " + msg.Text}
        stream.Send(response)
    }
}
```

---