GraphQL is a **query language for APIs** and a **runtime for executing those queries** with your existing data. It was developed by Facebook in 2012 and open-sourced in 2015. It offers an efficient, powerful, and flexible alternative to REST APIs.

---

### 🧠 Core Concepts of GraphQL:

#### 1. **Query-based API**
Clients send queries specifying exactly **what data they want**, and the server returns only that data — nothing more, nothing less.

#### 2. **Schema Definition**
A strongly typed schema defines the structure of the API — the types of data and how they relate. Example:
```graphql
type User {
  id: ID!
  name: String
  age: Int
}

type Query {
  getUser(id: ID!): User
}
```

#### 3. **Queries and Mutations**
- **Query**: Read-only fetches.
- **Mutation**: Used to create, update, or delete data.

Example:
```graphql
# Query
{
  getUser(id: "1") {
    name
    age
  }
}

# Mutation
mutation {
  updateUser(id: "1", name: "Alice") {
    name
    age
  }
}
```

#### 4. **Resolvers**
Resolvers are backend functions that map to fields in your schema. When a query is received, GraphQL invokes these resolvers to fetch data.

```js
const resolvers = {
  Query: {
    getUser: (_, { id }) => db.findUserById(id),
  },
  Mutation: {
    updateUser: (_, { id, name }) => db.updateUser(id, name),
  }
};
```

#### 5. **Nested Queries**
GraphQL allows deeply nested queries — perfect for hierarchical data (like in social media apps).

```graphql
{
  user(id: "1") {
    name
    posts {
      title
      comments {
        text
      }
    }
  }
}
```

#### 6. **Introspection & Tooling**
GraphQL APIs are self-documenting. Tools like **GraphiQL** or **Apollo Studio** let you explore the API and write/test queries.

---

### 🚀 Advantages over REST:

| REST                         | GraphQL                             |
|-----------------------------|-------------------------------------|
| Multiple endpoints           | Single endpoint                     |
| Over-fetching/Under-fetching| Exact data fetching                 |
| Fixed data shape             | Client defines response shape       |
| Versioning needed            | Schema evolves without versions     |
| Requires multiple round-trips| Nested data in a single request     |

---

### 🧱 Example Stack:

- **GraphQL Server**: Apollo Server, Express-GraphQL
- **Client**: Apollo Client, Relay
- **Backend**: Node.js, Java, Python, Go, etc.

---