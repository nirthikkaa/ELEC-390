# Theremin Gloves — Final Submission Checklist

This checklist is aligned to the official COEN/ELEC 390 final requirements from `project doc 390 Project Document Jan 2026.pdf` and the current `sprint3` codebase.

## Official Dates

- **April 7–10, 2026** — final oral presentation and live demo (online), **10–12 minutes**
- **April 15, 2026** — final submission due
- **Document packaging rule** — aside from code/app artifacts, milestone, sprint, and final written documents must be submitted as **one comprehensive PDF**; links are not acceptable

## Presentation Requirements

The final presentation and demo must:

- identify the customer
- explain the value of the product for that customer
- explain how the product functions, including architecture, communication links, and important signal processing / algorithms
- include a live final demo with the instructor and Bipin Patel

## Canonical Submission Sources

| Required item | Canonical source | Current status |
|---|---|---|
| Updated mission statement | [04_Mission_Statement.md](04_Mission_Statement.md) | Markdown source ready; final export still needed |
| Final product backlog | [12_Final_Product_Backlog.md](12_Final_Product_Backlog.md) + external spreadsheet | Markdown source ready; spreadsheet still needs manual update |
| Revised design document | [01_Design_Document.md](01_Design_Document.md) | Updated in repo; export still needed |
| User manual | [03_User_Manual.md](03_User_Manual.md) | Updated in repo; export still needed |
| Updated Definition of Done | [07_Definition_of_Done.md](07_Definition_of_Done.md) | Source ready; export still needed |
| Test document | [02_Test_Document.md](02_Test_Document.md) | Updated in repo; latest results are `40/40` JVM and `98/99` instrumented |
| Ethics report (2 pages) | [05_Ethics_Report.md](05_Ethics_Report.md) | Source ready; verify final length during export |
| Computer simulation summary (1 page) | [06_Computer_Simulation_Summary.md](06_Computer_Simulation_Summary.md) | Updated in repo; verify final length during export |
| Demo video (5–7 min, <=2 min intro) | Manual recording | Not done in repo |
| Generative AI usage + contribution description | [08_AI_Usage_Document.md](08_AI_Usage_Document.md), [AI_Usage_Document.md](AI_Usage_Document.md) | Source exists; final submission copy still needs confirmation |
| Slides converted to PDF | [09_Presentation_Notes.md](09_Presentation_Notes.md), [10_Demo_Preparation.md](10_Demo_Preparation.md) | Deck export still needed |
| Product app | Gradle build output | `./gradlew test` passes; APK export still needed |
| Final team blog with agreement page | External spreadsheet / Drive artifact | Manual external update still needed |
| Originality form | External course form | Manual signatures still needed |

## Current Codebase Ground Truth

Use these facts for every exported document and slide:

- public Play tone set: **11 tones** (`Theremin`, `Air Pad`, `Cello`, `Pad`, `Choir`, `Flute`, `Clarinet`, `Triangle`, `Saw`, `Square`, `Helicopter`)
- audio buffer constant: `AUDIO_WRITE_FRAMES = 1024`
- default frequency range: **20 Hz to 2,000 Hz**
- extended frequency range setting raises the ceiling to **20,000 Hz**
- `DrumEngine` uses a **hybrid** bank: bundled raw drum/bass samples plus in-code synthesis
- `MainActivity` and `ThereminBackgroundAudioService` hand off between **separate engine instances**, not one shared engine object
- Library uses **menu-based rename/move/delete** plus folder drag-drop, not swipe-to-delete or drag-to-reorder
- automated coverage is **139 tests total**: 40 JVM + 99 instrumented

## Verification Status

- `./gradlew test` — **pass**
- `./gradlew connectedDebugAndroidTest` — **98 / 99 pass**
- Remaining failing test: `BluetoothPromptUiTest.launch_promptsForBluetoothPermissions_whenMissing`
- Failure cause: the Pixel 7 already has Bluetooth permissions configured, so Android suppresses the permission dialog and the test cannot force that OS prompt to reappear reliably

## External Cleanup Still Required

- Refresh the Google Drive `Sprint 3` folder with exports from the updated repo docs.
- Remove or replace duplicate Drive user-manual PDFs.
- Update the Drive `Final Presentation` and `Final Report`; both were stale at audit time.
- Regenerate the `backup/submission-docs` export pack from the corrected repo sources before using it as a final archive.
- Update the external backlog spreadsheet and team blog.
- Record the demo video and export the final deck PDF.
- Merge the required written documents into one comprehensive PDF for eConcordia.
