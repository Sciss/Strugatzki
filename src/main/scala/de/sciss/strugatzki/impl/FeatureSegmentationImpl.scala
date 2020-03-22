/*
 *  FeatureSegmentationImpl.scala
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
package impl

import java.io.File

import de.sciss.processor.impl.ProcessorImpl
import de.sciss.span.Span
import de.sciss.synth.io.AudioFile

import scala.collection.immutable.{SortedSet => ISortedSet}
import scala.xml.XML

private[strugatzki] final class FeatureSegmentationImpl(val config: FeatureSegmentation.Config)
  extends FeatureSegmentation with ProcessorImpl[FeatureSegmentation.Product, FeatureSegmentation] {

  import FeatureSegmentation._

  protected def body(): Product = {
    import FeatureExtraction.{Config => ExtrConfig}

    val extr      = ExtrConfig.fromXML(XML.loadFile(config.metaInput))
    val stepSize  = extr.fftSize / extr.fftOverlap

    def fullToFeat(n: Long): Int  = ((n + (stepSize >> 1)) / stepSize).toInt
    def featToFull(i: Int ): Long = i.toLong * stepSize

    val normBuf = if (config.normalize) {
      val afNorm = AudioFile.openRead(new File(config.databaseFolder, Strugatzki.NormalizeName))
      try {
        require((afNorm.numChannels == extr.numCoeffs + 1) && afNorm.numFrames == 2L)
        val b = afNorm.buffer(2)
        afNorm.read(b)
        b
      } finally {
        afNorm.close()
      }
    } else null // None

    val halfWinLen  = fullToFeat(config.corrLen)
    val tempWeight  = config.temporalWeight

    var prio        = ISortedSet.empty[Break](BreakMaxOrd)
    var lastBreak   = null: Break

    def entryHasSpace: Boolean = prio.size < config.numBreaks

    def highestSim: Float =
      if (prio.nonEmpty) prio.last.sim
      else 0f // Float.NegativeInfinity

    // adds a break to the entry's priority queue. if the queue grows beyond numBreaks,
    // truncates the queue. if the match collides with a previous match that is closer
    // than minSpacing, it is either dropped (if the similarity is equal or smaller) or replaces
    // the previous match (if the similarity is greater).
    def addBreak(b: Break): Unit = {
      if ((lastBreak != null) && ((b.pos - lastBreak.pos) < config.minSpacing)) {
        // gotta collapse them
        if (lastBreak.sim > b.sim) {  // ok, replace previous match
          prio     -= lastBreak
          prio     += b
          lastBreak = b
        } // otherwise ignore the new match
      } else {
        prio += b
        if (prio.size > config.numBreaks) {
          prio -= prio.last // faster than dropRight( 1 ) ?
        }
        lastBreak = b
      }
    }

    val winLen = halfWinLen * 2
    val eInBuf = Array.ofDim[Float](extr.numCoeffs + 1, winLen)

    val afExtr = AudioFile.openRead(extr.featureOutput)
    try {
      val afStart = config.span match {
        case Span.HasStart(s) => math.max(0, fullToFeat(s))
        case _                => 0
      }
      val afStop = config.span match {
        case Span.HasStop(s)  => math.min(afExtr.numFrames.toInt, fullToFeat(s))
        case _                => afExtr.numFrames.toInt
      }
      val afLen = afStop - afStart

      if (afStart > 0) afExtr.seek(afStart)
      var left        = afLen // afExtr.numFrames
      var readSz      = winLen // read full buffer in first round
      var readOff     = 0
      var logicalOff  = 0
      var progBlock   = 0

      while (left > 0) {
        checkAborted()

        val chunkLen = math.min(left, readSz) // .toInt
        afExtr.read(eInBuf, readOff, chunkLen)
        val eInBufOff = logicalOff % winLen
        MathUtil.normalize(normBuf, eInBuf, readOff, chunkLen)
        val temporal = if (tempWeight > 0f) {
          MathUtil.correlateHalf(1, halfWinLen, eInBuf, eInBufOff, 0)
        } else 0f
        val spectral = if (tempWeight < 1f) {
          MathUtil.correlateHalf(extr.numCoeffs, halfWinLen, eInBuf, eInBufOff, 1)
        } else 0f
        val sim = temporal * tempWeight + spectral * (1f - tempWeight)
        if (entryHasSpace || sim < highestSim) {
          val pos = featToFull(afStart + logicalOff + halfWinLen)
          val b   = Break(sim, pos)
          addBreak(b)
        }
        left       -= chunkLen
        readOff     = (readOff + chunkLen) % winLen
        logicalOff += 1
        readSz      = 1 // read single frames in successive round (and rotate buffer)

        progBlock   = (progBlock + 1) % 128
        if (progBlock == 0) progress = left.toDouble / afLen
      }
      progress = 1.0

    } finally {
      afExtr.close()
    }

    val pay = prio.toIndexedSeq
    pay
  }
}