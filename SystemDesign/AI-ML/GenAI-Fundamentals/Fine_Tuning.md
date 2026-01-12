# Fine-Tuning LLMs

## What is Fine-Tuning?

Fine-tuning adapts a pre-trained model to specific tasks or domains by training it on custom data.

```
Pre-trained Model (General) 
        ↓
    + Your Data
        ↓
Fine-tuned Model (Specialized)
```

---

## When to Fine-Tune vs RAG

| Factor | Fine-Tuning | RAG |
|--------|-------------|-----|
| **Use case** | Change behavior/style | Add knowledge |
| **Data updates** | Requires retraining | Just update docs |
| **Cost** | High (training) | Lower (inference) |
| **Setup time** | Days/weeks | Hours |
| **Best for** | Style, format, tone | Facts, current info |

### Decision Guide
```
Need current/changing information? → RAG
Need specific writing style? → Fine-tune
Need domain terminology? → Either (RAG cheaper)
Need to follow specific format? → Fine-tune
Budget constrained? → RAG
```

---

## Fine-Tuning Options

### 1. Full Fine-Tuning
Train all model parameters.

- **Pros**: Maximum customization
- **Cons**: Expensive, needs lots of data, risk of catastrophic forgetting
- **When**: Large budget, specialized domain

### 2. LoRA (Low-Rank Adaptation)
Train small adapter layers.

```
Original Model (frozen)
        ↓
   + LoRA Adapters (trainable, ~1-5% of params)
        ↓
Fine-tuned Model
```

- **Pros**: Efficient, lower cost, less data needed
- **Cons**: Slightly less capable than full fine-tune
- **When**: Most fine-tuning scenarios

### 3. QLoRA
LoRA with quantization.

- **Pros**: Run on consumer GPUs
- **Cons**: Slightly lower quality
- **When**: Limited hardware

---

## OpenAI Fine-Tuning

### Prepare Data
```json
// training_data.jsonl
{"messages": [{"role": "system", "content": "You are a helpful assistant."}, {"role": "user", "content": "What's the weather?"}, {"role": "assistant", "content": "I don't have access to weather data."}]}
{"messages": [{"role": "system", "content": "You are a helpful assistant."}, {"role": "user", "content": "Tell me a joke"}, {"role": "assistant", "content": "Why did the developer quit? Because he didn't get arrays!"}]}
```

### Upload and Train
```python
from openai import OpenAI

client = OpenAI()

# Upload training file
file = client.files.create(
    file=open("training_data.jsonl", "rb"),
    purpose="fine-tune"
)

# Create fine-tuning job
job = client.fine_tuning.jobs.create(
    training_file=file.id,
    model="gpt-4o-mini-2024-07-18",  # Base model
    hyperparameters={
        "n_epochs": 3
    }
)

# Check status
status = client.fine_tuning.jobs.retrieve(job.id)
print(status.status)  # "running", "succeeded", "failed"

# Use fine-tuned model
response = client.chat.completions.create(
    model="ft:gpt-4o-mini-2024-07-18:org:custom:id",
    messages=[{"role": "user", "content": "Hello"}]
)
```

### Best Practices for OpenAI
- **Minimum data**: 10 examples (50-100 recommended)
- **Format**: JSONL with messages array
- **Quality**: Review examples carefully
- **Epochs**: Start with 3, adjust based on loss

---

## Open Source Fine-Tuning

### Using Hugging Face + LoRA
```python
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import LoraConfig, get_peft_model
from trl import SFTTrainer

# Load base model
model = AutoModelForCausalLM.from_pretrained(
    "meta-llama/Llama-3.1-8B",
    torch_dtype=torch.float16,
    device_map="auto"
)
tokenizer = AutoTokenizer.from_pretrained("meta-llama/Llama-3.1-8B")

# Configure LoRA
lora_config = LoraConfig(
    r=16,                # Rank
    lora_alpha=32,       # Scaling factor
    lora_dropout=0.05,
    target_modules=["q_proj", "v_proj"],
    bias="none",
    task_type="CAUSAL_LM"
)

# Apply LoRA
model = get_peft_model(model, lora_config)

# Train
trainer = SFTTrainer(
    model=model,
    train_dataset=dataset,
    tokenizer=tokenizer,
    args=training_args,
    max_seq_length=512
)

trainer.train()

# Save
model.save_pretrained("./my-fine-tuned-model")
```

