# Credits

## Wax cylinders: First Sounds

The recordings on the wax cylinders in the game are playbacks of the oldest recorded voices, phonautograms made by Édouard-Léon Scott de Martinville in 1857 and 1860. First Sounds ([firstsounds.org](https://firstsounds.org)) made the playbacks and licenses them under Creative Commons Attribution (CC BY, [version 4.0](https://creativecommons.org/licenses/by/4.0/)). The game plays each of them with a phonograph's treatment: band-limited, with wow and flutter, a bed of crackle and hiss, and light compression, in mono. [tools/sounds/first_sounds.py](tools/sounds/first_sounds.py) makes the files, and [tools/sounds/first_sounds.json](tools/sounds/first_sounds.json) records each source's URL and hash.

First Sounds asks that derivative works be made available to firstsounds.org. This repository is public, which does that.

- **Gamme de la Voix, vocal scale.** Phonautogram by Édouard-Léon Scott de Martinville (May 17, 1860); playback by First Sounds (firstsounds.org), co-produced by Patrick Feaster and David Giovannoni; CC BY. Source: https://firstsounds.org/sounds/1860-Vocal-Scale-05-09.mp3
- **Jeune Jouvencelle, vocal solo.** Phonautogram by Édouard-Léon Scott de Martinville (Aug. 17, 1857); playback by First Sounds (firstsounds.org), co-produced by Patrick Feaster and David Giovannoni; CC BY. Source: https://firstsounds.org/sounds/Scott-Feaster-No-09.mp3
- **Excerpt from Ducis' Othello.** Phonautogram by Édouard-Léon Scott de Martinville (Apr. 17, 1860); playback by First Sounds (firstsounds.org), co-produced by Patrick Feaster and David Giovannoni; CC BY. Source: https://firstsounds.org/sounds/Scott-Feaster-No-38.mp3

## Ambience: First Sounds

Faint fragments of the same recordings (and of four more) are heard in the dark of the layers: a syllable, a breath of a scale, half a word, a tuning fork surfacing from noise. [tools/sounds/ambience.py](tools/sounds/ambience.py) cuts and wears them for the depth they are heard at, and [tools/sounds/ambience.json](tools/sounds/ambience.json) records each source's hash and every cut. The Gamme de la Voix and Othello credits above cover their fragments; the others are credited here.

- **Vole Petite Abeille, vocal solo.** Phonautogram by Édouard-Léon Scott de Martinville (ca. Sept. 1860); playback by First Sounds (firstsounds.org), co-produced by Patrick Feaster and David Giovannoni; CC BY. Source: https://firstsounds.org/sounds/1860-Scott-Vole-Petite-Abeille.mp3
- **Vole Petite Abeille, vocal solo (no. 6).** Phonautogram by Édouard-Léon Scott de Martinville (Sept. 15, 1860); playback by First Sounds (firstsounds.org), co-produced by Patrick Feaster and David Giovannoni; CC BY. Source: https://firstsounds.org/sounds/1860-Scott-Vole-Petite-Abeille-no-6.mp3
- **Excerpt from Torquato Tasso's Aminta.** Phonautogram by Édouard-Léon Scott de Martinville (ca. April-May 1860); playback by First Sounds (firstsounds.org), co-produced by Patrick Feaster and David Giovannoni; CC BY. Source: https://firstsounds.org/sounds/1860-Scott-Torquato-Tassos-Aminta.mp3
- **Diapason at 435 Hz, at sequential stages of restoration.** Phonautogram by Édouard-Léon Scott de Martinville (deposited July 29, 1859); playback by First Sounds (firstsounds.org), co-produced by Patrick Feaster and David Giovannoni; CC BY. Source: https://firstsounds.org/sounds/1859-Scott-Diapason-435-Hz.mp3

## The wreck's song: First Sounds

One more of the same playbacks is the song a wrecked pod's phonograph plays, and a faint, filtered line of it heard far off in the dark. It is never a wax cylinder and never one of the ambience's fragments. [tools/sounds/first_sounds.py](tools/sounds/first_sounds.py) makes both variants, the phonograph's treatment for the wreck and a faint, filtered one with a long reverb tail for the dark, and [tools/sounds/first_sounds.json](tools/sounds/first_sounds.json) records the source's hash and the cut.

- **Au Clair de la Lune, vocal solo.** Phonautogram by Édouard-Léon Scott de Martinville (Apr. 9, 1860); playback by First Sounds (firstsounds.org), co-produced by Patrick Feaster and David Giovannoni; CC BY. Source: https://firstsounds.org/sounds/Scott-Feaster-No-36.mp3

## The punch clock: Lambert's talking clock

The punch clock in Shift Change calls out an hour now and then in the voice of an 1878 recording, and a few slowed, broken fragments of it are heard in the deepest layers. [tools/sounds/lambert_clock.py](tools/sounds/lambert_clock.py) cuts the hours and fragments from a local copy of the mp3 (which is not in the repository), and [tools/sounds/lambert_clock.json](tools/sounds/lambert_clock.json) records the source's URL, its sha256, its tags verbatim and every cut. The recording skips ten, so the game never calls it.

- **Experimental Talking Clock.** Frank Lambert, about 1878; lead cylinder transferred by Aaron Cramer in 1999; published by Glenn Sage at tinfoil.com (https://www.tinfoil.com/lam-clock~.mp3, notes at https://tinfoil.com/cm-0101.htm).

The file's own notice, quoted verbatim from its copyright tag: "(C)(P) Glenn Sage (Tinfoil.com).  Do not copy, modify or distribute without permission." Published in the public repo at the user's direction, despite the transfer's do-not-distribute note.
