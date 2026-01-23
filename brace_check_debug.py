
import sys

def check_braces(filename, start_line, end_line):
    with open(filename, 'r') as f:
        lines = f.readlines()
    
    stack = []
    for i, line in enumerate(lines):
        line_num = i + 1
        for char in line:
            if char == '{':
                stack.append(line_num)
            elif char == '}':
                if not stack:
                    if line_num >= start_line and line_num <= end_line:
                        print(f"L{line_num}: Extra closing brace")
                else:
                    stack.pop()
        
        if line_num >= start_line and line_num <= end_line:
            print(f"L{line_num} (depth {len(stack)}): {line.strip()[:50]}")

if __name__ == "__main__":
    check_braces(sys.argv[1], int(sys.argv[2]), int(sys.argv[3]))
