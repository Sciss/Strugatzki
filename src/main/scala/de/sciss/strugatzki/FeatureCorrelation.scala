/*
 *  FeatureCorrelation.scala
 *  (Strugatzki)
 *
 *  Copyright (c) 2011-2020 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Lesser General Public License v2.1+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.strugatzki

import de.sciss.file._
import de.sciss.processor.{ProcessorFactory, ProcessorLike}
import de.sciss.span.Span
import de.sciss.strugatzki.impl.FeatureCorrelationImpl

import scala.language.implicitConversions
import scala.xml.{NodeSeq, XML}

/** A processor which searches through the database and matches
  * entries against a given audio file input. Returns a given
  * number of best matches.
  */
object FeatureCorrelation extends ProcessorFactory.WithDefaults {
   var verbose = false

  /** The result is a sequence of matches, sorted
    * by descending similarity
    */
  type Product  = IndexedSeq[Match]
  type Repr     = FeatureCorrelation

  object Match {
    def fromXML(xml: NodeSeq): Match = {
      val sim       = (xml \ "sim"     ).text.toFloat
      val f         = file((xml \ "file").text)
      val start     = (xml \ "start"   ).text.toLong
      val stop      = (xml \ "stop"    ).text.toLong
      val boostIn   = (xml \ "boostIn" ).text.toFloat
      val boostOut  = (xml \ "boostOut").text.toFloat
      Match(sim, f, Span(start, stop), boostIn, boostOut)
    }
  }

  /** @param   sim      the matched similarity (where 1.0 would be an identical match)
    * @param   file     the audio file in the database associated with the match
    * @param   punch    the best matched punch
    * @param   boostIn  the estimated gain factor for the match at the punch's start
    * @param   boostOut the estimated gain factor for the match at the punch's stop
    */
  final case class Match(sim: Float, file: File, punch: Span, boostIn: Float, boostOut: Float) {
    def toXML: xml.Elem =
<match>
   <sim>{sim}</sim>
   <file>{file.getPath}</file>
   <start>{punch.start}</start>
   <stop>{punch.stop}</stop>
   <boostIn>{boostIn}</boostIn>
   <boostOut>{boostOut}</boostOut>
</match>

      def pretty : String = "Match(\n   sim      = " + sim +
                                  "\n   file     = " + file +
                                  "\n   punch    = " + punch +
                                  "\n   boostIn  = " + boostIn +
                                  "\n   boostOut = " + boostOut + "\n)"
   }

  // reverse ordering. since sorted-set orders ascending according to the ordering,
  // this means we get a sorted-set with high similarities at the head and low
  // similarities at the tail, like a priority queue
  private[strugatzki] object MatchMinOrd extends Ordering[Match] {
    def compare(a: Match, b: Match): Int = b.sim compare a.sim
  }

  protected def defaultConfig: Config = Config()

  protected def prepare(config: Config): Prepared = new FeatureCorrelationImpl(config)

  /** where temporal weight is between 0 (just spectral corr) and 1 (just temporal corr) */
  object Punch {
    def fromXML(xml: NodeSeq): Punch = {
      val start   = (xml \ "start" ).text.toLong
      val stop    = (xml \ "stop"  ).text.toLong
      val weight  = (xml \ "weight").text.toFloat
      Punch(Span(start, stop), weight)
    }
  }

  final case class Punch(span: Span, temporalWeight: Float = 0.5f) {
    def toXML: xml.Elem =
<punch>
  <start>{span.start}</start>
  <stop>{span.stop}</stop>
  <weight>{temporalWeight}</weight>
</punch>
  }

  /** All durations, spans and spacings are given in sample frames
    * with respect to the sample rate of the audio input file.
    */
  sealed trait ConfigLike {
    /** The folder which is scanned for extraction entries to be used in the search.
      * This currently includes '''only those files''' ending in `_feat.xml` and which
      * have the same number of coefficients and time resolution (step size) as the
      * target file (`metaInput`).
      */
    def databaseFolder: File

    def metaInput: File

    /** The span in the audio input serving for correlation to find the punch in material */
    def punchIn: Punch

    /** The span in the audio input serving for correlation to find the punch out material */
    def punchOut: Option[Punch]

    /** Minimum length of the material to punch in */
    def minPunch: Long

    /** Maximum length of the material to punch in */
    def maxPunch: Long

    /** Whether to apply normalization to the features (recommended) */
    def normalize: Boolean

    /** Maximum energy boost (as an amplitude factor) allowed for a match to be considered.
      * The estimation of the boost factor for two matched signals
      * is `exp ((ln( loud_in ) - ln( loud_db )) / 0.6 )`
      */
    def maxBoost: Float

    /** Maximum number of matches to report */
    def numMatches: Int

    /** Maximum number of matches to report of a single database entry */
    def numPerFile: Int

    /** Minimum spacing between matches within a single database entry */
    def minSpacing: Long

    final def pretty: String = {
      "Settings(\n   databaseFolder = " + databaseFolder +
               "\n   metaInput      = " + metaInput +
               "\n   punchIn        = " + punchIn +
               "\n   punchOut       = " + punchOut +
               "\n   minPunch       = " + minPunch +
               "\n   maxPunch       = " + maxPunch +
               "\n   normalize      = " + normalize +
               "\n   maxBoost       = " + maxBoost +
               "\n   numMatches     = " + numMatches +
               "\n   numPerFiles    = " + numPerFile +
               "\n   minSpacing     = " + minSpacing + "\n)"
    }
  }

