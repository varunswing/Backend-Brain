# Amazon Round 2 — SDE-2 (L5) Prep Pack

Mirror of what interviewers write in feedback. LP + LLD packs are **filled for learning** — speak them aloud; don’t read from the screen in the real loop.

## Round shape (from feedback screenshots)

| Block | Typical status labels | Time risk |
|-------|----------------------|-----------|
| **Summary** | Inclined / Not Inclined | — |
| **Insist on the Highest Standards** | Strength / Mild Concern / Concern | Behavioral ~10–15 min |
| **LLD** | Strength / Mild Concern / Concern | Design + code ~30–40 min |
| **Invent and Simplify** | Strength / Mild Concern / **No Data** | Often skipped if LLD overruns |

## Folder map

```
amazon round 2/
├── README.md
├── 00_round_debrief.md              ← what went wrong in past loops (study first)
├── 01_interview_feedback_template.md ← blank sheet matching interviewer format
├── leadership_principles/
│   ├── insist_on_highest_standards.md  ← DEFAULT = your HCP (not gov slab 40090); Grace backup
│   └── invent_and_simplify.md          ← FULL STAR + cross-Q answers (TrueFlexi primary)
└── lld/
    └── pizza_pricing_engine/
        ├── PROBLEM.md
        ├── DESIGN.md
        ├── CrossQuestions.md        ← FULL LLD Q&A (OOP, multi-store, edges, SOLID)
        ├── NOTES.md                 ← Positives / Concerns / Assessment (practice)
        └── src/                     ← working Java reference
```

## Prep order (90–120 min — learning depth)

1. Read `00_round_debrief.md` — fix the exact failure modes.
2. **Highest Standards:** DEFAULT = **your HCP** STAR in `insist_on_highest_standards.md` (refuse wrong money). Do **not** claim `INGO-40090` gov slab. Grace = backup.
3. **Invent & Simplify:** same with TrueFlexi; lock 60s cut for time-box.
4. **LLD:** `PROBLEM` → `DESIGN` → `CrossQuestions.md` (sections B–D critical) → re-code from memory in `src/`.
5. Self-grade into `NOTES.md` + `01_interview_feedback_template.md`.
6. Timed dry-run: LP 12 min + LLD 35 min. **Leave 5 min for Invent & Simplify.**

## Status vocabulary (use when self-grading)

- **Strength** — clear data points, little prompting
- **Mild Concern** — fundamentals OK, needed prompting / incomplete wiring
- **Concern** — could not demonstrate the bar even with follow-ups
- **No Data** — interviewer ran out of time (avoid by pacing LLD)

## Link back to bank

Source stories: `../STARPrinciples.md` and `../<Project>/STAR.md`.
