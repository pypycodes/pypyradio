---
description: How to commit and push changes to the pypyradio repository
---

# Git Push Workflow

Always follow these steps when committing and pushing changes:

1. **Increment versionCode and versionName** in `app/build.gradle.kts` before committing.
   - Read the current `versionCode` and `versionName` values.
   - Increment `versionCode` by 1 (e.g., 43 → 44).
   - Update `versionName` to match (e.g., "1.0.43" → "1.0.44").

2. **Stage, commit, and push** with a descriptive message including the new version:
// turbo
```bash
git add . && git commit -m "v<new_versionName>: <description of changes>" && git push
```
