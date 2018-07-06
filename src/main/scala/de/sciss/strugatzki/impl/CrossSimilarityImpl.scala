/*
 *  CrossSimilarityImpl.scala
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
import de.sciss.span.Span
import de.sciss.strugatzki.FeatureCorrelation.{FeatureMatrix, InputMatrix}
import de.sciss.synth.io.{AudioFile, AudioFileSpec, Frames, SampleFormat}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.blocking
import scala.xml.XML

private[strugatzki] final class CrossSimilarityImpl(val config: CrossSimilarity.Config)
  extends CrossSimilarity with ProcessorImpl[CrossSimilarity.Product, CrossSimilarity] {

  import CrossSimilarity._

  protected def body(): Product = blocking {
    import FeatureExtraction.{Config => ExtrSettings}

    var openFiles = Vec.empty[AudioFile]

    val extrIn1   = ExtrSettings.fromXML(XML.loadFile(config.metaInput1))
    val extrIn2   = ExtrSettings.fromXML(XML.loadFile(config.metaInput2))
    require(extrIn1.fftSize == extrIn2.fftSize && extrIn1.fftOverlap == extrIn2.fftOverlap &&
            extrIn1.numCoeffs == extrIn2.numCoeffs,
      s"Analysis settings for ${config.metaInput1} and ${config.metaInput2} differ.")

    val stepSize  = extrIn1.fftSize / extrIn1.fftOverlap

    def fullToFeat(n: Long) = ((n + (stepSize >> 1)) / stepSize).toInt
    // def featToFull(i: Int)  = i.toLong * stepSize

    val normBuf = if (config.normalize) {
      val afNorm = AudioFile.openRead(config.databaseFolder / Strugatzki.NormalizeName)
      try {
        require((afNorm.numChannels == extrIn1.numCoeffs + 1) && afNorm.numFrames == 2L)
        val b = afNorm.buffer(2)
        afNorm.read(b)
        b
      } finally {
        afNorm.close()
      }
    } else null

    def calcLnAvgLoud(b: Array[Float], bOff: Int, bLen: Int) = math.log(MathUtil.avg(b, bOff, bLen))

    def calcBoost(in: InputMatrix, b: Array[Float]): Float = {
      val lnAvgB = calcLnAvgLoud(b, 0, in.numFrames)
      math.exp((in.lnAvgLoudness - lnAvgB) / 0.6).toFloat
    }

    def openInput(extr: ExtrSettings, span: Span.NonVoid): (AudioFile, Long) = {
      val af      = AudioFile.openRead(extr.featureOutput)
      openFiles :+= af
      val sp      = span match {
        case Span(_start, _stop)    => Span(fullToFeat(_start), fullToFeat(_stop))
        case Span.HasStart(_start)  => Span(fullToFeat(_start), af.numFrames)
        case Span.HasStop(_stop)    => Span(0L, fullToFeat(_stop))
        case Span.All               => Span(0, af.numFrames)
      }
      val stop  = math.min(af.numFrames, sp.stop)
      val start = math.max(0, math.min(stop, sp.start))
      if (start > 0) af.seek(start)
      (af, stop - start)
    }

    try {
      val (_afIn1, _len1) = openInput(extrIn1, config.span1)
      val (_afIn2, _len2) = openInput(extrIn2, config.span2)

      // val fullSpec = AudioFile.readSpec(extrIn1.audioInput)
      val afOut = AudioFile.openWrite(config.audioOutput,
        AudioFileSpec(fileType = config.audioOutputType, sampleFormat = SampleFormat.Float, numChannels = 1,
          sampleRate = _afIn1.sampleRate))
      openFiles :+= afOut

      // put shorter file in afIn1, longer one in afIn2. afIn1 will be read in completely, afIn2 piecewise
      val (afIn1, len1, afIn2, len2) =
        if (_len1 < _len2) (_afIn1, _len1, _afIn2, _len2) else (_afIn2, _len2, _afIn1, _len1)

      require(len1 < 0x7FFFFFFF, s"File '${afIn1.file.get.name}' too large ($len1 frames)")
      val len1i = len1.toInt

      val matrixIn = {
        try {
          val b = afIn1.buffer(len1i)
          afIn1.read(b)
          MathUtil.normalize(normBuf, b, 0, len1i)

          def feat(mat: Frames) = {
            val (mean, stdDev) = MathUtil.stat(mat, 0, len1i, 0, mat.length)
            FeatureMatrix(mat, len1i, mean, stdDev)
          }

          InputMatrix(feat(b.take(1)), feat(b.drop(1)), calcLnAvgLoud(b(0), 0, len1i))
        } finally {
          afIn1.close()
        }
      }

      val inTempWeight  = config.temporalWeight
      // val bufSz         = fullToFeat(config.corrLen)
      val bufSz         = 8192
      val eInBuf        = Array.ofDim[Float](extrIn1.numCoeffs + 1, bufSz)
      val outBuf        = afOut.buffer(bufSz)

      var left          = len2
      var readSz        = bufSz // read full buffer in first round
      var readOff       = 0
      var writeOff      = 0
      var logicalOff    = 0

      def flushOut(): Unit = if (writeOff > 0) {
        afOut.write(outBuf, 0, writeOff)
        writeOff = 0
      }

      // println(s"afIn2.position ${afIn2.position}, numFrames ${afIn2.numFrames}, len2 $len2")

      while (left > 0) {
        checkAborted()

        val chunkLen  = math.min(left, readSz).toInt
        afIn2.read(eInBuf, readOff, chunkLen)
        val eInBufOff = logicalOff % len1i
        MathUtil.normalize(normBuf, eInBuf, readOff, chunkLen)
        val boost     = calcBoost(matrixIn, eInBuf(0))
        val sim       = if (boost <= config.maxBoost) {
          val temporal = if (inTempWeight > 0f) {
            correlate(matrixIn.temporal, eInBuf, eInBufOff, 0)
          } else 0f
          val spectral = if (inTempWeight < 1f) {
            correlate(matrixIn.spectral, eInBuf, eInBufOff, 1)
            //if( res > 1 ) println( "spec : " + res + " " + logicalOff )
          } else 0f
          temporal * inTempWeight + spectral * (1f - inTempWeight)
        } else {
          0f // Float.NegativeInfinity
        }

        outBuf(0)(writeOff) = sim
        writeOff += 1
        flushOut()

        // val start = featToFull(logicalOff)
        // val stop  = featToFull(logicalOff + punchInLen)
        // val m     = Match(sim, extrDB.audioInput, Span(start, stop), boost, 1f)

        left       -= chunkLen
        readOff     = (readOff + chunkLen) % len1i
        logicalOff += 1
        readSz      = 1 // read single frames in successive round (and rotate buffer)

        progress = (len2 - left).toDouble / len2
      }

      flushOut()
      progress = 1.0

    } finally {
      openFiles.foreach(_.close())
    }
  }

  private def correlate(a: FeatureMatrix, b: Frames, bFrameOff: Int, bChanOff: Int): Float = {
    val numChannels = a.numChannels
    val numFrames   = a.numFrames
    // note: stat does not wrap frame offset around b.numFrames.
    // we thus assume that b really has data from 0 to a.numFrames!
    val (bMean, bStdDev) = MathUtil.stat(b, 0 /* FrameOff */ , numFrames, bChanOff, numChannels)
    MathUtil.correlate(a.mat, a.mean, a.stdDev, numFrames, numChannels, b, bMean, bStdDev, bFrameOff, bChanOff)
  }
}