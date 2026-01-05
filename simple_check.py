f = open('/Users/danielmiller/Documents/Code Projects/ancient-halley/app/src/main/java/com/groovebox/MainActivity.kt', 'r')
lines = f.readlines()
f.close()

stack = []

for i, line in enumerate(lines):
    l = line.strip()
    # Very crude comment removal
    if '//' in l:
        l = l.split('//')[0]
    
    for char in l:
        if char == '{':
            stack.append(i + 1)
        elif char == '}':
            if len(stack) > 0:
                stack.pop()
            else:
                print(f"Extra closing brace at line {i + 1}")

if len(stack) > 0:
    print(f"Unclosed braces starting at lines: {stack[-5:]}")
else:
    print("Balanced.")
