
import argparse
import json
import os
import sys
import urllib.request
import urllib.error

# Path to keys.json
KEYS_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'keys.json')

def load_api_key(model_name):
    try:
        with open(KEYS_PATH, 'r') as f:
            keys = json.load(f)
            return keys.get(model_name)
    except FileNotFoundError:
        print(f"Error: keys.json not found at {KEYS_PATH}")
        sys.exit(1)
    except Exception as e:
        print(f"Error reading keys.json: {e}")
        sys.exit(1)

def query_deepseek(api_key, prompt):
    url = "https://api.deepseek.com/chat/completions"
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {api_key}"
    }
    data = {
        "model": "deepseek-coder",
        "messages": [
            {"role": "system", "content": "You are an expert software engineer analyzing code for duplication and architectural issues."},
            {"role": "user", "content": prompt}
        ],
        "stream": False
    }
    
    req = urllib.request.Request(url, data=json.dumps(data).encode('utf-8'), headers=headers)
    
    try:
        with urllib.request.urlopen(req) as response:
            result = json.loads(response.read().decode('utf-8'))
            return result['choices'][0]['message']['content']
    except urllib.error.HTTPError as e:
        print(f"HTTP Error: {e.code} - {e.reason}")
        print(e.read().decode('utf-8'))
        sys.exit(1)
    except Exception as e:
        print(f"Error querying Deepseek: {e}")
        sys.exit(1)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='Query Custom AI Models')
    parser.add_argument('--model', required=True, help='Model name (e.g., deepseek)')
    parser.add_argument('--prompt', required=True, help='Prompt to send to the model')
    parser.add_argument('--file_path', action='append', help='Path to file to analyze (can be specified multiple times)')
    
    args = parser.parse_args()

    full_prompt = args.prompt
    if args.file_path:
        for path in args.file_path:
            try:
                if not os.path.exists(path):
                    print(f"Warning: File not found: {path}")
                    continue
                with open(path, 'r', encoding='utf-8', errors='ignore') as f:
                    content = f.read()
                    filename = os.path.basename(path)
                    full_prompt += f"\n\n--- File: {filename} ({path}) ---\n```\n{content}\n```"
            except Exception as e:
                print(f"Error reading file {path}: {e}")
    
    if args.model == 'deepseek':
        api_key = load_api_key('deepseek')
        if not api_key:
            print("Error: No API key found for deepseek in keys.json")
            sys.exit(1)
            
        print(query_deepseek(api_key, full_prompt))
    else:
        print(f"Model {args.model} not yet implemented in this script.")
