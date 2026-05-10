# UPI Payment System - Low Level Design (Machine Coding)

## Table of Contents
1. [Problem Statement](#problem-statement)
2. [Requirements](#requirements)
3. [Database Design with Explanations](#database-design-with-explanations)
4. [Design Patterns Used](#design-patterns-used)
5. [SOLID Principles Applied](#solid-principles-applied)
6. [Code Implementation](#code-implementation)
7. [Edge Cases & Tests](#edge-cases--tests)
8. [Summary](#summary)

---

## Problem Statement

Design a UPI-based digital payment system that handles P2P transfers, wallet recharges, and bill payments. The system must ensure financial correctness through double-entry bookkeeping, thread-safe balance operations, and idempotent transaction execution.

---

## Requirements

### Functional Requirements
1. **User Management**: Register users, link bank accounts, create wallets
2. **P2P Transfers**: Send/receive money via UPI ID or phone number
3. **Wallet Operations**: Recharge wallet from bank, pay from wallet balance
4. **Bill Payments**: Pay utility bills, merchant payments
5. **Transaction History**: Query transactions by user, status, date range
6. **Multiple Payment Methods**: Wallet, bank direct, UPI (different strategies)

### Non-Functional Requirements
- **Thread Safety**: Concurrent balance updates must be atomic; no race conditions
- **Idempotency**: Duplicate requests (same idempotency key) must not double-debit
- **Double-Entry Bookkeeping**: Every debit has a matching credit; ledger always balances
- **Audit Trail**: Immutable transaction ledger for compliance and reconciliation
- **Extensibility**: Easy to add new payment methods without modifying core logic

---

## Database Design with Explanations

### Why Each Table Exists

```sql
-- ============================================================================
-- TABLE: users
-- WHY: Core identity entity. Every transaction involves users (sender/receiver).
--      We need KYC status for regulatory limits, and user metadata for
--      notifications and fraud detection. Users are the root of the payment graph.
-- ============================================================================
CREATE TABLE users (
    user_id UUID PRIMARY KEY,
    phone_number VARCHAR(15) UNIQUE NOT NULL,
    email VARCHAR(255),
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    kyc_status VARCHAR(20) DEFAULT 'pending',   -- pending, verified, rejected
    kyc_level INTEGER DEFAULT 1,                -- 1: basic, 2: full (affects limits)
    daily_limit DECIMAL(12,2) DEFAULT 5000,
    monthly_limit DECIMAL(12,2) DEFAULT 10000,
    user_status VARCHAR(20) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- WHY index on phone? Login/lookup by phone is the primary auth path.
CREATE INDEX idx_users_phone ON users(phone_number);
-- WHY index on kyc_status? Limit checks filter by KYC before every transaction.
CREATE INDEX idx_users_kyc ON users(kyc_status, kyc_level);


-- ============================================================================
-- TABLE: wallets
-- WHY SEPARATE WALLET FROM BANK: Wallets are our internal ledger. Bank accounts
--      are external. Separation gives us:
--      1. Wallet = fast, in-app balance (no bank round-trip for wallet-to-wallet)
--      2. Bank = source of truth for external funds; we only sync on recharge/withdraw
--      3. Regulatory clarity: wallet balance is our liability; bank is user's asset
--      4. Different consistency models: wallet needs ACID; bank is eventually consistent
--
-- RELATIONSHIP: wallets → users (One-to-One)
--   WHY FK? Each wallet belongs to exactly one user. user_id UNIQUE enforces 1:1.
-- ============================================================================
CREATE TABLE wallets (
    wallet_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE UNIQUE,
    available_balance DECIMAL(12,2) DEFAULT 0.00 NOT NULL,
    locked_balance DECIMAL(12,2) DEFAULT 0.00 NOT NULL,  -- Pending txns
    currency VARCHAR(3) DEFAULT 'INR',
    wallet_status VARCHAR(20) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    CHECK (available_balance >= 0),
    CHECK (locked_balance >= 0)
);

-- WHY index on user_id? Every balance check/update is by user. FK creates index, but
-- we add explicit one for wallet_status filter in batch jobs.
CREATE INDEX idx_wallets_user_status ON wallets(user_id, wallet_status);


-- ============================================================================
-- TABLE: bank_accounts
-- WHY: Users link external bank accounts for recharge and direct debit. We need
--      IFSC, account number, UPI ID for routing. Verification status tracks
--      penny-drop validation. Separate from wallet because bank is external;
--      we don't "own" the balance—we initiate debits via bank APIs.
-- ============================================================================
CREATE TABLE bank_accounts (
    bank_account_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    account_number VARCHAR(20) NOT NULL,
    ifsc_code VARCHAR(11) NOT NULL,
    bank_name VARCHAR(100) NOT NULL,
    account_holder_name VARCHAR(200) NOT NULL,
    upi_id VARCHAR(100),                         -- user@bankname
    verification_status VARCHAR(20) DEFAULT 'pending',
    is_primary BOOLEAN DEFAULT false,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id, account_number, ifsc_code)
);

CREATE INDEX idx_bank_accounts_user ON bank_accounts(user_id);
CREATE INDEX idx_bank_accounts_upi ON bank_accounts(upi_id) WHERE upi_id IS NOT NULL;


-- ============================================================================
-- TABLE: transactions
-- WHY: Immutable record of every payment attempt. Status tracks lifecycle
--      (INITIATED → PROCESSING → SUCCESS/FAILED). We never update amount;
--      only status and timestamps. Enables audit and dispute resolution.
-- ============================================================================
CREATE TABLE transactions (
    transaction_id UUID PRIMARY KEY,
    idempotency_key VARCHAR(64) UNIQUE NOT NULL,  -- WHY: Prevents duplicate execution
    sender_id UUID REFERENCES users(user_id),
    receiver_id UUID REFERENCES users(user_id),
    amount DECIMAL(12,2) NOT NULL,
    currency VARCHAR(3) DEFAULT 'INR',
    transaction_type VARCHAR(30) NOT NULL,        -- SEND, RECEIVE, RECHARGE, BILL_PAY
    transaction_status VARCHAR(20) DEFAULT 'INITIATED',
    payment_method VARCHAR(30) NOT NULL,
    description TEXT,
    failure_reason TEXT,
    initiated_at TIMESTAMP DEFAULT NOW(),
    completed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_transactions_sender_time ON transactions(sender_id, initiated_at DESC);
CREATE INDEX idx_transactions_receiver_time ON transactions(receiver_id, initiated_at DESC);
CREATE INDEX idx_transactions_status ON transactions(transaction_status, initiated_at);
-- WHY idempotency_key unique? Enforces at DB level that we never process same request twice.
CREATE UNIQUE INDEX idx_transactions_idempotency ON transactions(idempotency_key);


-- ============================================================================
-- TABLE: transaction_ledger
-- WHY DOUBLE-ENTRY LEDGER: In accounting, every transaction has two sides—debit
--      and credit. For "A sends 100 to B": debit A's wallet 100, credit B's 100.
--      Sum of all ledger entries must always be zero. This catches bugs (e.g.
--      we credited B but forgot to debit A) and enables reconciliation.
--
--      Each row is ONE side of a double entry. A single transaction creates
--      two rows: one DEBIT, one CREDIT. balance_after is running balance for
--      that wallet—useful for debugging and audit.
-- ============================================================================
CREATE TABLE transaction_ledger (
    ledger_id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES transactions(transaction_id),
    wallet_id UUID NOT NULL REFERENCES wallets(wallet_id),
    entry_type VARCHAR(10) NOT NULL,             -- DEBIT or CREDIT
    amount DECIMAL(12,2) NOT NULL,
    balance_before DECIMAL(12,2) NOT NULL,
    balance_after DECIMAL(12,2) NOT NULL,
    created_at TIMESTAMP DEFAULT NOW(),
    CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    CHECK (amount > 0)
);

CREATE INDEX idx_ledger_transaction ON transaction_ledger(transaction_id);
CREATE INDEX idx_ledger_wallet_time ON transaction_ledger(wallet_id, created_at DESC);


-- ============================================================================
-- TABLE: payment_methods
-- WHY: User can have multiple payment options (wallet, bank1, bank2, UPI).
--      This table stores user preferences and which methods are enabled.
--      Decouples "how user pays" from "what we support"—we can add new
--      methods (e.g. BNPL) without schema changes to transactions.
-- ============================================================================
CREATE TABLE payment_methods (
    payment_method_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    method_type VARCHAR(30) NOT NULL,            -- WALLET, BANK_DIRECT, UPI
    reference_id UUID,                           -- wallet_id or bank_account_id
    display_order INTEGER DEFAULT 0,
    is_default BOOLEAN DEFAULT false,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_payment_methods_user ON payment_methods(user_id);
```

---

## Design Patterns Used

| Pattern | Where Used | Why |
|---------|------------|-----|
| **Strategy** | `PaymentStrategy` → `WalletPayment`, `BankDirectPayment`, `UPIPayment` | Different payment flows (wallet vs bank vs UPI) without if-else chains. Open/Closed: add new payment method = new strategy class. |
| **State** | `Transaction` lifecycle: `INITIATED` → `PROCESSING` → `SUCCESS`/`FAILED`/`REFUNDED` | Strict state transitions prevent invalid ops (e.g. can't refund a FAILED txn). Encapsulates transition rules. |
| **Observer** | `TransactionObserver` → `NotificationObserver`, `FraudDetectionObserver`, `LedgerUpdateObserver` | Post-transaction side effects (notify, log, fraud check) are decoupled. Add new observers without touching core logic. |
| **Factory** | `PaymentStrategyFactory` | Creates appropriate strategy based on `PaymentMethod` enum. Centralizes creation logic; callers don't know concrete types. |
| **Command** | `TransactionCommand` with `execute()` / `undo()` | Encapsulates payment as object. Enables idempotency (check command ID before execute), undo for refunds, replay for retries. |

---

## SOLID Principles Applied

| Principle | Application |
|-----------|-------------|
| **S - Single Responsibility** | `Wallet` only manages balance; `Transaction` only holds state; `PaymentService` orchestrates; observers handle one concern each (notify, fraud, ledger). |
| **O - Open/Closed** | New payment method = new `PaymentStrategy` impl; new post-txn action = new `TransactionObserver`. No changes to `PaymentService`. |
| **L - Liskov Substitution** | Any `PaymentStrategy` can replace another; any `TransactionObserver` can replace another. Callers depend on interfaces. |
| **I - Interface Segregation** | `PaymentStrategy` has only `execute()`; `TransactionObserver` has only `onTransactionComplete()`. No fat interfaces. |
| **D - Dependency Inversion** | `PaymentService` depends on `PaymentStrategy` and `TransactionObserver` interfaces, not concrete classes. Strategies/observers injected. |

---

## Code Implementation

### Enums

```java
package com.upi.enums;

/**
 * Transaction lifecycle states. State pattern: only valid transitions allowed.
 * INITIATED -> PROCESSING -> SUCCESS | FAILED
 * SUCCESS -> REFUNDED (for refunds only)
 */
public enum TransactionStatus {
    INITIATED,   // Created, not yet processed
    PROCESSING,  // Balance locked, external call in progress
    SUCCESS,     // Completed
    FAILED,      // Failed (can retry with new idempotency key)
    REFUNDED     // Was SUCCESS, then refunded
}

public enum TransactionType {
    SEND,        // P2P: sender initiates
    RECEIVE,     // P2P: receiver side
    RECHARGE,    // Bank -> Wallet
    BILL_PAY     // Wallet/Bank -> Merchant
}

public enum PaymentMethod {
    WALLET,           // Pay from wallet balance
    BANK_DIRECT,      // Direct debit from linked bank
    UPI               // UPI ID / VPA payment
}
```

### Models with Encapsulation

```java
package com.upi.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Wallet with synchronized balance operations.
 * WHY: Balance updates must be atomic. Concurrent threads calling debit/credit
 * could cause race conditions. synchronized ensures only one thread modifies
 * balance at a time. BigDecimal for financial precision (no float/double).
 */
public class Wallet {
    private final UUID walletId;
    private final UUID userId;
    private BigDecimal availableBalance;
    private BigDecimal lockedBalance;

    public Wallet(UUID walletId, UUID userId, BigDecimal availableBalance, BigDecimal lockedBalance) {
        this.walletId = walletId;
        this.userId = userId;
        this.availableBalance = availableBalance;
        this.lockedBalance = lockedBalance;
    }

    /**
     * Thread-safe debit. Fails if insufficient balance.
     * WHY synchronized: Prevents lost updates when two debits happen concurrently.
     */
    public synchronized boolean debit(BigDecimal amount) {
        if (availableBalance.compareTo(amount) < 0) return false;
        availableBalance = availableBalance.subtract(amount);
        return true;
    }

    /**
     * Thread-safe credit.
     */
    public synchronized void credit(BigDecimal amount) {
        availableBalance = availableBalance.add(amount);
    }

    /**
     * Lock amount for pending transaction. Reduces available, increases locked.
     */
    public synchronized boolean lock(BigDecimal amount) {
        if (availableBalance.compareTo(amount) < 0) return false;
        availableBalance = availableBalance.subtract(amount);
        lockedBalance = lockedBalance.add(amount);
        return true;
    }

    /**
     * Unlock on failure: move locked back to available.
     */
    public synchronized void unlock(BigDecimal amount) {
        lockedBalance = lockedBalance.subtract(amount);
        availableBalance = availableBalance.add(amount);
    }

    /**
     * Commit lock: on success, locked becomes permanent debit (no-op on balance).
     */
    public synchronized void commitLock(BigDecimal amount) {
        lockedBalance = lockedBalance.subtract(amount);
    }

    public synchronized BigDecimal getAvailableBalance() {
        return availableBalance;
    }

    public UUID getWalletId() { return walletId; }
    public UUID getUserId() { return userId; }
}
```

```java
package com.upi.model;

import com.upi.enums.TransactionStatus;
import com.upi.enums.TransactionType;
import com.upi.enums.PaymentMethod;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Transaction with state machine. Strict transitions only.
 * WHY State pattern: Invalid transitions (e.g. FAILED -> SUCCESS) throw.
 * Ensures we never mark a failed txn as success by mistake.
 */
public class Transaction {
    private final UUID transactionId;
    private final String idempotencyKey;
    private final UUID senderId;
    private final UUID receiverId;
    private final BigDecimal amount;
    private final TransactionType type;
    private final PaymentMethod paymentMethod;
    private TransactionStatus status;
    private String failureReason;
    private final Instant initiatedAt;
    private Instant completedAt;

    public Transaction(UUID transactionId, String idempotencyKey, UUID senderId, UUID receiverId,
                       BigDecimal amount, TransactionType type, PaymentMethod paymentMethod) {
        this.transactionId = transactionId;
        this.idempotencyKey = idempotencyKey;
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.amount = amount;
        this.type = type;
        this.paymentMethod = paymentMethod;
        this.status = TransactionStatus.INITIATED;
        this.initiatedAt = Instant.now();
    }

    /**
     * State machine: only valid transitions allowed.
     */
    public void transitionTo(TransactionStatus newStatus) {
        switch (status) {
            case INITIATED:
                if (newStatus != TransactionStatus.PROCESSING) throw new IllegalStateException("INITIATED -> " + newStatus + " invalid");
                break;
            case PROCESSING:
                if (newStatus != TransactionStatus.SUCCESS && newStatus != TransactionStatus.FAILED)
                    throw new IllegalStateException("PROCESSING -> " + newStatus + " invalid");
                break;
            case SUCCESS:
                if (newStatus != TransactionStatus.REFUNDED)
                    throw new IllegalStateException("SUCCESS -> " + newStatus + " invalid");
                break;
            case FAILED:
            case REFUNDED:
                throw new IllegalStateException("Cannot transition from " + status);
        }
        this.status = newStatus;
        if (newStatus == TransactionStatus.SUCCESS || newStatus == TransactionStatus.FAILED || newStatus == TransactionStatus.REFUNDED) {
            this.completedAt = Instant.now();
        }
    }

    public void setFailureReason(String reason) { this.failureReason = reason; }
    public UUID getTransactionId() { return transactionId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getSenderId() { return senderId; }
    public UUID getReceiverId() { return receiverId; }
    public BigDecimal getAmount() { return amount; }
    public TransactionType getType() { return type; }
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public TransactionStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
}
```

### Strategy: PaymentStrategy

```java
package com.upi.strategy;

import com.upi.model.Transaction;
import com.upi.model.Wallet;

/**
 * Strategy pattern: Different payment methods have different execution flows.
 * Wallet = internal balance transfer. Bank = external API. UPI = NPCI call.
 * WHY: Open/Closed. Add new method = new strategy. No changes to PaymentService.
 */
public interface PaymentStrategy {
    /**
     * Execute the payment. Returns true if successful.
     * Implementations handle their own validation and external calls.
     */
    boolean execute(Transaction transaction, Wallet senderWallet, Wallet receiverWallet);
}
```

```java
package com.upi.strategy.impl;

import com.upi.model.Transaction;
import com.upi.model.Wallet;
import com.upi.strategy.PaymentStrategy;

/**
 * Pay from wallet balance. Debit sender, credit receiver.
 * Double-entry: one DEBIT ledger row, one CREDIT ledger row.
 */
public class WalletPaymentStrategy implements PaymentStrategy {
    @Override
    public boolean execute(Transaction transaction, Wallet senderWallet, Wallet receiverWallet) {
        if (senderWallet == null || receiverWallet == null) return false;
        // Lock sender balance first (optimistic: we'll commit or unlock)
        if (!senderWallet.lock(transaction.getAmount())) return false;
        try {
            senderWallet.commitLock(transaction.getAmount());  // Permanent debit
            receiverWallet.credit(transaction.getAmount());
            return true;
        } catch (Exception e) {
            senderWallet.unlock(transaction.getAmount());  // Rollback
            return false;
        }
    }
}
```

```java
package com.upi.strategy.impl;

import com.upi.model.Transaction;
import com.upi.model.Wallet;

/**
 * Direct debit from bank. Simulated: in real system, call bank API.
 * Flow: Bank debits -> we credit receiver wallet. Sender wallet not used.
 */
public class BankDirectPaymentStrategy implements PaymentStrategy {
    @Override
    public boolean execute(Transaction transaction, Wallet senderWallet, Wallet receiverWallet) {
        // In production: call bank API to debit sender's account
        // On success: credit receiver wallet
        if (receiverWallet == null) return false;
        receiverWallet.credit(transaction.getAmount());
        return true;  // Simulated success
    }
}
```

```java
package com.upi.strategy.impl;

import com.upi.model.Transaction;
import com.upi.model.Wallet;

/**
 * UPI payment: can use wallet or bank. Simplified: we use wallet here.
 * In production: route via NPCI, handle bank response.
 */
public class UPIPaymentStrategy implements PaymentStrategy {
    @Override
    public boolean execute(Transaction transaction, Wallet senderWallet, Wallet receiverWallet) {
        // UPI can be wallet-backed or bank-backed. Here: wallet.
        return new WalletPaymentStrategy().execute(transaction, senderWallet, receiverWallet);
    }
}
```

### Observer: TransactionObserver

```java
package com.upi.observer;

import com.upi.model.Transaction;

/**
 * Observer pattern: Post-transaction side effects. Notification, fraud, ledger.
 * WHY: Decouples "complete payment" from "notify user" / "update ledger".
 * Add new observers without modifying PaymentService.
 */
public interface TransactionObserver {
    void onTransactionComplete(Transaction transaction);
}
```

```java
package com.upi.observer.impl;

import com.upi.enums.TransactionStatus;
import com.upi.model.Transaction;
import com.upi.observer.TransactionObserver;

/**
 * Send notification to sender and receiver.
 */
public class NotificationObserver implements TransactionObserver {
    @Override
    public void onTransactionComplete(Transaction transaction) {
        if (transaction.getStatus() == TransactionStatus.SUCCESS) {
            // In production: push to sender, push to receiver
            System.out.println("Notification: Txn " + transaction.getTransactionId() + " completed.");
        }
    }
}
```

```java
package com.upi.observer.impl;

import com.upi.model.Transaction;
import com.upi.observer.TransactionObserver;

/**
 * Fraud detection: log for analysis, block if suspicious.
 */
public class FraudDetectionObserver implements TransactionObserver {
    @Override
    public void onTransactionComplete(Transaction transaction) {
        // In production: update ML features, check velocity, amount anomalies
        System.out.println("Fraud check: Txn " + transaction.getTransactionId());
    }
}
```

```java
package com.upi.observer.impl;

import com.upi.enums.TransactionStatus;
import com.upi.model.Transaction;
import com.upi.observer.TransactionObserver;

/**
 * Double-entry ledger update. Every successful txn: DEBIT sender, CREDIT receiver.
 * WHY: Audit trail, reconciliation. Sum of ledger entries = 0.
 */
public class LedgerUpdateObserver implements TransactionObserver {
    // In production: inject LedgerRepository
    @Override
    public void onTransactionComplete(Transaction transaction) {
        if (transaction.getStatus() != TransactionStatus.SUCCESS) return;
        // Persist: DEBIT entry for sender wallet, CREDIT entry for receiver wallet
        // balance_before, balance_after for audit
        System.out.println("Ledger: DEBIT sender, CREDIT receiver for txn " + transaction.getTransactionId());
    }
}
```

### Command: TransactionCommand for Idempotent Execution

```java
package com.upi.command;

import com.upi.model.Transaction;
import com.upi.model.Wallet;

/**
 * Command pattern: Encapsulate payment as object.
 * WHY: Idempotency—check command ID (idempotencyKey) before execute.
 * Can undo (refund), replay (retry with same key = no-op if already done).
 */
public interface TransactionCommand {
    String getIdempotencyKey();
    boolean execute(Transaction txn, Wallet sender, Wallet receiver);
    void undo(Transaction txn, Wallet sender, Wallet receiver);  // For refunds
}
```

```java
package com.upi.command.impl;

import com.upi.model.Transaction;
import com.upi.model.Wallet;
import com.upi.strategy.PaymentStrategy;
import com.upi.command.TransactionCommand;

/**
 * Concrete command: delegates to PaymentStrategy.
 * Idempotency handled by PaymentService (check DB for existing txn with same key).
 */
public class PaymentTransactionCommand implements TransactionCommand {
    private final String idempotencyKey;
    private final PaymentStrategy strategy;

    public PaymentTransactionCommand(String idempotencyKey, PaymentStrategy strategy) {
        this.idempotencyKey = idempotencyKey;
        this.strategy = strategy;
    }

    @Override
    public String getIdempotencyKey() { return idempotencyKey; }

    @Override
    public boolean execute(Transaction txn, Wallet sender, Wallet receiver) {
        return strategy.execute(txn, sender, receiver);
    }

    @Override
    public void undo(Transaction txn, Wallet sender, Wallet receiver) {
        // Refund: reverse the transfer
        if (sender != null) sender.credit(txn.getAmount());
        if (receiver != null) receiver.debit(txn.getAmount());
    }
}
```

### Factory: PaymentStrategyFactory

```java
package com.upi.factory;

import com.upi.enums.PaymentMethod;
import com.upi.strategy.PaymentStrategy;
import com.upi.strategy.impl.BankDirectPaymentStrategy;
import com.upi.strategy.impl.UPIPaymentStrategy;
import com.upi.strategy.impl.WalletPaymentStrategy;

/**
 * Factory pattern: Create strategy by payment method.
 * WHY: Callers don't instantiate concrete strategies. Centralized creation.
 */
public class PaymentStrategyFactory {
    public static PaymentStrategy getStrategy(PaymentMethod method) {
        return switch (method) {
            case WALLET -> new WalletPaymentStrategy();
            case BANK_DIRECT -> new BankDirectPaymentStrategy();
            case UPI -> new UPIPaymentStrategy();
        };
    }
}
```

### PaymentService as Orchestrator with DI

```java
package com.upi.service;

import com.upi.enums.PaymentMethod;
import com.upi.enums.TransactionStatus;
import com.upi.enums.TransactionType;
import com.upi.factory.PaymentStrategyFactory;
import com.upi.model.Transaction;
import com.upi.model.Wallet;
import com.upi.observer.TransactionObserver;
import com.upi.strategy.PaymentStrategy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrator with Dependency Injection.
 * WHY DI: Observers and strategy are injected. Testable (mock observers).
 * Idempotency: if txn with same idempotencyKey exists and is SUCCESS, return it.
 * Double-entry: observers (LedgerUpdateObserver) persist DEBIT/CREDIT.
 */
public class PaymentService {
    private final List<TransactionObserver> observers;
    // In production: WalletRepository, TransactionRepository, IdempotencyStore

    public PaymentService(List<TransactionObserver> observers) {
        this.observers = observers;
    }

    /**
     * Execute payment. Idempotent: same idempotencyKey = return existing result.
     */
    public Transaction processPayment(UUID senderId, UUID receiverId, BigDecimal amount,
                                       PaymentMethod method, String idempotencyKey) {
        // 1. Idempotency check: if txn with idempotencyKey exists
        //    - If SUCCESS: return it (no double execution)
        //    - If PROCESSING: wait or return conflict
        //    - If FAILED: can retry with NEW idempotency key only

        Transaction txn = new Transaction(
            UUID.randomUUID(), idempotencyKey, senderId, receiverId,
            amount, TransactionType.SEND, method
        );
        txn.transitionTo(TransactionStatus.PROCESSING);

        Wallet senderWallet = getWalletForUser(senderId);   // From repo
        Wallet receiverWallet = getWalletForUser(receiverId);

        PaymentStrategy strategy = PaymentStrategyFactory.getStrategy(method);
        boolean success = strategy.execute(txn, senderWallet, receiverWallet);

        if (success) {
            txn.transitionTo(TransactionStatus.SUCCESS);
        } else {
            txn.transitionTo(TransactionStatus.FAILED);
            txn.setFailureReason("Insufficient balance or execution failed");
        }

        // Notify observers (notification, fraud, ledger)
        for (TransactionObserver obs : observers) {
            obs.onTransactionComplete(txn);
        }

        return txn;
    }

    private Wallet getWalletForUser(UUID userId) {
        // In production: fetch from WalletRepository
        return new Wallet(UUID.randomUUID(), userId, BigDecimal.valueOf(1000), BigDecimal.ZERO);
    }
}
```

---

## Edge Cases & Tests

| # | Edge Case | Expected Behavior | Test |
|---|-----------|-------------------|------|
| 1 | **Duplicate idempotency key** | Second request returns existing SUCCESS txn; no double debit | `processPayment` twice with same key → balance debited once |
| 2 | **Insufficient wallet balance** | Debit fails; txn status FAILED; no partial transfer | Wallet with 50, send 100 → FAILED, balance still 50 |
| 3 | **Concurrent debits** | Both succeed or one fails; balance never negative | Two threads debit 60 each from 100 → one succeeds, one fails |
| 4 | **Invalid state transition** | `IllegalStateException` | `txn.transitionTo(REFUNDED)` when status is FAILED → throw |
| 5 | **Refund on SUCCESS** | Reverse transfer; status REFUNDED | Refund command: credit sender, debit receiver |
| 6 | **Ledger balance check** | Sum of all DEBIT = Sum of all CREDIT | After N txns, `SELECT SUM(CASE WHEN entry_type='DEBIT' THEN amount ELSE -amount END) = 0` |
| 7 | **Null receiver wallet** | Strategy returns false; txn FAILED | BankDirect with invalid receiver → FAILED |
| 8 | **Lock then crash** | Orphan locked balance | Use saga/compensation: timeout unlocks; or background job to reconcile |

---

## Summary

| Aspect | Approach |
|--------|----------|
| **Thread Safety** | `synchronized` on `Wallet.debit/credit/lock/unlock`; atomic DB updates |
| **Idempotency** | `idempotency_key` UNIQUE in DB; check before execute; return existing on duplicate |
| **Double-Entry** | `transaction_ledger` with DEBIT/CREDIT rows; sum = 0; `LedgerUpdateObserver` |
| **State Machine** | `TransactionStatus` with strict transitions; `transitionTo()` validates |
| **Extensibility** | Strategy (new payment method), Observer (new side effect), Factory (creation) |
| **SOLID** | SRP (Wallet/Transaction/Service), OCP (strategies/observers), DIP (inject interfaces) |

---

**This design demonstrates: financial correctness (double-entry, thread safety), idempotency for resilience, and clean architecture (Strategy, State, Observer, Command, Factory) for maintainability.**
