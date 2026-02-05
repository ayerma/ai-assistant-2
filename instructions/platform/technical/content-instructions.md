# Content Formatting Guidelines

## Overview

When generating answers for content-related questions, you MUST strictly follow the formatting guidelines specified in the `ADDING_CONTENT.md` file located in the target repository.

## Critical Requirements

### 1. Reference the ADDING_CONTENT.md file
- The ADDING_CONTENT.md file contains the canonical instructions for adding content properly
- This file is located at: `{target-repo-root}/public/data/ADDING_CONTENT.md`
- All answers must conform to the structure and format defined in this file

### 2. Content Structure Compliance
- Follow the exact JSON structure specified in ADDING_CONTENT.md
- Use the correct property names and data types
- Maintain proper nesting and hierarchy as defined
- Include all required fields

### 3. Formatting Standards
- Apply markdown formatting as specified in the guidelines
- Use proper code block syntax when including code examples
- Follow any specific spacing, indentation, or styling rules
- Preserve special characters and escape sequences correctly

### 4. Quality Standards
- Ensure content is clear, concise, and accurate
- Use appropriate technical terminology
- Provide practical, actionable information
- Include relevant examples where specified

## Validation

Before finalizing any answer:
1. ✅ Verify compliance with ADDING_CONTENT.md structure
2. ✅ Check all required fields are present
3. ✅ Validate JSON syntax if applicable
4. ✅ Ensure formatting matches guidelines
5. ✅ Confirm content quality and accuracy

## Error Prevention

If the ADDING_CONTENT.md file:
- Does not exist → Flow will stop with error
- Cannot be read → Flow will stop with error
- Is missing → Contact repository maintainer

This validation ensures consistent, high-quality content across all generated answers.

---

**Note**: The actual content of ADDING_CONTENT.md will be appended below during runtime for your reference.