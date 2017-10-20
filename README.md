# Strugatzki

[![Build Status](https://travis-ci.org/Sciss/Strugatzki.svg?branch=master)](https://travis-ci.org/Sciss/Strugatzki)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/de.sciss/strugatzki_2.11/badge.svg)](https://maven-badges.herokuapp.com/maven-central/de.sciss/strugatzki_2.11)

## statement

Strugatzki is a Scala library containing several algorithms for audio feature extraction, with the aim of similarity and dissimilarity measurements. They have been originally used in my live electronic piece ["Inter-Play/Re-Sound"](http://sciss.de/texts/liv_interplay.html), then successively in the tape piece ["Leere Null"](http://sciss.de/texts/tap_leerenull.html), the sound installation ["Writing Machine"](http://sciss.de/texts/ins_writingmachine.html), and the tape piece  ["Leere Null (2)"](http://sciss.de/texts/tap_leerenull2.html).

(C)opyright 2011&ndash;2017 by Hanns Holger Rutz. All rights reserved. It is released under the [GNU Lesser General Public License](https://raw.github.com/Sciss/Strugatzki/master/LICENSE) v2.1+ and comes with absolutely no warranties. To contact the author, send an email to `contact at sciss.de`.

## requirements / installation

Builds with sbt 0.13 against Scala 2.12, 2.11. Depends on [ScalaCollider](https://github.com/Sciss/ScalaCollider) and [scopt](https://github.com/jstrachan/scopt).

Strugatzki can be either used as a standalone command line tool, or embedded in your project as a library.

## contributing

Please see the file [CONTRIBUTING.md](CONTRIBUTING.md)

## running

### standalone use

This assumes you check out Strugatzki from source, as the easiest way to use it in the terminal is via the sbt prompt. First, start sbt without arguments. In the sbt shell, execute `run` which will print the switches for the different modules:

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

To find out the switches for the extraction module: `run -f`. This will print the particular options available for this module. While in the API times are all given in sample frames with respect to the original sound file's sample rate, the standalone/ terminal mode assumes times are all given as floating point seconds.

Another possibility is to build the standalone via `sbt assembly` and then execute it with shell script `./strugatzki`

### library use

If you build your project with sbt, the following line adds a dependency for Strugatzki:

    "de.sciss" %% "strugatzki" % v

The current version `v` is `"2.16.0"`.

As documentation you are referred to the API docs at the moment. These can be created in the standard way (`sbt doc`). The main classes to look are `FeatureExtraction`, `FeatureCorrelation`, and `FeatureSegmentation`. They are used in a similar fashion. E.g. to run feature extraction:

```scala
    
    import de.sciss.strugatzki._
    import de.sciss.file._
    
    val fs           = FeatureExtraction.Config()
    fs.audioInput    = file("my-audio-input")
    fs.featureOutput = file("my-feature-aiff-output")
    fs.metaOutput    = Some(file("my-meta-data-xml-output"))  // optional
    
    // the process is constructed with the settings and a partial function which
    // acts as a process observer
    val f = FeatureExtraction.run(fs) {
      case Processor.Success(_, _) => println("Done.")
    }
    // f is a `Future` of the result you may want to work with

```

For the detailed settings, such as FFT size, number of MFCC, etc., please refer to the API docs.

## algorithms

Strugatzki is not a full fledged MIR system, but was rather born of my personal preference and experience, resulting in an API which is a bit idiosyncratic, but nevertheless completely independent of my specific use cases.

The feature vectors used are spectral envelope as defined by the Mel Frequency Cepstral Coefficients (MFCC) and the Loudness in Sones. The actual DSP algorithms responsible for their extraction are the `MFCC` and `Loudness` UGens included with SuperCollider, which were written by Dan Stowell and Nick Collins. They are used behind the scenes, running ScalaCollider in Non-Realtime-Mode. 

In most processes, there is a parameter `temporalWeight` which specifies the weight assigned to MFCC versus loudness. A temporal weight of `0.0` means the temporal feature vector (loudness) is not taken into account, and a weight of `1.0` means that only the loudness is taken into account, while the spectral features (MFCC) are ignored.

The correlation, segmentation, and so forth are performed directly in Scala, using dedicated threads, providing an API for monitoring completion, failure, progress, and an abortion hook. As of the current version, all processes run single-threaded, so there is plenty of headroom for future performance boosts by providing some forms of parallelism. Strugatzki is an artistic and a research project, not a commercial application, so beware that it is not the fastest MIR system imaginable.

The feature vectors (MFCC and loudness) are calculated on a frame-by-frame basis using a sliding (FFT) window. They are written out as a regular AIFF sound file, which is a convenient format for storing evenly sampled multichannel floating point streams. Accompanied by a dedicated XML file which contains the extraction settings for future reference and use by the other algorithms.

There are two main algorithms that operate on the extracted features: The correlation module is capable of finding sound in a database that match a target sound in terms of similarity or dissimilarity. The segmentation module is capable of suggesting breaking points in a single target sound on the basis of novelty or maximisation of dissimilarity within a given time window.

### normalization

We have found it quite useful to normalize the MFCC by creating statistics over a large body of database sounds. Therefore, a particular stats module is provided which can scan a directory of feature extraction files and calculate the minimum and maximum ranges for each coefficient. In the standalone mode, these ranges can be written out to a dedicated AIFF file, and may be used for correlation and segmentation, yielding in our opinion better results.

### self similarity

For analysis and visualisation purposes, we have added a self similarity module which produces a `png` image file with the self similarity matrix of a given feature file.
