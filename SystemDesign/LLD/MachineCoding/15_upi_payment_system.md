# UPI Payment System - System Design Interview

## Problem Statement
*"Design a UPI-based digital wallet system like PhonePe or Paytm that handles millions of daily transactions with bank integrations and regulatory compliance."*

---

## Phase 1: Requirements Clarification (8 minutes)

### Questions I Would Ask:

**Functional Requirements:**
- "What payment methods should we support?" → UPI transfers, bank linking, card payments, QR code payments
- "Do we need P2P transfers?" → Yes, user-to-user money transfers using phone/UPI ID
- "Should we support merchant payments?" → Yes, bill payments, shopping, utility bills
- "Do we need wallet balance?" → Yes, users can maintain wallet balance for quick payments
- "What about KYC verification?" → Yes, regulatory compliance with different limits for verified/unverified users
- "Should we have transaction limits?" → Yes, daily/monthly limits based on KYC status

**Non-Functional Requirements:**
- "What's our transaction volume?" → 100M+ transactions daily, 50K+ peak TPS
- "Expected latency?" → <100ms for transaction initiation, <3 seconds end-to-end
- "Availability requirements?" → 99.99% uptime (money is critical)
- "Compliance needs?" → PCI DSS, RBI guidelines, KYC/AML compliance
- "Geographic scope?" → Initially India, with regulatory compliance per region

### Requirements Summary:
- **Scale**: 100M users, 100M+ daily transactions, 50K peak TPS
- **Features**: UPI transfers, wallet, bank linking, merchant payments, QR codes
- **Compliance**: KYC verification, transaction limits, audit trails
- **Performance**: <100ms response, <3 seconds end-to-end processing
- **Security**: PCI DSS, encryption, fraud detection

---

## Phase 2: Capacity Estimation (5 minutes)

### Transaction Volume:
```
Daily transactions: 100M
Peak hour: 10M transactions (during festivals/sales)
Peak TPS: 10M ÷ 3600 = ~3K TPS
With 20x spike: 50K TPS peak capacity needed
Average transaction value: ₹500
Daily money flow: 100M × ₹500 = ₹50 billion/day
```

### Storage Estimation:
```
User accounts: 100M users × 2KB = 200GB
Bank accounts: 200M linked accounts × 1KB = 200GB
Transactions: 100M/day × 1KB × 365 × 7 years = 256TB
Audit logs: 100M/day × 500B × 365 × 7 years = 128TB
Total storage: ~400TB with replicas and indexes
```

### Memory Requirements:
```
Active user sessions: 5M concurrent × 2KB = 10GB
Transaction processing: 50K TPS × 1KB × 30s = 1.5GB
Fraud detection cache: 2GB
Bank API response cache: 1GB
Total memory: ~15GB per region
```

---

## Phase 3: High-Level Architecture (12 minutes)

```
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│Mobile Apps  │  │Merchant     │  │Admin Portal │
│- UPI Pay    │  │ POS Systems │  │- Risk Mgmt  │
│- QR Scan    │  │- QR Display │  │- Compliance │
└─────────────┘  └─────────────┘  └─────────────┘
       │                │                │
       └────────────────┼────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│          Load Balancer + WAF            │
│- DDoS protection  - Rate limiting       │
└─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│            API Gateway                  │
│- Authentication  - Authorization        │
│- Request validation  - Circuit breaker │
└─────────────────────────────────────────┘
                        │
    ┌───────────────────┼───────────────────┐
    │                   │                   │
    ▼                   ▼                   ▼
┌────────────┐ ┌────────────┐ ┌────────────┐
│User        │ │Wallet      │ │Payment     │
│Service     │ │Service     │ │Service     │
│            │ │            │ │            │
│- KYC       │ │- Balance   │ │- UPI Txn   │
│- Profile   │ │- Top-up    │ │- Bank API  │
│- Auth      │ │- Withdraw  │ │- Settlement│
└────────────┘ └────────────┘ └────────────┘
    │                   │                   │
    └───────────────────┼───────────────────┘
                        │
    ┌───────────────────┼───────────────────┐
    │                   │                   │
    ▼                   ▼                   ▼
┌────────────┐ ┌────────────┐ ┌────────────┐
│Transaction │ │Fraud       │ │Notification│
│Service     │ │Detection   │ │Service     │
│            │ │            │ │            │
│- History   │ │- ML Models │ │- SMS/Email │
│- Audit     │ │- Rules     │ │- Push      │
│- Reconcile │ │- Block     │ │- Alerts    │
└────────────┘ └────────────┘ └────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│         External Integrations           │
│                                         │
│ ┌─────────┐  ┌─────────┐  ┌─────────┐   │
│ │   UPI   │  │  Banks  │  │  RBI    │   │
│ │ Switch  │  │   APIs  │  │ Mandates│   │
│ │ (NPCI)  │  │  (IMPS) │  │ Reports │   │
│ └─────────┘  └─────────┘  └─────────┘   │
└─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│              Data Layer                 │
│                                         │
│ ┌─────────┐  ┌─────────┐  ┌─────────┐   │
│ │PostgreSQL│  │  Redis  │  │  Kafka  │   │
│ │         │  │         │  │         │   │
│ │- Users  │  │- Session│  │- Events │   │
│ │- Txns   │  │- Balance│  │- Audit  │   │
│ │- Wallets│  │- Cache  │  │- Logs   │   │
│ └─────────┘  └─────────┘  └─────────┘   │
└─────────────────────────────────────────┘
```

