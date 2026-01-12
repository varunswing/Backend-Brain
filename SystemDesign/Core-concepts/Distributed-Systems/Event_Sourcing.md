# Event Sourcing

## What is Event Sourcing?

Event Sourcing stores all changes to application state as a sequence of events. Instead of storing current state, you store the history of changes.

```
Traditional (State-Based):
┌─────────────────────────────┐
│ Account: 12345              │
│ Balance: $500               │  ← Only current state
│ Updated: 2024-01-15         │
└─────────────────────────────┘

Event Sourcing:
┌─────────────────────────────────────────────────────┐
│ Event 1: AccountCreated    { balance: $0 }          │
│ Event 2: MoneyDeposited    { amount: $1000 }        │
│ Event 3: MoneyWithdrawn    { amount: $300 }         │
│ Event 4: MoneyWithdrawn    { amount: $200 }         │
│ Current State: $500 (computed from events)          │
└─────────────────────────────────────────────────────┘
```

---

## Why Event Sourcing?

| Benefit | Description |
|---------|-------------|
| **Complete History** | Full audit trail of all changes |
| **Time Travel** | Reconstruct state at any point in time |
| **Debugging** | Replay events to understand what happened |
| **Event Replay** | Rebuild read models, fix bugs in projections |
| **Analytics** | Analyze historical patterns |
| **Compliance** | Regulatory audit requirements |

---

## Core Concepts

### Event
An immutable record of something that happened.

```python
@dataclass
class Event:
    event_id: str
    event_type: str
    aggregate_id: str
    aggregate_type: str
    timestamp: datetime
    version: int
    data: dict
    metadata: dict

# Example events
AccountCreated(account_id="123", owner="John", initial_balance=0)
MoneyDeposited(account_id="123", amount=1000, source="ATM")
MoneyWithdrawn(account_id="123", amount=300, reason="Purchase")
```

### Aggregate
Domain object that encapsulates state and emits events.

```python
class BankAccount:
    def __init__(self, account_id):
        self.account_id = account_id
        self.balance = 0
        self.status = "active"
        self._events = []
        self._version = 0
    
    # Command handler
    def deposit(self, amount):
        if amount <= 0:
            raise ValueError("Amount must be positive")
        if self.status != "active":
            raise InvalidOperationError("Account is not active")
        
        # Emit event (don't mutate state directly)
        self._emit(MoneyDeposited(
            account_id=self.account_id,
            amount=amount,
            timestamp=datetime.utcnow()
        ))
    
    def withdraw(self, amount):
        if amount > self.balance:
            raise InsufficientFundsError()
        
        self._emit(MoneyWithdrawn(
            account_id=self.account_id,
            amount=amount,
            timestamp=datetime.utcnow()
        ))
    
    # Event handler (apply state changes)
    def _apply(self, event):
        if isinstance(event, AccountCreated):
            self.balance = event.initial_balance
            self.status = "active"
        elif isinstance(event, MoneyDeposited):
            self.balance += event.amount
        elif isinstance(event, MoneyWithdrawn):
            self.balance -= event.amount
        elif isinstance(event, AccountClosed):
            self.status = "closed"
        
        self._version += 1
    
    def _emit(self, event):
        self._apply(event)  # Apply to self
        self._events.append(event)  # Collect for persistence
    
    # Reconstruct from events
    @classmethod
    def from_events(cls, account_id, events):
        account = cls(account_id)
        for event in events:
            account._apply(event)
        return account
```

### Event Store
Append-only storage for events.

