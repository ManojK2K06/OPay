import os

def replace_in_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # Replacements
    content = content.replace('"0D0D0D"', '"0A1128"')  # Dark Navy
    content = content.replace('"1A1A2E"', '"1C2541"')  # Lighter Navy
    content = content.replace('"16213E"', '"0B1D3A"')  # Mid Navy
    content = content.replace('"00D4AA"', '"389B85"')  # Subtle Teal
    content = content.replace('"00A8FF"', '"226E5D"')  # Darker Subtle Teal for gradients

    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)

files = [
    r"C:\Users\user\Desktop\OPay\ios-client\OPayClient\OPayApp.swift",
    r"C:\Users\user\Desktop\OPay\ios-client\OPayClient\UI\MainTabView.swift",
    r"C:\Users\user\Desktop\OPay\ios-client\OPayClient\UI\Onboarding\OnboardingView.swift"
]

for f in files:
    if os.path.exists(f):
        replace_in_file(f)
        print(f"Updated {f}")
    else:
        print(f"File not found: {f}")
