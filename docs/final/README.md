# Theremin Gloves — Final Submission Package Draft

This folder contains the in-repo document drafts that can be prepared directly from the codebase and generated markdown.

## Recommended Reading Order

If someone is opening this folder for the first time, the fastest useful order is:

1. [01_Design_Document.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/01_Design_Document.md)
2. [03_User_Manual.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/03_User_Manual.md)
3. [10_Demo_Preparation.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/10_Demo_Preparation.md)
4. [02_Test_Document.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/02_Test_Document.md)
5. [11_Submission_Checklist.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/11_Submission_Checklist.md)

## Included Drafts

- [01_Design_Document.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/01_Design_Document.md)
- [02_Test_Document.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/02_Test_Document.md)
- [03_User_Manual.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/03_User_Manual.md)
- [04_Mission_Statement.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/04_Mission_Statement.md)
- [05_Ethics_Report.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/05_Ethics_Report.md)
- [06_Computer_Simulation_Summary.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/06_Computer_Simulation_Summary.md)
- [07_Definition_of_Done.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/07_Definition_of_Done.md)
- [08_AI_Usage_Document.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/08_AI_Usage_Document.md)
- [09_Presentation_Notes.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/09_Presentation_Notes.md)
- [10_Demo_Preparation.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/10_Demo_Preparation.md)
- [11_Submission_Checklist.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/11_Submission_Checklist.md)
- [12_Final_Product_Backlog.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/12_Final_Product_Backlog.md)

## Still Manual

These submission tasks still need manual handling outside the current repo-only pass:

- final test-document completion on the physical Pixel 7 for rows still marked `Pending manual verification`
- Excel backlog updates
  - `Product_Backlog_Sprint_2.xlsx` is not present in this repo
- team blog updates and sign-offs
  - `Team_blog_sprint2.xlsx` is not present in this repo
- PowerPoint slide creation/export to PDF
- live-demo rehearsal on the physical Pixel 7 with both gloves
- final demo-video recording/export
- final PDF merge for eConcordia

## Important Truthfulness Notes

- The current public Play build exposes **11 tones** (SQUARE was re-added in Sprint 3), not the older 9-waveform or 10-tone lists in stale docs.
- Persisted Calibration/Settings defaults are pitch `0°..90°`, volume `0°..90°`, and frequency `20..2000 Hz`, while Play-only restore defaults are pitch `-15°..55°`, volume `-10°..55°`, and frequency `880..2000 Hz`.
- The current audio buffer is `AUDIO_WRITE_FRAMES = 1024`, not `2048`.
- `MainActivity` and `ThereminBackgroundAudioService` hand off playback between separate engine instances; they do not literally share one audio-engine object.
- The test document in this folder is intentionally conservative and should not be turned into a “100% pass” claim without doing the remaining physical/manual checks.
- The canonical AI disclosure now exists at [docs/AI_Usage_Document.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/AI_Usage_Document.md), and [08_AI_Usage_Document.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/08_AI_Usage_Document.md) is the mirrored submission-pack copy.
- [Final_Presentation_Submission_Plan.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/Final_Presentation_Submission_Plan.md) and [conversation_summary.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/conversation_summary.md) are useful planning references, but the live code and audited `docs/final/` files remain the technical authority.
- The old root-level Sprint-era drafts remain retirement pointers only; use the audited files in `docs/final/` for the actual submission package.

## Folder Roles

- `docs/final/` is the submission-oriented draft set.
- `docs/generated/` is upstream generated source material, not the final package by itself.
- root-level `docs/*.md` files are either canonical reference files such as [AI_Usage_Document.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/AI_Usage_Document.md) or retired planning/history notes.