```python
class EventStore:
    def __init__(self, db):
        self.db = db
    
    def save_events(self, aggregate_id, events, expected_version):
        # Optimistic concurrency check
        current_version = self._get_version(aggregate_id)
        if current_version != expected_version:
            raise ConcurrencyError("Aggregate was modified")
        
        # Append events
        for i, event in enumerate(events):
            self.db.execute("""
                INSERT INTO events 
                (event_id, aggregate_id, event_type, version, data, timestamp)
                VALUES (?, ?, ?, ?, ?, ?)
            """, 
                str(uuid.uuid4()),
                aggregate_id,
                event.__class__.__name__,
                expected_version + i + 1,
                json.dumps(event.to_dict()),
                event.timestamp
            )
    
    def get_events(self, aggregate_id, from_version=0):
        rows = self.db.query("""
            SELECT * FROM events 
            WHERE aggregate_id = ? AND version > ?
            ORDER BY version
        """, aggregate_id, from_version)
        
        return [self._deserialize(row) for row in rows]
    
    def get_all_events(self, from_position=0):
        """For projections - get all events across aggregates"""
        return self.db.query("""
            SELECT * FROM events 
            WHERE global_position > ?
            ORDER BY global_position
        """, from_position)
```

---

## Event Sourcing + CQRS

```
┌──────────────────────────────────────────────────────────────┐
│                        Commands                               │
│                            │                                  │
│                            ▼                                  │
│                    ┌───────────────┐                         │
│                    │   Aggregate   │                         │
│                    └───────┬───────┘                         │
│                            │ Events                          │
│                            ▼                                  │
│                    ┌───────────────┐                         │
│                    │  Event Store  │                         │
│                    └───────┬───────┘                         │
│                            │                                  │
│              ┌─────────────┼─────────────┐                   │
│              ▼             ▼             ▼                   │
│        ┌──────────┐  ┌──────────┐  ┌──────────┐             │
│        │Projection│  │Projection│  │Projection│             │
│        │  (List)  │  │ (Search) │  │(Analytics│             │
│        └────┬─────┘  └────┬─────┘  └────┬─────┘             │
│             ▼             ▼             ▼                    │
│        ┌──────────┐  ┌──────────┐  ┌──────────┐             │
│        │  SQL DB  │  │Elastic   │  │ClickHouse│             │
│        └──────────┘  └──────────┘  └──────────┘             │
│                            ▲                                  │
│                            │ Queries                         │
└──────────────────────────────────────────────────────────────┘
```

---

## Projections

Build read models from events.

```python
class AccountBalanceProjection:
    """Builds a read model of account balances"""
    
    def __init__(self, read_db):
        self.read_db = read_db
        self.last_position = 0
    
    def handle(self, event):
        if isinstance(event, AccountCreated):
            self.read_db.upsert('account_balances', {
                'account_id': event.account_id,
                'balance': event.initial_balance,
                'owner': event.owner,
                'created_at': event.timestamp
            })
        
        elif isinstance(event, MoneyDeposited):
            self.read_db.update(
                'account_balances',
                {'account_id': event.account_id},
                {'$inc': {'balance': event.amount}}
            )
        
        elif isinstance(event, MoneyWithdrawn):
            self.read_db.update(
                'account_balances',
                {'account_id': event.account_id},
                {'$inc': {'balance': -event.amount}}
            )
        
        self.last_position = event.global_position
    
    def rebuild(self, event_store):
        """Rebuild entire projection from scratch"""
        self.read_db.drop('account_balances')
        self.last_position = 0
        
        for event in event_store.get_all_events():
            self.handle(event)

class TransactionHistoryProjection:
    """Builds a read model for transaction history"""
    
    def handle(self, event):
        if isinstance(event, (MoneyDeposited, MoneyWithdrawn)):
            self.read_db.insert('transactions', {
                'account_id': event.account_id,
                'type': 'deposit' if isinstance(event, MoneyDeposited) else 'withdrawal',
                'amount': event.amount,
                'timestamp': event.timestamp
            })
```

---

## Snapshots

Optimize reconstruction for aggregates with many events.

