import sys

def main():
    file_path = "app/src/main/java/com/example/MainActivity.kt"
    try:
        with open(file_path, "r", encoding="utf-8") as f:
            lines = f.readlines()
    except Exception as e:
        print(f"Error reading file: {e}")
        return

    balance = 0
    stack = []
    
    # We want to trace the brace blocks
    for idx, line in enumerate(lines):
        line_num = idx + 1
        # Strip comments and string literals to avoid counting braces inside strings/comments
        clean_line = ""
        in_string = False
        escape = False
        in_comment = False
        i = 0
        while i < len(line):
            char = line[i]
            if in_comment:
                if char == '*' and i + 1 < len(line) and line[i+1] == '/':
                    in_comment = False
                    i += 2
                    continue
            elif in_string:
                if escape:
                    escape = False
                elif char == '\\':
                    escape = True
                elif char == '"':
                    in_string = False
            else:
                if char == '/' and i + 1 < len(line) and line[i+1] == '/':
                    break # Single line comment
                elif char == '/' and i + 1 < len(line) and line[i+1] == '*':
                    in_comment = True
                    i += 2
                    continue
                elif char == '"':
                    in_string = True
                else:
                    clean_line += char
            i += 1
            
        for char in clean_line:
            if char == '{':
                balance += 1
                stack.append(line_num)
            elif char == '}':
                balance -= 1
                if stack:
                    stack.pop()
                else:
                    print(f"Extra closing brace at line {line_num}")
                    
    print(f"Final balance: {balance}")
    if balance > 0:
        print("Unclosed braces starting at lines:")
        for line_num in stack[-20:]:  # show last 20
            print(f"Line {line_num}: {lines[line_num - 1].strip()[:60]}")

if __name__ == "__main__":
    main()
