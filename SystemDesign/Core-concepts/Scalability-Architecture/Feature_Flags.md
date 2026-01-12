# Feature Flags (Feature Toggles)

## What are Feature Flags?

Feature flags are a technique to enable or disable features at runtime without deploying new code. They decouple deployment from release.

```
Traditional Release:
  Code Merged → Deployed → Feature Live (all users)

With Feature Flags:
  Code Merged → Deployed → Flag OFF (no users see it)
                         → Flag ON for 10% → Testing
                         → Flag ON for 100% → Full release
```

---

## Why Feature Flags?

| Benefit | Description |
|---------|-------------|
| **Safe Releases** | Turn off broken features instantly |
| **Gradual Rollout** | Release to % of users |
| **A/B Testing** | Compare feature variants |
| **Trunk-Based Dev** | Merge incomplete features safely |
| **Kill Switch** | Disable features without deploy |
| **User Targeting** | Enable for specific users/segments |

---

## Types of Feature Flags

### 1. Release Flags
Control feature rollout to users.

```python
if feature_flags.is_enabled('new_checkout_flow', user_id):
    return new_checkout()
else:
    return legacy_checkout()
```

### 2. Experiment Flags (A/B Tests)
Compare variants for metrics.

```python
variant = feature_flags.get_variant('pricing_experiment', user_id)
if variant == 'control':
    show_original_pricing()
elif variant == 'variant_a':
    show_discounted_pricing()
elif variant == 'variant_b':
    show_subscription_pricing()
```

### 3. Ops Flags
Control operational behavior.

```python
if feature_flags.is_enabled('enable_caching'):
    return cache.get(key) or fetch_and_cache(key)
else:
    return fetch_from_db(key)  # Cache disabled for debugging
```

### 4. Permission Flags
Control access for user segments.

```python
if feature_flags.is_enabled('beta_features', user_id):
    show_beta_dashboard()
```

---

## Implementation

### Basic Feature Flag Service

```python
from enum import Enum
from dataclasses import dataclass
import hashlib

class RolloutStrategy(Enum):
    ALL = "all"
    NONE = "none"
    PERCENTAGE = "percentage"
    USER_LIST = "user_list"
    USER_ATTRIBUTE = "user_attribute"

@dataclass
class FeatureFlag:
    name: str
    enabled: bool
    strategy: RolloutStrategy
    percentage: int = 0
    user_list: list = None
    attribute_rules: dict = None

class FeatureFlagService:
    def __init__(self, config_store):
        self.config_store = config_store
        self._cache = {}
    
    def is_enabled(self, flag_name: str, user_id: str = None, user_attributes: dict = None) -> bool:
        flag = self._get_flag(flag_name)
        
        if not flag or not flag.enabled:
            return False
        
        if flag.strategy == RolloutStrategy.ALL:
            return True
        
        if flag.strategy == RolloutStrategy.NONE:
            return False
        
        if flag.strategy == RolloutStrategy.PERCENTAGE:
            return self._check_percentage(flag_name, user_id, flag.percentage)
        
        if flag.strategy == RolloutStrategy.USER_LIST:
            return user_id in (flag.user_list or [])
        
        if flag.strategy == RolloutStrategy.USER_ATTRIBUTE:
            return self._check_attributes(user_attributes, flag.attribute_rules)
        
        return False
    
    def _check_percentage(self, flag_name: str, user_id: str, percentage: int) -> bool:
        """Consistent hashing for stable user assignment"""
        if not user_id:
            return False
        
        # Hash user_id + flag_name for consistent assignment
        hash_input = f"{flag_name}:{user_id}"
        hash_value = int(hashlib.md5(hash_input.encode()).hexdigest(), 16)
        bucket = hash_value % 100
        
        return bucket < percentage
    
    def _check_attributes(self, user_attributes: dict, rules: dict) -> bool:
        """Check if user matches attribute rules"""
        if not user_attributes or not rules:
            return False
        
        for attr, expected in rules.items():
            if user_attributes.get(attr) != expected:
                return False
        return True
    
    def _get_flag(self, flag_name: str) -> FeatureFlag:
        if flag_name not in self._cache:
            self._cache[flag_name] = self.config_store.get(flag_name)
        return self._cache[flag_name]
    
    def refresh_cache(self):
        """Refresh flags from config store"""
        self._cache = {}
```

### Usage Examples

```python
# Initialize
flag_service = FeatureFlagService(config_store)

# Simple boolean check
if flag_service.is_enabled('dark_mode'):
    apply_dark_theme()

# User-specific rollout
if flag_service.is_enabled('new_dashboard', user_id=current_user.id):
    render_new_dashboard()
else:
    render_old_dashboard()

# Attribute-based targeting
if flag_service.is_enabled('enterprise_features', 
    user_attributes={'plan': 'enterprise', 'country': 'US'}):
    show_enterprise_features()
```

---

## Feature Flag Patterns

### 1. Gradual Rollout

```python
# Week 1: 5% of users
flag_config = {
    'name': 'new_payment_flow',
    'strategy': 'percentage',
    'percentage': 5
}

# Week 2: 25% (after monitoring)
flag_config['percentage'] = 25

# Week 3: 100% (full release)
flag_config['percentage'] = 100

# Later: Remove flag from code
```

### 2. Canary Release

