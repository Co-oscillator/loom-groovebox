import sys

path = '/Users/danielmiller/Documents/Code Projects/Loom Groovebox/app/src/main/java/com/groovebox/MainActivity.kt'
with open(path, 'r') as f:
    text = f.read()

balance = 0
for i, char in enumerate(text):
    if char == '{':
        balance += 1
    elif char == '}':
        balance -= 1
    
    if balance < 0:
        # Find line number
        line_no = text[:i].count('\n') + 1
        print(f"Broke at line {line_no}: balance went below zero")
        sys.exit(1)

print(f"Final balance: {balance}")
