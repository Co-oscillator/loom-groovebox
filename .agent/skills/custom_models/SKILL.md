---
name: Consult Custom Models
description: Consult external AI models (Deepseek, etc.) for code analysis, debugging, and second opinions.
---

# Consult Custom Models

This skill allows you to query external AI models like Deepseek for assistance with coding tasks, debugging, and architecture reviews. It is useful when you need a "second opinion" or want to leverage a specific model's strengths.

## Usage

The primary entry point is the `scripts/query_model.py` Python script.

### Syntax

```bash
python3 .agent/skills/custom_models/scripts/query_model.py --model <model_name> --prompt "<prompt>" [--file_path <absolute_path_to_file> ...]
```

### Arguments

- `--model`: The name of the model to query. Currently supported: `deepseek`.
- `--prompt`: The text prompt to send to the model. enclose in quotes.
- `--file_path`: (Optional) Absolute path to a file to include in the context. You can repeat this argument multiple times to include multiple files.

### Example

```bash
python3 .agent/skills/custom_models/scripts/query_model.py \
  --model deepseek \
  --prompt "Why is this function returning null?" \
  --file_path /path/to/MyClass.kt \
  --file_path /path/to/Utils.kt
```

## Configuration

API keys are stored in `keys.json` in the skill directory.
- Deepseek: `deepseek` key in JSON.

## Supported Models

- **Deepseek**: 'deepseek-coder' model via Deepseek API.
