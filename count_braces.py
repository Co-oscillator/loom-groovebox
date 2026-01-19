import re

def remove_comments_and_strings(content):
    # Remove strings
    content = re.sub(r'"(\\"|[^"])*"', '""', content)
    content = re.sub(r"'(\\'|[^'])*'", "''", content)
    # Remove multi-line comments
    content = re.sub(r'/\*.*?\*/', '', content, flags=re.DOTALL)
    # Remove single-line comments
    content = re.sub(r'//.*', '', content)
    return content

with open('/Users/danielmiller/Documents/Code Projects/Loom Groovebox/app/src/main/java/com/groovebox/MainActivity.kt', 'r') as f:
    text = f.read()

# Split by lines to track line numbers
lines = text.split('\n')
balance = 0
open_stack = []

for i, line in enumerate(lines):
    # Process line for comments (simplified line-by-line check)
    clean_line = remove_comments_and_strings(line)
    
    for char in clean_line:
        if char == '{':
            balance += 1
            open_stack.append(i + 1)
        elif char == '}':
            balance -= 1
            if open_stack:
                open_stack.pop()
            else:
                print(f"Error: Unexpected closing brace at line {i + 1}")

if balance != 0:
    print(f"Final Balance: {balance}")
    print(f"Last few unclosed start lines: {open_stack[-5:]}")
else:
    print("Braces are balanced.")
