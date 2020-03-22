/*
 *  FeatureStatsImpl.scala
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
import de.sciss.synth.io.AudioFile

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.blocking

private[strugatzki] final class FeatureStatsImpl(val config: Vec[File])
  extends FeatureStats with ProcessorImpl[FeatureStats.Product, FeatureStats] {

  import FeatureStats._

  protected def body(): Product = blocking {
    var allMins: Array[Double] = null
    var allMaxs: Array[Double] = null
    val paths = config
    var i = 0; while (i < paths.size) {
      checkAborted()
      val path = paths(i)
      val (mins, maxs) = body1(path)
      if (i == 0) {
        require(mins.length == maxs.length)
        allMins = mins
        allMaxs = maxs
      } else {
        val numCh = allMins.length
        require(mins.length == numCh && maxs.length == numCh)
        var ch = 0; while(ch < numCh) {
          allMins(ch) = math.min(allMins(ch), mins(ch))
          allMaxs(ch) = math.max(allMaxs(ch), maxs(ch))
        ch += 1 }
      }
      progress = (i + 1).toDouble / paths.size
      i += 1
    }
    (allMins zip allMaxs).toIndexedSeq
  }

  private def body1(path: File): (Array[Double], Array[Double]) = {
    val af = AudioFile.openRead(path)
    try {
      val bufSz   = math.min(8192, af.numFrames).toInt
      val numCh   = af.numChannels
      val maxs    = Array.fill(numCh)(Float.NegativeInfinity)
      val mins    = Array.fill(numCh)(Float.PositiveInfinity)
      val sums    = new Array[Double](numCh)
      val skews   = new Array[Double](numCh)
      val p01     = new Array[Double](numCh)
      val p99     = new Array[Double](numCh)
      val b       = af.buffer(bufSz)
      var left    = af.numFrames
      val chans   = 0 until numCh
      while (left > 0) {
        val chunkLen = math.min(left, bufSz).toInt
        af.read(b, 0, chunkLen)
        for (ch <- chans) {
          val cb = b(ch)
          for (i <- 0 until chunkLen) {
            val f = cb(i)
            if (f < mins(ch)) mins(ch) = f
            if (f > maxs(ch)) maxs(ch) = f
            sums(ch) += f
          }
        }
        left -= chunkLen
      }

      val log05 = math.log(0.5)
      for (ch <- chans) {
        val mean = sums(ch) / af.numFrames
        val d = maxs(ch) - mins(ch)
        val mn = (mean - mins(ch)) / d
        skews(ch) = log05 / math.log(mn)
      }

      // second pass
      val pctils = Array.ofDim[Int](numCh, 2048)
      af.seek(0L)
      left = af.numFrames
      while (left > 0) {
        val chunkLen = math.min(left, bufSz).toInt
        af.read(b, 0, chunkLen)
        for (ch <- chans) {
          val cb  = b(ch)
          val cp  = pctils(ch)
          val min = mins(ch)
          val d   = maxs(ch) - min
          val skew = skews(ch)
          for (i <- 0 until chunkLen) {
            val f     = cb(i)
            val norm  = (math.pow((f - min) / d, skew) * 2047 + 0.5).toInt
            cp(norm) += 1
          }
        }
        left -= chunkLen
      }
      af.close()

      for (ch <- chans) {
        val cp    = pctils(ch)
        val p01n  = (af.numFrames * 0.01).toInt
        val p99n  = (af.numFrames * 0.99).toInt
        val skewr = 1.0 / skews(ch)
        val min   = mins(ch)
        val d     = maxs(ch) - min
        var cnt = 0; var i = 0; while (cnt < p01n) {
          cnt += cp(i)
          i += 1
        }
        p01(ch) = math.pow(i.toDouble / 2048, skewr) * d + min
        while (cnt < p99n) {
          cnt += cp(i)
          i += 1
        }
        p99(ch) = math.pow(i.toDouble / 2048, skewr) * d + min
      }

      (p01, p99)

    } finally {
      if (af.isOpen) af.close()
    }
  }
}