# Prompt rule ownership and request budget

The role's source of truth remains its existing profile, timeline, memories,
relationships, motives, and execution receipts. These are **data**, not
deduplicatable static instructions.

For proactive decision prompts, stable authored instructions are composed
through `PromptRuleAssemblyPolicy.Rule(id, text)`. A rule ID may occur once;
a repeated identical ID is emitted only once and a conflicting version
fails visibly rather than silently selecting an arbitrary version. IDs
represent explicit author-owned responsibilities, **not** semantic guesses
about user messages or memories.

- Character causal contract: `CharacterDecisionProtocol.runtimePrinciples`.
- Current expression channel: `CharacterExpressionGuide.channelNote`.
- Inner voice: `spontaneousInnerVoiceGuide`.
- Autonomous initiative: `CharacterSpontaneityGuide.autonomousPrinciples`.
- Scene-specific decision and action schema: `ProactiveDecisionPrompt`.

The long autonomous prompt has been consolidated where adjacent sections
repeated the same instructions about curiosity, action, durable motive,
evidence, and success. Its executable JSON keys and the authorized
tool-discovery context remain intact. Do not use a generic fuzzy-text
deduplicator on user input, world-book TOP/BOTTOM, history, or evidentiary
events: apparently repeated content may represent a correction, a new
source, or an intentional higher-priority placement.

Live requests still have a separate `PromptInputAudit` and
`MemoryWorkingSet` budget. This change does not assert that Gemini or
a relay will never time out; verify with request sizes, actual latency,
unit tests, and an APK on device.
