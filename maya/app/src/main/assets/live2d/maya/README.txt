PUT YOUR LIVE2D CUBISM MODEL FILES HERE
=======================================

Maya loads a REAL Live2D Cubism model from this folder through the
maya.model3.json entry point. The app validates the files at startup.

Required layout (exact filenames referenced by maya.model3.json):

  assets/live2d/maya/
  ├── maya.model3.json          <- entry point (Cubism 3 format, "version": 3)
  ├── maya.moc3                 <- the compiled model (must start with MOC3 magic)
  ├── maya.physics3.json        <- optional but recommended (hair/cloth physics)
  ├── maya.userdata3.json       <- optional
  ├── textures/                 <- png textures referenced by model3.json
  │   └── maya.2048/texture_00.png ...
  ├── motions/                  <- motion3.json files + groups in model3.json
  │   ├── idle_00.motion3.json
  │   ├── listening_00.motion3.json
  │   ├── thinking_00.motion3.json
  │   ├── speaking_00.motion3.json
  │   ├── greeting_00.motion3.json
  │   ├── happy_00.motion3.json
  │   ├── concerned_00.motion3.json
  │   └── error_00.motion3.json
  └── expressions/              <- expression3.json files
      ├── happy.exp3.json
      ├── concerned.exp3.json
      ├── angry.exp3.json
      └── surprised.exp3.json

maya.model3.json must reference the files RELATIVE to this folder, e.g.:

{
  "version": 3,
  "fileReferences": {
    "moc": "maya.moc3",
    "physics": "maya.physics3.json",
    "textures": [ "textures/maya.2048/texture_00.png" ],
    "motions": {
      "idle":      [ { "file": "motions/idle_00.motion3.json", "fadein": 500, "fadeout": 500 } ],
      "listening": [ { "file": "motions/listening_00.motion3.json" } ],
      "thinking":  [ { "file": "motions/thinking_00.motion3.json" } ],
      "speaking":  [ { "file": "motions/speaking_00.motion3.json" } ],
      "greeting":  [ { "file": "motions/greeting_00.motion3.json" } ],
      "happy":     [ { "file": "motions/happy_00.motion3.json" } ],
      "concerned": [ { "file": "motions/concerned_00.motion3.json" } ],
      "error":     [ { "file": "motions/error_00.motion3.json" } ]
    },
    "expressions": [
      { "name": "happy",     "file": "expressions/happy.exp3.json" },
      { "name": "concerned", "file": "expressions/concerned.exp3.json" },
      { "name": "angry",     "file": "expressions/angry.exp3.json" },
      { "name": "surprised", "file": "expressions/surprised.exp3.json" }
    ]
  },
  "hitAreas": [
    { "id": "head", "name": "Head" }
  ]
}

Motion groups the app asks for (falls back gracefully when absent):
  idle, listening, thinking, speaking, greeting, happy, concerned, error

Standard parameters the app drives (when your model exposes them):
  ParamMouthOpenY, ParamMouthForm, ParamEyeLOpen, ParamEyeROpen,
  ParamEyeBallX, ParamEyeBallY, ParamAngleX/Y/Z, ParamBodyAngleX, ParamBreath

WHERE TO GET A MODEL
--------------------
1) Export your own from Live2D Cubism Editor (File -> Export -> For Runtime).
   A Cubism 4/5 Editor export as "Cubism 3" produces compatible .moc3 + model3.json.
2) Use any Cubism-3-format model you have the license to use (many free models
   exist on live2d.com and booth.pm — check each model's license terms).

LIP SYNC
--------
The app drives ParamMouthOpenY from the TTS utterance lifecycle (amplitude-style
envelope), so any model with a standard mouth parameter animates while Maya speaks.

NOTE
----
This placeholder folder ships without a model because Cubism model assets and the
Cubism SDK runtime are licensed separately (Live2D Inc.). When the folder is empty
or invalid, the app shows a clearly-labeled placeholder avatar and keeps chat,
voice and all Maya features working.
