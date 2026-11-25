# Death sequence mock-ups (#1337)

Throwaway post-production for the user's pick between death sequence variants. Not shipped, not part of the build.

1. Footage: the `death-mockup` evidence scenario (`DeathMockupScenario`) walks a low-health pilot under a regolith roof in
   layer 1, drops the roof on them, and writes one frame per tick up to the tick before the vanilla death screen, plus
   `events.txt` (the frame of each step, crack, collapse, hurt and the death).
2. `mockup.py` rebuilds the gameplay audio from the game's own sound files at those frames (the cloud client records no
   sound), then cuts each variant's death part after the last frame: the cylinder replay, the phonautograph trace, the
   frozen frame, the needle skip and the Charter's CRT ledger.

Run: `python3 -I prototypes/death-sequence/mockup.py --frames <dir> --events <file> --mc <vanilla sounds dir> --out <dir>`
(needs numpy and Pillow).
