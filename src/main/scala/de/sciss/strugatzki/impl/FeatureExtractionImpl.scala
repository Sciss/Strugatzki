/*
 *  FeatureExtractionImpl.scala
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
package impl

import de.sciss.file._
import de.sciss.processor.impl.ProcessorImpl
import de.sciss.synth
import de.sciss.synth.GE
import de.sciss.synth.io.AudioFile

import scala.concurrent.blocking
import scala.xml.XML

private[strugatzki] final class FeatureExtractionImpl(val config: FeatureExtraction.Config)
  extends FeatureExtraction with ProcessorImpl[Unit, FeatureExtraction] {

  import FeatureExtraction._

  protected def body(): Unit = {
    import NonRealtimeProcessor.{BufferSpec, RenderConfig, render}

    val inSpec      = blocking(AudioFile.readSpec(config.audioInput))
    val inChannels  = inSpec.numChannels
    val stepSize    = config.fftSize / config.fftOverlap
    val numFeatures = config.numCoeffs + 1
    val fftWinType  = 1 // -1 rect, 0 sine, 1 hann

    def extract(in0: GE): GE = {
      import synth._
      import ugen._
      import Ops._

      val in      = config.channelsBehavior match {
        case ChannelsBehavior.Mix   => Mix(in0)
        case ChannelsBehavior.First => in0.out(0)
        case ChannelsBehavior.Last  => in0.out(inChannels - 1)
      }
      val chain   = FFT("fft".kr, in, 1.0 / config.fftOverlap, fftWinType)
      val coeffs  = MFCC.kr(chain, config.numCoeffs)
      val loud    = Loudness.kr(chain) / 32
      val sig     = Flatten(Seq(loud, coeffs))
      sig
    }

    val fftBuf = BufferSpec("fft", numFrames = config.fftSize)

    val rCfg = RenderConfig(
      name          = s"extract features from ${config.audioInput.path}",
      inFile        = config.audioInput,
      inSpec        = inSpec,
      numFeatures   = numFeatures,
      stepSize      = stepSize,
      buffers       = fftBuf :: Nil,
      outFile       = Some(config.featureOutput)
    )

    val r = render(rCfg)(extract)
    await(r)

    config.metaOutput.foreach { metaFile =>
      val xml = config.toXML
      blocking {
        XML.save(metaFile.getAbsolutePath, xml, "UTF-8", xmlDecl = true, doctype = null)
      }
    }
  }
}