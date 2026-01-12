# MCP (Model Context Protocol) & Tool Calling

## What is MCP?

**Model Context Protocol (MCP)** is an open standard that allows AI models to securely connect to external data sources, tools, and services. Created by Anthropic.

```
Traditional AI:
  User → LLM → Text Response (limited to training data)

With MCP:
  User → LLM → MCP Server → Tools/Data → Grounded Response
                    ↓
              [Files, APIs, Databases, Git, etc.]
```

---

## Why MCP Matters

| Without MCP | With MCP |
|-------------|----------|
| LLM can only generate text | LLM can execute actions |
| Knowledge is static | Access real-time data |
| No file access | Read/write files |
| No API integration | Call external APIs |
| Each integration is custom | Standardized protocol |

---

## MCP Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                       MCP Host                               │
│                    (Claude, Cursor)                          │
├─────────────────────────────────────────────────────────────┤
│                       MCP Client                             │
│              (Protocol Implementation)                       │
└─────────────────────────┬───────────────────────────────────┘
                          │
        ┌─────────────────┼─────────────────┐
        │                 │                 │
        ▼                 ▼                 ▼
┌───────────────┐ ┌───────────────┐ ┌───────────────┐
│  MCP Server   │ │  MCP Server   │ │  MCP Server   │
│   (GitHub)    │ │  (Database)   │ │ (File System) │
└───────────────┘ └───────────────┘ └───────────────┘
```

### Components
- **Host**: Application running the AI (Claude Desktop, Cursor)
- **Client**: Protocol handler in the host
- **Server**: Exposes tools, resources, prompts
- **Transport**: Communication layer (stdio, HTTP)

---

## MCP Capabilities

### 1. Tools
Functions the AI can call.

```json
{
  "name": "search_codebase",
  "description": "Search for code patterns in the repository",
  "inputSchema": {
    "type": "object",
    "properties": {
      "query": {"type": "string", "description": "Search pattern"},
      "file_type": {"type": "string", "description": "File extension filter"}
    },
    "required": ["query"]
  }
}
```

### 2. Resources
Data the AI can read.

```json
{
  "uri": "file:///project/README.md",
  "name": "Project README",
  "mimeType": "text/markdown"
}
```

### 3. Prompts
Pre-defined prompt templates.

```json
{
  "name": "code_review",
  "description": "Review code for best practices",
  "arguments": [
    {"name": "code", "description": "Code to review", "required": true}
  ]
}
```

---

## Tool Calling (Function Calling)

### How It Works
```
1. User: "What's the weather in Tokyo?"
2. LLM: Recognizes need for tool, outputs:
   { "tool": "get_weather", "args": {"city": "Tokyo"} }
3. System: Executes tool, returns result
4. LLM: "The weather in Tokyo is 22°C and sunny."
```

### OpenAI Function Calling
```python
from openai import OpenAI

client = OpenAI()

tools = [
    {
        "type": "function",
        "function": {
            "name": "get_weather",
            "description": "Get current weather for a location",
            "parameters": {
                "type": "object",
                "properties": {
                    "city": {"type": "string", "description": "City name"},
                    "unit": {"type": "string", "enum": ["celsius", "fahrenheit"]}
                },
                "required": ["city"]
            }
        }
    }
]

response = client.chat.completions.create(
    model="gpt-4o",
    messages=[{"role": "user", "content": "What's the weather in Tokyo?"}],
    tools=tools,
    tool_choice="auto"
)

# Check if tool was called
if response.choices[0].message.tool_calls:
    tool_call = response.choices[0].message.tool_calls[0]
    args = json.loads(tool_call.function.arguments)
    
    # Execute the tool
    weather_result = get_weather(args["city"])
    
    # Send result back to LLM
    messages.append(response.choices[0].message)
    messages.append({
        "role": "tool",
        "tool_call_id": tool_call.id,
        "content": json.dumps(weather_result)
    })
    
    final_response = client.chat.completions.create(
        model="gpt-4o",
        messages=messages
    )
```

### Claude Tool Use
```python
import anthropic

client = anthropic.Anthropic()

tools = [
    {
        "name": "get_weather",
        "description": "Get current weather for a location",
        "input_schema": {
            "type": "object",
            "properties": {
                "city": {"type": "string", "description": "City name"}
            },
            "required": ["city"]
        }
    }
]

response = client.messages.create(
    model="claude-3-5-sonnet-20241022",
    max_tokens=1024,
    tools=tools,
    messages=[{"role": "user", "content": "What's the weather in Tokyo?"}]
)

