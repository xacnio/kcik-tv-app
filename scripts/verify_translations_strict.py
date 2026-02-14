
import os
import re
import sys

# Configuration
RES_DIR = r"d:\Coding\Github\Xacnio\kick-tv\app\src\main\res\shared"
BASE_FILE = os.path.join(RES_DIR, "values", "strings.xml")

def parse_line_structure(line):
    """
    Returns a tuple describing the structural content of the line.
    Types:
    ('empty', '')
    ('comment', 'text')
    ('tag', 'key_name')
    ('resources_start', '')
    ('resources_end', '')
    ('xml_decl', '')
    ('unknown', 'content')
    
    Also returns indentation string.
    """
    stripped = line.strip()
    indent = line[:len(line) - len(line.lstrip())]
    
    if not stripped:
        return ('empty', ''), indent
        
    if stripped.startswith('<!--'):
        return ('comment', stripped), indent
        
    if stripped.startswith('<string '):
        # Extract name
        match = re.search(r'name="([^"]+)"', stripped)
        if match:
            return ('tag', match.group(1)), indent
        return ('tag', 'UNKNOWN_NAME'), indent

    if stripped.startswith('<string-array'):
        match = re.search(r'name="([^"]+)"', stripped)
        if match:
             return ('array', match.group(1)), indent
        return ('array', 'UNKNOWN'), indent
        
    if stripped.startswith('<item>'):
         return ('item', stripped), indent
         
    if stripped.startswith('</string-array>'):
        return ('array_end', ''), indent

    if stripped.startswith('<plurals'):
         match = re.search(r'name="([^"]+)"', stripped)
         if match:
             return ('plurals', match.group(1)), indent
         return ('plurals', 'UNKNOWN'), indent

    if stripped.startswith('</plurals>'):
        return ('plurals_end', ''), indent
        
    if stripped.startswith('<resources'):
        return ('resources_start', ''), indent
        
    if stripped.startswith('</resources>'):
        return ('resources_end', ''), indent
        
    if stripped.startswith('<?xml'):
        return ('xml_decl', ''), indent
        
    return ('unknown', stripped), indent

def verify_file(base_lines, target_path, lang_code):
    print(f"\nChecking [{lang_code}]: {target_path}")
    
    with open(target_path, 'r', encoding='utf-8') as f:
        target_lines = f.readlines()
        
    errors = []
    
    # Check 1: Line Count
    if len(base_lines) != len(target_lines):
        errors.append(f"Line count mismatch! Base: {len(base_lines)}, Target: {len(target_lines)}")
        # We continue to find specific mismatches
        
    limit = min(len(base_lines), len(target_lines))
    
    for i in range(limit):
        line_num = i + 1
        base_line = base_lines[i]
        target_line = target_lines[i]
        
        # Parse structures
        b_struct, b_indent = parse_line_structure(base_line)
        t_struct, t_indent = parse_line_structure(target_line)
        
        # Check 2: Structure Type Mismatch
        if b_struct[0] != t_struct[0]:
            errors.append(f"Line {line_num}: Structure mismatch. Expected {b_struct[0]} but found {t_struct[0]}")
            # If completely different, stop detailed checking for this file to avoid cascades
            if len(errors) > 5: 
                errors.append("Too many errors, stopping check for this file.")
                break
            continue
            
        # Check 3: Indentation
        # Allow slight lenience if needed, but user asked for strict.
        # Tab vs Space:
        # If base has 4 spaces, target should have 4 spaces.
        if b_indent != t_indent:
            # Visualize whitespace
            b_vis = b_indent.replace(' ', '·').replace('\t', '→')
            t_vis = t_indent.replace(' ', '·').replace('\t', '→')
            errors.append(f"Line {line_num}: Indentation mismatch. Expected '{b_vis}' but found '{t_vis}'")
            
        # Check 4: Content Specifics
        if b_struct[0] == 'comment':
            # Comments must match exactly
            if b_struct[1] != t_struct[1]:
                errors.append(f"Line {line_num}: Comment mismatch.\n\tExpected: {b_struct[1]}\n\tFound:    {t_struct[1]}")
                
        elif b_struct[0] == 'tag':
            # Keys must match
            if b_struct[1] != t_struct[1]:
                errors.append(f"Line {line_num}: Key mismatch. Expected '{b_struct[1]}' but found '{t_struct[1]}'")
                
        elif b_struct[0] == 'empty':
            # Both expected to be empty, verified by type check
            pass
            
    if errors:
        for e in errors:
            print(f"  [FAIL] {e}")
        return False
    else:
        print("  [PASS] Perfect match.")
        return True

def main():
    print("--- Strict Structural Integrity Check ---")
    if not os.path.exists(BASE_FILE):
        print(f"Base file not found: {BASE_FILE}")
        return
        
    with open(BASE_FILE, 'r', encoding='utf-8') as f:
        base_lines = f.readlines()
        
    print(f"Base: values/strings.xml ({len(base_lines)} lines)")
    
    # Get all translation dirs
    dirs = [d for d in os.listdir(RES_DIR) if d.startswith("values-") and os.path.isdir(os.path.join(RES_DIR, d))]
    
    all_pass = True
    for d in sorted(dirs):
        path = os.path.join(RES_DIR, d, "strings.xml")
        if not os.path.exists(path):
            continue
            
        lang = d.replace("values-", "")
        if not verify_file(base_lines, path, lang):
            all_pass = False
            
    print("\n" + "="*40)
    if all_pass:
        print("[SUCCESS] All files have identical structure.")
        sys.exit(0)
    else:
        print("[FAILURE] Structural mismatches found.")
        sys.exit(1)

if __name__ == "__main__":
    main()
