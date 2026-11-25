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

- **Experimental Talking Clock.** Frank Lambert, about 1878. The lead cylinder was discovered by Aaron Cramer, and was transferred from it in April 1999 by Glenn Sage (Tinfoil.com) (https://www.tinfoil.com/lam-clock~.mp3, notes at https://tinfoil.com/cm-0101.htm).

The file's own notice, quoted verbatim from its copyright tag: "(C)(P) Glenn Sage (Tinfoil.com).  Do not copy, modify or distribute without permission." Published in the public repo at the user's direction, despite the transfer's do-not-distribute note.

## The Continuity Event's laugh: the Okeh Laughing Record

When a person dies, the Company laughs: two laughers from a 1922 record, slowed, through a brass horn and into a cavern. [tools/sounds/okeh_laugh.py](tools/sounds/okeh_laugh.py) cuts and treats them from a local copy of the transfer (which is not in the repository), and [tools/sounds/okeh_laugh.json](tools/sounds/okeh_laugh.json) records the source's URL, its md5 and sha256, and its tags verbatim.

- **The Okeh Laughing Record.** Okeh 4678-A (1922), matrix 4678A. Transfer by the Great 78 Project, digitized at George Blood, L.P. (the file's tags). Licence: none stated. Source: https://archive.org/download/78_the-okeh-laughing-record_gbia0046783a/The%20Okeh%20Laughing%20Record.flac (page: https://archive.org/details/78_the-okeh-laughing-record_gbia0046783a)

The item states no licence. It is a sound recording published in the United States before 1923, so it is in the public domain in the US under the Music Modernization Act.

## More early recordings

Later and rarer recordings than the phonautograms. [tools/sounds/first_sounds.py](tools/sounds/first_sounds.py) and [tools/sounds/ambience.py](tools/sounds/ambience.py) make the files, and their manifests ([first_sounds.json](tools/sounds/first_sounds.json), [ambience.json](tools/sounds/ambience.json)) record each source's canonical URL and hash. Where a file states no licence, its credit says so: it is credited and linked, and no licence is claimed. The note above about derivative works and firstsounds.org covers the First Sounds playbacks here too.

### Wax cylinders

Five more recordings a player can find on a wax cylinder in a wreck's locker or a salvage crate, each trimmed to its strongest stretch and given the phonograph's treatment, and two more that are only on the colony's own phonograph, in the Lamp and Pick. The Volta Laboratory recordings are First Sounds' playbacks; the Edison paraffine cylinders and the talking doll come from the Thomas Edison National Historical Park.

- **Counting one to six, with trilled Rs.** Recorded at the Volta Laboratory (about 1881); playback by First Sounds (firstsounds.org). The file states no licence. Source: https://www.firstsounds.org/sounds/volta/312119_Lateral_Electroplated_Disc-120.wav
- **The Hamlet soliloquy.** Recorded at the Volta Laboratory (about 1884, perhaps by Chichester Bell); playback by First Sounds (firstsounds.org). The file states no licence. Source: https://www.firstsounds.org/sounds/volta/Example1-Green-p93.wav
- **After dinner toast at Little Menlo.** Arthur Sullivan (Oct. 5, 1888); Edison yellow paraffine cylinder, catalog E-2439-7. Courtesy of the U.S. National Park Service, Thomas Edison National Historical Park. Source: https://www.nps.gov/edis/learn/photosmultimedia/upload/EDIS-SRP-0155-14.mp3
- **Around the world on the phonograph.** Thomas A. Edison (1888); Edison yellow paraffine cylinder, catalog EDIS 566. Courtesy of the U.S. National Park Service, Thomas Edison National Historical Park. Source: https://www.nps.gov/edis/learn/photosmultimedia/upload/EDIS-SRP-0155-06.mp3
- **The Lost Chord, cornet and piano.** Cornet and piano (performers unknown), London (about Aug. 1888); Edison yellow paraffine cylinder, catalog E-2440-3. Courtesy of the U.S. National Park Service, Thomas Edison National Historical Park. Source: https://www.nps.gov/edis/learn/photosmultimedia/upload/EDIS-SRP-0154-02.mp3
- **Edison talking-doll prototype, "Twinkle, twinkle, little star".** Talking-doll prototype, "Twinkle, twinkle, little star" (1888); restored with Lawrence Berkeley National Laboratory (LBNL). Courtesy of the U.S. National Park Service, Thomas Edison National Historical Park. Source: https://www.nps.gov/nps-audiovideo/legacy/mp3/ner/avElement/edis-tenhp-lbnl-edison-c-E-821-8-edis-1279-20110523-80rpm-restored.mp3
- **Nursery rhyme as the machine winds down.** Recorded by Tainter and Rogers at the Volta Laboratory (1885); playback by First Sounds (firstsounds.org). The file states no licence. Source: https://www.firstsounds.org/sounds/volta/Example2-PD1-p93-44100.wav

### Deep fragments and the horn

Short fragments of the recordings below are heard in the deepest layers and from the Company's PA horn: a human ear's phonautogram, a syllable of the noematachogram, Morey's vowel, a vocal scale with its crosstalk, a diapason scale, a train on the elevated railroad with test voices, a barometer, and a far-off choir.

- **Phonautogram made using a human ear.** Phonautogram by August Lucae in Rudolph Koenig's workshop (published in Virchow's Archiv, 1862); playback by First Sounds (firstsounds.org); CC BY. Source: https://www.firstsounds.org/features/mp3/listening-dead.mp3
- **Noematachogram.** Recorded by Franciscus Cornelis Donders (1865, published 1868); playback by First Sounds (firstsounds.org); CC BY. Source: https://www.firstsounds.org/features/mp3/noematachogram.mp3
- **The vowel "oo" in "mood".** Phonautogram by Charles Anson Morey (published 1874); playback by First Sounds (firstsounds.org). The file states no licence. Source: https://www.firstsounds.org/features/mp3/morey-mood.mp3
- **Gamme de la Voix, vocal scale with crosstalk.** Phonautogram by Édouard-Léon Scott de Martinville (May 17, 1860); playback by First Sounds (firstsounds.org); CC BY. Source: https://www.firstsounds.org/sounds/1860-Vocal-Scale.mp3
- **Diapason Scale.** A waveform inscribed by Claude-Servais-Matthias Pouillet (1850); playback by First Sounds (firstsounds.org); CC BY. Source: https://www.firstsounds.org/features/mp3/diapason-scale.mp3
- **Metropolitan Elevated Railroad from 40 feet away, with test voices.** Phonautogram by Charles Batchelor for Thomas Edison (Sept. 19, 1878), from the Thomas Edison National Historical Park; playback by First Sounds (firstsounds.org); CC BY. Source: https://www.firstsounds.org/sounds/1878-Edison-MERR.mp3
- **Barometer, repeated.** Recorded at the Volta Laboratory (1884); playback by First Sounds (firstsounds.org). The file states no licence. Source: https://www.firstsounds.org/sounds/volta/287686_Barometer.wav
- **Handel Festival: "Israel in Egypt", excerpt.** Chorus conducted by August Manns (1888); Edison yellow paraffine cylinder, catalog E-2440-20. Courtesy of the U.S. National Park Service, Thomas Edison National Historical Park. Source: https://www.nps.gov/edis/learn/photosmultimedia/upload/EDIS-SRP-0154-17.mp3
