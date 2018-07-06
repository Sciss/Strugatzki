/*
 *  SelfSimilarity.scala
 *  (Strugatzki)
 *
 *  Copyright (c) 2011-2018 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Lesser General Public License v2.1+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.strugatzki

import java.io.File

import de.sciss.processor.{ProcessorFactory, ProcessorLike}
import de.sciss.span.Span

import scala.language.implicitConversions
import scala.xml.{NodeSeq, XML}

object SelfSimilarity extends ProcessorFactory.WithDefaults {
  var verbose = false

  type Product  = Unit
  type Repr     = SelfSimilarity

  object ColorScheme {
    def apply(logicalName: String): ColorScheme = logicalName match {
      case GrayScale    .logicalName => GrayScale
      case PsychoOptical.logicalName => PsychoOptical
    }

    def all:   Seq[ColorScheme] = Seq(GrayScale, PsychoOptical)
    def names: Seq[String]      = all.map(_.logicalName)
  }
  sealed trait ColorScheme {
    def logicalName: String
  }

  /** Plain gray scale. */
  case object GrayScale extends ColorScheme {
    val logicalName = "gray"
  }

  /** Psycho-optically optimized scheme originally taken from
    * Niklas Werner's Sonasound program. Uses a combination of
    * hues and brightnesses that produces as perceptually very
    * regular gradient. It goes from black over violet towards
    * yellow and finally white.
    */
  case object PsychoOptical extends ColorScheme {
    val logicalName = "psycho"
  }

  /** All durations, spans and spacings are given in sample frames
    * with respect to the sample rate of the audio input file.
    */
  sealed trait ConfigLike {
    /** The database folder is merely used to retrieve the normalization file,
      * given that `normalize` is `true`.
      */
    def databaseFolder: File

    /** The XML file holding the extractor parameters corresponding to the
      * audio input file. The audio input file's feature vector output file
      * is determined from this meta file.
      */
    def metaInput: File

    def metaInput2: Option[File]

    /** The file to which the self similarity matrix image is written. */
    def imageOutput: File

    /** An option which restricts the calculation to a given span within the
      * input file. If `Span.all`, the whole file is considered.
      */
    def span: Span.NonVoid

    /** The size of the sliding window over which the features are correlated.
      * That is, for a length of 1.0 second (given in sample frames, hence
      * 44100 for a sample rate of 44100 Hz), at any given point in time,
      * 0.5 seconds left of that point are correlated with 0.5 seconds right
      * of that point.
      */
    def corrLen: Long

    /** A decimation factor to produce smaller image size. A factor of 1 means
      * each frame step is performed, a factor of 2 means every second frame
      * is skipped, a factor of 3 means only one in three frames is considered,
      * and so forth.
      */
    def decimation: Int

    /** The balance between the feature of loudness curve and spectral composition (MFCC).
      * A value of 0.0 means the segmentation is only performed by considering the
      * spectral features, and a value of 1.0 means the segmentation is taking only
      * the loudness into consideration. Values in between give a measure that takes
      * both features into account with the given priorities.
      */
    def temporalWeight: Float

    /** The color scheme to use for the image. Either of `GrayScale` and `PsychoOptical`. */
    def colors: ColorScheme

    /** A warp factor (exponent) applied to the cross correlations before conversion to
      * a color value. Somewhat like a gamma correction. Values smaller than 1 produce
      * brighter images, values greater than 1 produce darker images.
      */
    def colorWarp: Float

    /** The ceiling cross correlation value corresponding to the maximally bright color.
      * Should be less than or equal to 1, and greater than zero. The smaller the value,
      * the earlier clipping occurs, and the more the colors are 'dragged' towards
      * brighter values.
      */
    def colorCeil: Float

    /** Whether the color scale should be inverted (`true`) or not (`false`). */
    def colorInv: Boolean

    /** Whether to apply normalization to the features (recommended). */
    def normalize: Boolean

    final def pretty: String = {
      "Config(\n   databaseFolder = " + databaseFolder +
             "\n   metaInput      = " + metaInput +
             "\n   metaInput2     = " + metaInput2 +
             "\n   imageOutput    = " + imageOutput +
             "\n   span           = " + span +
             "\n   corrLen        = " + corrLen +
             "\n   decimation     = " + decimation +
             "\n   temporalWeight = " + temporalWeight +
             "\n   colors         = " + colors +
             "\n   colorWarp      = " + colorWarp +
             "\n   colorCeil      = " + colorCeil +
             "\n   colorInv       = " + colorInv +
             "\n   normalize      = " + normalize + "\n)"
      }
   }

  object ConfigBuilder {
    def apply(settings: Config): ConfigBuilder = {
      val sb = Config()
      sb.read(settings)
      sb
    }
  }

  final class ConfigBuilder private[SelfSimilarity]() extends ConfigLike {
    /** The database folder defaults to `database` (relative path). */
    var databaseFolder = new File("database")
    /** The correlation input file's extractor meta data file defaults
      * to `input_feat.xml` (relative path).
      */
    var metaInput = new File("input_feat.xml")

    /** If present, cross-correlate with this file instead of self-similarity.
      * Default is `None`.
      */
    var metaInput2 = Option.empty[File]

    /** The image output file defaults to `output_selfsim.png` (relative path). */
    var imageOutput = new File("output_selfsim.png")
    /** The optional span restriction defaults to `Span.all`. */
    var span: Span.NonVoid = Span.all
    /** The correlation length defaults to 44100 sample frames
      * (or 1.0 seconds at 44.1 kHz sample rate).
      */
    var corrLen = 44100L
    /** The default decimation factor is `1` (no decimation). */
    var decimation = 1
    /** The temporal weight defaults to 0.5. */
    var temporalWeight = 0.5f
    /** The default color scheme is psycho-optical. */
    var colors: ColorScheme = PsychoOptical
    /** The default color warp is `1.0`. */
    var colorWarp = 1.0f
    /** The default color ceiling is `1.0`. */
    var colorCeil = 1.0f
    /** The colors are not inverted by default. */
    var colorInv = false
    /** The feature vector normalization flag defaults to `true`. */
    var normalize = true

    def build: Config = Impl(databaseFolder, metaInput, metaInput2,
      imageOutput, span, corrLen, decimation, temporalWeight,
      colors, colorWarp, colorCeil, colorInv, normalize)

    def read(settings: Config): Unit = {
      databaseFolder = settings.databaseFolder
      metaInput      = settings.metaInput
      metaInput2     = settings.metaInput2
      imageOutput    = settings.imageOutput
      span           = settings.span
      corrLen        = settings.corrLen
      decimation     = settings.decimation
      temporalWeight = settings.temporalWeight
      colors         = settings.colors
      colorWarp      = settings.colorWarp
      colorCeil      = settings.colorCeil
      colorInv       = settings.colorInv
      normalize      = settings.normalize
    }

    private final case class Impl(databaseFolder: File, metaInput: File, metaInput2: Option[File],
                                  imageOutput: File, span: Span.NonVoid,
                                  corrLen: Long, decimation: Int, temporalWeight: Float,
                                  colors: ColorScheme, colorWarp: Float, colorCeil: Float, colorInv: Boolean,
                                  normalize: Boolean)
      extends Config {
      override def productPrefix = "Config"

      private def spanToXML(span: Span.NonVoid) =
<span>
  {span match { case Span.HasStart(s) => <start>{s}</start>; case _ => Nil}}
  {span match { case Span.HasStop(s)  =>  <stop>{s}</stop>;  case _ => Nil}}
 </span>

      def toXML: xml.Elem =
<selfsimilarity>
  <database>{databaseFolder.getPath}</database>
  <input>{metaInput.getPath}</input>
  {metaInput2 match { case None => Nil; case Some(f) => <input2>{f.getPath}</input2>}}
  <output>{imageOutput.getPath}</output>
  {span match { case Span.All => Nil; case _ @ Span(_, _) => <span>{spanToXML(span).child}</span> }}
  <corr>{corrLen}</corr>
  <decimation>{decimation}</decimation>
  <weight>{temporalWeight}</weight>
  <colors>{colors.logicalName}</colors>
  <colorWarp>{colorWarp}</colorWarp>
  <colorCeil>{colorCeil}</colorCeil>
  <colorInv>{colorInv}</colorInv>
  <normalize>{normalize}</normalize>
</selfsimilarity>
    }
  }

  object Config {
    def apply(): ConfigBuilder = new ConfigBuilder

    implicit def build(sb: ConfigBuilder): Config = sb.build

    private def spanFromXML(xml: NodeSeq): Span.NonVoid = {
      val start = (xml \ "start").headOption.map(_.text.toLong)
      val stop  = (xml \ "stop" ).headOption.map(_.text.toLong)
      (start, stop) match {
        case (Some(_start), Some(_stop)) => Span(_start, _stop)
        case (Some(_start), None)        => Span.from(_start)
        case (None,         Some(_stop)) => Span.until(_stop)
        case (None,         None)        => Span.all
      }
    }

    def fromXMLFile(file: File): Config = fromXML(XML.loadFile(file))

    def fromXML(xml: NodeSeq): Config = {
      val sb            = Config()
      sb.databaseFolder = new File((xml \ "database").text)
      sb.metaInput      = new File((xml \ "input"   ).text)
      sb.metaInput2     = {
        val e = xml \ "input2"
        if (e.isEmpty) None else Some(new File(e.text))
      }
      sb.imageOutput    = new File((xml \ "output"  ).text)
      sb.span           = {
        val e = xml \ "span"
        if (e.isEmpty) Span.all else spanFromXML(e)
      }
      sb.corrLen        = (xml \ "corr"      ).text.toLong
      sb.decimation     = (xml \ "decimation").text.toInt
      sb.temporalWeight = (xml \ "weight"    ).text.toFloat
      sb.colors         = ColorScheme((xml \ "colors").text)
      sb.colorWarp      = (xml \ "colorWarp" ).text.toFloat
      sb.colorCeil      = (xml \ "colorCeil" ).text.toFloat
      sb.colorInv       = (xml \ "colorInv"  ).text.toBoolean
      sb.normalize      = (xml \ "normalize" ).text.toBoolean
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
    * @return              the self similarity matrix image generating process (to be started)
    */
  protected def prepare(config: Config): Prepared =
    new impl.SelfSimilarityImpl(config)
}
trait SelfSimilarity extends ProcessorLike[SelfSimilarity.Product, SelfSimilarity] {
  def config: SelfSimilarity.Config
}