# Handle tool use
for content in response.content:
    if content.type == "tool_use":
        tool_name = content.name
        tool_input = content.input
        
        # Execute tool
        result = execute_tool(tool_name, tool_input)
        
        # Continue conversation with result
        messages.append({"role": "assistant", "content": response.content})
        messages.append({
            "role": "user",
            "content": [{
                "type": "tool_result",
                "tool_use_id": content.id,
                "content": json.dumps(result)
            }]
        })
```

---

## Building an MCP Server

### Basic Server (Python)
```python
from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import Tool, TextContent

# Create server
server = Server("my-server")

# Define tools
@server.list_tools()
async def list_tools():
    return [
        Tool(
            name="search_files",
            description="Search for files in the project",
            inputSchema={
                "type": "object",
                "properties": {
                    "pattern": {"type": "string"}
                },
                "required": ["pattern"]
            }
        )
    ]

# Handle tool calls
@server.call_tool()
async def call_tool(name: str, arguments: dict):
    if name == "search_files":
        pattern = arguments["pattern"]
        results = search_files(pattern)
        return [TextContent(type="text", text=json.dumps(results))]

# Run server
async def main():
    async with stdio_server() as (read, write):
        await server.run(read, write)

asyncio.run(main())
```

### Configure in Claude Desktop
```json
// ~/Library/Application Support/Claude/claude_desktop_config.json
{
  "mcpServers": {
    "my-server": {
      "command": "python",
      "args": ["/path/to/server.py"]
    }
  }
}
```

---

## Popular MCP Servers

| Server | Purpose | Example Use |
|--------|---------|-------------|
| **GitHub** | Repository access | "List open PRs in repo X" |
| **Postgres** | Database queries | "Show top 10 users" |
| **Filesystem** | File operations | "Read config.yaml" |
| **Brave Search** | Web search | "Search for X" |
| **Google Drive** | Document access | "Find budget spreadsheet" |
| **Slack** | Messaging | "Send message to #dev" |

---

## Best Practices

### Tool Design
1. **Clear descriptions** - LLM decides based on description
2. **Specific parameters** - Use enums when possible
3. **Error handling** - Return meaningful errors
4. **Idempotent** - Safe to call multiple times
5. **Scoped permissions** - Minimum necessary access

### Security
```python
# Validate inputs
def search_files(pattern: str):
    # Prevent path traversal
    if ".." in pattern or pattern.startswith("/"):
        raise ValueError("Invalid pattern")
    
    # Limit scope
    allowed_dirs = ["./src", "./docs"]
    # ... continue with search
```

### Error Handling
```python
@server.call_tool()
async def call_tool(name: str, arguments: dict):
    try:
        result = execute_tool(name, arguments)
        return [TextContent(type="text", text=json.dumps(result))]
    except ValidationError as e:
        return [TextContent(type="text", text=f"Invalid input: {e}")]
    except PermissionError:
        return [TextContent(type="text", text="Permission denied")]
    except Exception as e:
        return [TextContent(type="text", text=f"Error: {str(e)}")]
```

---

## Tool Calling Patterns

### Sequential Tool Calls
```
User: "Find the user john@example.com and update their role to admin"

LLM → Tool 1: find_user(email="john@example.com") → User ID: 123
LLM → Tool 2: update_role(user_id=123, role="admin") → Success
LLM: "I found John and updated their role to admin."
```

### Parallel Tool Calls
```
User: "What's the weather in Tokyo, London, and NYC?"

LLM → [
  Tool: get_weather(city="Tokyo"),
  Tool: get_weather(city="London"),
  Tool: get_weather(city="NYC")
] → All results at once

LLM: "Tokyo: 22°C, London: 15°C, NYC: 20°C"
```

---

## Interview Questions

**Q: What is MCP and why was it created?**
> MCP is an open protocol for connecting AI models to external tools and data. Created to standardize integrations - instead of custom code for each tool, use one protocol.

**Q: How does tool calling work?**
> LLM recognizes when to use a tool from its description, outputs structured call with arguments, system executes and returns result, LLM incorporates result into response.

**Q: How do you ensure tool security?**
> Input validation, scoped permissions, rate limiting, audit logging, sandboxed execution, user confirmation for sensitive actions.

---

## Quick Reference

| Concept | Description |
|---------|-------------|
| **MCP** | Protocol for AI-tool connections |
| **Tool** | Function AI can call |
| **Resource** | Data AI can read |
| **Tool Calling** | AI invoking external functions |
| **Function Calling** | OpenAI's term for tool calling |
| **MCP Server** | Exposes tools via MCP protocol |
| **MCP Host** | App running AI (Claude, Cursor) |