```python
class SnapshotStore:
    def __init__(self, db, snapshot_frequency=100):
        self.db = db
        self.snapshot_frequency = snapshot_frequency
    
    def save_snapshot(self, aggregate):
        self.db.upsert('snapshots', {
            'aggregate_id': aggregate.id,
            'version': aggregate._version,
            'state': aggregate.to_dict(),
            'timestamp': datetime.utcnow()
        })
    
    def get_snapshot(self, aggregate_id):
        return self.db.find_one('snapshots', {'aggregate_id': aggregate_id})

class AggregateRepository:
    def __init__(self, event_store, snapshot_store):
        self.event_store = event_store
        self.snapshot_store = snapshot_store
    
    def load(self, aggregate_id):
        # Try to load from snapshot
        snapshot = self.snapshot_store.get_snapshot(aggregate_id)
        
        if snapshot:
            # Restore from snapshot
            aggregate = BankAccount.from_snapshot(snapshot['state'])
            from_version = snapshot['version']
        else:
            # Start fresh
            aggregate = BankAccount(aggregate_id)
            from_version = 0
        
        # Apply events since snapshot
        events = self.event_store.get_events(aggregate_id, from_version)
        for event in events:
            aggregate._apply(event)
        
        return aggregate
    
    def save(self, aggregate):
        # Save events
        self.event_store.save_events(
            aggregate.id,
            aggregate._events,
            aggregate._version - len(aggregate._events)
        )
        
        # Save snapshot periodically
        if aggregate._version % self.snapshot_store.snapshot_frequency == 0:
            self.snapshot_store.save_snapshot(aggregate)
        
        aggregate._events = []  # Clear pending events
```

---

## Event Versioning

Handle schema evolution.

```python
class EventUpgrader:
    def upgrade(self, event_data, from_version, to_version):
        for version in range(from_version, to_version):
            upgrader = getattr(self, f'upgrade_v{version}_to_v{version+1}', None)
            if upgrader:
                event_data = upgrader(event_data)
        return event_data
    
    def upgrade_v1_to_v2(self, event_data):
        """MoneyDeposited v1 → v2: Added 'source' field"""
        if event_data['type'] == 'MoneyDeposited':
            event_data['data']['source'] = 'unknown'  # Default value
        return event_data
    
    def upgrade_v2_to_v3(self, event_data):
        """MoneyDeposited v2 → v3: Renamed 'source' to 'channel'"""
        if event_data['type'] == 'MoneyDeposited':
            event_data['data']['channel'] = event_data['data'].pop('source')
        return event_data
```

---

## When to Use Event Sourcing

### ✅ Good Fit
- Audit trail is required (finance, healthcare)
- Complex domain with business rules
- Need to analyze historical patterns
- Multiple read models needed
- Debugging state changes is important

### ❌ Not Recommended
- Simple CRUD applications
- No audit requirements
- Team unfamiliar with pattern
- Very high write throughput needed
- Simple queries on current state only

---

## Challenges & Solutions

| Challenge | Solution |
|-----------|----------|
| **Event store growth** | Snapshots, archival policies |
| **Schema evolution** | Event versioning, upcasting |
| **Eventual consistency** | Clearly communicate to users |
| **Complexity** | Start simple, add incrementally |
| **Debugging** | Good tooling, event replay |

---

## Interview Questions

**Q: What is Event Sourcing?**
> Storing all state changes as immutable events instead of current state. Can reconstruct any past state by replaying events. Benefits: audit trail, debugging, analytics, event replay.

**Q: Event Sourcing vs Event-Driven Architecture?**
> Event Sourcing: events as source of truth for state. EDA: events for communication between services. Can use both together but they're independent concepts.

**Q: How do you handle event schema changes?**
> Event versioning with upcasters that transform old events to new format. Never modify stored events. Keep events backward compatible when possible.

---

## Quick Reference

| Component | Purpose |
|-----------|---------|
| **Event** | Immutable record of change |
| **Aggregate** | Domain object + event emitter |
| **Event Store** | Append-only event storage |
| **Projection** | Read model built from events |
| **Snapshot** | Optimization for long event streams |
