---
name: plain-readable-code
description: "Write code in a plain, junior-readable style — clear over clever, with functions that earn their existence rather than being extracted reflexively. Use this skill whenever the user asks Claude to write, edit, refactor, review, or extend code in any language, even if they don't explicitly ask for a particular style. Triggers on any coding task — implementing features, fixing bugs, refactoring, code review, or building scripts and apps. Do not skip this skill on the assumption the user wants 'default' code; this style IS the default they want."
---

# Plain, Readable Code

Write code the way a thoughtful new grad would: clear, simple, organized, using the basic features of the language first. The goal is code a teammate can read in one pass and modify without fear. Cleverness, deep abstractions, and language tricks are not assets — they are friction every future reader has to push through.

## Default to Plain

Reach for the basic features of the language before the advanced ones. Loops before chained `reduce`/`flatMap` acrobatics. Named functions before anonymous functions inside anonymous functions. Plain `if`/`else` before nested ternaries. Standard `for` loops before custom iterator gymnastics.

This isn't about dumbing down code. It's about not paying a clarity cost without getting something back. If a fancier construct genuinely makes the code clearer, use it. If it just makes you look smart, skip it.

## Don't Be Cheap About Tooling

When a task genuinely needs a library — auth, date handling, HTTP, parsing, cryptography, retry logic, schema validation — use a well-known, well-maintained one. Don't hand-roll complex behavior to avoid a dependency. The bug surface of homegrown auth or date math is huge, and the time saved is fake — you'll spend it later on edge cases the library already handled.

Pick boring, established tools over trendy ones. "Boring" means: widely used, stable API, easy to find help for. A library that's been around for five years and is still maintained beats a library that trended on Hacker News last month.

## One Function, One Job — But Earn the Function

A function should do one clear thing. But don't extract a function just to follow the rule. Extract only when at least one of these is true:

- It's used in more than one place
- The logic is non-obvious and a name helps document it
- The caller becomes meaningfully shorter or easier to scan

Don't create one-line, single-caller wrappers. A name with no payoff just adds navigation cost — readers have to jump to find what should have been right there.

Many small focused functions are still better than one big tangled one. But each function should earn its existence.

**Example.** In a Wordle game:

- `pickRandomWord()` wrapping a single `Math.floor(Math.random() * WORDS.length)` adds nothing. Inline it inside `startNewGame()`.
- `evaluateGuess(guess, answer)` doing the green/yellow/grey logic with duplicate-letter handling earns its name — the logic is non-obvious, the name documents the intent, and the caller stays readable.
- `showNewGameButton()` wrapping a one-line `style.display = "block"` doesn't earn it — that line in the caller is already self-explanatory.

When in doubt, write the code inline first. Extract only when the discomfort of leaving it inline is real, not theoretical.

## Clear Structure

- Group related code together — don't scatter logic that belongs side by side
- Keep folder and file layout simple and predictable — a new contributor should be able to guess where things live
- Name things clearly — no abbreviations, no single-letter variables outside short loops (`i`, `j` in nested loops is fine; `x` for "transaction" is not)
- Order code top-down — high-level functions first, helpers below them, so a reader can scan from the entry point downward and never has to read forward

## What to Avoid

- Clever code that takes effort to decode
- Functions longer than they need to be (long is fine when the work is genuinely linear; long because of mixed responsibilities is not)
- Mixing unrelated responsibilities in one function or file
- Reinventing things that established libraries already solve correctly
- Premature abstraction, deep inheritance, "just in case" generality
- Extracting one-liners just to inflate the function count
- Comments that describe *what* the code does (the code already says that). Comments earn their place by explaining *why*, or by flagging something non-obvious

## When in Doubt

- Between "fewer lines" and "easier to understand" — pick easier to understand
- Between "more functions" and "fewer functions" — pick whichever makes the calling code clearer to a first-time reader
- If you're unsure whether a piece of cleverness is worth its cost, it isn't
