/*
 *  FeatureSegmentation.scala
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
import de.sciss.strugatzki.impl.SpanUtil

import scala.language.implicitConversions
import scala.xml.{NodeSeq, XML}

/** A processor which performs segmentation on a given file.
  * Returns a given number of best matches (maximum change in
  * the feature vector).
  */
object FeatureSegmentation extends ProcessorFactory.WithDefaults {
  var verbose = false

  /** The result is a sequence of matches, sorted
    * by descending dissimilarity
    */
  type Product  = IndexedSeq[Break]

  type Repr     = FeatureSegmentation

  object Break {
    def fromXML(xml: NodeSeq): Break = {
      val sim = (xml \ "sim").text.toFloat
      val pos = (xml \ "pos").text.toLong
      Break(sim, pos)
    }
  }

  final case class Break(sim: Float, pos: Long) {
    def toXML: xml.Elem =
<break>
  <sim>{sim}</sim>
  <pos>{pos}</pos>
</break>

    def pretty: String = s"Break(sim = $sim, pos = $pos)"
  }

  // sorted-set orders ascending according to the ordering, and with this
  // ordering we will have low similarities (high dissimilarities)
  // at the head and high similarities at the tail
  private[strugatzki] object BreakMaxOrd extends Ordering[Break] {
    def compare(a: Break, b: Break): Int = a.sim compare b.sim
  }

  protected def defaultConfig: Config = Config()

  protected def prepare(config: Config): Prepared = new impl.FeatureSegmentationImpl(config)

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

    /** An option which restricts segmentation to a given span within the
      * input file. That is, only breaking points within this span are
      * reported. If `None`, the whole file is considered.
      */
    def span: Span.NonVoid

    /** The size of the sliding window over which the features are correlated.
      * That is, for a length of 1.0 second (given in sample frames, hence
      * 44100 for a sample rate of 44100 Hz), at any given point in time,
      * 0.5 seconds left of that point are correlated with 0.5 seconds right
      * of that point. Breaking points are those where correlation is minimised.
      */
    def corrLen: Long

    /** The balance between the feature of loudness curve and spectral composition (MFCC).
      * A value of 0.0 means the segmentation is only performed by considering the
      * spectral features, and a value of 1.0 means the segmentation is taking only
      * the loudness into consideration. Values in between give a measure that takes
      * both features into account with the given priorities.
      */
    def temporalWeight: Float

    /** Whether to apply normalization to the features (recommended) */
    def normalize: Boolean

    /** Maximum number of breaks to report */
    def numBreaks: Int

    /** Minimum spacing between breaks */
    def minSpacing: Long

    final def pretty: String = {
      "Config(\n   databaseFolder = " + databaseFolder +
             "\n   metaInput      = " + metaInput +
             "\n   span           = " + span +
             "\n   corrLen        = " + corrLen +
             "\n   temporalWeight = " + temporalWeight +
             "\n   normalize      = " + normalize +
             "\n   numBreaks      = " + numBreaks +
             "\n   minSpacing     = " + minSpacing + "\n)"
    }
  }

  object ConfigBuilder {
    def apply(settings: Config): ConfigBuilder = {
      val sb = Config()
      sb.read(settings)
      sb
    }
  }

  final class ConfigBuilder private[FeatureSegmentation]() extends ConfigLike {
    /** The database folder defaults to `database` (relative path). */
    var databaseFolder = new File("database")

    /** The input file's extractor meta data file defaults to `input_feat.xml` (relative path). */
    var metaInput = new File("input_feat.xml")

    /** The optional span restriction defaults to `None`. */
    var span: Span.NonVoid = Span.All

    /** The correlation length defaults to 22050 sample frames (or 0.5 seconds at 44.1 kHz sample rate). */
    var corrLen = 22050L

    /** The temporal weight defaults to 0.5. */
    var temporalWeight = 0.5f

    /** The feature vector normalization flag defaults to `true`. */
    var normalize = true

    /** The number of breaking points reported defaults to 1. */
    var numBreaks = 1

    /** The minimum spacing between breaking points defaults to 22050 sample frames
      * (or 0.5 seconds at 44.1 kHz sample rate).
      */
    var minSpacing = 22050L

    def build: Config = Impl(databaseFolder, metaInput, span, corrLen, temporalWeight, normalize, numBreaks, minSpacing)

     def read(settings: Config): Unit = {
       databaseFolder = settings.databaseFolder
       metaInput      = settings.metaInput
       span           = settings.span
       corrLen        = settings.corrLen
       temporalWeight = settings.temporalWeight
       normalize      = settings.normalize
       numBreaks      = settings.numBreaks
       minSpacing     = settings.minSpacing
     }

    private final case class Impl(databaseFolder: File, metaInput: File, span: Span.NonVoid, corrLen: Long,
                                  temporalWeight: Float, normalize: Boolean, numBreaks: Int, minSpacing: Long)
      extends Config {
      override def productPrefix = "Config"

      def toXML: xml.Elem =
<segmentation>
     <database>{databaseFolder.getPath}</database>
     <input>{metaInput.getPath}</input>
     <span>{SpanUtil.toXML(span)}</span>
     <corr>{corrLen}</corr>
     <weight>{temporalWeight}</weight>
     <normalize>{normalize}</normalize>
     <numBreaks>{numBreaks}</numBreaks>
     <minSpacing>{minSpacing}</minSpacing>
  </segmentation>
    }
  }

  object Config {
    def apply(): ConfigBuilder = new ConfigBuilder()

    implicit def build(sb: ConfigBuilder): Config = sb.build

    def fromXMLFile(file: File): Config = fromXML(XML.loadFile(file))

    def fromXML(xml: NodeSeq): Config = {
      val sb = Config()
      sb.databaseFolder = new File( (xml \ "database").text )
      sb.metaInput      = new File((xml \ "input").text)
      sb.span           = SpanUtil.fromXML(xml \ "span")
      sb.corrLen        = (xml \ "corr").text.toLong
      sb.temporalWeight = (xml \ "weight").text.toFloat
      sb.normalize      = (xml \ "normalize").text.toBoolean
      sb.numBreaks      = (xml \ "numBreaks").text.toInt
      sb.minSpacing     = (xml \ "minSpacing").text.toLong
      sb.build
    }
  }

  sealed trait Config extends ConfigLike {
    def toXML: xml.Node
  }
}

trait FeatureSegmentation extends ProcessorLike[FeatureSegmentation.Product, FeatureSegmentation] {
  def config: FeatureSegmentation.Config
}