### Using Axolotl (Simplified)
```yaml
# config.yml
base_model: meta-llama/Llama-3.1-8B
model_type: LlamaForCausalLM

load_in_8bit: true
adapter: lora
lora_r: 16
lora_alpha: 32

datasets:
  - path: ./my_data.jsonl
    type: alpaca

sequence_len: 2048
micro_batch_size: 4
gradient_accumulation_steps: 4
num_epochs: 3
```

```bash
accelerate launch -m axolotl.cli.train config.yml
```

---

## Data Preparation

### Quality > Quantity
```
❌ 10,000 low-quality examples
✅ 500 high-quality examples
```

### Data Format Examples

**Instruction Following**
```json
{
  "instruction": "Summarize the following text",
  "input": "Long article text...",
  "output": "Concise summary..."
}
```

**Chat Format**
```json
{
  "messages": [
    {"role": "system", "content": "You are a medical assistant."},
    {"role": "user", "content": "What causes headaches?"},
    {"role": "assistant", "content": "Headaches can be caused by..."}
  ]
}
```

**Completion Format**
```json
{
  "prompt": "Translate to French: Hello",
  "completion": "Bonjour"
}
```

### Data Quality Checklist
- [ ] Examples are accurate and high-quality
- [ ] Consistent format across all examples
- [ ] Diverse inputs (not repetitive)
- [ ] Balanced across categories
- [ ] No contradictory examples
- [ ] Appropriate length outputs

---

## Evaluation

### Metrics
| Metric | Use Case |
|--------|----------|
| **Loss** | Training progress |
| **Perplexity** | Language quality |
| **BLEU/ROUGE** | Text similarity |
| **Human eval** | Real quality |
| **Task accuracy** | Classification |

### A/B Testing
```python
# Compare base vs fine-tuned
prompts = load_test_prompts()

for prompt in prompts:
    base_response = base_model.generate(prompt)
    ft_response = fine_tuned_model.generate(prompt)
    
    # Log for human evaluation
    log_comparison(prompt, base_response, ft_response)
```

---

## Cost Estimation

### OpenAI Fine-Tuning Costs
```
Training: ~$0.008 / 1K tokens
Inference: 
  - gpt-4o-mini fine-tuned: $0.30 / 1M input, $1.20 / 1M output

Example:
- 1000 training examples × 500 tokens = 500K tokens
- Training cost: 500 × $0.008 = $4
- Plus inference costs per use
```

### Self-Hosted Costs
```
GPU rental (A100): ~$1-3/hour
Training time: Hours to days depending on:
  - Model size
  - Dataset size
  - Hardware
```

---

## Common Pitfalls

### 1. Catastrophic Forgetting
Model loses general capabilities.

**Solution**: Use LoRA, smaller learning rates, diverse data

### 2. Overfitting
Model memorizes training data.

**Solution**: More diverse data, fewer epochs, validation set

### 3. Poor Data Quality
Garbage in, garbage out.

**Solution**: Careful data curation, human review

### 4. Wrong Use Case
Fine-tuning when RAG would work.

**Solution**: Evaluate needs first (style vs knowledge)

---

## Interview Questions

**Q: When would you choose fine-tuning over RAG?**
> Fine-tune for: style/tone changes, specific output formats, behavior modification. RAG for: factual knowledge, frequently changing data, lower cost. Fine-tuning changes HOW model responds; RAG changes WHAT it knows.

**Q: What is LoRA and why is it popular?**
> LoRA (Low-Rank Adaptation) trains small adapter layers instead of full model. Benefits: 10-100x fewer parameters, runs on consumer GPUs, fast training, easy to swap adapters. Trade-off: slightly less capable than full fine-tune.

**Q: How much data do you need for fine-tuning?**
> Depends on task complexity. OpenAI: minimum 10, recommended 50-100+ for quality. Open source: typically 1000+ for significant improvement. Quality matters more than quantity.

---

## Quick Reference

| Method | Data Needed | Hardware | Cost | When |
|--------|-------------|----------|------|------|
| OpenAI FT | 50-100+ | None | $$ | Quick setup |
| LoRA | 500-1000 | 1 GPU | $ | Most cases |
| QLoRA | 500-1000 | Consumer | $ | Limited HW |
| Full FT | 10K+ | Many GPUs | $$$ | Max quality |