### Key Components:
- **Payment Service**: Core UPI transaction processing, bank integrations
- **Wallet Service**: Balance management, top-up, withdrawal operations
- **Fraud Detection**: Real-time risk assessment, ML-based anomaly detection
- **Transaction Service**: History, reconciliation, dispute management
- **Notification Service**: Transaction confirmations, alerts, OTP delivery

---

## Phase 4: Database Design (8 minutes)

### Core Entities:
- Users (with KYC), Wallets, Bank Accounts, Transactions, Payment Methods

### PostgreSQL Schema:
```sql
-- Users with KYC information
CREATE TABLE users (
    user_id UUID PRIMARY KEY,
    phone_number VARCHAR(15) UNIQUE NOT NULL,
    email VARCHAR(255),
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    
    -- KYC status and verification
    kyc_status VARCHAR(20) DEFAULT 'pending',  -- pending, verified, rejected
    kyc_level INTEGER DEFAULT 1,               -- 1: basic, 2: intermediate, 3: full
    kyc_documents JSONB DEFAULT '[]',
    verification_date TIMESTAMP,
    
    -- Limits based on KYC level
    daily_transaction_limit DECIMAL(12,2) DEFAULT 5000,
    monthly_transaction_limit DECIMAL(12,2) DEFAULT 10000,
    
    -- Security
    pin_hash VARCHAR(255),
    failed_pin_attempts INTEGER DEFAULT 0,
    locked_until TIMESTAMP,
    
    -- Status
    user_status VARCHAR(20) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_users_phone (phone_number),
    INDEX idx_users_kyc_status (kyc_status, kyc_level)
);

-- Digital wallets
CREATE TABLE wallets (
    wallet_id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(user_id) UNIQUE,
    
    -- Balance information
    available_balance DECIMAL(12,2) DEFAULT 0.00,
    locked_balance DECIMAL(12,2) DEFAULT 0.00,    -- Pending transactions
    total_balance DECIMAL(12,2) GENERATED ALWAYS AS (available_balance + locked_balance) STORED,
    
    -- Wallet configuration
    currency VARCHAR(3) DEFAULT 'INR',
    auto_add_money BOOLEAN DEFAULT false,
    min_balance_threshold DECIMAL(10,2) DEFAULT 100.00,
    
    -- Usage statistics
    total_money_added DECIMAL(15,2) DEFAULT 0,
    total_money_spent DECIMAL(15,2) DEFAULT 0,
    last_transaction_at TIMESTAMP,
    
    -- Status and security
    wallet_status VARCHAR(20) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_wallets_user (user_id),
    INDEX idx_wallets_status (wallet_status),
    CHECK (available_balance >= 0),
    CHECK (locked_balance >= 0)
);

-- Bank account linking
CREATE TABLE bank_accounts (
    bank_account_id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(user_id),
    
    -- Account details
    account_number VARCHAR(20) NOT NULL,
    ifsc_code VARCHAR(11) NOT NULL,
    bank_name VARCHAR(100) NOT NULL,
    account_holder_name VARCHAR(200) NOT NULL,
    account_type VARCHAR(20) DEFAULT 'savings',  -- savings, current
    
    -- UPI details
    upi_id VARCHAR(100),                         -- user@bankname
    
    -- Verification status
    verification_status VARCHAR(20) DEFAULT 'pending', -- pending, verified, failed
    verified_at TIMESTAMP,
    verification_amount DECIMAL(4,2),           -- Penny drop amount
    
    -- Account status
    is_primary BOOLEAN DEFAULT false,
    is_active BOOLEAN DEFAULT true,
    
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_bank_accounts_user (user_id),
    INDEX idx_bank_accounts_upi (upi_id),
    UNIQUE(user_id, account_number, ifsc_code)
);

-- Transactions (financial transactions)
CREATE TABLE transactions (
    transaction_id UUID PRIMARY KEY,
    
    -- Transaction participants
    sender_id UUID REFERENCES users(user_id),
    receiver_id UUID REFERENCES users(user_id),
    merchant_id UUID,                           -- For merchant payments
    
    -- Transaction details
    transaction_type VARCHAR(30) NOT NULL,      -- p2p_transfer, wallet_topup, merchant_payment, bill_payment
    amount DECIMAL(12,2) NOT NULL,
    currency VARCHAR(3) DEFAULT 'INR',
    
    -- Status tracking
    transaction_status VARCHAR(20) DEFAULT 'initiated', -- initiated, pending, completed, failed, cancelled
    payment_method VARCHAR(30),                 -- wallet, upi, bank_transfer, card
    
    -- External references
    external_transaction_id VARCHAR(100),       -- Bank/UPI reference
    upi_reference_number VARCHAR(50),
    bank_reference_number VARCHAR(50),
    
    -- Timing
    initiated_at TIMESTAMP DEFAULT NOW(),
    completed_at TIMESTAMP,
    failed_at TIMESTAMP,
    
    -- Additional information
    description TEXT,
    failure_reason TEXT,
    notes JSONB DEFAULT '{}',
    
    -- Fees and charges
    transaction_fee DECIMAL(8,2) DEFAULT 0,
    gst_on_fee DECIMAL(8,2) DEFAULT 0,
    net_amount DECIMAL(12,2) GENERATED ALWAYS AS (amount - transaction_fee - gst_on_fee) STORED,
    
    -- Reconciliation
    is_reconciled BOOLEAN DEFAULT false,
    reconciled_at TIMESTAMP,
    
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    
    -- Indexes for efficient queries
    INDEX idx_transactions_sender (sender_id, created_at DESC),
    INDEX idx_transactions_receiver (receiver_id, created_at DESC),
    INDEX idx_transactions_status_time (transaction_status, created_at),
    INDEX idx_transactions_external_ref (external_transaction_id),
    INDEX idx_transactions_reconciliation (is_reconciled, created_at)
);

-- Wallet balance history (for audit and reconciliation)
CREATE TABLE wallet_transactions (
    wallet_transaction_id UUID PRIMARY KEY,
    wallet_id UUID REFERENCES wallets(wallet_id),
    transaction_id UUID REFERENCES transactions(transaction_id),
    
    -- Balance changes
    transaction_type VARCHAR(30) NOT NULL,      -- credit, debit, lock, unlock
    amount DECIMAL(12,2) NOT NULL,
    
    -- Balance snapshots
    balance_before DECIMAL(12,2) NOT NULL,
    balance_after DECIMAL(12,2) NOT NULL,
    
    -- Metadata
    description TEXT,
    reference_type VARCHAR(50),                 -- topup, payment, refund, cashback
    reference_id UUID,
    
    created_at TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_wallet_transactions_wallet_time (wallet_id, created_at DESC),
    INDEX idx_wallet_transactions_transaction (transaction_id)
);
```

