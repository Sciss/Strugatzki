# Strugatzki

[![Build Status](https://travis-ci.org/Sciss/Strugatzki.svg?branch=master)](https://travis-ci.org/Sciss/Strugatzki)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/de.sciss/strugatzki_2.13/badge.svg)](https://maven-badges.herokuapp.com/maven-central/de.sciss/strugatzki_2.13)

## statement

Strugatzki is a Scala library containing several algorithms for audio feature extraction, with the aim of 
similarity and dissimilarity measurements. They have been originally used in my live electronic 
piece ["Inter-Play/Re-Sound"](https://sciss.de/texts/liv_interplay.html), then successively in the tape 
piece ["Leere Null"](https://sciss.de/texts/tap_leerenull.html), the sound installation
["Writing Machine"](https://sciss.de/texts/ins_writingmachine.html), and the tape piece
["Leere Null (2)"](https://sciss.de/texts/tap_leerenull2.html).

(C)opyright 2011&ndash;2019 by Hanns Holger Rutz. All rights reserved. It is released under
the [GNU Lesser General Public License](https://git.iem.at/sciss/Strugatzki/raw/master/LICENSE) v2.1+ and 
comes with absolutely no warranties. To contact the author, send an email to `contact at sciss.de`.

## requirements / installation

Builds with sbt against Scala 2.13, 2.12. Depends on [ScalaCollider](https://git.iem.at/sciss/ScalaCollider) 
and [scopt](https://github.com/scopt/scopt). The last version to support Scala 2.11 was v2.19.0.

Strugatzki can be either used as a standalone command line tool, or embedded in your project as a library.

## contributing

Please see the file [CONTRIBUTING.md](CONTRIBUTING.md)

## running

### Standalone Use

This assumes you check out Strugatzki from source, as the easiest way to use it in the terminal is via the sbt 
prompt. First, start sbt without arguments. In the sbt shell, execute `run` which will print the switches for 
the different modules:

    -f | --feature
          Feature extraction
    -c | --correlate
          Find best correlation with database
    -s | --segmentation
          Find segmentation breaks with a file
    -x | --selfsimilarity
          Create an image of the self similarity matrix
    --stats
          Statistics from feature database

To find out the switches for the extraction module: `run -f`. This will print the particular options available 
for this module. While in the API times are all given in sample frames with respect to the original sound file's 
sample rate, the standalone/ terminal mode assumes times are all given as floating point seconds.

Another possibility is to build the standalone via `sbt assembly` and then execute it with shell script `./strugatzki`

### Library Use

If you build your project with sbt, the following line adds a dependency for Strugatzki:

    "de.sciss" %% "strugatzki" % v

The current version `v` is `"2.19.1"`.

As documentation you are referred to the API docs at the moment. These can be created in the standard way
(`sbt doc`). The main classes to look are `FeatureExtraction`, `FeatureCorrelation`, and `FeatureSegmentation`. 
They are used in a similar fashion. E.g. to run feature extraction:

```scala
import de.sciss.processor._
import de.sciss.strugatzki._
import de.sciss.file._
import scala.concurrent.ExecutionContext.Implicits._

val fs           = FeatureExtraction.Config()
fs.audioInput    = file("my-audio-input")
fs.featureOutput = file("my-feature-aiff-output")
fs.metaOutput    = Some(file("my-meta-data-xml-output"))  // optional

// the process is constructed with the settings and a partial function which
// acts as a process observer
val f = FeatureExtraction.run(fs) {
  case Processor.Result(_, _) => println("Done.")
}
// f is a `Future` of the result you may want to work with
```

For the detailed settings, such as FFT size, number of MFCC, etc., please refer to the API docs.

## Algorithms

Strugatzki is not a full fledged MIR system, but was rather born of my personal preference and experience, 
resulting in an API which is a bit idiosyncratic, but nevertheless completely independent of my specific use cases.

The feature vectors used are spectral envelope as defined by the Mel Frequency Cepstral Coefficients (MFCC) and 
the Loudness in Sones. The actual DSP algorithms responsible for their extraction are the `MFCC` and `Loudness` 
UGens included with SuperCollider, which were written by Dan Stowell and Nick Collins. They are used behind the 
scenes, running ScalaCollider in Non-Realtime-Mode. 

In most processes, there is a parameter `temporalWeight` which specifies the weight assigned to MFCC versus 
loudness. A temporal weight of `0.0` means the temporal feature vector (loudness) is not taken into account, 
and a weight of `1.0` means that only the loudness is taken into account, while the spectral features (MFCC) 
are ignored.

The correlation, segmentation, and so forth are performed directly in Scala, using dedicated threads, providing 
an API for monitoring completion, failure, progress, and an abortion hook. As of the current version, all processes 
run single-threaded, so there is plenty of headroom for future performance boosts by providing some forms of 
parallelism. Strugatzki is an artistic and a research project, not a commercial application, so beware that it is 
not the fastest MIR system imaginable.

The feature vectors (MFCC and loudness) are calculated on a frame-by-frame basis using a sliding (FFT) window. They 
are written out as a regular AIFF sound file, which is a convenient format for storing evenly sampled multichannel 
floating point streams. Accompanied by a dedicated XML file which contains the extraction settings for future 
reference and use by the other algorithms.

There are two main algorithms that operate on the extracted features: The correlation module is capable of finding 
sound in a database that match a target sound in terms of similarity or dissimilarity. The segmentation module is 
capable of suggesting breaking points in a single target sound on the basis of novelty or maximisation of 
dissimilarity within a given time window.

### Normalization

We have found it quite useful to normalize the MFCC by creating statistics over a large body of database sounds. 
Therefore, a particular stats module is provided which can scan a directory of feature extraction files and 
calculate the minimum and maximum ranges for each coefficient. In the standalone mode, these ranges can be 
written out to a dedicated AIFF file, and may be used for correlation and segmentation, yielding in our opinion 
better results.

### Self Similarity

For analysis and visualisation purposes, we have added a self similarity module which produces a `png` image file 
with the self similarity matrix of a given feature file.

## Examples

### Finding all occurrences of one sound sample within a longer sound file

This is a case that was not directly aimed at in the original design, so while it is possible to perform this task,
the way one has to call Strugatzki is perhaps a bit strange. Here are the main concepts needed:

- The 'database' to search for in this case contains only a single sound -- the long file.
- In order to find more than one occurrence, we must set in advance the number of hits we want to have reported

For the sake of the example, let's assume the long file is the public domain recording of an H.P. Lovecraft text,
as placed in the librevox project and made available through
[archive.org](https://archive.org/details/collected_lovecraft_0810_librivox/beyond_the_wall_of_sleep_lovecraft_dw.mp3).

Let's assume we are in a terminal inside the Strugatzki project's base directory. The file
`beyond_the_wall_of_sleep_lovecraft_dw.mp3` has been downloaded to `~/Downloads/`. 
The file has to be decompressed first, because Strugatzki can only handle files in uncompressed formats such as AIFF
or WAV. For example, if you have `lame` installed:

    $ lame --decode ~/Downloads/beyond_the_wall_of_sleep_lovecraft_dw.mp3 beyond_the_wall.wav
    
Let's assume we have a snippet from this file as the sound we want to find as a pattern. Take the word 'but' as spoken
at time 1:19.350" for the duration of 350ms. Use a sound editor to copy this snippet into a new file named
`beyond_the_wall_BUT.wav`. Now we want to find sounds similar to this word 'but' in the entire file.

First, both files have to be feature analysed. Since Strugatzki requires that the long file be in a 'database'
directory, we create a new directory `db`. Then we extract the features of both the short and the long file, making
sure that only the long file is extracted into the `db` directory:

    $ sbt 'run -f beyond_the_wall_BUT.wav -d .'

and

    $ sbt 'run -f beyond_the_wall.wav -d db'
    
You will find for each file a `_feat.aif` and a `_feat.xml` file. The former contains the feature vectors, the latter
contains the meta-data. We can now run the correlation algorithm:

    $ sbt 'run -c -d db --in-start 0.0 --in-stop 0.350 --dur-min 0.350 --dur-max 0.350 --num-matches 20 --num-per-file 20 --no-norm --spacing 0.2 --boost-max 2.0 beyond_the_wall_BUT_feat.xml'

What are the parameters?

- `-d db` - the 'database' directory (containing only one file, the long sound file)
- `--in-start 0.0` - the start offset in second into the 'template' file (the short BUT file). If we wanted to select only a
  fraction of that file, we could give an offset here
- `--in-stop 0.350` - the stop offset in second into the 'template' file. We give its total duration here.
- `--dur-min 0.350` and `--dur-max 0.350` - the duration range for matching the template and the database. We force
  the program to match exactly the 350 milliseconds.
- `--num-matches 20` and `--num-per-file 20` - by default, the program would return only one match, the best match.
  Here we say, we want the twenty best matches, and that it is allowed to return up to twenty matches from the same
  file. (Otherwise only one match per file would be permitted)
- `--no-norm` - we do not have a feature vector normalisation file
- `--spacing 0.2` - different matches must be at least 200 milliseconds away from each other
- `--boost-max 2.0` - the maximum gain factor between template and match (6 dB)

Here are the first four matches:

    File      beyond_the_wall.wav
    Similarity: 97.3%
    Span start: 3499520
    Boost in  : 0.5 dB
    
    File      beyond_the_wall.wav
    Similarity: 91.6%
    Span start: 52465664
    Boost in  : 1.3 dB
    
    File      beyond_the_wall.wav
    Similarity: 90.6%
    Span start: 60998144
    Boost in  : -0.6 dB
    
    File      beyond_the_wall.wav
    Similarity: 89.3%
    Span start: 64767488
    Boost in  : 1.4 dB
    
The `Span start` fields are the offsets into the long file in sample frames. To convert them to seconds, they have to
be divided by the sampling rate (44100), thus the first four matches occur at 79.354s (1:19.354"), 
1189.698s (19:49.698"), 1383.178s (23:03.178"), 1468.651s (24:28.651"). Obviously the first match is the original sample. The
reason why the similarity is not 100% is that the algorithm steps across the file in small windows, so there is a tiny
amount of time smearing involved. The second match, if you listen into the file, sounds like 'bite' and is the second
part of the word 'despite'. The third match sounds similar, it's the word 'light'.

