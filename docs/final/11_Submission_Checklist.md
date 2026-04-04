# Theremin Gloves — Final Submission Checklist

This checklist turns the remaining Phase 4 and Phase 5 work into one place. It is intentionally operational, not narrative.

## Highest-Risk Remaining Items

If the team only has time to focus on a few things first, do these before polish work:

1. finish the remaining manual Pixel 7 checks in the test document
2. rehearse the live demo with the actual gloves and exact demo phone state
3. export the slide deck and verify it matches the current code, not stale Sprint notes
4. record the demo video after the live rehearsal path is stable
5. only then do the final PDF merge and submission packaging

## eConcordia Package Checklist

- Mission Statement
  - Source draft: [04_Mission_Statement.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/04_Mission_Statement.md)
- Final Product Backlog
  - Markdown draft: [12_Final_Product_Backlog.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/12_Final_Product_Backlog.md)
  - Excel spreadsheet still needs manual update outside repo
- Design Document
  - Source draft: [01_Design_Document.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/01_Design_Document.md)
- User Manual
  - Source draft: [03_User_Manual.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/03_User_Manual.md)
- Definition of Done
  - Source draft: [07_Definition_of_Done.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/07_Definition_of_Done.md)
- Test Document
  - Source draft: [02_Test_Document.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/02_Test_Document.md)
  - Still requires final manual Pixel 7 fill-in for pending rows
- Ethics Report
  - Source draft: [05_Ethics_Report.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/05_Ethics_Report.md)
- Computer Simulation Summary
  - Source draft: [06_Computer_Simulation_Summary.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/06_Computer_Simulation_Summary.md)
- Demo video
  - Manual recording/export
- AI usage document
  - Submission-pack copy: [08_AI_Usage_Document.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/08_AI_Usage_Document.md)
  - Canonical source: [docs/AI_Usage_Document.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/AI_Usage_Document.md)
- Final oral presentation slides PDF
  - Manual PowerPoint export
- APK
  - Build from Gradle
- Final team blog with sign-offs
  - Manual spreadsheet update outside repo
- Expectation of Originality form
  - Manual signatures outside repo

## Repo Assets Already Prepared

- Generated content pack: [docs/generated](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/generated)
- Final draft pack: [docs/final](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final)
- Presentation speaker notes: [09_Presentation_Notes.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/09_Presentation_Notes.md)
- Demo runbook and break-test checklist: [10_Demo_Preparation.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/10_Demo_Preparation.md)

## Manual Gaps That Still Need Real-World Completion

- Run the remaining manual Pixel 7 checks and replace `Pending manual verification` in the test document.
- Update the backlog spreadsheet.
- Update the team blog spreadsheet and collect sign-offs.
- Build the slide deck and export it to PDF.
- Record and export the 5-7 minute demo video.
- Merge the final PDFs into one submission package.

## Demo Video Steps

- Mirror or capture the Pixel 7 screen.
- Record the app launch, connection, calibration, play, recording, library, and settings flow.
- Keep the video between 5 and 7 minutes.
- Use the live-demo order from [10_Demo_Preparation.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/10_Demo_Preparation.md).
- Export as MP4.

## APK Build

Debug build:

```bash
./gradlew :app:assembleDebug
```

Expected output:

- [app-debug.apk](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/app/build/outputs/apk/debug/app-debug.apk)

If a release keystore exists and the team decides to use it:

```bash
./gradlew :app:assembleRelease
```

## Word/PDF Export Workflow

- Move each Markdown draft into Word or Google Docs.
- Apply final formatting, page numbers, title pages, and diagrams.
- Export each document to PDF.
- Merge all required PDFs into one final submission PDF.

## Suggested Timeline

### April 3

- Review the generated and final draft markdown.
- Run the full physical-demo rehearsal once.
- Confirm which phone state will be used for the presentation: first-launch or returning-user.

### April 4

- Draft the PowerPoint slide deck from [09_Presentation_Notes.md](/Users/nirajpatel/AndroidStudioProjects/ThereminGlovestest2/docs/final/09_Presentation_Notes.md).
- Draft the design document and user manual into Word.

### April 5

- Run the physical Pixel 7 manual test rows.
- Complete the test document.
- Fix any demo-blocking bugs found in rehearsal.

### April 6

- Full-team rehearsal with timing.
- Rehearse the live demo exactly as written.
- Rehearse individual contribution answers.

### Presentation Window

- Deliver the presentation and demo.
- Record notes on any live questions that should be reflected in final documents.

### After Presentation

- Finalize PDFs.
- Record the demo video.
- Build the APK.
- Merge the submission package.
- Submit on eConcordia before the deadline.