### Redis Cache Strategy:
```javascript
// User session and wallet balance
"user_session:{user_id}": {
  "wallet_balance": 1500.50,
  "kyc_level": 2,
  "daily_limit_used": 2500.00,
  "monthly_limit_used": 15000.00,
  "is_blocked": false,
  "last_activity": 1704110400,
  "ttl": 3600
}

// Transaction processing cache
"transaction:{transaction_id}": {
  "status": "pending",
  "sender_id": "uuid",
  "receiver_id": "uuid", 
  "amount": 500.00,
  "initiated_at": 1704110400,
  "external_ref": "UPI123456789",
  "ttl": 1800  // 30 minutes
}

// Fraud detection cache
"fraud_check:{user_id}": {
  "transaction_count_today": 15,
  "total_amount_today": 5000.00,
  "suspicious_activity": false,
  "last_transaction": 1704110400,
  "risk_score": 0.2,
  "ttl": 86400  // 24 hours
}

// Bank API response cache
"bank_balance:{user_id}:{account_id}": {
  "available_balance": 25000.50,
  "last_updated": 1704110400,
  "ttl": 300  // 5 minutes
}
```

### Design Decisions:
- **Audit trail**: Complete transaction history for compliance
- **Balance consistency**: Atomic updates with balance snapshots
- **KYC-based limits**: Different transaction limits based on verification
- **Idempotency**: Prevent duplicate transactions with unique constraints

---

## Phase 5: Critical Flow - UPI Money Transfer (8 minutes)

### Most Critical Flow: P2P UPI Transfer