```python
def get_service_version(user_id):
    if feature_flags.is_enabled('use_canary_service', user_id):
        return 'service-v2.canary.internal'
    return 'service-v2.stable.internal'
```

### 3. Kill Switch

```python
@app.route('/api/heavy-operation')
def heavy_operation():
    if not feature_flags.is_enabled('heavy_operation_enabled'):
        return {'error': 'Service temporarily disabled'}, 503
    
    return perform_heavy_operation()
```

### 4. Beta Program

```python
BETA_USERS = ['user123', 'user456', 'user789']

flag_config = {
    'name': 'beta_features',
    'strategy': 'user_list',
    'user_list': BETA_USERS
}

# Or attribute-based
flag_config = {
    'name': 'beta_features',
    'strategy': 'user_attribute',
    'attribute_rules': {'is_beta_tester': True}
}
```

---

## A/B Testing with Feature Flags

```python
class ABTestService:
    def __init__(self, flag_service, analytics):
        self.flag_service = flag_service
        self.analytics = analytics
    
    def get_variant(self, experiment_name: str, user_id: str) -> str:
        """Assign user to experiment variant"""
        
        # Consistent assignment using hash
        variants = self.flag_service.get_variants(experiment_name)
        hash_value = self._hash(f"{experiment_name}:{user_id}")
        
        cumulative = 0
        for variant in variants:
            cumulative += variant['percentage']
            if hash_value < cumulative:
                # Track assignment
                self.analytics.track('experiment_assigned', {
                    'experiment': experiment_name,
                    'variant': variant['name'],
                    'user_id': user_id
                })
                return variant['name']
        
        return 'control'  # Default
    
    def _hash(self, value: str) -> int:
        return int(hashlib.md5(value.encode()).hexdigest(), 16) % 100

# Usage
variant = ab_test.get_variant('checkout_redesign', user.id)

if variant == 'control':
    render_original_checkout()
elif variant == 'variant_a':
    render_simplified_checkout()
elif variant == 'variant_b':
    render_one_page_checkout()

# Track conversion
if purchase_completed:
    analytics.track('purchase', {'experiment': 'checkout_redesign', 'variant': variant})
```

---

## Feature Flag Tools

| Tool | Type | Best For |
|------|------|----------|
| **LaunchDarkly** | SaaS | Enterprise, full-featured |
| **Split.io** | SaaS | A/B testing focus |
| **Unleash** | Open source | Self-hosted |
| **Flagsmith** | Open source/SaaS | Flexible |
| **AWS AppConfig** | AWS | AWS ecosystem |
| **Firebase Remote Config** | Google | Mobile apps |

---

## Best Practices

### Do's ✅
1. **Name flags clearly**: `enable_new_checkout_v2` not `flag1`
2. **Set expiration dates**: Remove flags after full rollout
3. **Document flags**: Who owns it, why it exists
4. **Test both paths**: Unit test enabled AND disabled
5. **Monitor flag usage**: Alert on flag evaluations

### Don'ts ❌
1. **Don't nest flags**: Creates complexity
2. **Don't keep forever**: Technical debt
3. **Don't use for config**: Flags ≠ configuration
4. **Don't skip cleanup**: Remove after release

### Flag Lifecycle

```
1. CREATE   → Define flag, default OFF
2. DEVELOP  → Code behind flag, merge to main
3. TEST     → Enable for QA/staging
4. ROLLOUT  → Gradual % increase
5. RELEASE  → 100% enabled
6. CLEANUP  → Remove flag from code
7. DELETE   → Remove from flag service
```

---

## Technical Considerations

### Performance

```python
# Cache flag evaluations
class CachedFlagService:
    def __init__(self, flag_service, cache_ttl=60):
        self.flag_service = flag_service
        self.cache = {}
        self.cache_ttl = cache_ttl
    
    def is_enabled(self, flag_name, user_id=None):
        cache_key = f"{flag_name}:{user_id}"
        
        if cache_key in self.cache:
            value, timestamp = self.cache[cache_key]
            if time.time() - timestamp < self.cache_ttl:
                return value
        
        result = self.flag_service.is_enabled(flag_name, user_id)
        self.cache[cache_key] = (result, time.time())
        return result
```

### Fallback Behavior

```python
def is_enabled_safe(flag_name, user_id, default=False):
    """Always return a value, even if flag service fails"""
    try:
        return flag_service.is_enabled(flag_name, user_id)
    except Exception as e:
        logger.error(f"Flag service error: {e}")
        return default  # Safe fallback
```

---

## Interview Questions

**Q: What are feature flags and why use them?**
> Feature flags toggle features at runtime without deployment. Benefits: safe rollouts, instant rollback, A/B testing, gradual releases, decouples deploy from release.

**Q: How do you ensure consistent user experience with percentage rollouts?**
> Use consistent hashing of user_id + flag_name. Same user always gets same result. Don't use random - would flip-flop on each request.

**Q: How do you manage feature flag technical debt?**
> Set expiration dates, track flag age, require cleanup as part of release process, monitor unused flags, regular audits.

---

## Quick Reference

| Flag Type | Use Case | Lifespan |
|-----------|----------|----------|
| Release | Gradual rollout | Days-weeks |
| Experiment | A/B testing | Weeks |
| Ops | System behavior | Varies |
| Permission | User access | Long-term |
| Kill Switch | Emergency | Permanent |
