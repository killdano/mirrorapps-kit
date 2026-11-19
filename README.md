# mirrorapps-kit

Shared Android code for the MirrorApps companion apps (MirrorMap Dash and
MirrorMsg): the Feedback tab and the session recorder. Apps include it as a
git submodule at `kit/`.

`updater/` is a separate module: the test-build updater, which lets an
app's debug build install newer test builds by itself. Apps take it as
`debugImplementation`, so release builds contain none of it; see
`updater/build.gradle.kts` for how to include it.

Public repo: nothing private goes here (no keys, access codes or user data),
and issues are filed in the repo of the app that showed them.
