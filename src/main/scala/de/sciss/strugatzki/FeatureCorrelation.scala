/*
 *  FeatureCorrelation.scala
 *  (Strugatzki)
 *
 *  Copyright (c) 2011-2013 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either
 *  version 2, june 1991 of the License, or (at your option) any later version.
 *
 *  This software is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *  General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public
 *  License (gpl.txt) along with this software; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.strugatzki

import java.io.File
import xml.{NodeSeq, XML}
import language.implicitConversions
import concurrent.{ExecutionContext, Promise}

/**
* A processor which searches through the database and matches
* entries against a given audio file input. Returns a given
* number of best matches.
*/
object FeatureCorrelation extends ProcessorCompanion {
  /**
   * The result is a sequence of matches, sorted
   * by descending similarity
   */
  type PayLoad = IndexedSeq[Match]

  object Match {
    def fromXML(xml: NodeSeq): Match = {
      val sim       = (xml \ "sim").text.toFloat
      val file      = new File((xml \ "file").text)
      val start     = (xml \ "start").text.toLong
      val stop      = (xml \ "stop").text.toLong
      val boostIn   = (xml \ "boostIn").text.toFloat
      val boostOut  = (xml \ "boostOut").text.toFloat
      Match(sim, file, Span(start, stop), boostIn, boostOut)
    }
  }

  /**
   * @param   sim      the matched similarity (where 1.0 would be an identical match)
   * @param   file     the audio file in the database associated with the match
   * @param   punch    the best matched punch
   * @param   boostIn  the estimated gain factor for the match at the punch's start
   * @param   boostOut the estimated gain factor for the match at the punch's stop
   */
  final case class Match(sim: Float, file: File, punch: Span, boostIn: Float, boostOut: Float) {
    def toXML =
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

  // reverse ordering. since sortedset orders ascending according to the ordering,
  // this means we get a sortedset with high similarities at the head and low
  // similarities at the tail, like a priority queue
  private[strugatzki] object MatchMinOrd extends Ordering[Match] {
    def compare(a: Match, b: Match) = b.sim compare a.sim
  }

  protected def defaultConfig: Config = Config()

  protected def create(config: Config, observer: FeatureCorrelation.Observer,
                       promise: Promise[FeatureCorrelation.PayLoad])
                      (implicit exec: ExecutionContext): Processor[FeatureCorrelation.PayLoad, Config] =
    new impl.FeatureCorrelation(config, observer, promise)

  /** where temporal weight is between 0 (just spectral corr) and 1 (just temporal corr) */
   object Punch {
      def fromXML( xml: NodeSeq ) : Punch = {
         val start   = (xml \ "start").text.toLong
         val stop    = (xml \ "stop").text.toLong
         val weight  = (xml \ "weight").text.toFloat
         Punch( Span( start, stop ), weight )
      }
   }
   final case class Punch( span: Span, temporalWeight: Float = 0.5f ) {
      def toXML =
<punch>
   <start>{span.start}</start>
   <stop>{span.stop}</stop>
   <weight>{temporalWeight}</weight>
</punch>
   }

   /**
    * All durations, spans and spacings are given in sample frames
    * with respect to the sample rate of the audio input file.
    */
   sealed trait ConfigLike {
      /**
       * The folder which is scanned for extraction entries to be used in the search.
       * This currently includes '''only those files''' ending in `_feat.xml` and which
       * have the same number of coefficients and time resolution (step size) as the
       * target file (`metaInput`).
       */
      def databaseFolder : File
      def metaInput: File
      /** The span in the audio input serving for correlation to find the punch in material */
      def punchIn: Punch
      /** The span in the audio input serving for correlation to find the punch out material */
      def punchOut : Option[ Punch ]
      /** Minimum length of the material to punch in */
      def minPunch: Long
      /** Maximum length of the material to punch in */
      def maxPunch: Long
      /** Whether to apply normalization to the features (recommended) */
      def normalize : Boolean
      /**
       * Maximum energy boost (as an amplitude factor) allowed for a match to be considered.
       * The estimation of the boost factor for two matched signals
       * is `exp ((ln( loud_in ) - ln( loud_db )) / 0.6 )`
       */
      def maxBoost : Float
      /** Maximum number of matches to report */
      def numMatches : Int
      /** Maximum number of matches to report of a single database entry */
      def numPerFile : Int
      /** Minimum spacing between matches within a single database entry */
      def minSpacing : Long

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
         sb.read( config )
         sb
      }
   }

  final class ConfigBuilder private[FeatureCorrelation]() extends ConfigLike {
    /**
     * The database folder defaults to `database` (relative path)
     */
    var databaseFolder = new File("database") // Strugatzki.defaultDir
    /**
     * The correlation input file's extractor meta data file defaults
     * to `input_feat.xml` (relative path)
     */
    var metaInput = new File("input_feat.xml")
    /**
     * The punch in defaults to a `Span( 0L, 44100L )` and a temporal weight of 0.5.
     */
    var punchIn = Punch(Span(0L, 44100L), 0.5f)
    /**
     * The punch out option defaults to `None`.
     */
    var punchOut = Option.empty[Punch]
    /**
     * The minimum punch length defaults to 22050 sample frames
     * (or 0.5 seconds at 44.1 kHz sample rate)
     */
    var minPunch = 22050L
    /**
     * The maximum punch length defaults to 88200 sample frames
     * (or 2.0 seconds at 44.1 kHz sample rate)
     */
    var maxPunch = 88200L
    /**
     * The vector normalization flag defaults to `true`.
     */
    var normalize = true
    /**
     * The maximum boost factor defaults to 8.0.
     */
    var maxBoost = 8f
    /**
     * The number of matches defaults to 1.
     */
    var numMatches = 1
    /**
     * The maximum number of matches per file defaults to 1.
     */
    var numPerFile = 1
    /**
     * The minimum spacing between matches defaults to 0 sample frames.
     */
    var minSpacing = 0L // 22050L

    def build: Config = Impl(databaseFolder, metaInput, punchIn, punchOut, minPunch, maxPunch, normalize,
      maxBoost, numMatches, numPerFile, minSpacing)

    def read(config: Config) {
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

    private final case class Impl( databaseFolder: File, metaInput: File, punchIn: Punch, punchOut: Option[ Punch ],
                                minPunch: Long, maxPunch: Long, normalize: Boolean, maxBoost: Float,
                                numMatches: Int, numPerFile: Int, minSpacing: Long )
     extends Config {
       override def productPrefix = "Config"
        def toXML =
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
         sb.databaseFolder = new File( (xml \ "database").text )
         sb.metaInput      = new File( (xml \ "input").text )
         sb.punchIn        = Punch.fromXML( xml \ "punchIn" )
         sb.punchOut       = {
            val e = xml \ "punchOut"
            if( e.isEmpty ) None else Some( Punch.fromXML( e ))
         }
         sb.minPunch       = (xml \ "minPunch").text.toLong
         sb.maxPunch       = (xml \ "maxPunch").text.toLong
         sb.normalize      = (xml \ "normalize").text.toBoolean
         sb.maxBoost       = (xml \ "maxBoost").text.toFloat
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
    def numChannels = mat.length
    def matSize     = numFrames * numChannels
  }

  private[strugatzki] final case class InputMatrix(temporal: FeatureMatrix, spectral: FeatureMatrix,
                                                   lnAvgLoudness: Double) {

    require(temporal.numFrames == spectral.numFrames)

    def numFrames: Int = temporal.numFrames
  }
}