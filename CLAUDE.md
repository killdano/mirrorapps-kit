# CLAUDE.md — mirrorapps-kit

Shared Android code for the MirrorApps companion apps: **MirrorMap Dash**
and the **MirrorMsg** Android app. Read this whole file before changing
anything.

## This repo is public

- Nothing private, ever: no keys, tokens, access codes, signing files,
  the owner's data, user text, locations, or anything copied from a private
  repo's logs. Codes the apps need (a dev code, the user's access code)
  reach the kit only at run time, through `KitConfig`.
- Test data is made up and obviously fake.
- **No issues are filed here.** A kit bug is filed in the repo of the app
  that showed it, and its fix is a kit PR plus a submodule bump in each app.

## Shape

One Android library module, `kit/` (namespace `com.mirrorapps.kit`,
Compose, minSdk 26: the lower of the two apps). Packages:

- `com.mirrorapps.kit`: `MirrorAppsKit.init(context, KitConfig(...))`, the
  host API: apps, host version, credential, log source, bundle
  contributors, theme, Feedback options.
- `feedback`: the Feedback tab. `FeedbackPane` (no app switch: the host
  names one hub app in `FeedbackOptions.app`, Dash `dash`, and the hub
  works out which app a report is about), `FeedbackSession` (the chat,
  attachments, voice), `FeedbackTransport` (SSE to the hub),
  `DiagnosticBundle` (host contributors plus redacted logs), `media/`
  (photo picker reading, voice recording, uploads).
- `sessions`: `SessionRecorder` (one per app: windows, idle-window
  dropping, upload queue, enabled and consent), `SessionChunks` (the pure
  half), the "Share my …" row and dialogs with host wording.
- `privacy`: `PiiTags` (the log tagging format), `Redactor` (bug-report
  redaction), `ShapeFilter` (MirrorMsg's content shapes).
- `ui`: the host-themed pieces (toggle row).

## How apps use it

- Submodule at `kit/` in each app; the module is `kit/kit/`:
  ```
  include(":kit")
  project(":kit").projectDir = file("kit/kit")
  ```
  App builds check out with `submodules: true`.
- `kit/build.gradle.kts` applies `com.android.library` and the Compose
  compiler plugin **by bare id**: the app's root build must already have
  AGP and `org.jetbrains.kotlin.plugin.compose` on its classpath. Never add
  versions there, and never use an app's version catalog (`libs.`) in the
  kit: each app names things differently. Plain coordinates only.
- The root `settings.gradle.kts`/`build.gradle.kts` here are only for the
  kit's own CI. Keep their plugin versions equal to Dash's catalog.
- A kit change reaches an app only when that app bumps its submodule. Say
  in the kit PR which apps need the bump.

## Contracts

The kit talks to `api.mirrormap.app`. The backend repo's
`trip-review/SPEC.md` is the contract for sessions and media; Feedback's
wire format is the hub's (`functions/src/feedback/` there).

- Feedback: `{app, sessionId?, text, bundle?, media?}` and
  `{app, sessionId, granted, bundle?}`, SSE events in `FeedbackEvent`.
  The bundle's `logs.dashLog` / `dashLogLines` / `logcat` / `redactions`
  keys are read by the hub for every app, whatever the host is called
  (`logcat` is absent when the host sets `FeedbackOptions(logcat = false)`).
  Add fields and events; never rename one.
- Sessions: chunk `app` is the session app's id string (the backend
  refuses anything else); the host's name and version go in `client`.
  `GET /v1/trips/enabled?app=`, `POST /v1/trips/consent {app, ...}`.
- Media: start, resumable PUT to the Storage URL the backend returns,
  done, then poll `GET /v1/media/<id>` for a transcript. `recordedAt` is
  when the recording started on the phone's clock; for a picked video,
  its creation time from the file's metadata.
- App ids (`mirrormap`, `mirrormov`, `mirrormsg`) are wire values.
- A change to any of these is a change to the backend's SPEC.md in the
  same round of PRs.

## Privacy rules the code keeps

- Bug-report logs go through `Redactor` per the host's "Logs in bug
  reports" setting; codes, tokens and emails go in every mode. The chat
  shows that setting (Full / Redacted / None) at its top in every app and
  saves it through the host, so a host doesn't draw its own.
- MirrorMsg never sends message content: notification, conversation and
  reply text goes through `ShapeFilter`, names become `Name` + length.
  Its log lines tag names (`PiiTags.KIND_NAME`) and message text
  (`PiiTags.KIND_MESSAGE`) so `ShapeFilter.filterLogLine` can shape them;
  untagged text passes unchanged.
- A host whose native code writes to logcat without its scrubbing sets
  `FeedbackOptions(logcat = false)`, so only its own log goes in a report
  (MirrorMsg: its Go bridges log there).
- Media leaves the phone only when the user attaches it. Voice needs
  RECORD_AUDIO, asked for when the user taps Voice.

## Build and tests

GitHub Actions (`.github/workflows/build.yml`) is the build of record:
`./gradlew :kit:assembleDebug :kit:testDebugUnitTest` on every push to
`main` or a `claude/` branch, failures as a check-run annotation. You
don't need the Android SDK in your session; if it isn't there, don't try
to install it. The pure parts (`SessionChunks`, `ShapeFilter`,
`Redactor`, `FeedbackEvent`, `MediaClient`) have unit tests; keep it that
way for anything that can be pure.

The apps' own builds compile the kit too: a kit change isn't done until
each app that bumps to it builds.

## Testing honesty

A clean build is not a tested fix. The Feedback tab, uploads, voice and
recording only really run on a phone. Say what you didn't verify, and put
the steps the owner should run in the app PR that bumps the kit.

## Don't

- Don't put anything private here (see the top).
- Don't file issues here.
- Don't push to `main` or merge. The owner merges.
- Don't rename wire fields, app ids or SSE events.
