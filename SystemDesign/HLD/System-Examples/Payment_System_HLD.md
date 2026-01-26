# Payment System (Stripe/PayPal) - High Level Design

## Table of Contents
1. [System Requirements](#system-requirements)
2. [Capacity Estimation](#capacity-estimation)
3. [High-Level Design](#high-level-design)
4. [Request Flows](#request-flows)
5. [Detailed Component Design](#detailed-component-design)
6. [Trade-offs and Tech Choices](#trade-offs-and-tech-choices)
7. [Failure Scenarios and Bottlenecks](#failure-scenarios-and-bottlenecks)
8. [Future Improvements](#future-improvements)
9. [Interviewer Questions & Answers](#interviewer-questions--answers)

---

## System Requirements

### Functional Requirements

1. **Payment Processing**
   - Accept payments (cards, bank transfers, wallets)
   - Authorize, capture, void transactions
   - Support multiple currencies
   - Handle recurring payments (subscriptions)

2. **Merchant Services**
   - Merchant onboarding
   - API keys management
   - Dashboard and reporting
   - Payout to merchants

3. **Security & Compliance**
   - PCI DSS compliance
   - Fraud detection
   - 3D Secure authentication
   - Tokenization

4. **Refunds & Disputes**
   - Process refunds
   - Handle chargebacks
   - Dispute resolution

5. **Financial Operations**
   - Settlement and reconciliation
   - Multi-currency support
   - Tax calculation

### Non-Functional Requirements

1. **Reliability**: 99.999% for critical paths
2. **Consistency**: Strong consistency (no double charges)
3. **Latency**: < 500ms for auth, < 2s end-to-end
4. **Security**: Zero tolerance for breaches
5. **Compliance**: PCI DSS Level 1, SOC 2, GDPR
6. **Scale**: Millions of transactions per day

---

## Capacity Estimation

### Traffic Estimates

```
Transactions:
- Daily transactions: 100 Million
- Peak TPS: 5,000 transactions/second
- Average TPS: 1,200 transactions/second

API Calls:
- Tokenization: 500M/day
- Auth calls: 100M/day
- Capture calls: 90M/day
- Refunds: 5M/day
```

### Storage Estimates

```
Transaction Records:
- Per transaction: 5 KB
- Daily: 100M * 5 KB = 500 GB/day
- Yearly: 182 TB

Logs and Audit:
- 10x transaction size (compliance)
- Yearly: 1.8 PB

Tokenized Card Data:
- 500M cards * 1 KB = 500 GB
```

### Money Flow

```
Daily Volume:
- GMV: $10 Billion/day
- Average transaction: $100
- Fee revenue: ~2.9% = $290M/day
```

---

## High-Level Design

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                               MERCHANT APPLICATIONS                                  │
│                        (E-commerce, Mobile Apps, POS)                               │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                                    HTTPS + TLS 1.3
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              API GATEWAY                                             │
│                                                                                     │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐               │
│  │    Auth     │  │ Rate Limit  │  │   Routing   │  │   Logging   │               │
│  │ (API Keys)  │  │             │  │             │  │             │               │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘               │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
              ┌──────────────────────────┼──────────────────────────┐
              ▼                          ▼                          ▼
┌──────────────────────┐   ┌──────────────────────┐   ┌──────────────────────────────┐
│  TOKENIZATION SVC    │   │   PAYMENT SERVICE    │   │    MERCHANT SERVICE          │
│  (PCI Scope)         │   │                      │   │                              │
│                      │   │  - Payment intents   │   │  - Onboarding                │
│  - Card tokenization │   │  - Authorization     │   │  - API keys                  │
│  - Secure storage    │   │  - Capture           │   │  - Settings                  │
│  - PAN encryption    │   │  - Refunds           │   │  - Reporting                 │
└──────────────────────┘   └──────────────────────┘   └──────────────────────────────┘
          │                          │
          │                          ▼
          │              ┌──────────────────────┐
          │              │   FRAUD SERVICE      │
          │              │                      │
          │              │  - Risk scoring      │
          │              │  - Rules engine      │
          │              │  - ML models         │
          └─────────────►│  - 3DS orchestration │
                         └──────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                           PAYMENT PROCESSOR                                          │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                      GATEWAY INTEGRATION LAYER                               │   │
│  │                                                                              │   │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐     │   │
│  │  │  Visa    │  │  Master  │  │   Amex   │  │  Banks   │  │  Wallets │     │   │
│  │  │  Network │  │  Card    │  │          │  │  (ACH)   │  │ (PayPal) │     │   │
│  │  └──────────┘  └──────────┘  └──────────┘  └──────────┘  └──────────┘     │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              MESSAGE QUEUE (Kafka)                                   │
│                                                                                     │
│  Topics: payments, settlements, webhooks, disputes, audit-log                       │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
         ┌───────────────────────────────┼───────────────────────────────┐
         ▼                               ▼                               ▼
┌─────────────────────┐    ┌─────────────────────────┐    ┌─────────────────────────┐
│  SETTLEMENT SVC     │    │    WEBHOOK SERVICE      │    │    LEDGER SERVICE       │
│                     │    │                         │    │                         │
│  - Batch processing │    │  - Event delivery       │    │  - Double-entry         │
│  - Reconciliation   │    │  - Retry logic          │    │  - Account balances     │
│  - Merchant payouts │    │  - Signature verification│   │  - Audit trail          │
└─────────────────────┘    └─────────────────────────┘    └─────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                  DATA LAYER                                          │
├──────────────────┬──────────────────┬──────────────────┬────────────────────────────┤
│  TRANSACTION DB  │   TOKEN VAULT    │     LEDGER       │        CACHE               │
│   (PostgreSQL    │  (HSM-backed     │  (Immutable      │       (Redis)              │
│    Cluster)      │   encrypted)     │   append-only)   │                            │
├──────────────────┼──────────────────┼──────────────────┼────────────────────────────┤
│  MERCHANT DB     │   FRAUD DATA     │     AUDIT LOG    │    ANALYTICS               │
│  (PostgreSQL)    │  (Feature Store) │  (Compliance)    │   (ClickHouse)             │
└──────────────────┴──────────────────┴──────────────────┴────────────────────────────┘
```

### Core Components

1. **Tokenization Service**: Secure card storage (PCI scope)
2. **Payment Service**: Transaction orchestration
3. **Fraud Service**: Risk assessment and prevention
4. **Settlement Service**: Batch processing and payouts
5. **Ledger Service**: Financial record keeping
6. **Webhook Service**: Event notifications

---

## Request Flows

### Payment Authorization Flow

```
┌────────┐  ┌─────────┐  ┌─────────┐  ┌───────────┐  ┌─────────┐  ┌─────────┐
│Merchant│  │   API   │  │ Payment │  │   Fraud   │  │ Token   │  │ Card    │
│  App   │  │ Gateway │  │ Service │  │  Service  │  │ Vault   │  │ Network │
└───┬────┘  └────┬────┘  └────┬────┘  └─────┬─────┘  └────┬────┘  └────┬────┘
    │            │            │             │             │            │
    │ POST /v1/payment_intents             │             │            │
    │ {amount, currency, payment_method}   │             │            │
    │───────────>│            │             │             │            │
    │            │ Validate   │             │             │            │
    │            │ API key    │             │             │            │
    │            │───────────>│             │             │            │
    │            │            │             │             │            │
    │            │            │ 1. Create payment intent  │            │
    │            │            │    (idempotency check)    │            │
    │            │            │             │             │            │
    │            │            │ 2. Risk assessment        │            │
    │            │            │────────────>│             │            │
    │            │            │             │ Check rules │            │
    │            │            │             │ ML scoring  │            │
    │            │            │<────────────│ ALLOW/3DS   │            │
    │            │            │             │             │            │
    │            │            │ 3. Get card details       │            │
    │            │            │───────────────────────────>│            │
    │            │            │             │             │ Decrypt    │
    │            │            │<───────────────────────────│ PAN       │
    │            │            │             │             │            │
    │            │            │ 4. Send to card network   │            │
    │            │            │────────────────────────────────────────>│
    │            │            │             │             │            │
    │            │            │<────────────────────────────────────────│
    │            │            │ Auth code   │             │            │
    │            │            │             │             │            │
    │            │            │ 5. Store result          │            │
    │            │            │ 6. Queue webhook         │            │
    │            │            │             │             │            │
    │<───────────│<───────────│             │             │            │
    │ {id, status: "succeeded", ...}       │             │            │
```

### Payment Capture Flow

```
┌────────┐  ┌─────────┐  ┌─────────┐  ┌───────────┐  ┌─────────┐
│Merchant│  │ Payment │  │ Ledger  │  │ Settlement│  │ Network │
│  App   │  │ Service │  │ Service │  │  Queue    │  │         │
└───┬────┘  └────┬────┘  └────┬────┘  └─────┬─────┘  └────┬────┘
    │            │            │             │             │
    │ POST /v1/payment_intents/{id}/capture │             │
    │───────────>│            │             │             │
    │            │ Validate state (must be authorized)   │
    │            │            │             │             │
    │            │ Send capture to network  │             │
    │            │────────────────────────────────────────>│
    │            │<────────────────────────────────────────│
    │            │            │             │             │
    │            │ Record in ledger         │             │
    │            │───────────>│             │             │
    │            │            │ Debit: Customer          │
    │            │            │ Credit: Merchant (pending)
    │            │            │             │             │
    │            │ Queue for settlement     │             │
    │            │──────────────────────────>│             │
    │            │            │             │             │
    │<───────────│ {status: "captured"}     │             │
```

### 3D Secure Flow

```
┌────────┐  ┌─────────┐  ┌─────────┐  ┌───────────┐  ┌─────────┐  ┌─────────┐
│Customer│  │Merchant │  │ Payment │  │    3DS    │  │  Issuer │  │  Card   │
│Browser │  │  App    │  │ Service │  │  Server   │  │   ACS   │  │ Network │
└───┬────┘  └────┬────┘  └────┬────┘  └─────┬─────┘  └────┬────┘  └────┬────┘
    │            │            │             │             │            │
    │ Checkout   │            │             │             │            │
    │───────────>│            │             │             │            │
    │            │ Create payment           │             │            │
    │            │───────────>│             │             │            │
    │            │            │ Check 3DS required        │            │
    │            │            │────────────>│             │            │
    │            │            │             │ Authentication request   │
    │            │            │             │────────────>│            │
    │            │            │             │<────────────│            │
    │            │            │             │ Challenge required       │
    │            │            │<────────────│             │            │
    │            │<───────────│             │             │            │
    │            │ {requires_action: true, redirect_url}  │            │
    │<───────────│            │             │             │            │
    │            │            │             │             │            │
    │ Redirect to issuer ACS  │             │             │            │
    │─────────────────────────────────────────────────────>│            │
    │            │            │             │             │            │
    │ Enter OTP/biometric     │             │             │            │
    │<─────────────────────────────────────────────────────│            │
    │─────────────────────────────────────────────────────>│            │
    │            │            │             │             │            │
    │ Redirect back with authentication result            │            │
    │<─────────────────────────────────────────────────────│            │
    │───────────>│            │             │             │            │
    │            │ Confirm payment          │             │            │
    │            │───────────>│             │             │            │
    │            │            │ Continue auth with cavv  │            │
    │            │            │─────────────────────────────────────────>│
    │            │            │<─────────────────────────────────────────│
    │            │<───────────│ Success     │             │            │
    │<───────────│ Order confirmed          │             │            │
```

---

## Detailed Component Design

### 1. Tokenization & Card Vault

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              TOKEN VAULT ARCHITECTURE                                │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  PCI DSS Scope Isolation:                                                           │
│                                                                                     │
│  ┌───────────────────────────────────────────────────────────────────────────────┐ │
│  │                         CARD DATA ENVIRONMENT (CDE)                           │ │
│  │                                                                                │ │
│  │  ┌─────────────────────────────────────────────────────────────────────────┐ │ │
│  │  │                                                                          │ │ │
│  │  │  Tokenization Flow:                                                      │ │ │
│  │  │                                                                          │ │ │
│  │  │  Card Number: 4242424242424242                                          │ │ │
│  │  │       │                                                                  │ │ │
│  │  │       ▼                                                                  │ │ │
│  │  │  ┌─────────────────────────────────────────────────────────────────┐   │ │ │
│  │  │  │  1. Generate token: tok_1234567890abcdef                        │   │ │ │
│  │  │  │  2. Encrypt PAN with HSM key                                    │   │ │ │
│  │  │  │  3. Store encrypted PAN with token reference                    │   │ │ │
│  │  │  └─────────────────────────────────────────────────────────────────┘   │ │ │
│  │  │       │                                                                  │ │ │
│  │  │       ▼                                                                  │ │ │
│  │  │  Token: tok_1234567890abcdef (returned to merchant)                     │ │ │
│  │  │                                                                          │ │ │
│  │  └─────────────────────────────────────────────────────────────────────────┘ │ │
│  │                                                                                │ │
│  │  Storage:                                                                      │ │
│  │  ┌─────────────────────────────────────────────────────────────────────────┐ │ │
│  │  │                                                                          │ │ │
│  │  │  {                                                                       │ │ │
│  │  │    "token": "tok_1234567890abcdef",                                    │ │ │
│  │  │    "encrypted_pan": "AES256-GCM encrypted blob",                       │ │ │
│  │  │    "key_id": "key_v23",  // Key version for rotation                   │ │ │
│  │  │    "last_four": "4242",                                                │ │ │
│  │  │    "brand": "visa",                                                    │ │ │
│  │  │    "exp_month": 12,                                                    │ │ │
│  │  │    "exp_year": 2025,                                                   │ │ │
│  │  │    "fingerprint": "hash_for_dedup",                                    │ │ │
│  │  │    "created_at": "2024-01-15T10:30:00Z"                               │ │ │
│  │  │  }                                                                       │ │ │
│  │  │                                                                          │ │ │
│  │  └─────────────────────────────────────────────────────────────────────────┘ │ │
│  │                                                                                │ │
│  │  HSM (Hardware Security Module):                                              │ │
│  │  - Stores master encryption keys                                              │ │
│  │  - Performs encryption/decryption                                             │ │
│  │  - FIPS 140-2 Level 3 certified                                              │ │
│  │  - Key rotation without re-encryption                                         │ │
│  │                                                                                │ │
│  └───────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                     │
│  Network Tokenization:                                                              │
│  - Visa Token Service (VTS)                                                        │
│  - Mastercard Digital Enablement Service (MDES)                                    │
│  - Replaces real PAN with network token                                            │
│  - Higher approval rates, better security                                          │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 2. Double-Entry Ledger

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                            LEDGER ARCHITECTURE                                       │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Double-entry bookkeeping principle:                                                │
│  Every transaction has equal debits and credits                                     │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  Example: $100 payment from customer to merchant                            │   │
│  │                                                                              │   │
│  │  Entry 1: Authorization (hold funds)                                        │   │
│  │  ├── Debit:  Customer Card Account      $100 (asset -)                     │   │
│  │  └── Credit: Pending Settlement         $100 (liability +)                 │   │
│  │                                                                              │   │
│  │  Entry 2: Capture (confirm payment)                                         │   │
│  │  ├── Debit:  Pending Settlement         $100 (liability -)                 │   │
│  │  └── Credit: Merchant Balance (pending) $97.10 (liability +)               │   │
│  │  └── Credit: Platform Revenue           $2.90 (revenue +)                  │   │
│  │                                                                              │   │
│  │  Entry 3: Settlement (payout to merchant)                                   │   │
│  │  ├── Debit:  Merchant Balance (pending) $97.10 (liability -)               │   │
│  │  └── Credit: Merchant Bank Account      $97.10 (asset -)                   │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Account Types:                                                                     │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  Assets:          Liabilities:           Revenue:                           │   │
│  │  - Cash           - Customer balances    - Processing fees                  │   │
│  │  - Receivables    - Merchant balances    - Subscription fees                │   │
│  │  - Reserves       - Pending settlements  - FX spread                        │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Ledger Implementation:                                                             │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  CREATE TABLE ledger_entries (                                              │   │
│  │      entry_id UUID PRIMARY KEY,                                             │   │
│  │      transaction_id UUID NOT NULL,                                          │   │
│  │      account_id UUID NOT NULL,                                              │   │
│  │      entry_type ENUM('DEBIT', 'CREDIT'),                                   │   │
│  │      amount DECIMAL(20, 8) NOT NULL,                                        │   │
│  │      currency CHAR(3) NOT NULL,                                             │   │
│  │      balance_after DECIMAL(20, 8) NOT NULL,                                │   │
│  │      created_at TIMESTAMP NOT NULL,                                         │   │
│  │      metadata JSONB                                                         │   │
│  │  );                                                                          │   │
│  │                                                                              │   │
│  │  -- Immutable: Only INSERT, never UPDATE or DELETE                         │   │
│  │  -- Corrections via reversing entries                                       │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 3. Fraud Detection System

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                           FRAUD DETECTION ARCHITECTURE                               │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Multi-layer defense:                                                               │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  Layer 1: Rules Engine (milliseconds)                                       │   │
│  │  ├── Velocity rules: >5 transactions in 1 hour → flag                      │   │
│  │  ├── Amount rules: >$10,000 single transaction → review                    │   │
│  │  ├── Geographic rules: Card country ≠ IP country → 3DS                     │   │
│  │  ├── Blocklists: Known fraud BINs, IPs, devices                           │   │
│  │  └── Merchant-specific rules                                                │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                      │                                             │
│                                      ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  Layer 2: ML Models (tens of milliseconds)                                  │   │
│  │                                                                              │   │
│  │  Features:                                                                   │   │
│  │  ├── Transaction: amount, currency, merchant category                       │   │
│  │  ├── Card: age, usage pattern, previous fraud                              │   │
│  │  ├── Device: fingerprint, browser, IP reputation                           │   │
│  │  ├── Behavioral: time patterns, typing speed, mouse movement               │   │
│  │  └── Network: connections to known fraud rings                             │   │
│  │                                                                              │   │
│  │  Models:                                                                     │   │
│  │  ├── Gradient Boosted Trees (primary)                                       │   │
│  │  ├── Neural network (card-not-present)                                      │   │
│  │  └── Graph neural network (fraud ring detection)                            │   │
│  │                                                                              │   │
│  │  Output: Risk score 0-100                                                   │   │
│  │  ├── 0-30: Auto approve                                                     │   │
│  │  ├── 31-70: 3D Secure required                                              │   │
│  │  └── 71-100: Decline or manual review                                       │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                      │                                             │
│                                      ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  Layer 3: Post-transaction Analysis (batch)                                 │   │
│  │  ├── Pattern detection across transactions                                  │   │
│  │  ├── Graph analysis for fraud rings                                         │   │
│  │  ├── Chargeback prediction                                                  │   │
│  │  └── Model retraining with new fraud signals                               │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Implementation:                                                                    │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  class FraudDetector:                                                        │   │
│  │      async def evaluate(self, payment: Payment) -> FraudDecision:          │   │
│  │          # Layer 1: Rules (fast path)                                       │   │
│  │          rule_result = self.rules_engine.evaluate(payment)                  │   │
│  │          if rule_result.is_definitive:                                      │   │
│  │              return rule_result.decision                                    │   │
│  │                                                                              │   │
│  │          # Layer 2: ML scoring                                              │   │
│  │          features = await self.extract_features(payment)                    │   │
│  │          risk_score = self.ml_model.predict(features)                       │   │
│  │                                                                              │   │
│  │          # Decision                                                          │   │
│  │          if risk_score < 30:                                                │   │
│  │              return FraudDecision.APPROVE                                   │   │
│  │          elif risk_score < 70:                                              │   │
│  │              return FraudDecision.REQUIRE_3DS                               │   │
│  │          else:                                                               │   │
│  │              return FraudDecision.DECLINE                                   │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Trade-offs and Tech Choices

### 1. Database Selection

| Component | Choice | Reason |
|-----------|--------|--------|
| Transactions | PostgreSQL | ACID, complex queries |
| Ledger | PostgreSQL | Strong consistency |
| Token Vault | Encrypted PostgreSQL + HSM | Security |
| Cache | Redis | Speed |
| Analytics | ClickHouse | Time-series, aggregations |

### 2. Consistency vs Availability

| Operation | Priority | Reason |
|-----------|----------|--------|
| Authorization | Consistency | No double charges |
| Settlement | Consistency | Financial accuracy |
| Webhooks | Availability | Retry handles failures |
| Analytics | Availability | Can replay |

### 3. Synchronous vs Asynchronous

```
Synchronous (blocking):
- Authorization (merchant waiting)
- Tokenization (card input flow)
- Fraud check (part of auth)

Asynchronous (queued):
- Settlement (batch process)
- Webhooks (fire and forget with retry)
- Reporting
- Reconciliation
```

---

## Failure Scenarios and Bottlenecks

### 1. Payment Network Timeout

```
┌─────────────────────────────────────────────────────────────────┐
│              TIMEOUT HANDLING                                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Scenario: Card network doesn't respond within 30 seconds       │
│                                                                 │
│  Strategy:                                                       │
│  1. Timeout after 30 seconds                                    │
│  2. Mark transaction as UNKNOWN                                 │
│  3. Return soft decline to merchant                             │
│  4. Background job queries network for status                   │
│  5. If approved → notify merchant via webhook                  │
│  6. If declined → no action needed                             │
│                                                                 │
│  Implementation:                                                 │
│  async def process_payment(payment):                            │
│      try:                                                        │
│          result = await asyncio.wait_for(                       │
│              network.authorize(payment),                        │
│              timeout=30                                         │
│          )                                                       │
│          return result                                          │
│      except asyncio.TimeoutError:                               │
│          payment.status = "UNKNOWN"                             │
│          await queue_for_status_check(payment)                  │
│          return PaymentResult(                                  │
│              status="pending",                                  │
│              requires_followup=True                             │
│          )                                                       │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 2. Idempotency for Retries

```
Problem: Network failure, merchant retries, risk of double charge

Solution: Idempotency keys

POST /v1/charges
Idempotency-Key: order_12345_attempt_1
{amount: 100, currency: "usd", ...}

Implementation:
1. Hash (idempotency_key + merchant_id)
2. Check if exists in cache/DB
3. If exists: Return cached result
4. If not: Process and cache result

TTL: 24 hours (Stripe's approach)
```

### 3. Database Failover

```
┌─────────────────────────────────────────────────────────────────┐
│              DATABASE HA                                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  PostgreSQL with synchronous replication:                       │
│                                                                 │
│  Primary ──sync──► Standby 1 (same AZ)                         │
│     │                                                           │
│     └──async──► Standby 2 (different AZ)                       │
│     └──async──► Standby 3 (different region - DR)              │
│                                                                 │
│  Failover:                                                       │
│  1. Health check fails on primary (3 consecutive)              │
│  2. Promote Standby 1 to primary                               │
│  3. Update connection pool                                      │
│  4. Alert operations                                            │
│                                                                 │
│  RTO: < 30 seconds                                              │
│  RPO: 0 (synchronous replication)                              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Future Improvements

### 1. Real-Time Payments

- Instant bank transfers (RTP, FedNow)
- 24/7 settlement
- Lower fees than cards

### 2. Cryptocurrency Support

- Stablecoin settlements
- Bitcoin/Ethereum acceptance
- Instant global transfers

### 3. Embedded Finance

- Banking-as-a-Service
- Card issuing for platforms
- Lending integration

---

## Interviewer Questions & Answers

### Q1: How do you ensure no double charges?

**Answer:**

**Multi-layer protection:**

1. **Idempotency keys:**
```python
async def create_charge(idempotency_key, charge_request):
    # Check if already processed
    existing = await redis.get(f"idem:{idempotency_key}")
    if existing:
        return json.loads(existing)  # Return cached result
    
    # Acquire lock to prevent parallel processing
    lock = await redis.set(
        f"idem_lock:{idempotency_key}",
        "1",
        nx=True,  # Only if not exists
        ex=60     # 60 second expiry
    )
    
    if not lock:
        raise ConcurrentRequestError()
    
    try:
        result = await process_charge(charge_request)
        
        # Cache result
        await redis.setex(
            f"idem:{idempotency_key}",
            86400,  # 24 hours
            json.dumps(result)
        )
        
        return result
    finally:
        await redis.delete(f"idem_lock:{idempotency_key}")
```

2. **Database constraints:**
```sql
CREATE UNIQUE INDEX idx_charge_idempotency 
ON charges(merchant_id, idempotency_key);
```

3. **Card network deduplication:**
- Same card + merchant + amount within 5 minutes flagged
- Unique transaction reference sent to network

---

### Q2: Explain your PCI compliance architecture.

**Answer:**

**Scope reduction:**
```
┌─────────────────────────────────────────────────────────────────┐
│                 PCI SCOPE ISOLATION                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  In-Scope (CDE):                    Out-of-Scope:              │
│  ├── Tokenization service           ├── API Gateway            │
│  ├── Token vault                    ├── Payment orchestration  │
│  ├── HSM                            ├── Merchant portal        │
│  └── Direct network connections     ├── Analytics              │
│                                     └── Webhooks               │
│                                                                 │
│  Data flow:                                                      │
│  Card → Tokenization → Token → Payment Service → Token Vault   │
│         (in scope)              (out of scope)    (in scope)   │
│                                                                 │
│  Merchant never sees card numbers (tokenization on frontend)   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Key controls:**
- HSM for key management (FIPS 140-2 Level 3)
- Encryption at rest (AES-256)
- TLS 1.3 for all connections
- Network segmentation
- Quarterly penetration tests
- Annual PCI audit

---

### Q3: How do you handle refunds?

**Answer:**

**Refund flow:**
```python
class RefundService:
    async def process_refund(self, charge_id, amount=None, reason=None):
        charge = await get_charge(charge_id)
        
        # Validate
        if charge.status != "succeeded":
            raise InvalidChargeStatus()
        
        refund_amount = amount or charge.amount
        if refund_amount > charge.amount - charge.amount_refunded:
            raise RefundExceedsCharge()
        
        # Create refund record
        refund = Refund(
            charge_id=charge_id,
            amount=refund_amount,
            status="pending",
            reason=reason
        )
        
        # Process with network
        if charge.captured:
            # Actual refund (money back)
            result = await card_network.refund(
                charge.network_transaction_id,
                refund_amount
            )
        else:
            # Just void/release (no money moved yet)
            result = await card_network.void(
                charge.network_transaction_id
            )
        
        # Update ledger
        await ledger.record_refund(refund)
        
        # Update charge
        charge.amount_refunded += refund_amount
        
        # Webhook
        await webhook.send("refund.created", refund)
        
        return refund
```

**Ledger entries:**
```
Refund $50 on $100 charge:
├── Debit:  Platform Revenue    $1.45 (fee clawback)
├── Debit:  Merchant Balance    $48.55
└── Credit: Customer Card       $50.00
```

---

### Q4: How do you handle settlement and payouts?

**Answer:**

**Settlement cycle:**
```
Day 0: Transaction captured
Day 1: Included in settlement batch
Day 2: Funds available in merchant balance
Day 2-3: Payout to merchant bank (if auto-payout enabled)
```

**Implementation:**
```python
class SettlementService:
    async def run_daily_settlement(self, date):
        # Get all captured transactions for the day
        transactions = await get_transactions_for_settlement(date)
        
        # Group by merchant
        by_merchant = group_by_merchant(transactions)
        
        for merchant_id, txns in by_merchant.items():
            # Calculate totals
            gross = sum(t.amount for t in txns)
            fees = sum(t.fee for t in txns)
            refunds = sum(t.refund_amount for t in txns)
            net = gross - fees - refunds
            
            # Create settlement record
            settlement = Settlement(
                merchant_id=merchant_id,
                date=date,
                gross_amount=gross,
                fee_amount=fees,
                refund_amount=refunds,
                net_amount=net,
                transaction_count=len(txns)
            )
            
            # Update merchant balance
            await ledger.credit_merchant_balance(
                merchant_id,
                net,
                settlement_id=settlement.id
            )
            
            # Check if auto-payout enabled
            merchant = await get_merchant(merchant_id)
            if merchant.payout_schedule == "automatic":
                await queue_payout(merchant_id, net)
```

---

### Q5: Design the webhook delivery system.

**Answer:**

**Requirements:**
- At-least-once delivery
- Ordered (within same event type)
- Retry with backoff
- Signature verification

**Implementation:**
```python
class WebhookService:
    async def send_webhook(self, event):
        merchant = await get_merchant(event.merchant_id)
        
        payload = {
            "id": event.id,
            "type": event.type,
            "created": event.created_at,
            "data": event.data
        }
        
        # Sign payload
        signature = self.sign(payload, merchant.webhook_secret)
        
        # Queue for delivery
        await kafka.send("webhooks", {
            "endpoint": merchant.webhook_url,
            "payload": payload,
            "signature": signature,
            "attempt": 1,
            "created_at": now()
        })
    
    async def deliver_webhook(self, webhook):
        try:
            response = await http.post(
                webhook["endpoint"],
                json=webhook["payload"],
                headers={
                    "Stripe-Signature": webhook["signature"],
                    "Content-Type": "application/json"
                },
                timeout=30
            )
            
            if response.status_code == 200:
                await mark_delivered(webhook)
            else:
                await schedule_retry(webhook)
                
        except Exception:
            await schedule_retry(webhook)
    
    async def schedule_retry(self, webhook):
        attempt = webhook["attempt"]
        if attempt >= 8:
            await mark_failed(webhook)
            return
        
        # Exponential backoff: 5min, 30min, 2hr, 8hr, 24hr, 48hr, 72hr
        delay = [300, 1800, 7200, 28800, 86400, 172800, 259200][attempt - 1]
        
        webhook["attempt"] = attempt + 1
        await kafka.send("webhooks", webhook, delay_seconds=delay)
```

---

### Q6: How do you handle multi-currency?

**Answer:**

**Currency handling:**
```python
class CurrencyService:
    async def process_payment(self, payment):
        # Payment currency
        presentment_currency = payment.currency  # EUR
        
        # Merchant settlement currency
        settlement_currency = payment.merchant.settlement_currency  # USD
        
        if presentment_currency == settlement_currency:
            # No conversion needed
            settlement_amount = payment.amount
        else:
            # Get FX rate
            rate = await fx_service.get_rate(
                presentment_currency,
                settlement_currency
            )
            
            # Convert
            settlement_amount = payment.amount * rate
            
            # Store for reconciliation
            payment.fx_rate = rate
            payment.presentment_amount = payment.amount
            payment.presentment_currency = presentment_currency
            payment.settlement_amount = settlement_amount
            payment.settlement_currency = settlement_currency
        
        # Charge in presentment currency (what customer sees)
        # Settle in settlement currency (what merchant receives)

# Fee calculation
def calculate_fee(payment):
    base_fee = payment.settlement_amount * 0.029  # 2.9%
    
    if is_international(payment):
        base_fee += payment.settlement_amount * 0.01  # +1% international
    
    if is_currency_conversion(payment):
        base_fee += payment.settlement_amount * 0.01  # +1% FX
    
    return base_fee + 0.30  # + $0.30 fixed
```

---

### Q7: How do you handle chargebacks?

**Answer:**

**Chargeback flow:**
```
1. Customer disputes with bank
2. Bank issues chargeback
3. Card network notifies us
4. We debit merchant balance
5. We notify merchant
6. Merchant submits evidence (optional)
7. Bank decides
8. If won: Credit merchant back
9. If lost: Chargeback finalized
```

**Implementation:**
```python
class ChargebackService:
    async def handle_chargeback(self, chargeback_notice):
        # Find original charge
        charge = await find_charge_by_network_id(
            chargeback_notice.original_transaction_id
        )
        
        # Create dispute record
        dispute = Dispute(
            charge_id=charge.id,
            amount=chargeback_notice.amount,
            reason=chargeback_notice.reason_code,
            evidence_due_by=chargeback_notice.respond_by,
            status="needs_response"
        )
        
        # Debit merchant (funds held)
        await ledger.hold_for_dispute(
            merchant_id=charge.merchant_id,
            amount=dispute.amount + DISPUTE_FEE
        )
        
        # Notify merchant
        await webhook.send("charge.dispute.created", dispute)
        await email.send_dispute_notification(charge.merchant, dispute)
        
        return dispute
    
    async def submit_evidence(self, dispute_id, evidence):
        dispute = await get_dispute(dispute_id)
        
        # Package evidence
        evidence_package = {
            "customer_signature": evidence.signature,
            "shipping_tracking": evidence.tracking_number,
            "delivery_confirmation": evidence.delivery_proof,
            "customer_communication": evidence.emails,
            "refund_policy": evidence.policy_url
        }
        
        # Submit to network
        await card_network.submit_dispute_evidence(
            dispute.network_id,
            evidence_package
        )
        
        dispute.status = "under_review"
        await save(dispute)
```

---

### Q8: How do you ensure high availability?

**Answer:**

**Architecture:**
```
┌─────────────────────────────────────────────────────────────────┐
│                    HA ARCHITECTURE                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Multi-AZ deployment:                                           │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐            │
│  │    AZ-1     │  │    AZ-2     │  │    AZ-3     │            │
│  │             │  │             │  │             │            │
│  │ API servers │  │ API servers │  │ API servers │            │
│  │ (active)    │  │ (active)    │  │ (active)    │            │
│  │             │  │             │  │             │            │
│  │ DB primary  │  │ DB standby  │  │ DB standby  │            │
│  │ (sync)      │  │ (sync)      │  │ (async)     │            │
│  └─────────────┘  └─────────────┘  └─────────────┘            │
│                                                                 │
│  Load balancer: Active-active across all AZs                   │
│  Health checks: 2 second intervals                             │
│  Failover: Automatic in < 30 seconds                           │
│                                                                 │
│  Circuit breakers per external dependency:                     │
│  - Card networks                                               │
│  - Bank connections                                            │
│  - Fraud service                                               │
│                                                                 │
│  Degradation modes:                                            │
│  - Fraud service down → Allow with logging                    │
│  - Secondary network down → Route to primary only             │
│  - Analytics down → Continue processing                       │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**SLAs:**
- 99.999% for auth endpoint
- 99.99% for other APIs
- < 500ms p99 latency

---

### Q9: How do you handle subscriptions/recurring payments?

**Answer:**

**Subscription engine:**
```python
class SubscriptionService:
    async def create_subscription(self, customer_id, plan_id, payment_method):
        plan = await get_plan(plan_id)
        
        subscription = Subscription(
            customer_id=customer_id,
            plan_id=plan_id,
            payment_method_id=payment_method,
            status="active",
            current_period_start=now(),
            current_period_end=now() + plan.interval,
            billing_cycle_anchor=now()
        )
        
        # Charge first period
        charge = await create_charge(
            customer_id=customer_id,
            amount=plan.amount,
            payment_method=payment_method,
            metadata={"subscription_id": subscription.id}
        )
        
        if charge.status != "succeeded":
            subscription.status = "incomplete"
        
        return subscription
    
    # Cron job: Run every hour
    async def process_renewals(self):
        due = await get_subscriptions_due_for_renewal()
        
        for sub in due:
            await self.renew_subscription(sub)
    
    async def renew_subscription(self, subscription):
        # Create invoice
        invoice = await create_invoice(subscription)
        
        # Attempt charge
        charge = await create_charge(
            customer_id=subscription.customer_id,
            amount=invoice.amount,
            payment_method=subscription.payment_method_id
        )
        
        if charge.status == "succeeded":
            # Extend subscription
            subscription.current_period_start = subscription.current_period_end
            subscription.current_period_end += subscription.plan.interval
            
            await webhook.send("invoice.paid", invoice)
        else:
            # Retry logic
            await handle_failed_renewal(subscription, charge)
    
    async def handle_failed_renewal(self, subscription, failed_charge):
        # Retry schedule: 3 days, 5 days, 7 days
        if subscription.retry_count < 3:
            subscription.retry_count += 1
            subscription.next_retry = now() + RETRY_SCHEDULE[subscription.retry_count]
        else:
            # Cancel subscription
            subscription.status = "canceled"
            subscription.canceled_at = now()
            await webhook.send("customer.subscription.deleted", subscription)
```

---

### Q10: How do you prevent and detect fraud?

**Answer:**

**Real-time signals:**
```python
class FraudFeatures:
    async def extract(self, payment, device_info, behavior):
        features = {}
        
        # Transaction features
        features["amount"] = payment.amount
        features["currency"] = payment.currency
        features["is_first_transaction"] = await is_first_transaction(payment.customer_id)
        
        # Card features
        card = await get_card(payment.card_id)
        features["card_age_days"] = (now() - card.created_at).days
        features["card_country"] = card.country
        features["card_bin_risk"] = await get_bin_risk(card.bin)
        
        # Device features
        features["device_fingerprint"] = device_info.fingerprint
        features["is_known_device"] = await is_known_device(payment.customer_id, device_info)
        features["ip_country"] = geoip(device_info.ip).country
        features["ip_risk_score"] = await get_ip_risk(device_info.ip)
        features["is_vpn"] = await detect_vpn(device_info.ip)
        
        # Velocity features
        features["transactions_1h"] = await count_transactions(payment.card_id, hours=1)
        features["transactions_24h"] = await count_transactions(payment.card_id, hours=24)
        features["amount_24h"] = await sum_amount(payment.card_id, hours=24)
        features["unique_merchants_24h"] = await count_merchants(payment.card_id, hours=24)
        
        # Behavioral features (from JS SDK)
        features["typing_speed"] = behavior.typing_speed
        features["mouse_entropy"] = behavior.mouse_entropy
        features["form_completion_time"] = behavior.form_time
        
        # Network features
        features["shared_card_count"] = await count_shared_card(card.fingerprint)
        features["shared_device_count"] = await count_shared_device(device_info.fingerprint)
        
        return features

# Model inference
async def score_transaction(payment, device_info, behavior):
    features = await fraud_features.extract(payment, device_info, behavior)
    
    # Primary model
    primary_score = await primary_model.predict(features)
    
    # Secondary model (catches different patterns)
    secondary_score = await secondary_model.predict(features)
    
    # Ensemble
    final_score = primary_score * 0.7 + secondary_score * 0.3
    
    return final_score
```

---

### Bonus: Quick Architecture Summary

```
┌─────────────────────────────────────────────────────────────────┐
│              PAYMENT SYSTEM - FINAL ARCHITECTURE                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Scale: 100M transactions/day, $10B GMV/day                    │
│                                                                 │
│  Core Components:                                               │
│  ├── Tokenization Service - PCI scope, HSM-backed              │
│  ├── Payment Service - Auth, capture, refund orchestration     │
│  ├── Fraud Service - ML scoring, rules engine                  │
│  ├── Ledger Service - Double-entry, immutable                  │
│  ├── Settlement Service - Daily batch, reconciliation          │
│  └── Webhook Service - At-least-once delivery                  │
│                                                                 │
│  Data Stores:                                                   │
│  ├── PostgreSQL - Transactions, ledger (strong consistency)    │
│  ├── Token Vault - HSM-encrypted card data                     │
│  ├── Redis - Idempotency, rate limiting, cache                 │
│  └── ClickHouse - Analytics, reporting                         │
│                                                                 │
│  Key Decisions:                                                 │
│  ├── PCI scope isolation via tokenization                      │
│  ├── Double-entry ledger for financial accuracy                │
│  ├── Idempotency keys for retry safety                         │
│  ├── Multi-layer fraud detection (rules + ML)                  │
│  └── Synchronous auth, async settlement                        │
│                                                                 │
│  SLA: 99.999% (auth), <500ms p99 latency                       │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```