  object ConfigBuilder {
    def apply(config: Config): ConfigBuilder = {
      val sb = Config()
      sb.read(config)
      sb
    }
  }

  final class ConfigBuilder private[FeatureCorrelation]() extends ConfigLike {
    /** The database folder defaults to `database` (relative path). */
    var databaseFolder: File = file("database") // Strugatzki.defaultDir

    /** The correlation input file's extractor meta data file defaults
      * to `input_feat.xml` (relative path)
      */
    var metaInput: File = file("input_feat.xml")

    /** The punch in defaults to a `Span(0L, 44100L)` and a temporal weight of 0.5. */
    var punchIn: Punch = Punch(Span(0L, 44100L), 0.5f)

    /** The punch out option defaults to `None`. */
    var punchOut = Option.empty[Punch]

    /** The minimum punch length defaults to 22050 sample frames
      * (or 0.5 seconds at 44.1 kHz sample rate).
      */
    var minPunch = 22050L

    /** The maximum punch length defaults to 88200 sample frames
      * (or 2.0 seconds at 44.1 kHz sample rate).
      */
    var maxPunch = 88200L

    /** The vector normalization flag defaults to `true`. */
    var normalize = true

    /** The maximum boost factor defaults to 8.0. */
    var maxBoost = 8f

    /** The number of matches defaults to 1. */
    var numMatches = 1

    /** The maximum number of matches per file defaults to 1. */
    var numPerFile = 1

    /** The minimum spacing between matches defaults to 0 sample frames. */
    var minSpacing = 0L // 22050L

    def build: Config = Impl(databaseFolder, metaInput, punchIn, punchOut, minPunch, maxPunch, normalize,
      maxBoost, numMatches, numPerFile, minSpacing)

    def read(config: Config): Unit = {
      databaseFolder  = config.databaseFolder
      metaInput       = config.metaInput
      punchIn         = config.punchIn
      punchOut        = config.punchOut
      minPunch        = config.minPunch
      maxPunch        = config.maxPunch
      normalize       = config.normalize
      maxBoost        = config.maxBoost
      numMatches      = config.numMatches
      numPerFile      = config.numPerFile
      minSpacing      = config.minSpacing
    }

    private final case class Impl(databaseFolder: File, metaInput: File, punchIn: Punch, punchOut: Option[Punch],
                                  minPunch: Long, maxPunch: Long, normalize: Boolean, maxBoost: Float,
                                  numMatches: Int, numPerFile: Int, minSpacing: Long)
      extends Config {
      override def productPrefix = "Config"

      def toXML: xml.Elem =
<correlate>
  <database>{databaseFolder.getPath}</database>
  <input>{metaInput.getPath}</input>
  <punchIn>{punchIn.toXML.child}</punchIn>
  {punchOut match { case Some( p ) => <punchOut>{p.toXML.child}</punchOut>; case _ => Nil }}
  <minPunch>{minPunch}</minPunch>
  <maxPunch>{maxPunch}</maxPunch>
  <normalize>{normalize}</normalize>
  <maxBoost>{maxBoost}</maxBoost>
  <numMatches>{numMatches}</numMatches>
  <numPerFile>{numPerFile}</numPerFile>
  <minSpacing>{minSpacing}</minSpacing>
</correlate>
    }
  }

  object Config {
    def apply(): ConfigBuilder = new ConfigBuilder

    implicit def build(b: ConfigBuilder): Config = b.build

    def fromXMLFile(file: File): Config = fromXML(XML.loadFile(file))

    def fromXML(xml: NodeSeq): Config = {
      val sb = Config()
      sb.databaseFolder = file((xml \ "database").text)
      sb.metaInput      = file((xml \ "input").text)
      sb.punchIn        = Punch.fromXML(xml \ "punchIn")
      sb.punchOut       = {
        val e = xml \ "punchOut"
        if (e.isEmpty) None else Some(Punch.fromXML(e))
      }
      sb.minPunch       = (xml \ "minPunch"  ).text.toLong
      sb.maxPunch       = (xml \ "maxPunch"  ).text.toLong
      sb.normalize      = (xml \ "normalize" ).text.toBoolean
      sb.maxBoost       = (xml \ "maxBoost"  ).text.toFloat
      sb.numMatches     = (xml \ "numMatches").text.toInt
      sb.numPerFile     = (xml \ "numPerFile").text.toInt
      sb.minSpacing     = (xml \ "minSpacing").text.toLong
      sb.build
    }
  }

  sealed trait Config extends ConfigLike {
    def toXML: xml.Node
  }

  private[strugatzki] final case class FeatureMatrix(mat: Array[Array[Float]], numFrames: Int,
                                                     mean: Double, stdDev: Double) {
    def numChannels : Int = mat.length
    def matSize     : Int = numFrames * numChannels
  }

  private[strugatzki] final case class InputMatrix(temporal: FeatureMatrix, spectral: FeatureMatrix,
                                                   lnAvgLoudness: Double) {
    require(temporal.numFrames == spectral.numFrames)
    def numFrames: Int = temporal.numFrames
  }
}
trait FeatureCorrelation extends ProcessorLike[FeatureCorrelation.Product, FeatureCorrelation] {
  def config: FeatureCorrelation.Config
}