**1. Transaction Initiation**
```
User initiates UPI transfer:
POST /api/v1/payments/upi/transfer
{
  "receiver_upi_id": "john@paytm",
  "amount": 500.00,
  "description": "Dinner split",
  "pin": "encrypted_pin"
}
```

**2. Validation & Fraud Check**
```
Payment Service processing:
1. Validate user PIN and authentication
2. Check daily/monthly transaction limits based on KYC level
3. Verify sufficient wallet balance or linked bank account
4. Run real-time fraud detection:
   - Check transaction patterns
   - Velocity checks (too many transactions)
   - Amount validation (unusual high amounts)
   - Device/location verification
5. If fraud score > threshold, block or require OTP
```

**3. UPI Network Integration**
```
External UPI processing:
1. Generate unique UPI reference number
2. Create UPI payment request with NPCI
3. Send payment instruction to sender's bank
4. NPCI validates and routes to receiver's bank
5. Bank deducts amount and sends confirmation
6. NPCI sends success/failure response
```

**4. Balance Management**
```
For wallet-based payment (atomic transaction):
1. BEGIN TRANSACTION
2. Lock sender's wallet balance
3. Deduct amount from sender's available_balance
4. Create debit entry in wallet_transactions
5. Update transaction status to 'processing'
6. If UPI succeeds:
   - Commit transaction
   - Update status to 'completed'
7. If UPI fails:
   - Rollback balance changes
   - Update status to 'failed'
8. END TRANSACTION
```

**5. Notification & Reconciliation**
```
Post-transaction processing:
1. Send push notification to both sender and receiver
2. Send SMS confirmation with transaction details
3. Update daily/monthly limit usage
4. Log transaction for audit and compliance
5. Trigger background reconciliation job
6. Update fraud detection models with new data
```

### Technical Challenges:

**Transaction Consistency:**
- "Use ACID transactions for wallet balance updates"
- "Implement distributed transactions for cross-service operations"
- "Compensation patterns for external API failures"

**Fraud Detection:**
- "Real-time ML models for risk assessment"
- "Behavioral analysis and anomaly detection"
- "Rate limiting and velocity checks"

**Regulatory Compliance:**
- "Complete audit trail for all transactions"
- "KYC verification and limit enforcement"
- "RBI reporting and compliance automation"

**High Availability:**
- "Circuit breaker for bank API failures"
- "Retry logic with exponential backoff"
- "Graceful degradation during peak loads"

---

## Phase 6: Scaling & Bottlenecks (2 minutes)

### Main Bottlenecks:
1. **Database contention**: High-frequency balance updates
2. **External API latency**: Bank and UPI network calls
3. **Fraud detection processing**: Real-time ML inference
4. **Regulatory compliance**: Audit log volumes

### Scaling Solutions:

**Database Scaling:**
```
- Read replicas: Separate read/write for transaction history
- Sharding: Partition by user_id for wallet operations
- Connection pooling: Manage database connections
- Write optimization: Batch non-critical operations
```

**External Integration:**
```
- Circuit breakers: Fail fast when banks are down
- Retry queues: Async retry for failed transactions
- Multiple providers: Fallback to different bank partners
- Caching: Cache bank account validation results
```

**Real-time Processing:**
```
- Event streaming: Kafka for transaction events
- Async processing: Background jobs for reconciliation
- ML optimization: Cached fraud models for faster inference
- Load balancing: Distribute fraud checks across nodes
```

**Compliance & Audit:**
```
- Data archiving: Move old transactions to cold storage
- Async reporting: Background jobs for regulatory reports
- Index optimization: Efficient audit trail queries
- Compression: Compress historical audit logs
```

### Trade-offs:
- **Consistency vs Performance**: Strong consistency for money vs faster transactions
- **Security vs UX**: More verification steps vs seamless payments
- **Compliance vs Speed**: Detailed logging vs transaction latency

---

## Advanced Features:

**Regulatory Compliance:**
- Automated KYC verification workflows
- Real-time transaction monitoring and reporting
- AML (Anti-Money Laundering) pattern detection
- Regulatory reporting automation

**Security:**
- Multi-factor authentication for high-value transactions
- Device fingerprinting and behavioral biometrics
- Transaction encryption and tokenization
- PCI DSS compliance for card data

---

## Success Metrics:
- **Transaction Success Rate**: >99.5% UPI transaction success
- **Processing Latency**: <100ms API response, <3s end-to-end
- **Fraud Prevention**: <0.01% fraud rate with minimal false positives
- **System Uptime**: 99.99% availability for payment services
- **Compliance**: 100% regulatory reporting accuracy and timeliness

**🎯 This design demonstrates fintech expertise, regulatory compliance, fraud prevention, and building mission-critical payment systems handling billions in transactions.**




