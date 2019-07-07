/*
 *  CrossSimilarity.scala
 *  (Strugatzki)
 *
 *  Copyright (c) 2011-2019 Hanns Holger Rutz. All rights reserved.
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
import de.sciss.strugatzki.impl.CrossSimilarityImpl
import de.sciss.synth.io.AudioFileType

import scala.language.implicitConversions
import scala.xml.{NodeSeq, XML}

object CrossSimilarity extends ProcessorFactory.WithDefaults {
  var verbose = false // currently unused

  type Product  = Unit
  type Repr     = CrossSimilarity

  /** All durations, spans and spacings are given in sample frames
    * with respect to the sample rate of the audio input file.
    */
  sealed trait ConfigLike {
    /** The database folder is merely used to retrieve the normalization file,
      * given that `normalize` is `true`.
      */
    def databaseFolder: File

    /** The XML file holding the extractor parameters corresponding to the
      * first audio input file. The audio input file's feature vector output file
      * is determined from this meta file.
      */
    def metaInput1: File

    /** The XML file holding the extractor parameters corresponding to the
      * second audio input file. The audio input file's feature vector output file
      * is determined from this meta file.
      */
    def metaInput2: File

    /** The file to which the cross similarity vector is written as an audio file. */
    def audioOutput: File

    /** The format type for the output file. */
    def audioOutputType: AudioFileType

    /** An option which restricts the calculation to a given span within the
      * first input file. If `Span.all`, the whole file is considered.
      */
    def span1: Span.NonVoid

    /** An option which restricts the calculation to a given span within the
      * second input file. If `Span.all`, the whole file is considered.
      */
    def span2: Span.NonVoid

    //    /** The size of the sliding window over which the features are correlated.
    //      * That is, for a length of 1.0 second (given in sample frames, hence
    //      * 44100 for a sample rate of 44100 Hz), at any given point in time,
    //      * 0.5 seconds left of that point are correlated with 0.5 seconds right
    //      * of that point.
    //      */
    //    def corrLen: Long

    /** The balance between the feature of loudness curve and spectral composition (MFCC).
      * A value of 0.0 means the segmentation is only performed by considering the
      * spectral features, and a value of 1.0 means the segmentation is taking only
      * the loudness into consideration. Values in between give a measure that takes
      * both features into account with the given priorities.
      */
    def temporalWeight: Float

    /** Whether to apply normalization to the features (recommended). */
    def normalize: Boolean

    /** Maximum energy boost (as an amplitude factor) allowed for a match to be considered.
      * The estimation of the boost factor for two matched signals
      * is `exp ((ln( loud_in ) - ln( loud_db )) / 0.6 )`
      */
    def maxBoost: Float

    final def pretty: String = {
      "Config(\n   databaseFolder = " + databaseFolder +
             "\n   metaInput1     = " + metaInput1 +
             "\n   metaInput2     = " + metaInput2 +
             "\n   audioOutput    = " + audioOutput +
             "\n   audioOutputType= " + audioOutputType +
             "\n   span1          = " + span1 +
             "\n   span2          = " + span2 +
             // "\n   corrLen        = " + corrLen +
             "\n   temporalWeight = " + temporalWeight +
             "\n   normalize      = " + normalize +
             "\n   maxBoost       = " + maxBoost + "\n)"
      }
   }

  object ConfigBuilder {
    def apply(settings: Config): ConfigBuilder = {
      val sb = Config()
      sb.read(settings)
      sb
    }
  }

  final class ConfigBuilder private[CrossSimilarity]() extends ConfigLike {
    /** The database folder defaults to `database` (relative path). */
    var databaseFolder: File = file("database") // Strugatzki.defaultDir
    /** The first correlation input file's extractor meta data file defaults
      * to `input1_feat.xml` (relative path)
      */
    var metaInput1: File = file("input1_feat.xml")
    /** The second correlation input file's extractor meta data file defaults
      * to `input2_feat.xml` (relative path)
      */
    var metaInput2: File = file("input2_feat.xml")

    private[CrossSimilarity] var _audioOutput      = file("output.aif")
    private[CrossSimilarity] var _audioOutputType  = AudioFileType.AIFF: AudioFileType

    private def outputExtMatches = _audioOutputType.extensions.contains(_audioOutput.ext)

    /** The audio output file defaults to `output.aif` (relative path). */
    def audioOutput: File = _audioOutput

    /** Sets the audio output file. If currently type and file extension match,
      * this will also switch `audioOutputType` if necessary.
      */
    def audioOutput_=(value: File): Unit = if (_audioOutput != value) {
      val m         = outputExtMatches
      _audioOutput  = value
      val ext       = value.ext
      if (m) AudioFileType.writable.find(_.extensions.contains(ext)).foreach { tpe =>
        _audioOutputType = tpe
      }
    }

    /** The audio output file type defaults to AIFF. */
    def audioOutputType: AudioFileType = _audioOutputType
    /** Sets the audio output file type. If currently type and file extension match,
      * this will also exchange the extension of `audioOutput` if necessary.
      */
    def audioOutputType_=(value: AudioFileType): Unit = if (_audioOutputType != value) {
      val m             = outputExtMatches
      _audioOutputType  = value
      if (m) _audioOutput = _audioOutput.replaceExt(value.extension)
    }

    /** The optional span restriction for the first file defaults to `Span.all`. */
    var span1: Span.NonVoid = Span.all

    /** The optional span restriction for the second file defaults to `Span.all`. */
    var span2: Span.NonVoid = Span.all

    //    /** The correlation length defaults to 44100 sample frames
    //      * (or 1.0 seconds at 44.1 kHz sample rate).
    //      */
    //    var corrLen = 44100L

    /** The temporal weight defaults to 0.5. */
    var temporalWeight = 0.5f

    /** The feature vector normalization flag defaults to `true`. */
    var normalize = true

    /** The maximum boost factor defaults to 8.0. */
    var maxBoost = 8f

    def build: Config = Impl(databaseFolder, metaInput1, metaInput2, audioOutput, audioOutputType,
      span1, span2, /* corrLen, */ temporalWeight, normalize, maxBoost)

    def read(settings: Config): Unit = {
      databaseFolder    = settings.databaseFolder
      metaInput1        = settings.metaInput1
      metaInput2        = settings.metaInput2
      _audioOutput      = settings.audioOutput
      _audioOutputType  = settings.audioOutputType
      span1             = settings.span1
      span2             = settings.span2
      // corrLen           = settings.corrLen
      temporalWeight    = settings.temporalWeight
      normalize         = settings.normalize
      maxBoost          = settings.maxBoost
    }

    private final case class Impl(databaseFolder: File, metaInput1: File, metaInput2: File, audioOutput: File,
                                  audioOutputType: AudioFileType, span1: Span.NonVoid, span2: Span.NonVoid,
                                  /* corrLen: Long, */ temporalWeight: Float, normalize: Boolean, maxBoost: Float)
      extends Config {
      override def productPrefix = "Config"

      private def spanToXML(span: Span.NonVoid) =
<span>
  {span match { case Span.HasStart(s) => <start>{s}</start>; case _ => Nil}}
  {span match { case Span.HasStop(s)  =>  <stop>{s}</stop>;  case _ => Nil}}
 </span>

      def toXML: xml.Elem = // <corr>{corrLen}</corr>
<crosssimilarity>
  <database>{databaseFolder.getPath}</database>
  <input1>{metaInput1.getPath}</input1>
  <input2>{metaInput2.getPath}</input2>
  <output>{audioOutput.getPath}</output>
  <outputType>{audioOutputType.id}</outputType>
  {span1 match { case Span.All => Nil; case _ @ Span(_, _) => <span1>{spanToXML(span1).child}</span1> }}
  {span2 match { case Span.All => Nil; case _ @ Span(_, _) => <span2>{spanToXML(span2).child}</span2> }}
  <weight>{temporalWeight}</weight>
  <normalize>{normalize}</normalize>
  <maxBoost>{maxBoost}</maxBoost>
</crosssimilarity>
    }
  }

  object Config {
    def apply(): ConfigBuilder = new ConfigBuilder

    implicit def build(sb: ConfigBuilder): Config = sb.build

    private def spanFromXML(xml: NodeSeq): Span.NonVoid = {
      val start = (xml \ "start").headOption.map(_.text.toLong)
      val stop = (xml \ "stop").headOption.map(_.text.toLong)
      (start, stop) match {
        case (Some(_start), Some(_stop)) => Span(_start, _stop)
        case (Some(_start), None)        => Span.from(_start)
        case (None,         Some(_stop)) => Span.until(_stop)
        case (None,         None)        => Span.all
      }
    }

    def fromXMLFile(file: File): Config = fromXML(XML.loadFile(file))

    def fromXML(xml: NodeSeq): Config = {
      val sb              = Config()
      sb.databaseFolder   = file((xml \ "database").text)
      sb.metaInput1       = file((xml \ "input1"  ).text)
      sb.metaInput2       = file((xml \ "input2"  ).text)
      sb._audioOutput     = file((xml \ "output"  ).text)
      sb._audioOutputType = AudioFileType((xml \ "outputType").text)
      sb.span1            = {
        val e = xml \ "span1"
        if (e.isEmpty) Span.all else spanFromXML(e)
      }
      sb.span2            = {
        val e = xml \ "span2"
        if (e.isEmpty) Span.all else spanFromXML(e)
      }
      // sb.corrLen          = (xml \ "corr"     ).text.toLong
      sb.temporalWeight   = (xml \ "weight"   ).text.toFloat
      sb.normalize        = (xml \ "normalize").text.toBoolean
      sb.maxBoost         = (xml \ "maxBoost" ).text.toFloat
      sb.build
    }
  }

  sealed trait Config extends ConfigLike {
    def toXML: xml.Node
  }

  protected def defaultConfig: Config = Config()

  /** Creates a new process to calculate the self similarity matrix of a sound file and save it as an image file.
    *
    * @param config        the settings for the process (e.g. input and output files)
    * @return              the cross similarity generating process (to be started)
    */
  protected def prepare(config: Config): Prepared = new CrossSimilarityImpl(config)
}
trait CrossSimilarity extends ProcessorLike[CrossSimilarity.Product, CrossSimilarity] {
  def config: CrossSimilarity.Config
}