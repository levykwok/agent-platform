---
name: document-summary
description: Summarize a supplied or uploaded document faithfully. Use for requests such as summarize, abstract, key conclusions, key points, executive summary, PPT summary, document digest, or 总结文档/关键结论/提炼要点. Do not use this skill to write a release brief, product review, PRD, or project plan unless the user explicitly asks for that deliverable.
---

# Document Summary

Use this skill when the user asks to understand, summarize, extract conclusions from, or digest
an attached, uploaded, or retrieved document.

## Response language

- Answer in the language used in the user's question. A Chinese question must receive a Chinese
  answer, including headings, explanations, and recommendations.
- Preserve proper nouns, document titles, product names, and short necessary quotations in their
  original language. Do not translate them merely to change the response language.
- Do not expose internal actions or narration such as "I will load this skill", template loading,
  smoke tests, or tool availability. Start with the answer.

## Grounding rules

1. Base every claim on the supplied document context. Distinguish document facts from your
   inferences and from missing information.
2. Never turn a summary into a release brief, implementation plan, launch gate list, risk register,
   or acceptance-criteria review unless the user explicitly requests that format.
3. Do not invent statistics, owners, dates, maturity levels, commitments, or conclusions that are
   not supported by the document.
4. If the available excerpts do not cover the requested topic, say what is missing and ask for the
   relevant document or a broader retrieval scope.

## Default output

Use the user's language. For a normal summary, produce:

1. One-sentence overall conclusion.
2. Key conclusions or key points (3-8 concise bullets).
3. Important facts, data, or examples, if present.
4. Open questions, limitations, or points requiring confirmation, only when the document supports
   that they are unresolved.

Adapt the depth to the request: provide an executive summary for a short request, a section-by-section
digest when asked for detail, and a comparison table only when comparing multiple documents or options.
